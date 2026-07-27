package com.kiduyuk.klausk.kiduyutv.ui.player.directstream.model

/**
 * External subtitle source merged into the active Media3 playback source.
 *
 * The URL can point to an HTTP(S) subtitle or a downloaded local cache file.
 * Request headers are applied when Media3 fetches remote subtitle content.
 */
data class SubtitleItem(
    val url: String,
    val mimeType: String,
    val language: String? = null,
    val label: String? = null,
    val headers: Map<String, String> = emptyMap()
)
