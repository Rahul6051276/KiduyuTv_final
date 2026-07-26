package com.kiduyuk.klausk.kiduyutv.ui.player.directstream.model

/**
 * One stream returned by the kiduyuTv_providers server.
 *
 * With `enableProxy=false` on the server, the [url] points directly at the
 * upstream CDN. The [headers] map (typically `Referer` + `User-Agent`) must
 * be attached when fetching the manifest and segments; [PlayerEngine] does
 * this at the DataSource level.
 */
data class StreamItem(
    /** Short label, e.g. `"Vixsrc - 1080p"`. Used as a fallback display name. */
    val title: String,
    /** Verbose label, e.g. `"Vidfast vRapid"`. Optional; empty when server omits it. */
    val name: String = "",
    /** Direct HTTPS URL to the upstream HLS playlist or progressive file. */
    val url: String,
    /** Quality hint, e.g. `"1080p"`, `"720p"`, `"Auto"`. */
    val quality: String,
    /** Originating provider key, e.g. `"vidfast"`, `"vixsrc"`. */
    val provider: String,
    /** Optional backend media hint such as `"hls"` or `"progressive"`. */
    val type: String = "",
    /** Optional MIME type, for example `application/vnd.apple.mpegurl`. */
    val mimeType: String = "",
    /** HTTP headers to attach when fetching the manifest and segments. */
    val headers: Map<String, String> = emptyMap()
)

data class SubtitleItem(
    val url: String,
    val mimeType: String,
    val language: String? = null,
    val label: String? = null,
    val headers: Map<String, String> = emptyMap()
)

/**
 * Top-level response from `GET /api/streams/{type}/{tmdbId}`. Used for
 * diagnostics and resume in the future; today only the [streams] list is
 * consumed.
 */
data class StreamResponse(
    val tmdbId: Int,
    val imdbId: String?,
    val streams: List<StreamItem>
)
