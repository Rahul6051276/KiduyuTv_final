package com.kiduyuk.klausk.kiduyutv.ui.player.directstream.playback

import android.util.Log
import com.kiduyuk.klausk.kiduyutv.ui.player.directstream.api.ProvidersApi
import com.kiduyuk.klausk.kiduyutv.ui.player.directstream.model.StreamItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext

/**
 * Fetches streams from the kiduyuTv_providers server and surfaces them.
 * The caller decides which stream to play (and may show a quality
 * picker to the user first).
 */
class StreamResolver {

    private val tag = "KiduyuLiteProvider"

    /**
     * Returns the full stream list returned by the server, in the order
     * the server provided. For series titles, both [season] and
     * [episode] are required and must be > 0.
     *
     * Kept for callers that still want the full list before deciding
     * what to play. New code should prefer [loadFlow] so playback can
     * start on the first parsed item.
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
            "Resolver.load provider=${provider.displayName} " +
                "key=${provider.key.ifEmpty { "<aggregate>" }} " +
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

    /**
     * Streaming variant of [load]. Emits [StreamItem]s as soon as they
     * are parsed out of the providers response. The activity collects
     * this and calls [play] for the highest-ranked item as soon as the
     * first one arrives — playback begins before the rest of the list
     * is even parsed.
     *
     * The full list is still materialised internally so callers that
     * need the complete picture (e.g. to populate the Streams picker
     * dialog) can collect `take(count).toList()` once the response
     * ends. The default activity path only consumes the first.
     */
    fun loadFlow(
        type: String,
        tmdbId: Int,
        season: Int? = null,
        episode: Int? = null,
        provider: StreamProviderChoice = StreamCatalog.default
    ): Flow<StreamItem> {
        Log.i(
            tag,
            "Resolver.loadFlow provider=${provider.displayName} " +
                "key=${provider.key.ifEmpty { "<aggregate>" }} " +
                "type=$type tmdbId=$tmdbId season=${season ?: "-"} episode=${episode ?: "-"}"
        )
        return ProvidersApi.streamsFlow(
            type = type,
            tmdbId = tmdbId,
            season = season,
            episode = episode,
            provider = provider.key.takeIf { it.isNotEmpty() }
        ).flowOn(Dispatchers.IO)
    }
}
