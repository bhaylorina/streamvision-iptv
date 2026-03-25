package com.streamvision.iptv.presentation.ui.player

import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Base64
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
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
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.streamvision.iptv.R
import com.streamvision.iptv.databinding.FragmentPlayerBinding
import com.streamvision.iptv.domain.model.Channel
import com.streamvision.iptv.presentation.viewmodel.PlayerViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import kotlin.math.abs

@UnstableApi
@AndroidEntryPoint
class PlayerFragment : Fragment() {

    private var _binding: FragmentPlayerBinding? = null
    private val binding get() = _binding!!

    private val viewModel: PlayerViewModel by viewModels()
    private val args: PlayerFragmentArgs by navArgs()

    private var player: ExoPlayer? = null
    private var currentChannel: Channel? = null

    // Zoom state: true = FIT (default), false = FILL
    private var isZoomFit = true

    // -------------------------------------------------------------------------
    // Gesture Variables
    // -------------------------------------------------------------------------
    private lateinit var audioManager: AudioManager
    private var maxVolume = 0
    private var initVolume = 0
    private var initBrightness = 0f

    private var gestureStartX = 0f
    private var gestureStartY = 0f
    private var gestureType = GestureType.NONE
    private val GESTURE_THRESHOLD = 20f
    private val BAR_TRACK_DP = 120

    private val overlayHandler = Handler(Looper.getMainLooper())
    private val hideBrightness = Runnable { _binding?.brightnessOverlay?.visibility = View.GONE }
    private val hideVolume = Runnable { _binding?.volumeOverlay?.visibility = View.GONE }

    private enum class GestureType { NONE, BRIGHTNESS, VOLUME, HORIZONTAL }

    companion object {
        private const val TAG = "PlayerFragment"
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPlayerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // Initialize Audio Manager for Volume control
        audioManager = requireContext().getSystemService(Context.AUDIO_SERVICE) as AudioManager
        maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        
        hideSystemUI()
        setupGestures()
        setupClickListeners()
        observeState()
        viewModel.loadChannel(args.channelId)
    }

    private fun hideSystemUI() {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        val window = activity?.window ?: return
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, requireView())
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    fun handlePipModeChange(isInPiP: Boolean) {
        if (_binding == null) return
        binding.playerView.useController = !isInPiP
        binding.tvChannelName.visibility = if (isInPiP) View.GONE else View.VISIBLE
    }

    // -------------------------------------------------------------------------
    // Gestures (Brightness & Volume with Edge Safe Margins)
    // -------------------------------------------------------------------------

    private fun setupGestures() {
        binding.playerView.setOnTouchListener { v, event ->
            if (_binding == null) return@setOnTouchListener false
            
            val screenWidth = v.width.toFloat()
            // 50dp safe zone to prevent conflicting with Android Back Swipe
            val edgeMarginPx = 50 * resources.displayMetrics.density 

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // Ignore gesture if user touches extreme edges (allow system back swipe)
                    if (event.x < edgeMarginPx || event.x > screenWidth - edgeMarginPx) {
                        return@setOnTouchListener false
                    }

                    gestureStartX = event.x
                    gestureStartY = event.y
                    gestureType = GestureType.NONE
                    initVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    initBrightness = getCurrentBrightness()
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - gestureStartX
                    val dy = event.y - gestureStartY

                    if (gestureType == GestureType.NONE) {
                        // Wait until threshold is crossed to determine swipe intent
                        if (abs(dy) < GESTURE_THRESHOLD && abs(dx) < GESTURE_THRESHOLD) {
                            return@setOnTouchListener false
                        }
                        
                        gestureType = if (abs(dy) > abs(dx)) {
                            // Vertical Swipe
                            if (gestureStartX < screenWidth / 2) GestureType.BRIGHTNESS else GestureType.VOLUME
                        } else {
                            // Horizontal Swipe
                            GestureType.HORIZONTAL
                        }
                    }

                    when (gestureType) {
                        GestureType.BRIGHTNESS -> {
                            handleBrightness(dy, v.height.toFloat())
                            true
                        }
                        GestureType.VOLUME -> {
                            handleVolume(dy, v.height.toFloat())
                            true
                        }
                        else -> false
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    gestureType = GestureType.NONE
                    false
                }
                else -> false
            }
        }
    }

    private fun handleBrightness(dy: Float, viewHeight: Float) {
        if (_binding == null) return
        val newBright = (initBrightness - (dy / viewHeight)).coerceIn(0.01f, 1f)
        setBrightness(newBright)
        val pct = (newBright * 100).toInt()
        
        setBarHeight(binding.brightnessBar, pct)
        binding.tvBrightnessPct.text = "$pct%"
        
        binding.brightnessOverlay.visibility = View.VISIBLE
        overlayHandler.removeCallbacks(hideBrightness)
        overlayHandler.postDelayed(hideBrightness, 1500L)
    }

    private fun setBrightness(value: Float) {
        val window = activity?.window ?: return
        val lp = window.attributes
        lp.screenBrightness = value
        window.attributes = lp
    }

    private fun getCurrentBrightness(): Float {
        val lp = activity?.window?.attributes ?: return 0.5f
        return if (lp.screenBrightness < 0) {
            try {
                Settings.System.getInt(requireContext().contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
            } catch (e: Exception) { 0.5f }
        } else lp.screenBrightness
    }

    private fun handleVolume(dy: Float, viewHeight: Float) {
        if (_binding == null) return
        val newVol = (initVolume - (dy / viewHeight) * maxVolume).toInt().coerceIn(0, maxVolume)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0)
        
        val pct = if (maxVolume > 0) (newVol * 100) / maxVolume else 0
        setBarHeight(binding.volumeBar, pct)
        binding.tvVolumePct.text = "$pct%"
        
        binding.ivVolumeIcon.setImageResource(
            when {
                newVol == 0 -> R.drawable.ic_volume_mute
                newVol < maxVolume / 2 -> R.drawable.ic_volume_down
                else -> R.drawable.ic_volume_up
            }
        )

        binding.volumeOverlay.visibility = View.VISIBLE
        overlayHandler.removeCallbacks(hideVolume)
        overlayHandler.postDelayed(hideVolume, 1500L)
    }

    private fun setBarHeight(barView: View, pct: Int) {
        val density = resources.displayMetrics.density
        val lp = barView.layoutParams
        lp.height = ((BAR_TRACK_DP * density).toInt() * pct) / 100
        barView.layoutParams = lp
    }

    // -------------------------------------------------------------------------
    // Click listeners
    // -------------------------------------------------------------------------

    private fun setupClickListeners() {
        binding.btnRetry.setOnClickListener {
            currentChannel?.let { channel ->
                releasePlayer()
                setupAndPlay(channel)
            }
        }

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    isEnabled = false // Safe back crash prevention
                    releasePlayer()
                    findNavController().popBackStack()
                }
            }
        )

        binding.playerView.post {
            if (_binding == null) return@post

            binding.playerView.findViewById<View>(R.id.btn_audio_track)
                ?.setOnClickListener { showAudioTrackDialog() }

            binding.playerView.findViewById<View>(R.id.btn_video_quality)
                ?.setOnClickListener { showVideoQualityDialog() }

            binding.playerView.findViewById<View>(R.id.btn_zoom)
                ?.setOnClickListener { toggleZoom() }
        }
    }

    // -------------------------------------------------------------------------
    // State observer
    // -------------------------------------------------------------------------

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    if (_binding == null) return@collect

                    state.currentChannel?.let { channel ->
                        if (currentChannel?.id != channel.id) {
                            currentChannel = channel
                            Log.d(TAG, "New channel: ${channel.name} | URL: ${channel.url}")
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
        binding.errorOverlay.visibility = View.GONE
        binding.progressBuffering.visibility = View.VISIBLE

        val httpFactory = buildHttpDataSourceFactory(channel)
        val playerBuilder = ExoPlayer.Builder(requireContext())

        if (!channel.drmKey.isNullOrBlank()) {
            val drm = buildClearKeyDrmSessionManager(channel.drmKey)
            val msf = DefaultMediaSourceFactory(requireContext())
                .setDataSourceFactory(httpFactory)
                .apply { if (drm != null) setDrmSessionManagerProvider { drm } }
            playerBuilder.setMediaSourceFactory(msf)
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
    }

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

    private fun buildClearKeyDrmSessionManager(drmKey: String): DefaultDrmSessionManager? {
        return try {
            val parts = drmKey.trim().split(":")
            if (parts.size != 2) return null
            val keyIdB64 = hexToBase64Url(parts[0])
            val keyB64   = hexToBase64Url(parts[1])
            val json = """{"keys":[{"kty":"oct","k":"$keyB64","kid":"$keyIdB64"}],"type":"temporary"}"""
            val callback = LocalMediaDrmCallback(json.toByteArray(Charsets.UTF_8))
            DefaultDrmSessionManager.Builder()
                .setUuidAndExoMediaDrmProvider(C.CLEARKEY_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
                .setMultiSession(false)
                .build(callback)
        } catch (e: Exception) {
            Log.e(TAG, "DRM setup failed: ${e.message}", e)
            null
        }
    }

    // -------------------------------------------------------------------------
    // UI Dialogs & Controls
    // -------------------------------------------------------------------------

    private fun showAudioTrackDialog() {
        val exoPlayer = player ?: return
        val audioGroups = exoPlayer.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }

        if (audioGroups.isEmpty()) {
            AlertDialog.Builder(requireContext())
                .setTitle("Audio Track")
                .setMessage("No audio tracks available.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val labels = audioGroups.mapIndexed { index, group ->
            val fmt  = group.getTrackFormat(0)
            val lang = fmt.language?.uppercase() ?: "Track ${index + 1}"
            val ch   = if (fmt.channelCount > 0) " ${fmt.channelCount}ch" else ""
            val kbps = if (fmt.bitrate > 0) " ${fmt.bitrate / 1000}kbps" else ""
            "$lang$ch$kbps"
        }

        val currentIndex = audioGroups.indexOfFirst { it.isSelected }

        AlertDialog.Builder(requireContext())
            .setTitle("Audio Track")
            .setSingleChoiceItems(labels.toTypedArray(), currentIndex) { dlg, which ->
                val override = TrackSelectionOverride(audioGroups[which].mediaTrackGroup, 0)
                exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                    .buildUpon()
                    .setOverrideForType(override)
                    .build()
                dlg.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showVideoQualityDialog() {
        val exoPlayer = player ?: return
        val videoGroups = exoPlayer.currentTracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }

        if (videoGroups.isEmpty()) {
            AlertDialog.Builder(requireContext())
                .setTitle("Video Quality")
                .setMessage("No video tracks available.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        data class Entry(val groupIdx: Int, val trackIdx: Int, val label: String, val bitrate: Int)

        val entries = mutableListOf<Entry>()
        videoGroups.forEachIndexed { gi, group ->
            for (ti in 0 until group.length) {
                val fmt  = group.getTrackFormat(ti)
                val res  = if (fmt.height > 0) "${fmt.height}p" else "Track ${gi + 1}"
                val fps  = if (fmt.frameRate > 0) " ${fmt.frameRate.toInt()}fps" else ""
                val kbps = if (fmt.bitrate > 0) " (${fmt.bitrate / 1000}kbps)" else ""
                entries.add(Entry(gi, ti, "$res$fps$kbps", fmt.bitrate))
            }
        }
        entries.sortByDescending { it.bitrate }

        val labels = mutableListOf("Auto") + entries.map { it.label }
        val currentEntry = entries.indexOfFirst { e -> videoGroups[e.groupIdx].isTrackSelected(e.trackIdx) }
        val checkedIndex = if (currentEntry >= 0) currentEntry + 1 else 0

        AlertDialog.Builder(requireContext())
            .setTitle("Video Quality")
            .setSingleChoiceItems(labels.toTypedArray(), checkedIndex) { dlg, which ->
                if (which == 0) {
                    exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                        .buildUpon()
                        .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                        .build()
                } else {
                    val e = entries[which - 1]
                    val override = TrackSelectionOverride(videoGroups[e.groupIdx].mediaTrackGroup, e.trackIdx)
                    exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                        .buildUpon()
                        .setOverrideForType(override)
                        .build()
                }
                dlg.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toggleZoom() {
        isZoomFit = !isZoomFit
        applyZoomMode()
        val iconRes = if (isZoomFit) R.drawable.ic_zoom_fit else R.drawable.ic_zoom_fill
        binding.playerView.findViewById<ImageButton>(R.id.btn_zoom)?.setImageResource(iconRes)
    }

    private fun applyZoomMode() {
        binding.playerView.resizeMode = if (isZoomFit)
            AspectRatioFrameLayout.RESIZE_MODE_FIT
        else
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    }

    // -------------------------------------------------------------------------
    // Player listener
    // -------------------------------------------------------------------------

    private val playerListener = object : Player.Listener {

        override fun onTracksChanged(tracks: Tracks) {
            if (_binding == null) return
            val exoPlayer = player ?: return
            val videoGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
            if (videoGroups.isEmpty()) return

            var bestGroup   = videoGroups[0]
            var bestTrack   = 0
            var bestBitrate = -1

            videoGroups.forEach { group ->
                for (ti in 0 until group.length) {
                    val bps = group.getTrackFormat(ti).bitrate
                    if (bps > bestBitrate) {
                        bestBitrate = bps
                        bestGroup   = group
                        bestTrack   = ti
                    }
                }
            }

            if (!bestGroup.isTrackSelected(bestTrack)) {
                val override = TrackSelectionOverride(bestGroup.mediaTrackGroup, bestTrack)
                exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                    .buildUpon()
                    .setOverrideForType(override)
                    .build()
                Log.d(TAG, "Auto-selected highest video: ${bestBitrate / 1000}kbps")
            }
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (_binding == null) return
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
            if (_binding == null) return
            viewModel.updatePlaybackState(isPlaying = isPlaying)
        }

        override fun onPlayerError(error: PlaybackException) {
            if (_binding == null) return
            Log.e(TAG, "Player error [${error.errorCode}]: ${error.message}", error)
            showError("${error.message}\n\nError Code: ${error.errorCode}")
        }
    }

    // -------------------------------------------------------------------------
    // Helpers & Lifecycle
    // -------------------------------------------------------------------------

    private fun showError(message: String) {
        if (_binding == null) return
        binding.progressBuffering.visibility = View.GONE
        binding.errorOverlay.visibility = View.VISIBLE
        binding.tvError.text = message
    }

    private fun hexToBase64Url(hex: String): String =
        Base64.encodeToString(hexToBytes(hex), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "Hex string must have even length" }
        return ByteArray(hex.length / 2) { i ->
            ((Character.digit(hex[i * 2], 16) shl 4) or Character.digit(hex[i * 2 + 1], 16)).toByte()
        }
    }

    private fun releasePlayer() {
        _binding?.playerView?.player = null 
        player?.removeListener(playerListener)
        player?.release()
        player = null
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        
        // Remove handler callbacks safely to prevent memory leaks
        overlayHandler.removeCallbacksAndMessages(null)
        releasePlayer()
        
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        activity?.window?.let { WindowCompat.setDecorFitsSystemWindows(it, true) }
        _binding = null
    }
}
