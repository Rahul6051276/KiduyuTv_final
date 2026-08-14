package com.kiduyuk.klausk.kiduyutv.data.api

import com.kiduyuk.klausk.kiduyutv.data.model.SkipSegmentsResponse
import retrofit2.http.GET
import retrofit2.http.Query

interface SkipDbApiService {
    companion object {
        const val BASE_URL = "https://api.skipdb.tv/"
    }

    @GET("api/segments")
    suspend fun getSegments(
        @Query("imdb_id") imdbId: String,
        @Query("season") season: Int? = null,
        @Query("episode") episode: Int? = null,
        @Query("type") type: String? = null,
        @Query("duration") durationSec: Long? = null,
        @Query("adjust") adjust: String? = "conservative"
    ): SkipSegmentsResponse
}
