package com.kiduyuk.klausk.kiduyutv.ui.player.directstream.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Tracks
import java.util.Locale

/**
 * Builds human-readable labels for [Format]s and [Tracks.Group]s.
 *
 * The HLS manifests returned by the providers server usually include:
 *   - video: resolution (`1280x720`) + bitrate (`2500 kbps`) + codec
 *   - audio: language + bitrate + sample rate + channel count
 *   - text (subtitle): language + label
 *
 * Labels are deliberately short so a 640px-wide dialog can fit one per
 * row without truncating the channel count or codec.
 */
object TrackFormatter {

    /**
     * Returns a one-line description for a single [Format] (e.g.
     * `"1280x720 · 2500 kbps · avc1.640028"`).
     */
    fun describe(format: Format): String {
        val parts = mutableListOf<String>()

        val resolution = resolutionOf(format)
        if (resolution != null) parts.add(resolution)

        val bitrate = bitrateOf(format)
        if (bitrate != null) parts.add(bitrate)

        val sampleRate = sampleRateOf(format)
        if (sampleRate != null) parts.add(sampleRate)

        val channels = channelsOf(format)
        if (channels != null) parts.add(channels)

        format.codecs?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
        return parts.joinToString(" · ")
    }

    /**
     * Returns a short title for a track row (used as the main line in
     * the dialog list). Prefers the explicit `label`, then the language,
     * then a fallback derived from the format (e.g. "1280x720").
     */
    fun titleOf(format: Format): String {
        format.label?.takeIf { it.isNotBlank() }?.let { return it }
        format.language
            ?.takeUnless { it.isBlank() || it.equals("und", ignoreCase = true) }
            ?.let { return languageDisplay(it) }
        resolutionOf(format)?.let { return it }
        channelsOf(format)?.let { return "$it audio" }
        return "Track"
    }

    /** Returns the language code as a readable name when possible. */
    fun languageDisplay(language: String): String {
        if (language.isBlank() || language.equals("und", ignoreCase = true)) return "Default"
        val locale = Locale(language)
        val display = locale.displayLanguage
        return if (display.isNullOrBlank() || display == language) language.uppercase()
               else display
    }

    /** Returns true when the [Tracks] object exposes any selectable track. */
    fun hasSelectableTracks(tracks: Tracks): Boolean =
        tracks.groups.any { it.length > 0 }

    private fun resolutionOf(format: Format): String? {
        val w = format.width
        val h = format.height
        if (w == Format.NO_VALUE || h == Format.NO_VALUE) return null
        if (w <= 0 || h <= 0) return null
        return "${w}x${h}"
    }

    private fun bitrateOf(format: Format): String? {
        val bitrate = format.bitrate
        if (bitrate == Format.NO_VALUE || bitrate <= 0) return null
        return when {
            bitrate >= 1_000_000 -> String.format(Locale.US, "%.1f Mbps", bitrate / 1_000_000.0)
            bitrate >= 1_000     -> String.format(Locale.US, "%d kbps", bitrate / 1_000)
            else                 -> String.format(Locale.US, "%d bps", bitrate)
        }
    }

    private fun sampleRateOf(format: Format): String? {
        val rate = format.sampleRate
        if (rate == Format.NO_VALUE || rate <= 0) return null
        return String.format(Locale.US, "%.1f kHz", rate / 1000.0)
    }

    private fun channelsOf(format: Format): String? {
        val count = format.channelCount
        if (count == Format.NO_VALUE || count <= 0) return null
        return if (count == 1) "Mono" else if (count == 2) "Stereo" else "${count}ch"
    }

    /** Human-readable name for a track type constant. */
    fun nameForType(type: Int): String = when (type) {
        C.TRACK_TYPE_VIDEO -> "Video"
        C.TRACK_TYPE_AUDIO -> "Audio"
        C.TRACK_TYPE_TEXT  -> "Subtitle"
        else               -> "Track"
    }
}
