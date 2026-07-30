package com.kiduyuk.klausk.kiduyutv.ui.player.directstream

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.ui.AspectRatioFrameLayout
import com.bumptech.glide.Glide
import com.kiduyuk.klausk.kiduyutv.data.local.database.DatabaseManager
import com.kiduyuk.klausk.kiduyutv.data.repository.TmdbRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.kiduyuk.klausk.kiduyutv.R
import com.kiduyuk.klausk.kiduyutv.databinding.ActivityDirectStreamBinding
import com.kiduyuk.klausk.kiduyutv.ui.player.directstream.model.StreamItem
import com.kiduyuk.klausk.kiduyutv.ui.player.directstream.model.SubtitleItem
import com.kiduyuk.klausk.kiduyutv.ui.player.directstream.api.SubdlSubtitleClient
import com.kiduyuk.klausk.kiduyutv.ui.player.directstream.api.SubdlSubtitleResult
import com.kiduyuk.klausk.kiduyutv.ui.player.webviewsniffer.SniffedSubtitle
import com.kiduyuk.klausk.kiduyutv.ui.player.directstream.playback.PlayerEngine
import com.kiduyuk.klausk.kiduyutv.ui.player.directstream.playback.StreamCatalog
import com.kiduyuk.klausk.kiduyutv.ui.player.directstream.playback.StreamProviderChoice
import com.kiduyuk.klausk.kiduyutv.ui.player.directstream.playback.StreamResolver
import com.kiduyuk.klausk.kiduyutv.ui.player.directstream.playback.StreamSelectionDialog
import com.kiduyuk.klausk.kiduyutv.ui.player.directstream.playback.TrackSelectionDialog
import com.kiduyuk.klausk.kiduyutv.util.FirebaseManager
import com.kiduyuk.klausk.kiduyutv.util.QuitDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import org.json.JSONArray

/**
 * TV-first native player for streams returned by the kiduyuTv_providers
 * (TMDB-Embed-API) server. Replaces the previous WebView-based
 * implementation: no JS injection, no ad blocker, no provider host
 * allowlist.
 *
 * Flow:
 *   1. Read the title metadata from Intent extras (type, tmdbId,
 *      season/episode, provider).
 *   2. Call [ProvidersApi.streams] via [StreamResolver] to fetch every
 *      available stream.
 *   3. Pick the highest-ranked stream (or fall back to the first) and
 *      hand it to [PlayerEngine.play].
 *   4. Map D-pad keys to native player actions: left/right ramp-seek,
 *      center play/pause, back to finish.
 */
class DirectStreamActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDirectStreamBinding
    private lateinit var engine: PlayerEngine
    private lateinit var resolver: StreamResolver

    private var streamJob: Job? = null
    private var trackDialog: TrackSelectionDialog? = null
    private var streamDialog: StreamSelectionDialog? = null
    private var subtitleDialog: AlertDialog? = null
    private var quitDialog: QuitDialog? = null
    private var subtitleJob: Job? = null
    private var availableStreams: List<StreamItem> = emptyList()
    private var activeStream: StreamItem? = null
    private var activeSubtitles: List<SubtitleItem> = emptyList()
    private val uiHandler = Handler(Looper.getMainLooper())
    private var controlsLockedVisible = false
    private var userSeeking = false
    private var resizeModeIndex = 0
    private var muted = false
    private var currentMediaType = TYPE_MOVIE
    private var currentTmdbId = 0
    private var currentSeason: Int? = null
    private var currentEpisode: Int? = null
    private var currentTitle: String = ""
    private var currentOverview: String? = null
    private var currentPosterPath: String? = null
    private var currentBackdropPath: String? = null
    private var currentVoteAverage: Double = 0.0
    private var currentReleaseDate: String? = null
    private var currentProvider: StreamProviderChoice = StreamCatalog.default
    private val repository = TmdbRepository()
    private var pendingStartPositionMs = 0L
    private var pendingReadySeekPositionMs = 0L
    private var handlingPlaybackError = false
    private var watchHistoryReady = false
    private val controlsClock = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
    private val controlsTime = SimpleDateFormat("h:mm a", Locale.getDefault())

    private val watchProgressTick = object : Runnable {
        override fun run() {
            persistWatchProgress()
            uiHandler.postDelayed(this, WATCH_PROGRESS_INTERVAL_MS)
        }
    }

    private val progressTick = object : Runnable {
        override fun run() {
            if (::engine.isInitialized) {
                val duration = engine.player.duration.takeIf { it > 0 } ?: 0L
                val currentPosition = engine.player.currentPosition.coerceAtLeast(0L)
                if (!userSeeking) {
                    binding.seekBar.progress =
                        if (duration > 0) ((currentPosition * 1000L) / duration).toInt() else 0
                    binding.tvCurrentTime.text = formatPlaybackTime(currentPosition)
                }
                binding.tvTotalTime.text = formatPlaybackTime(duration)
                binding.seekBar.secondaryProgress =
                    if (duration > 0) {
                        ((engine.player.bufferedPosition.coerceAtMost(duration) * 1000L) / duration).toInt()
                    } else {
                        0
                    }
                binding.btnPlayPause.text = if (engine.player.isPlaying) "Ⅱ" else "▶"
            }
            val now = Date()
            binding.tvDate.text = controlsClock.format(now)
            binding.tvTime.text = controlsTime.format(now)
            uiHandler.postDelayed(this, 1_000)
        }
    }

    // D-pad left/right ramp seeking: 10s on press, repeating every 600ms
    // and ramping up to 60s after 5 seconds of holding.
    private var skipDirection = 0
    private var skipHoldStart = 0L
    private val skipTick = object : Runnable {
        override fun run() {
            if (skipDirection == 0) return
            val held = System.currentTimeMillis() - skipHoldStart
            val progress = (held.toFloat() / SKIP_RAMP_DURATION_MS).coerceIn(0f, 1f)
            val seconds = (SKIP_SEC_MIN +
                (SKIP_SEC_MAX - SKIP_SEC_MIN) * progress).toInt()
            val deltaMs = (if (skipDirection < 0) -seconds else seconds) * 1000L
            engine.seekBy(deltaMs)
            uiHandler.postDelayed(this, SKIP_REPEAT_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "Player activity created")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityDirectStreamBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Glide.with(this)
            .load(normalizeArtworkUrl(intent.getStringExtra(EXTRA_BACKDROP_URL)))
            .into(binding.loadingBackdrop)
        showLoadingArtwork()

        currentMediaType = intent.getStringExtra(EXTRA_TYPE)
            ?: if (intent.getBooleanExtra(EXTRA_IS_TV, false)) TYPE_SERIES else TYPE_MOVIE
        currentTmdbId = intent.getIntExtra(EXTRA_TMDB_ID, 0)
        currentSeason = intent.getIntExtra(EXTRA_SEASON, -1).takeIf { it > 0 }
        currentEpisode = intent.getIntExtra(EXTRA_EPISODE, -1).takeIf { it > 0 }
        currentTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        currentOverview = intent.getStringExtra(EXTRA_OVERVIEW)
        currentPosterPath = intent.getStringExtra(EXTRA_POSTER_PATH)
        currentBackdropPath = intent.getStringExtra(EXTRA_BACKDROP_URL)
        currentVoteAverage = intent.getDoubleExtra(EXTRA_VOTE_AVERAGE, 0.0)
        currentReleaseDate = intent.getStringExtra(EXTRA_RELEASE_DATE)
        currentProvider = StreamCatalog.resolve(intent.getStringExtra(EXTRA_PROVIDER))
        updatePlayerTitle()

        Log.i(
            PROVIDER_TAG,
            "Player opened type=$currentMediaType tmdbId=$currentTmdbId " +
                "season=${currentSeason ?: "-"} episode=${currentEpisode ?: "-"} " +
                "provider=${currentProvider.displayName} key=${currentProvider.key.ifEmpty { "<aggregate>" }}"
        )

        if (currentTmdbId <= 0) {
            Toast.makeText(this, R.string.playback_link_unavailable, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        resolver = StreamResolver()
        engine = PlayerEngine(this).apply {
            onError = { code -> handlePlaybackError(code) }
            onPlaybackStateChanged = { state ->
                when (state) {
                    Player.STATE_BUFFERING -> {
                        showLoadingArtwork()
                        showStatus(getString(R.string.buffering), retry = false)
                    }
                    Player.STATE_READY -> {
                        binding.playerStatus.visibility = View.GONE
                        applyPendingReadySeek()
                        startWatchProgressUpdates()
                    }
                    Player.STATE_ENDED -> {
                        stopWatchProgressUpdates()
                        persistWatchProgress()
                        binding.playerStatus.visibility = View.GONE
                    }
                    // Preserve "Loading streams" and retry messages while
                    // Media3 is idle; IDLE does not mean the request failed.
                    Player.STATE_IDLE -> Unit
                }
            }
            onIsPlayingChanged = { isPlaying ->
                if (isPlaying) hideLoadingArtwork()
            }
            onTracksChanged = { tracks ->
                updateTracksButton(tracks)
                trackDialog?.updateCurrentTracks(tracks)
            }
        }
        binding.playerView.player = engine.player
        binding.playerView.subtitleView?.apply {
            visibility = View.VISIBLE
            setApplyEmbeddedStyles(true)
            setApplyEmbeddedFontSizes(true)
        }
        // The Media3 default settings cog is left in place: in Media3 1.4.1
        // the PlayerView has no public setShowSettingsButton (it was added
        // in a later release). Our custom Tracks button (btnPlayerTracks) is
        // in a different visual position — top-right of the activity
        // chrome vs top-right of the in-player control bar — so the two
        // don't overlap. The subtitle button is hidden via the layout's
        // app:show_subtitle_button="false" because we don't render
        // subtitle tracks via the Media3 overlay.
        binding.btnPlayerBack.setOnClickListener { showExitConfirmationDialog() }
        binding.btnPlayerTracks.setOnClickListener { showTrackDialog() }
        binding.btnPlayerStreams.setOnClickListener { showStreamDialog() }
        binding.btnPlayerSubtitles.setOnClickListener { searchSubdlSubtitles() }
        binding.playerView.setOnClickListener { showControls() }
        binding.overlayControls.setOnClickListener { showControls() }
        binding.btnRewind.setOnClickListener { engine.seekBy(-10_000L); showControls() }
        binding.btnForward.setOnClickListener { engine.seekBy(10_000L); showControls() }
        binding.btnPlayPause.setOnClickListener {
            if (engine.player.isPlaying) engine.pause() else engine.resume()
            showControls()
        }
        binding.btnFill.setOnClickListener {
            resizeModeIndex = (resizeModeIndex + 1) % resizeModes.size
            applyResizeMode()
            showControls()
        }
        binding.btnVolume.setOnClickListener {
            muted = !muted
            engine.player.volume = if (muted) 0f else 1f
            binding.btnVolume.text = if (muted) "MUTE" else "VOL"
            showControls()
        }
        binding.btnPreviousEpisode.setOnClickListener { loadAdjacentEpisode(-1) }
        binding.btnNextEpisode.setOnClickListener { loadAdjacentEpisode(1) }
        updateEpisodeButtons()
        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val duration = engine.player.duration
                if (duration > 0L) {
                    binding.tvCurrentTime.text =
                        formatPlaybackTime((duration * progress) / 1000L)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                userSeeking = true
                controlsLockedVisible = true
            }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val duration = engine.player.duration
                if (duration > 0) engine.player.seekTo((duration * (seekBar?.progress ?: 0)) / 1000L)
                userSeeking = false
                controlsLockedVisible = false
                showControls()
            }
        })
        updateBottomFocusChain()
        showControls()
        uiHandler.post(progressTick)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                showExitConfirmationDialog()
            }
        })

        checkAndAddToWatchHistory()
    }

    private fun playSniffedStream(url: String) {
        val headers = linkedMapOf<String, String>()
        intent.getStringExtra(EXTRA_SNIFFED_HEADERS)?.let { encoded ->
            runCatching {
                val json = JSONObject(encoded)
                json.keys().forEach { key ->
                    json.optString(key).takeIf { it.isNotBlank() }?.let { headers[key] = it }
                }
            }.onFailure { Log.w(TAG, "Could not parse sniffed request headers", it) }
        }
        intent.getStringExtra(EXTRA_SNIFFED_COOKIE)
            ?.takeIf { it.isNotBlank() && headers.keys.none { key -> key.equals("Cookie", true) } }
            ?.let { headers["Cookie"] = it }

        val stream = StreamItem(
            name = "Web Sniffer",
            title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "Captured WebView stream" },
            url = url,
            quality = "Auto",
            provider = "WebSniffer",
            type = intent.getStringExtra(EXTRA_SNIFFED_TYPE).orEmpty(),
            mimeType = intent.getStringExtra(EXTRA_SNIFFED_MIME_TYPE).orEmpty(),
            headers = headers
        )
        availableStreams = listOf(stream)
        activeStream = stream
        showStatus(getString(R.string.buffering), retry = false)
        activeSubtitles = parseSniffedSubtitles()
        // A non-zero seek while Media3 is still resolving a sniffed video's
        // external subtitle timelines can trigger ERROR_CODE_FAILED_RUNTIME_CHECK.
        // Prepare the merged source first, then restore progress at STATE_READY.
        pendingReadySeekPositionMs = consumePendingStartPosition()
        engine.play(stream, 0L, activeSubtitles)
    }

    private fun parseSniffedSubtitles(): List<SubtitleItem> {
        val encoded = intent.getStringExtra(EXTRA_SNIFFED_SUBTITLES) ?: return emptyList()
        return runCatching {
            val array = JSONArray(encoded)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val url = item.optString("url")
                    val mimeType = item.optString("mimeType")
                    if (url.isBlank() || mimeType.isBlank()) continue
                    val headers = linkedMapOf<String, String>()
                    item.optJSONObject("headers")?.let { json ->
                        json.keys().forEach { key ->
                            json.optString(key).takeIf { it.isNotBlank() }?.let { headers[key] = it }
                        }
                    }
                    item.optString("cookie")
                        .takeIf {
                            it.isNotBlank() &&
                                headers.keys.none { key -> key.equals("Cookie", ignoreCase = true) }
                        }
                        ?.let { headers["Cookie"] = it }
                    add(
                        SubtitleItem(
                            url = url,
                            mimeType = mimeType,
                            label = "Subtitle ${index + 1}",
                            headers = headers
                        )
                    )
                }
            }
        }.onFailure {
            Log.w(TAG, "Could not parse sniffed subtitles", it)
        }.getOrDefault(emptyList())
    }

    private fun applyResizeMode() {
        val mode = resizeModes[resizeModeIndex]
        binding.playerView.resizeMode = mode.resizeMode
        // Some decoders update the SurfaceView dimensions independently
        // after reporting a new video size. Explicitly relayout both levels
        // so the selected mode also takes effect for those streams.
        binding.playerView.requestLayout()
        binding.playerView.videoSurfaceView?.apply {
            requestLayout()
            invalidate()
        }
        binding.btnFill.setText(mode.label)
        Log.i(TAG, "Player resize mode changed to ${getString(mode.label)}")
    }

    private fun formatPlaybackTime(positionMs: Long): String {
        val totalSeconds = positionMs.coerceAtLeast(0L) / 1_000L
        val hours = totalSeconds / 3_600L
        val minutes = (totalSeconds % 3_600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
        }
    }

    private fun loadAdjacentEpisode(delta: Int) {
        if (currentMediaType != TYPE_SERIES) return
        val episode = currentEpisode ?: return
        val nextEpisode = episode + delta
        if (nextEpisode < 1) return

        currentEpisode = nextEpisode
        pendingStartPositionMs = 0L
        currentTitle = currentTitle.substringBefore(" • ")
        updatePlayerTitle()
        updateEpisodeButtons()
        trackDialog?.takeIf { it.isShowing }?.dismiss()
        streamDialog?.takeIf { it.isShowing }?.dismiss()
        engine.pause()
        showLoadingArtwork()
        Log.i(
            PROVIDER_TAG,
            "Loading adjacent episode season=$currentSeason episode=$nextEpisode delta=$delta"
        )
        resetWatchProgressForCurrentEpisode()
        loadCurrentMedia()
        showControls()
    }

    private fun updateEpisodeButtons() {
        val isSeries = currentMediaType == TYPE_SERIES && currentEpisode != null
        binding.btnNextEpisode.visibility = if (isSeries) View.VISIBLE else View.GONE
        binding.btnPreviousEpisode.visibility =
            if (isSeries && (currentEpisode ?: 1) > 1) View.VISIBLE else View.GONE
        updateBottomFocusChain()
    }

    private fun updatePlayerTitle() {
        val hasEpisodeNumber = Regex("""(?i)\bS\d+\s*E\d+\b""").containsMatchIn(currentTitle)
        binding.tvPlayerTitle.text = if (
            currentMediaType == TYPE_SERIES &&
            currentSeason != null &&
            currentEpisode != null &&
            !hasEpisodeNumber
        ) {
            "$currentTitle • S${currentSeason} E${currentEpisode}"
        } else {
            currentTitle
        }
    }

    private fun loadCurrentMedia() {
        loadAndPlay(
            currentMediaType,
            currentTmdbId,
            currentSeason,
            currentEpisode,
            currentProvider
        )
    }

    /**
     * Show the custom tracks button only when the manifest exposes at
     * least one track the user can switch to. HLS playlists with a single
     * video track and a single audio track will leave the button hidden.
     */
    private fun updateTracksButton(tracks: Tracks) {
        val hasVideoChoices = tracks.groups.any { it.type == C.TRACK_TYPE_VIDEO && it.length > 1 }
        val hasAudioChoices = tracks.groups.any { it.type == C.TRACK_TYPE_AUDIO && it.length > 1 }
        // A single subtitle track is still a real choice because the dialog
        // also provides an explicit Off row.
        val hasSubtitleChoices = tracks.groups.any { it.type == C.TRACK_TYPE_TEXT && it.length > 0 }
        val anyChoice = hasVideoChoices || hasAudioChoices || hasSubtitleChoices
        binding.btnPlayerTracks.visibility = if (anyChoice) View.VISIBLE else View.GONE
        updateBottomFocusChain()
        Log.i(
            TAG,
            "Tracks button visibility=$anyChoice " +
                "(video=$hasVideoChoices audio=$hasAudioChoices subtitle=$hasSubtitleChoices)"
        )
    }

    private fun showTrackDialog() {
        // Don't stack two dialogs.
        if (trackDialog?.isShowing == true) return
        val tracks = engine.currentTracks()
        if (tracks.groups.isEmpty()) {
            Toast.makeText(this, R.string.track_none_available, Toast.LENGTH_SHORT).show()
            return
        }
        trackDialog = TrackSelectionDialog(
            context = this,
            tracks = tracks,
            initialParameters = engine.currentTrackSelectionParameters(),
            onApply = { params -> engine.applyTrackSelectionParameters(params) }
        )
        trackDialog?.setOnDismissListener { trackDialog = null }
        trackDialog?.show()
    }

    private fun showStreamDialog() {
        if (streamDialog?.isShowing == true || availableStreams.size < 2) return
        streamDialog = StreamSelectionDialog(
            context = this,
            streams = availableStreams,
            activeUrl = activeStream?.url,
            onStreamSelected = ::switchStream
        )
        streamDialog?.setOnDismissListener { streamDialog = null }
        streamDialog?.show()
    }

    private fun searchSubdlSubtitles() {
        if (subtitleJob?.isActive == true || subtitleDialog?.isShowing == true) return
        val client = SubdlSubtitleClient(applicationContext)
        if (!client.isConfigured) {
            Toast.makeText(this, R.string.subdl_key_missing, Toast.LENGTH_LONG).show()
            return
        }

        Toast.makeText(this, R.string.subdl_searching, Toast.LENGTH_SHORT).show()
        subtitleJob = lifecycleScope.launch {
            runCatching {
                client.search(
                    tmdbId = currentTmdbId,
                    isTv = currentMediaType == TYPE_SERIES,
                    season = currentSeason,
                    episode = currentEpisode
                )
            }.onSuccess { results ->
                Log.i(TAG, "SubDL search returned ${results.size} selectable subtitles")
                if (results.isEmpty()) {
                    Toast.makeText(
                        this@DirectStreamActivity,
                        R.string.subdl_no_results,
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    showSubdlResults(results, client)
                }
            }.onFailure { error ->
                Log.e(TAG, "SubDL subtitle search failed", error)
                Toast.makeText(
                    this@DirectStreamActivity,
                    getString(
                        R.string.subdl_search_failed,
                        error.message ?: error.javaClass.simpleName
                    ),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun showSubdlResults(
        results: List<SubdlSubtitleResult>,
        client: SubdlSubtitleClient
    ) {
        subtitleDialog = AlertDialog.Builder(this)
            .setTitle(R.string.subdl_choose)
            .setItems(results.map { it.displayName }.toTypedArray()) { dialog, index ->
                dialog.dismiss()
                downloadAndLoadSubtitle(results[index], client)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .also { dialog ->
                dialog.setOnDismissListener { subtitleDialog = null }
                dialog.show()
            }
    }

    private fun downloadAndLoadSubtitle(
        result: SubdlSubtitleResult,
        client: SubdlSubtitleClient
    ) {
        subtitleJob?.cancel()
        Toast.makeText(this, R.string.subdl_downloading, Toast.LENGTH_SHORT).show()
        subtitleJob = lifecycleScope.launch {
            runCatching { client.download(result) }
                .onSuccess { subtitle ->
                    loadExternalSubtitle(subtitle)
                    Toast.makeText(
                        this@DirectStreamActivity,
                        getString(R.string.subdl_loaded, result.language.ifBlank { "SubDL" }),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                .onFailure { error ->
                    Log.e(TAG, "SubDL subtitle download failed", error)
                    Toast.makeText(
                        this@DirectStreamActivity,
                        getString(
                            R.string.subdl_download_failed,
                            error.message ?: error.javaClass.simpleName
                        ),
                        Toast.LENGTH_LONG
                    ).show()
                }
        }
    }

    private fun loadExternalSubtitle(subtitle: SubtitleItem) {
        val stream = activeStream ?: run {
            Toast.makeText(this, R.string.playback_link_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        val positionMs = engine.player.currentPosition.coerceAtLeast(0L)
        activeSubtitles = listOf(subtitle) + activeSubtitles.filterNot {
            it.label?.startsWith("SubDL", ignoreCase = true) == true
        }
        Log.i(
            TAG,
            "Loading external subtitle label=${subtitle.label.orEmpty()} " +
                "language=${subtitle.language.orEmpty()} mimeType=${subtitle.mimeType}"
        )
        pendingReadySeekPositionMs = positionMs
        showStatus(getString(R.string.buffering), retry = false)
        engine.play(stream, 0L, activeSubtitles)
    }

    private fun switchStream(stream: StreamItem) {
        if (stream.url == activeStream?.url) return
        val positionMs = engine.player.currentPosition.coerceAtLeast(0L)
        activeStream = stream
        Log.i(
            PROVIDER_TAG,
            "Switching stream provider=${stream.provider.ifBlank { "?" }} " +
                "quality=${stream.quality} positionMs=$positionMs"
        )
        startStreamPlayback(stream, positionMs)
    }

    private fun handlePlaybackError(code: String) {
        if (handlingPlaybackError || isFinishing || isDestroyed) return
        handlingPlaybackError = true
        Log.e(
            TAG,
            "Playback failed code=$code; keeping DirectStreamActivity open for stream selection"
        )
        stopWatchProgressUpdates()
        engine.pause()
        hideLoadingArtwork()
        binding.playerStatus.visibility = View.GONE
        showControls()
        if (binding.btnPlayerStreams.visibility == View.VISIBLE) {
            binding.btnPlayerStreams.requestFocus()
        }
        Toast.makeText(
            this,
            R.string.playback_failed_try_another_stream,
            Toast.LENGTH_LONG
        ).show()
    }

    private fun loadAndPlay(
        type: String,
        tmdbId: Int,
        season: Int?,
        episode: Int?,
        provider: StreamProviderChoice
    ) {
        streamJob?.cancel()
        availableStreams = emptyList()
        activeStream = null
        activeSubtitles = emptyList()
        pendingReadySeekPositionMs = 0L
        handlingPlaybackError = false
        binding.btnPlayerStreams.visibility = View.GONE
        updateBottomFocusChain()
        showStatus(getString(R.string.streams_loading), retry = false)
        streamJob = lifecycleScope.launch {
            val result = runCatching {
                resolver.load(type, tmdbId, season, episode, provider)
            }
            result.onSuccess { items ->
                Log.i(PROVIDER_TAG, "loadAndPlay received ${items.size} streams for provider=${provider.displayName}")
                if (items.isEmpty()) {
                    Log.w(PROVIDER_TAG, "Empty stream list for provider=${provider.displayName}")
                    showStatus(getString(R.string.streams_empty), retry = true)
                } else {
                    availableStreams = items
                    binding.btnPlayerStreams.visibility =
                        if (items.size > 1) View.VISIBLE else View.GONE
                    updateBottomFocusChain()
                    binding.playerStatus.visibility = View.GONE
                    playBest(items)
                }
            }.onFailure { error ->
                Log.w(TAG, "Stream fetch failed: ${error.message}")
                Log.w(PROVIDER_TAG, "Stream fetch failed for provider=${provider.displayName}: ${error.message}")
                showStatus(getString(R.string.streams_failed), retry = true)
            }
        }
    }

    /**
     * Automatically picks the best stream up to 1080p. Higher-bandwidth
     * 1440p/2160p streams remain available in the Streams dialog so the
     * viewer can opt into them explicitly.
     */
    private fun playBest(items: List<StreamItem>) {
        val automaticCandidates = items.filterNot {
            (qualityResolution(it.quality) ?: 0) >= 1440
        }
        val chosen = automaticCandidates
            .ifEmpty { items }
            .maxByOrNull { qualityRank(it.quality) }
            ?: items.first()
        activeStream = chosen
        val scheme = chosen.url.substringBefore(':').uppercase()
        Log.i(
            PROVIDER_TAG,
            "playBest picked provider=${chosen.provider.ifBlank { "?" }} " +
                "quality=${chosen.quality} scheme=$scheme " +
                "excludedHighResolution=${items.size - automaticCandidates.size} url=${chosen.url}"
        )
        startStreamPlayback(chosen, consumePendingStartPosition())
    }

    private fun startStreamPlayback(stream: StreamItem, startPositionMs: Long = 0L) {
        handlingPlaybackError = false
        stopWatchProgressUpdates()
        showLoadingArtwork()
        showStatus(getString(R.string.buffering), retry = false)
        // A replacement MediaSource has its own buffer. Clear the old
        // source's buffered marker so the seek bar reflects the new source
        // as Media3 fills it.
        binding.seekBar.secondaryProgress = 0
        engine.play(stream, startPositionMs, activeSubtitles)
    }

    private fun consumePendingStartPosition(): Long {
        val position = pendingStartPositionMs.coerceAtLeast(0L)
        pendingStartPositionMs = 0L
        return position
    }

    private fun applyPendingReadySeek() {
        val requestedPosition = pendingReadySeekPositionMs
        if (requestedPosition <= 0L) return
        pendingReadySeekPositionMs = 0L
        val duration = engine.player.duration
        val target = if (duration > 0L) {
            requestedPosition.coerceAtMost((duration - 1_000L).coerceAtLeast(0L))
        } else {
            requestedPosition
        }
        engine.player.seekTo(target)
    }

    private fun qualityRank(quality: String): Int {
        return qualityResolution(quality) ?: 0
    }

    private fun qualityResolution(quality: String): Int? {
        val normalized = quality.lowercase()
        if (normalized.contains("4k")) return 2160
        if (normalized.contains("2k")) return 1440
        return Regex("""(\d{3,4})\s*p""")
            .find(normalized)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }

    private fun showStatus(message: String, retry: Boolean) {
        binding.playerStatus.text = message
        binding.playerStatus.visibility = View.VISIBLE
        if (retry) {
            binding.playerStatus.setOnClickListener { loadCurrentMedia() }
        } else {
            binding.playerStatus.setOnClickListener(null)
        }
    }

    private fun showLoadingArtwork() {
        binding.loadingArtwork.animate().cancel()
        binding.loadingArtwork.alpha = 1f
        binding.loadingArtwork.visibility = View.VISIBLE
    }

    private fun hideLoadingArtwork() {
        binding.loadingArtwork.animate()
            .alpha(0f)
            .setDuration(250L)
            .withEndAction {
                binding.loadingArtwork.visibility = View.GONE
                binding.loadingArtwork.alpha = 1f
            }
            .start()
    }

    private fun showExitConfirmationDialog() {
        if (quitDialog?.isShowing == true) return
        quitDialog = QuitDialog(
            context = this,
            title = "Stop Playback?",
            message = "Are you sure you want to stop playback and exit?",
            positiveButtonText = "Stop",
            negativeButtonText = "Continue",
            lottieAnimRes = R.raw.exit,
            onNo = { quitDialog = null },
            onYes = {
                quitDialog = null
                finish()
            }
        ).also { dialog ->
            dialog.setOnDismissListener { quitDialog = null }
            dialog.show()
        }
    }

    private val hideControlsRunnable = Runnable {
        if (
            !controlsLockedVisible &&
            trackDialog?.isShowing != true &&
            streamDialog?.isShowing != true &&
            subtitleDialog?.isShowing != true
        ) {
            binding.overlayControls.visibility = View.GONE
        }
    }

    private fun showControls() {
        uiHandler.removeCallbacks(hideControlsRunnable)
        val wasHidden = binding.overlayControls.visibility != View.VISIBLE
        binding.overlayControls.visibility = View.VISIBLE
        if (wasHidden || currentFocus == null || currentFocus === binding.overlayControls) {
            binding.btnPlayPause.post { binding.btnPlayPause.requestFocus() }
        }
        uiHandler.postDelayed(hideControlsRunnable, 4_000)
    }

    private fun updateBottomFocusChain() {
        val controls = listOf(
            binding.btnPreviousEpisode,
            binding.btnFill,
            binding.btnPlayerSubtitles,
            binding.btnPlayerTracks,
            binding.btnPlayerStreams,
            binding.btnVolume,
            binding.btnNextEpisode
        ).filter { it.visibility == View.VISIBLE }
        controls.forEachIndexed { index, control ->
            control.nextFocusLeftId = controls[(index - 1 + controls.size) % controls.size].id
            control.nextFocusRightId = controls[(index + 1) % controls.size].id
            control.nextFocusUpId = binding.btnPlayPause.id
        }
        binding.btnPlayPause.nextFocusDownId = controls.first().id
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Track dialog swallows its own back key; defer to the dialog first.
        if (trackDialog?.isShowing == true) {
            return super.dispatchKeyEvent(event)
        }
        if (streamDialog?.isShowing == true) {
            return super.dispatchKeyEvent(event)
        }
        if (subtitleDialog?.isShowing == true) {
            return super.dispatchKeyEvent(event)
        }
        showControls()
        when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    engine.seekBy(-SEEK_STEP_MS)
                }
                return true
            }

            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    engine.seekBy(SEEK_STEP_MS)
                }
                return true
            }

            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    engine.resume()
                }
                return true
            }

            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    engine.pause()
                }
                return true
            }

            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    if (engine.player.isPlaying) engine.pause() else engine.resume()
                }
                return true
            }

            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    showExitConfirmationDialog()
                    return true
                }
                return true
            }

//            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
//                if (event.action == KeyEvent.ACTION_UP) {
//                    if (engine.player.isPlaying) engine.pause() else engine.resume()
//                    return true
//                }
//            }

            // KeyEvent.KEYCODE_DPAD_LEFT -> {
            //     return handleSkip(event.action, -1)
            // }

            // KeyEvent.KEYCODE_DPAD_RIGHT -> {
            //     return handleSkip(event.action, +1)
            // }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun handleSkip(action: Int, dir: Int): Boolean = when (action) {
        KeyEvent.ACTION_DOWN -> {
            if (skipDirection != dir) {
                stopSkipRamp()
                skipDirection = dir
                skipHoldStart = System.currentTimeMillis()
                engine.seekBy(dir * SKIP_SEC_MIN * 1000L)
                uiHandler.postDelayed(skipTick, SKIP_REPEAT_MS)
            }
            true
        }
        KeyEvent.ACTION_UP -> {
            stopSkipRamp()
            true
        }
        else -> false
    }

    private fun stopSkipRamp() {
        skipDirection = 0
        uiHandler.removeCallbacks(skipTick)
    }

    private data class ResumeHistory(
        val positionMs: Long,
        val durationMs: Long = 0L,
        val season: Int? = null,
        val episode: Int? = null,
        val updatedAt: Long = 0L,
        val source: String
    )

    private fun checkAndAddToWatchHistory() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val isTv = currentMediaType == TYPE_SERIES
                val localHistory = repository.getWatchHistoryItem(
                    this@DirectStreamActivity,
                    currentTmdbId,
                    isTv
                )
                val firebaseHistory = withTimeoutOrNull(FIREBASE_HISTORY_TIMEOUT_MS) {
                    FirebaseManager.getWatchHistoryOnce()
                }
                val remoteHistory = extractFirebaseResumeHistory(firebaseHistory, isTv)
                val localResume = localHistory?.let {
                    ResumeHistory(
                        positionMs = it.playbackPosition.coerceAtLeast(0L),
                        season = it.seasonNumber,
                        episode = it.episodeNumber,
                        updatedAt = it.lastWatched,
                        source = "local"
                    )
                }
                val selectedResume = listOfNotNull(localResume, remoteHistory)
                    .filter { historyMatchesCurrentMedia(it, isTv) }
                    .maxWithOrNull(
                        compareBy<ResumeHistory> { it.updatedAt }
                            .thenBy { it.positionMs }
                    )
                val resumePosition = selectedResume?.positionMs?.coerceAtLeast(0L) ?: 0L

                pendingStartPositionMs = resumePosition
                Log.i(
                    TAG,
                    "[WatchHistory] Resume resolved from ${selectedResume?.source ?: "new history"} " +
                        "at ${resumePosition}ms"
                )

                if (localHistory == null) {
                    DatabaseManager.addToWatchHistoryAsync(
                        id = currentTmdbId,
                        mediaType = if (isTv) "tv" else "movie",
                        title = currentTitle,
                        overview = currentOverview,
                        posterPath = currentPosterPath,
                        backdropPath = currentBackdropPath,
                        voteAverage = currentVoteAverage,
                        releaseDate = currentReleaseDate,
                        seasonNumber = currentSeason.takeIf { isTv },
                        episodeNumber = currentEpisode.takeIf { isTv },
                        playbackPosition = resumePosition
                    )
                } else {
                    val mediaType = if (isTv) "tv" else "movie"
                    DatabaseManager.watchHistoryDao().updatePlaybackPosition(
                        currentTmdbId,
                        mediaType,
                        resumePosition
                    )
                    if (isTv) {
                        DatabaseManager.watchHistoryDao().updateEpisodeInfo(
                            currentTmdbId,
                            mediaType,
                            currentSeason ?: 1,
                            currentEpisode ?: 1
                        )
                    }
                }
                syncWatchHistory(
                    position = resumePosition,
                    duration = selectedResume?.durationMs ?: 0L
                )
            } catch (error: Exception) {
                Log.e(TAG, "[WatchHistory] Could not initialize playback progress", error)
            }

            withContext(Dispatchers.Main) {
                watchHistoryReady = true
                val sniffedUrl = intent.getStringExtra(EXTRA_SNIFFED_URL)
                if (sniffedUrl.isNullOrBlank()) {
                    loadCurrentMedia()
                } else {
                    playSniffedStream(sniffedUrl)
                }
            }
        }
    }

    private fun extractFirebaseResumeHistory(
        watchHistory: Map<String, Any>?,
        isTv: Boolean
    ): ResumeHistory? {
        val mediaCollection = watchHistory
            ?.get(if (isTv) "tv" else "movies") as? Map<*, *>
            ?: return null
        val media = mediaCollection.entries
            .firstOrNull { it.key.toString() == currentTmdbId.toString() }
            ?.value as? Map<*, *>
            ?: return null

        return ResumeHistory(
            positionMs = media.longValue("playbackPosition").coerceAtLeast(0L),
            durationMs = media.longValue("duration").coerceAtLeast(0L),
            season = media.intValueOrNull("seasonNumber"),
            episode = media.intValueOrNull("episodeNumber"),
            updatedAt = media.longValue("updatedAt"),
            source = "firebase"
        )
    }

    private fun historyMatchesCurrentMedia(history: ResumeHistory, isTv: Boolean): Boolean {
        return !isTv ||
            (history.season == currentSeason && history.episode == currentEpisode)
    }

    private fun Map<*, *>.longValue(key: String): Long {
        return when (val value = this[key]) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull() ?: 0L
            else -> 0L
        }
    }

    private fun Map<*, *>.intValueOrNull(key: String): Int? {
        return when (val value = this[key]) {
            is Number -> value.toInt()
            is String -> value.toIntOrNull()
            else -> null
        }
    }

    private fun startWatchProgressUpdates() {
        uiHandler.removeCallbacks(watchProgressTick)
        uiHandler.postDelayed(watchProgressTick, WATCH_PROGRESS_INTERVAL_MS)
    }

    private fun stopWatchProgressUpdates() {
        uiHandler.removeCallbacks(watchProgressTick)
    }

    private fun persistWatchProgress() {
        if (
            !watchHistoryReady ||
            !::engine.isInitialized ||
            engine.player.currentMediaItem == null ||
            currentTmdbId <= 0
        ) return

        val isTv = currentMediaType == TYPE_SERIES
        val mediaType = if (isTv) "tv" else "movie"
        val position = engine.player.currentPosition.coerceAtLeast(0L)
        val duration = engine.player.duration.takeIf { it > 0 } ?: 0L
        val season = currentSeason
        val episode = currentEpisode

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                DatabaseManager.watchHistoryDao().updatePlaybackPosition(
                    currentTmdbId,
                    mediaType,
                    position
                )
                if (isTv) {
                    DatabaseManager.watchHistoryDao().updateEpisodeInfo(
                        currentTmdbId,
                        mediaType,
                        season ?: 1,
                        episode ?: 1
                    )
                }
                syncWatchHistory(position, duration, season, episode)
            } catch (error: Exception) {
                Log.e(TAG, "[WatchHistory] Could not persist playback progress", error)
            }
        }
    }

    private fun resetWatchProgressForCurrentEpisode() {
        if (currentMediaType != TYPE_SERIES) return
        val season = currentSeason ?: 1
        val episode = currentEpisode ?: 1
        lifecycleScope.launch(Dispatchers.IO) {
            DatabaseManager.watchHistoryDao().updatePlaybackPosition(currentTmdbId, "tv", 0L)
            DatabaseManager.watchHistoryDao().updateEpisodeInfo(
                currentTmdbId,
                "tv",
                season,
                episode
            )
            syncWatchHistory(0L, 0L, season, episode)
        }
    }

    private suspend fun syncWatchHistory(
        position: Long,
        duration: Long,
        season: Int? = currentSeason,
        episode: Int? = currentEpisode
    ) {
        val isTv = currentMediaType == TYPE_SERIES
        FirebaseManager.syncWatchHistory(
            tmdbId = currentTmdbId,
            isTv = isTv,
            seasonNumber = season.takeIf { isTv },
            episodeNumber = episode.takeIf { isTv },
            playbackPosition = position,
            duration = duration,
            title = currentTitle,
            overview = currentOverview,
            posterPath = currentPosterPath,
            backdropPath = currentBackdropPath,
            voteAverage = currentVoteAverage,
            releaseDate = currentReleaseDate
        ).await()
    }

    override fun onStart() {
        super.onStart()
        if (::engine.isInitialized) engine.resume()
        if (
            watchHistoryReady &&
            ::engine.isInitialized &&
            engine.player.playbackState == Player.STATE_READY
        ) {
            startWatchProgressUpdates()
        }
    }

    override fun onStop() {
        stopWatchProgressUpdates()
        if (::engine.isInitialized) {
            persistWatchProgress()
            engine.pause()
        }
        super.onStop()
    }

    override fun onDestroy() {
        streamJob?.cancel()
        subtitleJob?.cancel()
        trackDialog?.takeIf { it.isShowing }?.dismiss()
        trackDialog = null
        streamDialog?.takeIf { it.isShowing }?.dismiss()
        streamDialog = null
        subtitleDialog?.takeIf { it.isShowing }?.dismiss()
        subtitleDialog = null
        quitDialog?.takeIf { it.isShowing }?.dismiss()
        quitDialog = null
        uiHandler.removeCallbacksAndMessages(null)
        if (::engine.isInitialized) engine.release()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "KiduyuLitePlayer"
        private const val PROVIDER_TAG = "KiduyuLiteProvider"

        const val EXTRA_TYPE = "MEDIA_TYPE"
        const val EXTRA_IS_TV = "IS_TV"
        const val EXTRA_TMDB_ID = "TMDB_ID"
        const val EXTRA_SEASON = "SEASON_NUMBER"
        const val EXTRA_EPISODE = "EPISODE_NUMBER"
        const val EXTRA_PROVIDER = "PROVIDER"
        const val EXTRA_BACKDROP_URL = "BACKDROP_PATH"
        const val EXTRA_TITLE = "TITLE"
        const val EXTRA_POSTER_PATH = "POSTER_PATH"
        const val EXTRA_OVERVIEW = "OVERVIEW"
        const val EXTRA_VOTE_AVERAGE = "VOTE_AVERAGE"
        const val EXTRA_RELEASE_DATE = "RELEASE_DATE"
        const val EXTRA_SNIFFED_URL = "SNIFFED_STREAM_URL"
        const val EXTRA_SNIFFED_HEADERS = "SNIFFED_STREAM_HEADERS"
        const val EXTRA_SNIFFED_COOKIE = "SNIFFED_STREAM_COOKIE"
        const val EXTRA_SNIFFED_TYPE = "SNIFFED_STREAM_TYPE"
        const val EXTRA_SNIFFED_MIME_TYPE = "SNIFFED_STREAM_MIME_TYPE"
        const val EXTRA_SNIFFED_SUBTITLES = "SNIFFED_SUBTITLES"

        fun createIntent(
            context: Context,
            tmdbId: Int,
            isTv: Boolean,
            season: Int? = null,
            episode: Int? = null,
            title: String = "",
            posterPath: String? = null,
            backdropPath: String? = null,
            overview: String? = null,
            voteAverage: Double = 0.0,
            releaseDate: String? = null
        ): Intent = Intent(context, DirectStreamActivity::class.java).apply {
            putExtra(EXTRA_TMDB_ID, tmdbId)
            putExtra(EXTRA_TYPE, if (isTv) TYPE_SERIES else TYPE_MOVIE)
            putExtra(EXTRA_IS_TV, isTv)
            putExtra(EXTRA_SEASON, season ?: 0)
            putExtra(EXTRA_EPISODE, episode ?: 0)
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_POSTER_PATH, posterPath)
            putExtra(EXTRA_BACKDROP_URL, backdropPath)
            putExtra(EXTRA_OVERVIEW, overview)
            putExtra(EXTRA_VOTE_AVERAGE, voteAverage)
            putExtra(EXTRA_RELEASE_DATE, releaseDate)
        }

        fun createSniffedIntent(
            context: Context,
            tmdbId: Int,
            isTv: Boolean,
            season: Int?,
            episode: Int?,
            title: String,
            posterPath: String?,
            backdropPath: String?,
            overview: String?,
            voteAverage: Double,
            releaseDate: String?,
            streamUrl: String,
            headers: Map<String, String>,
            cookie: String?,
            type: String,
            mimeType: String,
            subtitles: List<SniffedSubtitle>
        ): Intent = createIntent(
            context = context,
            tmdbId = tmdbId,
            isTv = isTv,
            season = season,
            episode = episode,
            title = title,
            posterPath = posterPath,
            backdropPath = backdropPath,
            overview = overview,
            voteAverage = voteAverage,
            releaseDate = releaseDate
        ).apply {
            putExtra(EXTRA_SNIFFED_URL, streamUrl)
            putExtra(EXTRA_SNIFFED_HEADERS, JSONObject(headers).toString())
            putExtra(EXTRA_SNIFFED_COOKIE, cookie)
            putExtra(EXTRA_SNIFFED_TYPE, type)
            putExtra(EXTRA_SNIFFED_MIME_TYPE, mimeType)
            putExtra(
                EXTRA_SNIFFED_SUBTITLES,
                JSONArray().apply {
                    subtitles.forEach { subtitle ->
                        put(
                            JSONObject()
                                .put("url", subtitle.url)
                                .put("mimeType", subtitle.mimeType)
                                .put("headers", JSONObject(subtitle.headers))
                                .put("cookie", subtitle.cookie.orEmpty())
                        )
                    }
                }.toString()
            )
        }

        const val TYPE_MOVIE  = "movie"
        const val TYPE_SERIES = "series"

        private const val SKIP_SEC_MIN = 10
        private const val WATCH_PROGRESS_INTERVAL_MS = 15_000L
        private const val FIREBASE_HISTORY_TIMEOUT_MS = 8_000L
        private const val SKIP_SEC_MAX = 60
        private const val SEEK_STEP_MS = 10_000L
        private const val SKIP_RAMP_DURATION_MS = 5_000L
        private const val SKIP_REPEAT_MS = 600L
    }

    private fun normalizeArtworkUrl(path: String?): String? = when {
        path.isNullOrBlank() -> null
        path.startsWith("http://") || path.startsWith("https://") -> path
        else -> "https://image.tmdb.org/t/p/original/${path.trimStart('/')}"
    }

    private data class ResizeModeOption(
        val resizeMode: Int,
        val label: Int
    )

    private val resizeModes = listOf(
        ResizeModeOption(AspectRatioFrameLayout.RESIZE_MODE_FIT, R.string.player_fit),
        ResizeModeOption(AspectRatioFrameLayout.RESIZE_MODE_FILL, R.string.player_fill),
        ResizeModeOption(AspectRatioFrameLayout.RESIZE_MODE_ZOOM, R.string.player_zoom),
        ResizeModeOption(AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH, R.string.player_fixed_width),
        ResizeModeOption(AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT, R.string.player_fixed_height)
    )
}
