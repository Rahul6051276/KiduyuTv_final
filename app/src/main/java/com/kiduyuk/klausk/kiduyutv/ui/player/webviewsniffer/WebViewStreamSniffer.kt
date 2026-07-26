package com.kiduyuk.klausk.kiduyutv.ui.player.webviewsniffer

import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import java.util.concurrent.atomic.AtomicBoolean

data class SniffedStream(
    val url: String,
    val headers: Map<String, String>,
    val cookie: String?,
    val type: String,
    val mimeType: String
)

/**
 * Detects manifest and direct-media requests made by a provider WebView.
 * A sniffer instance captures only the first playable request so manifests,
 * segments, and retries cannot launch multiple native players.
 */
class WebViewStreamSniffer(
    private val onStreamCaptured: (SniffedStream) -> Unit
) {
    private val captured = AtomicBoolean(false)

    fun inspect(request: WebResourceRequest?) {
        val requestValue = request ?: return
        if (requestValue.isForMainFrame) return
        val url = requestValue.url?.toString().orEmpty()
        val mediaType = detectMediaType(url) ?: return
        if (!captured.compareAndSet(false, true)) return

        val headers = LinkedHashMap<String, String>().apply {
            putAll(requestValue.requestHeaders.orEmpty())
        }
        val cookie = runCatching {
            CookieManager.getInstance().getCookie(url)
        }.getOrNull()?.takeIf { it.isNotBlank() }

        onStreamCaptured(
            SniffedStream(
                url = url,
                headers = headers,
                cookie = cookie,
                type = mediaType.first,
                mimeType = mediaType.second
            )
        )
    }

    private fun detectMediaType(url: String): Pair<String, String>? {
        val normalized = url.lowercase()
        return when {
            ".m3u8" in normalized || "/m3u8-proxy" in normalized ->
                "hls" to "application/vnd.apple.mpegurl"
            ".mpd" in normalized ->
                "dash" to "application/dash+xml"
            normalized.substringBefore('?').endsWith(".mp4") ->
                "direct" to "video/mp4"
            normalized.substringBefore('?').endsWith(".mkv") ->
                "direct" to "video/x-matroska"
            normalized.substringBefore('?').endsWith(".webm") ->
                "direct" to "video/webm"
            else -> null
        }
    }
}
