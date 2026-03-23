package com.streamvision.iptv.presentation.ui.player

import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.LocalMediaDrmCallback
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.TimeBar
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.streamvision.iptv.R
import com.streamvision.iptv.databinding.FragmentPlayerBinding
import com.streamvision.iptv.domain.model.Channel
import com.streamvision.iptv.presentation.viewmodel.PlayerViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

@UnstableApi
@AndroidEntryPoint
class PlayerFragment : Fragment() {

    private var _binding: FragmentPlayerBinding? = null
    private val binding get() = _binding!!

    private val viewModel: PlayerViewModel by viewModels()
    private val args: PlayerFragmentArgs by navArgs()

    private var player: ExoPlayer? = null
    private var currentChannel: Channel? = null
    private var isZoomFit = true

    private val hideHandler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { hideControls() }
    private var controlsVisible = true

    private val progressHandler = Handler(Looper.getMainLooper())
    private val progressRunnable = object : Runnable {
        override fun run() {
            updateProgressBar()
            progressHandler.postDelayed(this, 500)
        }
    }

    companion object {
        private const val TAG = "PlayerFragment"
        private const val CONTROLS_HIDE_DELAY = 3000L
        private const val SEEK_INCREMENT_MS = 10_000L
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPlayerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        enterPlayerMode()
        setupControls()
        observeState()
        viewModel.loadChannel(args.channelId)
    }

    // -------------------------------------------------------------------------
    // System UI
    // -------------------------------------------------------------------------

    private fun enterPlayerMode() {
        val window = activity?.window ?: return
        window.statusBarColor     = Color.BLACK
        window.navigationBarColor = Color.BLACK
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, requireView()).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    private fun exitPlayerMode() {
        val window = activity?.window ?: return
        WindowCompat.setDecorFitsSystemWindows(window, true)
        WindowInsetsControllerCompat(window, requireView())
            .show(WindowInsetsCompat.Type.systemBars())
        window.navigationBarColor = Color.parseColor("#1E1E2E")
        window.statusBarColor     = Color.parseColor("#1E1E2E")
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    // -------------------------------------------------------------------------
    // Controls wiring
    // -------------------------------------------------------------------------

    private fun setupControls() {
        /*
         * KEY INSIGHT: In Android, touch events are delivered to children FIRST,
         * then bubble up to the parent only if no child consumed them.
         *
         * So: tap on a button  → button gets it, parent (player_root) does NOT.
         *     tap on empty video area → no child wants it → player_root gets it → toggleControls().
         *
         * This means we do NOT need a separate touch_interceptor view at all.
         * Just set the click listener on the root FrameLayout (player_root).
         */
        binding.playerRoot.setOnClickListener { toggleControls() }

        // Retry
        binding.btnRetry.setOnClickListener {
            currentChannel?.let { ch -> releasePlayer(); setupAndPlay(ch) }
        }

        // Back
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    releasePlayer()
                    findNavController().popBackStack()
                }
            }
        )

        // Play / Pause
        binding.btnPlayPause.setOnClickListener {
            player?.let { p -> if (p.isPlaying) p.pause() else p.play() }
            resetHideTimer()
        }

        // Rewind 10 s
        binding.btnRewind.setOnClickListener {
            player?.seekTo(maxOf(0L, (player?.currentPosition ?: 0L) - SEEK_INCREMENT_MS))
            resetHideTimer()
        }

        // Fast-forward 10 s
        binding.btnFastForward.setOnClickListener {
            player?.let { p ->
                val dur = p.duration.takeIf { it > 0L } ?: Long.MAX_VALUE
                p.seekTo(minOf(dur, p.currentPosition + SEEK_INCREMENT_MS))
            }
            resetHideTimer()
        }

        // Audio / Quality / Zoom
        binding.btnAudioTrack.setOnClickListener   { showAudioTrackDialog();   resetHideTimer() }
        binding.btnVideoQuality.setOnClickListener { showVideoQualityDialog(); resetHideTimer() }
        binding.btnZoom.setOnClickListener         { toggleZoom();             resetHideTimer() }

        // Seek bar
        binding.exoProgress.addListener(object : TimeBar.OnScrubListener {
            override fun onScrubStart(timeBar: TimeBar, position: Long) { resetHideTimer() }
            override fun onScrubMove(timeBar: TimeBar, position: Long)  {}
            override fun onScrubStop(timeBar: TimeBar, position: Long, canceled: Boolean) {
                if (!canceled) player?.seekTo(position)
                resetHideTimer()
            }
        })
    }

    // -------------------------------------------------------------------------
    // Controls visibility
    // -------------------------------------------------------------------------

    private fun toggleControls() {
        if (controlsVisible) hideControls() else showControls()
    }

    private fun showControls() {
        controlsVisible = true
        binding.controlsContainer.visibility = View.VISIBLE
        binding.tvChannelName.visibility     = View.VISIBLE
        resetHideTimer()
    }

    private fun hideControls() {
        controlsVisible = false
        binding.controlsContainer.visibility = View.GONE
        binding.tvChannelName.visibility     = View.GONE
    }

    private fun resetHideTimer() {
        hideHandler.removeCallbacks(hideRunnable)
        if (controlsVisible) hideHandler.postDelayed(hideRunnable, CONTROLS_HIDE_DELAY)
    }

    // -------------------------------------------------------------------------
    // Progress bar update (every 500 ms)
    // -------------------------------------------------------------------------

    private fun updateProgressBar() {
        val p = player ?: return
        val pos = p.currentPosition
        val dur = p.duration.takeIf { it > 0L } ?: 0L
        binding.exoProgress.setDuration(dur)
        binding.exoProgress.setPosition(pos)
        binding.exoProgress.setBufferedPosition(p.bufferedPosition)
        binding.exoPosition.text = formatTime(pos)
        binding.exoDuration.text = formatTime(dur)
        binding.btnPlayPause.setImageResource(
            if (p.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        )
    }

    private fun formatTime(ms: Long): String {
        if (ms <= 0L) return "00:00"
        val h = TimeUnit.MILLISECONDS.toHours(ms)
        val m = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
        val s = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    // -------------------------------------------------------------------------
    // State observer
    // -------------------------------------------------------------------------

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    state.currentChannel?.let { channel ->
                        if (currentChannel?.id != channel.id) {
                            currentChannel = channel
                            releasePlayer()
                            setupAndPlay(channel)
                        }
                        binding.tvChannelName.text = channel.name
                    }
                    if (state.error != null) showError(state.error)
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Player setup
    // -------------------------------------------------------------------------

    private fun setupAndPlay(channel: Channel) {
        binding.errorOverlay.visibility   = View.GONE
        binding.progressBuffering.visibility = View.VISIBLE

        val httpFactory   = buildHttpDataSourceFactory(channel)
        val playerBuilder = ExoPlayer.Builder(requireContext())

        if (!channel.drmKey.isNullOrBlank()) {
            val drm = buildClearKeyDrmSessionManager(channel.drmKey)
            playerBuilder.setMediaSourceFactory(
                DefaultMediaSourceFactory(requireContext())
                    .setDataSourceFactory(httpFactory)
                    .apply { if (drm != null) setDrmSessionManagerProvider { drm } }
            )
        } else {
            playerBuilder.setMediaSourceFactory(
                DefaultMediaSourceFactory(requireContext()).setDataSourceFactory(httpFactory)
            )
        }

        player = playerBuilder.build().also { exoPlayer ->
            binding.playerView.player = exoPlayer
            exoPlayer.addListener(playerListener)
            exoPlayer.setMediaItem(MediaItem.Builder().setUri(channel.url).build())
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
        }

        applyZoomMode()
        progressHandler.post(progressRunnable)
        showControls()
    }

    // -------------------------------------------------------------------------
    // HTTP headers
    // -------------------------------------------------------------------------

    private fun buildHttpDataSourceFactory(channel: Channel): DefaultHttpDataSource.Factory {
        val headers = mutableMapOf<String, String>()
        if (!channel.userAgent.isNullOrBlank()) headers["User-Agent"] = channel.userAgent
        if (!channel.referer.isNullOrBlank())   headers["Referer"]     = channel.referer
        if (!channel.cookie.isNullOrBlank())    headers["Cookie"]      = channel.cookie
        return DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)
            .setDefaultRequestProperties(headers)
    }

    // -------------------------------------------------------------------------
    // ClearKey DRM
    // -------------------------------------------------------------------------

    private fun buildClearKeyDrmSessionManager(drmKey: String): DefaultDrmSessionManager? {
        return try {
            val parts = drmKey.trim().split(":")
            if (parts.size != 2) return null
            val json = """{"keys":[{"kty":"oct","k":"${hexToBase64Url(parts[1])}","kid":"${hexToBase64Url(parts[0])}"}],"type":"temporary"}"""
            DefaultDrmSessionManager.Builder()
                .setUuidAndExoMediaDrmProvider(C.CLEARKEY_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
                .setMultiSession(false)
                .build(LocalMediaDrmCallback(json.toByteArray(Charsets.UTF_8)))
        } catch (e: Exception) {
            Log.e(TAG, "DRM setup failed: ${e.message}", e); null
        }
    }

    // -------------------------------------------------------------------------
    // Audio Track dialog
    // -------------------------------------------------------------------------

    private fun showAudioTrackDialog() {
        val exo = player ?: return
        val groups = exo.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        if (groups.isEmpty()) {
            AlertDialog.Builder(requireContext()).setTitle("Audio Track")
                .setMessage("No audio tracks available.").setPositiveButton("OK", null).show()
            return
        }
        val labels = groups.mapIndexed { i, g ->
            val f = g.getTrackFormat(0)
            "${f.language?.uppercase() ?: "Track ${i+1}"}${if (f.channelCount > 0) " ${f.channelCount}ch" else ""}"
        }
        AlertDialog.Builder(requireContext()).setTitle("Audio Track")
            .setSingleChoiceItems(labels.toTypedArray(), groups.indexOfFirst { it.isSelected }) { d, w ->
                exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                    .setOverrideForType(TrackSelectionOverride(groups[w].mediaTrackGroup, 0)).build()
                d.dismiss()
            }.setNegativeButton("Cancel", null).show()
    }

    // -------------------------------------------------------------------------
    // Video Quality dialog
    // -------------------------------------------------------------------------

    private fun showVideoQualityDialog() {
        val exo = player ?: return
        val vGroups = exo.currentTracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
        if (vGroups.isEmpty()) {
            AlertDialog.Builder(requireContext()).setTitle("Video Quality")
                .setMessage("No video tracks available.").setPositiveButton("OK", null).show()
            return
        }
        data class E(val gi: Int, val ti: Int, val label: String, val bps: Int)
        val entries = mutableListOf<E>()
        vGroups.forEachIndexed { gi, g ->
            for (ti in 0 until g.length) {
                val f = g.getTrackFormat(ti)
                entries.add(E(gi, ti,
                    "${if (f.height > 0) "${f.height}p" else "Track ${gi+1}"}${if (f.frameRate > 0) " ${f.frameRate.toInt()}fps" else ""}${if (f.bitrate > 0) " (${f.bitrate/1000}kbps)" else ""}",
                    f.bitrate))
            }
        }
        entries.sortByDescending { it.bps }
        val labels = mutableListOf("Auto") + entries.map { it.label }
        val cur = entries.indexOfFirst { e -> vGroups[e.gi].isTrackSelected(e.ti) }
        AlertDialog.Builder(requireContext()).setTitle("Video Quality")
            .setSingleChoiceItems(labels.toTypedArray(), if (cur >= 0) cur + 1 else 0) { d, w ->
                if (w == 0) {
                    exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                        .clearOverridesOfType(C.TRACK_TYPE_VIDEO).build()
                } else {
                    val e = entries[w - 1]
                    exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                        .setOverrideForType(TrackSelectionOverride(vGroups[e.gi].mediaTrackGroup, e.ti)).build()
                }
                d.dismiss()
            }.setNegativeButton("Cancel", null).show()
    }

    // -------------------------------------------------------------------------
    // Zoom toggle
    // -------------------------------------------------------------------------

    private fun toggleZoom() {
        isZoomFit = !isZoomFit
        applyZoomMode()
        binding.btnZoom.setImageResource(
            if (isZoomFit) R.drawable.ic_zoom_fit else R.drawable.ic_zoom_fill
        )
    }

    private fun applyZoomMode() {
        binding.playerView.resizeMode =
            if (isZoomFit) AspectRatioFrameLayout.RESIZE_MODE_FIT
            else           AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    }

    // -------------------------------------------------------------------------
    // Player listener
    // -------------------------------------------------------------------------

    private val playerListener = object : Player.Listener {

        override fun onTracksChanged(tracks: Tracks) {
            val exo = player ?: return
            val vGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
            if (vGroups.isEmpty()) return
            var bestG = vGroups[0]; var bestT = 0; var bestBps = -1
            vGroups.forEach { g ->
                for (ti in 0 until g.length) {
                    val bps = g.getTrackFormat(ti).bitrate
                    if (bps > bestBps) { bestBps = bps; bestG = g; bestT = ti }
                }
            }
            if (!bestG.isTrackSelected(bestT)) {
                exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                    .setOverrideForType(TrackSelectionOverride(bestG.mediaTrackGroup, bestT)).build()
                Log.d(TAG, "Auto-selected ${bestBps/1000}kbps video")
            }
        }

        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_BUFFERING -> {
                    binding.progressBuffering.visibility = View.VISIBLE
                    viewModel.updatePlaybackState(isPlaying = false, isBuffering = true)
                }
                Player.STATE_READY -> {
                    binding.progressBuffering.visibility = View.GONE
                    viewModel.updatePlaybackState(isPlaying = player?.isPlaying ?: false, isBuffering = false)
                }
                else -> binding.progressBuffering.visibility = View.GONE
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            viewModel.updatePlaybackState(isPlaying = isPlaying)
            binding.btnPlayPause.setImageResource(
                if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
            )
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "Player error [${error.errorCode}]: ${error.message}", error)
            showError("${error.message}\n\nError Code: ${error.errorCode}")
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun showError(message: String) {
        binding.progressBuffering.visibility = View.GONE
        binding.errorOverlay.visibility      = View.VISIBLE
        binding.tvError.text                 = message
    }

    private fun hexToBase64Url(hex: String) =
        Base64.encodeToString(hexToBytes(hex), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0)
        return ByteArray(hex.length / 2) { i ->
            ((Character.digit(hex[i * 2], 16) shl 4) or Character.digit(hex[i * 2 + 1], 16)).toByte()
        }
    }

    private fun releasePlayer() {
        progressHandler.removeCallbacks(progressRunnable)
        hideHandler.removeCallbacks(hideRunnable)
        player?.removeListener(playerListener)
        player?.release()
        player = null
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun onPause() {
        super.onPause()
        player?.pause()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        releasePlayer()
        exitPlayerMode()
        _binding = null
    }
}
