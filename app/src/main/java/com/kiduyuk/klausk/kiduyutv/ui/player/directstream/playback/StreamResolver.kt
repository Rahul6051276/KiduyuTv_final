package com.kiduyuk.klausk.kiduyutv.ui.player.directstream.playback

import android.util.Log
import com.kiduyuk.klausk.kiduyutv.ui.player.directstream.api.ProvidersApi
import com.kiduyuk.klausk.kiduyutv.ui.player.directstream.model.StreamItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Fetches streams from the kiduyuTv_providers server and surfaces them as a
 * list. The caller decides which stream to play (and may show a quality
 * picker to the user first).
 */
class StreamResolver {

    private val tag = "KiduyuLiteProvider"

    /**
     * Returns the full stream list returned by the server, in the order the
     * server provided. For series titles, both [season] and [episode] are
     * required and must be > 0.
     */
    suspend fun load(
        type: String,
        tmdbId: Int,
        season: Int? = null,
        episode: Int? = null,
        provider: StreamProviderChoice = StreamCatalog.default
    ): List<StreamItem> = withContext(Dispatchers.IO) {
        Log.i(
            tag,
            "Resolver.load provider=${provider.displayName} key=${provider.key.ifEmpty { "<aggregate>" }} " +
                "type=$type tmdbId=$tmdbId season=${season ?: "-"} episode=${episode ?: "-"}"
        )
        val result = ProvidersApi.streams(
            type = type,
            tmdbId = tmdbId,
            season = season,
            episode = episode,
            provider = provider.key.takeIf { it.isNotEmpty() }
        )
        Log.i(tag, "Resolver.load returned ${result.streams.size} streams")
        result.streams
    }
}
