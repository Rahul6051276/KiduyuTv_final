package com.kiduyuk.klausk.kiduyutv.data.model

import com.google.gson.annotations.SerializedName

/**
 * One segment returned by SkipDB for a movie or episode.
 */
data class SkipSegment(
    @SerializedName("start_ms") val startMs: Long,
    @SerializedName("end_ms") val endMs: Long?,
    @SerializedName("match") val match: String?,
    @SerializedName("adjusted") val adjusted: Boolean?,
    @SerializedName("offset_ms") val offsetMs: Long?,
    @SerializedName("confidence") val confidence: Double?
)

enum class SkipSegmentType(val wire: String) {
    INTRO("intro"),
    RECAP("recap"),
    OUTRO("outro"),
    PREVIEW("preview")
}

data class SkipSegments(
    @SerializedName("intro") val intro: SkipSegment?,
    @SerializedName("recap") val recap: SkipSegment?,
    @SerializedName("outro") val outro: SkipSegment?,
    @SerializedName("preview") val preview: SkipSegment?
) {
    fun get(type: SkipSegmentType): SkipSegment? = when (type) {
        SkipSegmentType.INTRO -> intro
        SkipSegmentType.RECAP -> recap
        SkipSegmentType.OUTRO -> outro
        SkipSegmentType.PREVIEW -> preview
    }
}

data class SkipSegmentsResponse(
    @SerializedName("imdb_id") val imdbId: String,
    @SerializedName("season") val season: Int?,
    @SerializedName("episode") val episode: Int?,
    @SerializedName("segments") val segments: SkipSegments,
    @SerializedName("intro_length_estimate_ms") val introLengthEstimateMs: Long?
)

object SkipSegmentQuality {
    const val MIN_CONFIDENCE = 0.4

    fun isUsable(segment: SkipSegment?): Boolean {
        if (segment == null) return false
        val confidence = segment.confidence ?: 0.0
        if (confidence < MIN_CONFIDENCE) return false
        if (segment.match == "out-of-range") return false
        return true
    }
}
