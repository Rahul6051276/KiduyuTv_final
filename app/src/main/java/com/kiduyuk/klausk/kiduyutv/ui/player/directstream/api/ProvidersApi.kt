package com.kiduyuk.klausk.kiduyutv.ui.player.directstream.api

import android.util.Log
import com.kiduyuk.klausk.kiduyutv.ui.player.directstream.model.StreamItem
import com.kiduyuk.klausk.kiduyutv.ui.player.directstream.model.StreamResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Minimal client for the local kiduyuTv_providers (TMDB-Embed-API)
 * server. Call every public method from a background dispatcher.
 *
 * The server is configured with `enableProxy=false`, so stream URLs in
 * the response point **directly** to the upstream CDN. Each stream
 * object carries its own `headers` (typically `Referer` + `User-Agent`)
 * that the player must attach when fetching the manifest and segments.
 *
 * Endpoints mounted at the KiduyuTV providers backend:
 *   - GET api/streams/{type}/{tmdbId}[?season=&episode=]            (aggregate)
 *   - GET api/streams/{provider}/{type}/{tmdbId}[?season=&episode=] (single)
 *
 * Where:
 *   - `type` is "movie" or "series"
 *   - `provider` is one of the lowercased provider keys recognised by
 *     the server (see
 *     [com.kiduyuk.klausk.kiduyutv.ui.player.directstream.playback.StreamCatalog])
 *   - `season` / `episode` are required only when `type == "series"`
 */
object ProvidersApi {

    private const val TAG = "KiduyuLiteProvider"
    private const val PROVIDER_BASE_URL = "https://sflatransport.com/kiduyuTv_providers"

    /**
     * Process-wide OkHttp client.
     *
     *   - 5s connect / 10s read timeouts — the providers API only
     *     serves small JSON manifests, not the streams themselves, so
     *     the previous 15s/30s timeouts were unnecessarily patient
     *     (and made every "stream list unavailable" scenario feel
     *     slow).
     *   - 5 idle connections × 5 min keep-alive — most titles are
     *     opened in bursts, so reusing the TCP+TLS session to the
     *     providers host shaves 100-1000ms off the second and later
     *     calls in a viewing session.
     *   - HTTP/2 enabled by default in OkHttp 4.x; the providers host
     *     serves h2.
     */
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * Returns the server-side keys of providers currently enabled by
     * `/api/providers`. Disabled entries are deliberately omitted.
     */
    fun enabledProviderNames(): List<String> {
        val urlString = "$PROVIDER_BASE_URL/api/providers"
        Log.i(TAG, "GET $urlString")
        val request = Request.Builder()
            .url(urlString)
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw IOException("Providers API HTTP ${response.code}")
                val json = JSONObject(body)
                if (!json.optBoolean("success", false)) {
                    throw IOException("Providers API returned success=false")
                }
                val providers = json.optJSONArray("providers")
                    ?: throw IOException("Providers API response has no providers array")
                buildList {
                    for (index in 0 until providers.length()) {
                        val item = providers.optJSONObject(index) ?: continue
                        val name = item.optString("name").trim().lowercase()
                        if (item.optBoolean("enabled", false) && name.isNotBlank()) add(name)
                    }
                }.distinct().also {
                    Log.i(TAG, "Enabled providers (${it.size}): ${it.joinToString()}")
                }
            }
        }.getOrElse { error ->
            Log.w(TAG, "enabledProviderNames failed: ${error.message}")
            emptyList()
        }
    }

    /**
     * Fetch the full stream list (blocking). Prefer [streamsFlow] for
     * first-item-wins playback so playback can start as soon as a
     * single stream is parsed.
     */
    fun streams(
        type: String,
        tmdbId: Int,
        season: Int? = null,
        episode: Int? = null,
        provider: String? = null
    ): StreamResponse {
        require(type == "movie" || type == "series") { "invalid type: $type" }
        require(tmdbId > 0) { "invalid tmdbId: $tmdbId" }

        val urlString = buildStreamUrl(type, tmdbId, season, episode, provider)
        val providerLabel = provider?.takeIf { it.isNotBlank() } ?: "aggregate"
        Log.i(
            TAG,
            "Request provider=$providerLabel type=$type tmdbId=$tmdbId " +
                "season=${season ?: "-"} episode=${episode ?: "-"}"
        )
        Log.i(TAG, "GET $urlString")

        val request = Request.Builder()
            .url(urlString)
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .build()

        val response = client.newCall(request).execute()
        response.use {
            val body = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                Log.w(TAG, "HTTP ${it.code} from providers API (provider=$providerLabel)")
                Log.w(TAG, "Body[0..200]=${body.take(200)}")
                throw IOException("Providers API HTTP ${it.code}")
            }
            val parsed = parse(JSONObject(body))
            Log.i(
                TAG,
                "Response provider=$providerLabel tmdbId=${parsed.tmdbId} " +
                    "imdbId=${parsed.imdbId ?: "-"} count=${parsed.streams.size}"
            )
            parsed.streams.forEachIndexed { index, item ->
                val scheme = item.url.substringBefore(':').uppercase()
                Log.i(
                    TAG,
                    "  stream[$index] provider=${item.provider.ifBlank { "?" }} " +
                        "quality=${item.quality} scheme=$scheme " +
                        "host=${runCatching { android.net.Uri.parse(item.url).host }.getOrNull() ?: "?"} " +
                        "headers=${item.headers.size} url=${item.url}"
                )
            }
            return parsed
        }
    }

    /**
     * Streaming variant of [streams]. Emits [StreamItem]s as they are
     * parsed out of the JSON response, so the caller can begin playback
     * the moment the first stream is known instead of waiting for the
     * full list to be parsed.
     *
     * The returned Flow is cold; collect on a background dispatcher
     * (the resolver wraps it in [Dispatchers.IO]).
     */
    fun streamsFlow(
        type: String,
        tmdbId: Int,
        season: Int? = null,
        episode: Int? = null,
        provider: String? = null
    ): Flow<StreamItem> = callbackFlow {
        require(type == "movie" || type == "series") { "invalid type: $type" }
        require(tmdbId > 0) { "invalid tmdbId: $tmdbId" }

        val urlString = buildStreamUrl(type, tmdbId, season, episode, provider)
        val providerLabel = provider?.takeIf { it.isNotBlank() } ?: "aggregate"
        Log.i(
            TAG,
            "StreamRequest provider=$providerLabel type=$type tmdbId=$tmdbId " +
                "season=${season ?: "-"} episode=${episode ?: "-"} url=$urlString"
        )

        val request = Request.Builder()
            .url(urlString)
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .build()

        val call = client.newCall(request)
        // Cancel the HTTP call if the collector goes away.
        invokeOnClose { runCatching { call.cancel() } }

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.w(TAG, "streamsFlow request failed: ${e.message}")
                close(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        val body = it.body?.string().orEmpty()
                        if (!it.isSuccessful) {
                            val msg = "Providers API HTTP ${it.code}"
                            Log.w(TAG, "$msg (provider=$providerLabel)")
                            Log.w(TAG, "Body[0..200]=${body.take(200)}")
                            close(IOException(msg))
                            return
                        }
                        val parsed = parse(JSONObject(body))
                        Log.i(
                            TAG,
                            "StreamResponse provider=$providerLabel " +
                                "tmdbId=${parsed.tmdbId} imdbId=${parsed.imdbId ?: "-"} " +
                                "count=${parsed.streams.size}"
                        )
                        var emitted = 0
                        parsed.streams.forEach { item ->
                            if (!isClosedForSend) {
                                trySend(item)
                                emitted++
                            }
                        }
                        Log.i(TAG, "Emitted $emitted streams for provider=$providerLabel")
                        close()
                    }
                } catch (error: Exception) {
                    Log.w(TAG, "streamsFlow failed: ${error.message}")
                    close(error)
                }
            }
        })

        awaitClose { runCatching { call.cancel() } }
    }.flowOn(Dispatchers.IO)

    private fun buildStreamUrl(
        type: String,
        tmdbId: Int,
        season: Int?,
        episode: Int?,
        provider: String?
    ): String {
        val pathSegment = if (provider.isNullOrBlank()) {
            "api/streams/$type/$tmdbId"
        } else {
            "api/streams/${provider.lowercase()}/$type/$tmdbId"
        }
        val httpBuilder = "$PROVIDER_BASE_URL/$pathSegment".toHttpUrl().newBuilder()
        if (type == "series") {
            season?.let { httpBuilder.addQueryParameter("season", it.toString()) }
            episode?.let { httpBuilder.addQueryParameter("episode", it.toString()) }
        }
        return httpBuilder.build().toString()
    }

    /**
     * Parses the response JSON. Note the server sends `tmdbId` as a
     * string (e.g. `"550"`) even though it round-trips an int on the
     * way in, so we coerce it. `imdbId` may be JSON `null`, which
     * `optString` surfaces as the literal `"null"` — we treat that as
     * absent.
     */
    private fun parse(json: JSONObject): StreamResponse {
        val tmdbId = json.optString("tmdbId")
            .toIntOrNull()
            ?: json.optInt("tmdbId", 0)
        val imdbId = json.optString("imdbId")
            .takeUnless { it.isBlank() || it.equals("null", ignoreCase = true) }
        val arr = json.optJSONArray("streams") ?: return StreamResponse(tmdbId, imdbId, emptyList())
        val items = buildList {
            for (i in 0 until arr.length()) {
                parseStream(arr.optJSONObject(i))?.let { add(it) }
            }
        }
        return StreamResponse(tmdbId, imdbId, items)
    }

    private fun parseStream(s: JSONObject?): StreamItem? {
        if (s == null) return null
        val url = s.optString("url").takeIf { it.isNotBlank() } ?: return null
        val provider = s.optString("provider", "")
        val isVixsrcHls = provider.equals("vixsrc", ignoreCase = true) &&
            url.contains("vixsrc.to/playlist/", ignoreCase = true)
        val type = s.optString("type", "").ifBlank {
            if (isVixsrcHls) "hls" else ""
        }
        val mimeType = s.optString(
            "mimeType",
            s.optString("contentType", "")
        ).ifBlank {
            if (isVixsrcHls) HLS_MIME_TYPE else ""
        }
        val headers = s.optJSONObject("headers")?.let { h ->
            val map = LinkedHashMap<String, String>(h.length())
            h.keys().forEach { k -> map[k] = h.optString(k) }
            map
        } ?: linkedMapOf()
        val cookie = when {
            s.optJSONObject("cookies") != null -> {
                val cookies = s.getJSONObject("cookies")
                buildList {
                    cookies.keys().forEach { name ->
                        val value = cookies.optString(name)
                        if (name.isNotBlank() && value.isNotBlank()) add("$name=$value")
                    }
                }.joinToString("; ")
            }
            s.optString("cookie").isNotBlank() -> s.optString("cookie")
            s.optString("cookies").isNotBlank() -> s.optString("cookies")
            else -> ""
        }
        if (cookie.isNotBlank() && headers.keys.none { it.equals("Cookie", true) }) {
            headers["Cookie"] = cookie
        }
        return StreamItem(
            title = s.optString("title", s.optString("name", "Stream")),
            name = s.optString("name"),
            url = url,
            quality = s.optString("quality", "Auto"),
            provider = provider,
            type = type,
            mimeType = mimeType,
            headers = headers
        )
    }

    private const val USER_AGENT = "KiduyuTVLite/1.0 (Android)"
    private const val HLS_MIME_TYPE = "application/vnd.apple.mpegurl"
}
