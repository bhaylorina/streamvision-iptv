package com.streamvision.iptv.presentation.ui.player

import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
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
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.streamvision.iptv.R
import com.streamvision.iptv.databinding.FragmentPlayerBinding
import com.streamvision.iptv.player.PlayerManager
import com.streamvision.iptv.presentation.viewmodel.PlayerViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.abs

@UnstableApi
@AndroidEntryPoint
class PlayerFragment : Fragment() {

    private var _binding: FragmentPlayerBinding? = null
    private val binding get() = _binding!!

    private val viewModel: PlayerViewModel by viewModels()
    private val args: PlayerFragmentArgs by navArgs()

    @Inject
    lateinit var playerManager: PlayerManager

    private var isZoomFit = true

    // Gestures
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
        
        audioManager = requireContext().getSystemService(Context.AUDIO_SERVICE) as AudioManager
        maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        
        hideSystemUI()
        setupGestures()
        setupClickListeners()
        observeState()
        
        // Load UI info
        viewModel.loadChannel(args.channelId)

        // CHALTE HUE PLAYER KO ATTACH KARNA (Seamless Playback)
        binding.playerView.player = playerManager.player
        playerManager.addListener(playerListener)
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
    }

    // -------------------------------------------------------------------------
    // Gestures (Brightness & Volume)
    // -------------------------------------------------------------------------

    private fun setupGestures() {
        binding.playerView.setOnTouchListener { v, event ->
            if (_binding == null) return@setOnTouchListener false
            
            val screenWidth = v.width.toFloat()
            val edgeMarginPx = 50 * resources.displayMetrics.density 

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (event.x < edgeMarginPx || event.x > screenWidth - edgeMarginPx) {
                        return@setOnTouchListener false
                    }
                    gestureStartX = event.x
                    gestureStartY = event.y
                    gestureType = GestureType.NONE
                    initVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    initBrightness = getCurrentBrightness()
                    
                    // FIX: Temporarily disable controller auto show to prevent controls popping up during a swipe gesture
                    binding.playerView.controllerAutoShow = false
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - gestureStartX
                    val dy = event.y - gestureStartY

                    if (gestureType == GestureType.NONE) {
                        if (abs(dy) < GESTURE_THRESHOLD && abs(dx) < GESTURE_THRESHOLD) {
                            return@setOnTouchListener false
                        }
                        gestureType = if (abs(dy) > abs(dx)) {
                            if (gestureStartX < screenWidth / 2) GestureType.BRIGHTNESS else GestureType.VOLUME
                        } else {
                            GestureType.HORIZONTAL
                        }
                    }

                    when (gestureType) {
                        GestureType.BRIGHTNESS -> {
                            binding.playerView.hideController() // FIX: Hide controller forcibly
                            handleBrightness(dy, v.height.toFloat())
                            true
                        }
                        GestureType.VOLUME -> {
                            binding.playerView.hideController() // FIX: Hide controller forcibly
                            handleVolume(dy, v.height.toFloat())
                            true
                        }
                        else -> false
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // FIX: Restore auto show state. If it was a simple tap, toggle manually.
                    binding.playerView.controllerAutoShow = true
                    if (gestureType == GestureType.NONE && event.actionMasked == MotionEvent.ACTION_UP) {
                        if (binding.playerView.isControllerFullyVisible) {
                            binding.playerView.hideController()
                        } else {
                            binding.playerView.showController()
                        }
                        gestureType = GestureType.NONE
                        return@setOnTouchListener true
                    }
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
    // Click listeners & Dialogs
    // -------------------------------------------------------------------------

    private fun setupClickListeners() {
        binding.btnRetry.setOnClickListener {
            playerManager.currentChannel?.let { channel ->
                playerManager.play(channel)
            }
        }

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    isEnabled = false
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
                
            binding.playerView.findViewById<ImageButton>(R.id.btn_fullscreen)?.apply {
                setImageResource(R.drawable.ic_fullscreen_exit)
                setOnClickListener {
                    requireActivity().onBackPressedDispatcher.onBackPressed()
                }
            }
        }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    if (_binding == null) return@collect
                    if (state.error != null) showError(state.error)
                }
            }
        }
    }

    private fun showAudioTrackDialog() {
        val exoPlayer = playerManager.player
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
        val exoPlayer = playerManager.player
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
    // Player Listener & Lifecycle
    // -------------------------------------------------------------------------

    private val playerListener = object : Player.Listener {
        override fun onTracksChanged(tracks: Tracks) {
            if (_binding == null) return
            val exoPlayer = playerManager.player
            val videoGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
            if (videoGroups.isEmpty()) return

            var bestGroup = videoGroups[0]
            var bestTrack = 0
            var bestBitrate = -1

            videoGroups.forEach { group ->
                for (ti in 0 until group.length) {
                    val bps = group.getTrackFormat(ti).bitrate
                    if (bps > bestBitrate) {
                        bestBitrate = bps
                        bestGroup = group
                        bestTrack = ti
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
                    // Keep WakeLock during buffering to prevent screen off
                    playerManager.acquireWakeLock()
                }
                Player.STATE_READY -> {
                    binding.progressBuffering.visibility = View.GONE
                    viewModel.updatePlaybackState(isPlaying = playerManager.isPlaying, isBuffering = false)
                    // WakeLock is managed by PlayerManager.play() - no need to acquire here
                }
                else -> binding.progressBuffering.visibility = View.GONE
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (_binding == null) return
            viewModel.updatePlaybackState(isPlaying = isPlaying)
            // WakeLock is now managed by PlayerManager - no need to set keepScreenOn
        }

        override fun onPlayerError(error: PlaybackException) {
            if (_binding == null) return
            Log.e(TAG, "Player error[${error.errorCode}]: ${error.message}", error)
            showError("${error.message}\n\nError Code: ${error.errorCode}")
        }
    }

    private fun showError(message: String) {
        if (_binding == null) return
        binding.progressBuffering.visibility = View.GONE
        binding.errorOverlay.visibility = View.VISIBLE
        binding.tvError.text = message
    }

    override fun onDestroyView() {
        super.onDestroyView()
        
        overlayHandler.removeCallbacksAndMessages(null)
        playerManager.removeListener(playerListener)
        
        binding.playerView.player = null 
        
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        activity?.window?.let { window ->
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.show(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
        }
        
        _binding = null
    }
}
