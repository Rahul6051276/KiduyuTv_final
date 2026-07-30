package com.kiduyuk.klausk.kiduyutv.ui.player.directstream.playback

import android.util.Log
import com.kiduyuk.klausk.kiduyutv.ui.player.directstream.model.StreamItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Probes each [StreamItem] to confirm the upstream is reachable and returns
 * a successful response. A stream is considered "ok" when the server replies
 * with a 2xx status code, either to a HEAD request or, when HEAD is
 * unsupported, to a small Range GET (first 8 KiB).
 *
 * Why this exists:
 *  - Providers frequently return a stale or rotated URL that resolves
 *    successfully at the manifest level but yields 4xx when the player
 *    starts downloading segments. Probing before playback surfaces those
 *    failures up front so the user can pick a working source from the
 *    stream selection dialog.
 *  - The probe runs in parallel per-stream with a short timeout to keep the
 *    "Choose Stream" UI responsive even on slow CDNs.
 */
object StreamValidator {

    private const val TAG = "StreamValidator"

    /** How long any individual probe may run before being aborted. */
    private const val PROBE_TIMEOUT_SECONDS = 6L

    /** Number of bytes requested when we have to fall back from HEAD to GET. */
    private const val RANGE_FALLBACK_BYTES = "8192"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(false)
            .build()
    }

    /**
     * Probe every stream in [streams] in parallel. Each item is mutated
     * in place: [StreamItem.isChecking] is toggled on/off and
     * [StreamItem.isValid] is set to the probe result.
     *
     * @return the same list with validation flags populated, for chaining.
     */
    suspend fun validateAll(streams: List<StreamItem>): List<StreamItem> =
        withContext(Dispatchers.IO) {
            if (streams.isEmpty()) return@withContext streams
            // Mark every entry as "currently being checked" before fanning out
            // so the UI can render a pending state if it happens to be open.
            streams.forEach { it.isChecking = true }
            try {
                coroutineScope {
                    streams.map { stream ->
                        async {
                            val ok = probe(stream)
                            stream.isValid = ok
                            stream.isChecking = false
                            Log.i(
                                TAG,
                                "stream ok=${ok} provider=${stream.provider.ifBlank { "?" }} " +
                                    "quality=${stream.quality} url=${stream.url}"
                            )
                        }
                    }.awaitAll()
                }
            } catch (error: Throwable) {
                Log.w(TAG, "validateAll failed: ${error.message}")
            } finally {
                streams.forEach { it.isChecking = false }
            }
            streams
        }

    /**
     * Run a single probe against [stream]. The probe first tries a HEAD
     * request (cheap: no body transfer) and, if the server rejects HEAD with
     * 405/501, falls back to a tiny Range GET so we still see a real status
     * code without downloading the entire media.
     */
    private suspend fun probe(stream: StreamItem): Boolean {
        if (stream.url.isBlank()) return false
        val baseBuilder = Request.Builder().url(stream.url)
        stream.headers.forEach { (key, value) ->
            runCatching { baseBuilder.header(key, value) }
        }
        val head = runCatching { client.newCall(baseBuilder.head().build()).execute() }.getOrNull()
        if (head != null) {
            head.use { response ->
                when {
                    response.isSuccessful -> return true
                    response.code == 405 || response.code == 501 -> {
                        // Method not allowed/implemented — retry with a Range GET.
                    }
                    else -> {
                        Log.w(
                            TAG,
                            "HEAD ${stream.url} -> HTTP ${response.code}"
                        )
                        return false
                    }
                }
            }
        }
        val getRequest = baseBuilder
            .get()
            .header("Range", "bytes=0-${RANGE_FALLBACK_BYTES.toInt() - 1}")
            .build()
        return runCatching {
            client.newCall(getRequest).execute().use { response ->
                response.isSuccessful
            }
        }.getOrDefault(false)
    }
}
