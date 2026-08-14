package com.kiduyuk.klausk.kiduyutv.ui.player.directstream

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.kiduyuk.klausk.kiduyutv.R
import com.kiduyuk.klausk.kiduyutv.data.api.TmdbApiService
import com.kiduyuk.klausk.kiduyutv.data.model.Episode

/**
 * RecyclerView adapter for the episodes side panel.
 *
 * The currently playing episode is highlighted with a coloured badge and
 * is rendered as the focused row by default so the D-pad "feels right"
 * when the user opens the panel.
 */
class EpisodeAdapter(
    private val onClick: (Episode) -> Unit
) : ListAdapter<Episode, EpisodeAdapter.EpisodeViewHolder>(DIFF) {

    private var currentlyPlayingEpisodeNumber: Int? = null

    fun setCurrentlyPlaying(episodeNumber: Int?) {
        if (currentlyPlayingEpisodeNumber == episodeNumber) return
        currentlyPlayingEpisodeNumber = episodeNumber
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EpisodeViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_episode_row, parent, false)
        return EpisodeViewHolder(view, onClick)
    }

    override fun onBindViewHolder(holder: EpisodeViewHolder, position: Int) {
        val episode = getItem(position)
        val isPlaying = episode.episodeNumber == currentlyPlayingEpisodeNumber
        holder.bind(episode, isPlaying)
        // Auto-focus the row that matches the currently playing episode so
        // the user's D-pad selection lands on a sensible place.
        if (isPlaying) holder.itemView.post { holder.itemView.requestFocus() }
    }

    class EpisodeViewHolder(
        view: View,
        private val onClick: (Episode) -> Unit
    ) : RecyclerView.ViewHolder(view) {

        private val root: View = view.findViewById(R.id.episodeRowRoot)
        private val still: ImageView = view.findViewById(R.id.ivEpisodeStill)
        private val stillFallback: TextView = view.findViewById(R.id.tvEpisodeStillFallback)
        private val numberAndTitle: TextView = view.findViewById(R.id.tvEpisodeNumberAndTitle)
        private val runtime: TextView = view.findViewById(R.id.tvEpisodeRuntime)
        private val playingBadge: TextView = view.findViewById(R.id.tvEpisodePlayingBadge)

        fun bind(episode: Episode, isPlaying: Boolean) {
            numberAndTitle.text = "${episode.episodeNumber}. ${episode.name ?: "Episode ${episode.episodeNumber}"}"

            val ctx = itemView.context
            val minutes = episode.runtime
            runtime.text = if (minutes != null && minutes > 0) {
                ctx.getString(R.string.episode_runtime_minutes, minutes)
            } else {
                ""
            }
            runtime.visibility = if (runtime.text.isNullOrEmpty()) View.GONE else View.VISIBLE

            val stillPath = episode.stillPath
            if (!stillPath.isNullOrBlank()) {
                stillFallback.visibility = View.GONE
                still.visibility = View.VISIBLE
                Glide.with(ctx)
                    .load(TmdbApiService.IMAGE_BASE_URL + TmdbApiService.STILL_SIZE + stillPath)
                    .placeholder(R.drawable.bg_episode_panel)
                    .into(still)
            } else {
                still.visibility = View.GONE
                stillFallback.visibility = View.VISIBLE
                stillFallback.text = "S${episode.seasonNumber}E${episode.episodeNumber}"
            }

            playingBadge.visibility = if (isPlaying) View.VISIBLE else View.GONE

            root.setOnClickListener { onClick(episode) }
            root.setOnKeyListener { _, keyCode, event ->
                if (event.action == android.view.KeyEvent.ACTION_DOWN &&
                    (keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                     keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
                     keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER)) {
                    onClick(episode)
                    true
                } else false
            }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Episode>() {
            override fun areItemsTheSame(oldItem: Episode, newItem: Episode) =
                oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: Episode, newItem: Episode) =
                oldItem == newItem
        }
    }
}
