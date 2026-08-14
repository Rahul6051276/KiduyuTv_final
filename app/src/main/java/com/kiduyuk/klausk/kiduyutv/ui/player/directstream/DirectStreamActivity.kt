    private var currentImdbId: String? = null
    private var skipData: SkipSegmentsResponse? = null
    private var shownSkipType: SkipSegmentType? = null
    // Track which content the skipData corresponds to so we don't clear
    // the UI when a transient fetch failure happens while switching
    // streams for the same title.
    private var skipLoadedForImdbId: String? = null
    private var skipLoadedForTmdbId: Int? = null
    // Remember which segment we've already auto-skipped to avoid repeats
    private var autoSkippedSegmentStartMs: Long? = null
    private lateinit var settingsManager: com.kiduyuk.klausk.kiduyutv.util.SettingsManager
    ): String = "$type|$tmdbId|${season ?: 0}|${episode ?: 0}|${provider.key}"

    private fun loadSkipSegments() {
        // Prefer a known IMDb id. If missing for TV shows, resolve it from
        // TMDB's external_ids endpoint and pass that to SkipDB.
        lifecycleScope.launch {
            val currentImdb = currentImdbId
            val currentTmdb = currentTmdbId
            val durationMs = engine.player.duration.takeIf { it > 0L }
            val result: SkipSegmentsResponse? = when {
                !currentImdbId.isNullOrBlank() -> {
                }
                else -> null
            }
            // If we successfully fetched segments, record which content
            // they belong to and update the UI. If the fetch failed but
            // the previously-loaded segments belong to the same content,
            // keep them rather than clearing the UI (avoids blink when
            // switching streams). Only clear when we truly don't have
            // segments for this content.
            if (result != null) {
                skipData = result
                skipLoadedForImdbId = currentImdb
                skipLoadedForTmdbId = currentTmdb
                // Draw every valid intro/recap/outro/preview interval once
                // the SkipDB response arrives. The custom SeekBar keeps these
                // colors visible while playback continues.
                binding.seekBar.setSegments(result.segments)
            } else {
                val sameContent = (skipLoadedForImdbId != null && skipLoadedForImdbId == currentImdb) ||
                    (skipLoadedForTmdbId != null && skipLoadedForTmdbId == currentTmdb && currentImdb.isNullOrBlank())
                if (!sameContent) {
                    skipData = null
                    skipLoadedForImdbId = null
                    skipLoadedForTmdbId = null
                    binding.seekBar.clearHighlights()
                    hideSkipButton()
                }
                // else: keep existing skipData for the same content
            }
        }
    }

    private fun updateSkipButton() {
        val data = skipData ?: run {
            binding.seekBar.clearHighlights()
