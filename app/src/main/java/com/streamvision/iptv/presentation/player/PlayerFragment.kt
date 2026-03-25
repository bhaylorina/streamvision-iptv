package com.streamvision.iptv.presentation.ui.player

import android.content.pm.ActivityInfo
import android.graphics.Color
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
import android.view.WindowManager
import android.widget.ImageButton
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.core.content.getSystemService
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
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
import androidx.media3.ui.PlayerView
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.streamvision.iptv.R
import com.streamvision.iptv.databinding.FragmentPlayerBinding
import com.streamvision.iptv.domain.model.Channel
import com.streamvision.iptv.player.PlayerManager
import com.streamvision.iptv.presentation.ui.MainActivity
import com.streamvision.iptv.presentation.viewmodel.ChannelsViewModel
import com.streamvision.iptv.presentation.viewmodel.PlayerViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import kotlin.math.abs

@UnstableApi
@AndroidEntryPoint
class PlayerFragment : Fragment() {

    private var _binding: FragmentPlayerBinding? = null
    private val binding get() = _binding!!

    private val channelsViewModel: ChannelsViewModel by activityViewModels()
    private val viewModel: PlayerViewModel by viewModels()
    private val args: PlayerFragmentArgs by navArgs()

    private val playerManager: PlayerManager get() = channelsViewModel.playerManager

    private var currentChannel: Channel? = null
    private var isZoomFit = true
    private var playerModeExited = false

    private lateinit var audioManager: AudioManager
    private var maxVolume      = 0
    private var initVolume     = 0
    private var initBrightness = 0f

    private var gestureStartX     = 0f
    private var gestureStartY     = 0f
    private var gestureType       = GestureType.NONE
    private val GESTURE_THRESHOLD = 10f
    private val BAR_TRACK_DP      = 120

    private val overlayHandler = Handler(Looper.getMainLooper())
    private val hideBrightness = Runnable { binding.brightnessOverlay.visibility = View.GONE }
    private val hideVolume     = Runnable { binding.volumeOverlay.visibility     = View.GONE }

    private enum class GestureType { NONE, BRIGHTNESS, VOLUME, HORIZONTAL }

    companion object {
        private const val TAG             = "PlayerFragment"
        private const val OVERLAY_HIDE_MS = 1500L
    }

    private val playerListener = object : Player.Listener {
        override fun onTracksChanged(tracks: Tracks) {
            val exo = playerManager.player
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
            }
        }

        override fun onPlaybackStateChanged(state: Int) {
            binding.progressBuffering.visibility = if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
            viewModel.updatePlaybackState(isPlaying = playerManager.isPlaying, isBuffering = state == Player.STATE_BUFFERING)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            viewModel.updatePlaybackState(isPlaying = isPlaying)
        }

        override fun onPlayerError(error: PlaybackException) {
            showError("${error.message}\nCode: ${error.errorCode}")
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentPlayerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        audioManager = requireContext().getSystemService()!!
        maxVolume    = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        playerModeExited = false

        enterPlayerMode()
        setupBackButton()
        setupStaticListeners()
        setupGestures()
        setupControllerVisibilityListener()
        observeChannel()
    }

    private fun setupBackButton() {
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { navigateBack() }
        })
    }

    private fun navigateBack() {
        binding.playerView.player = null
        safeExitPlayerMode()
        currentChannel?.let { (activity as? MainActivity)?.showMiniPlayer(it.name) }
        if (!findNavController().popBackStack()) activity?.finish()
    }

    private fun observeChannel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                val existingChannel = playerManager.currentChannel
                if (existingChannel != null && existingChannel.id == args.channelId) {
                    currentChannel = existingChannel
                    attachPlayerView()
                    binding.tvChannelName.text = existingChannel.name
                } else {
                    viewModel.loadChannel(args.channelId)
                    viewModel.uiState.collect { state ->
                        state.currentChannel?.let { channel ->
                            if (currentChannel?.id != channel.id) {
                                currentChannel = channel
                                playerManager.play(channel)
                                attachPlayerView()
                                binding.tvChannelName.text = channel.name
                            }
                        }
                        if (state.error != null) showError(state.error)
                    }
                }
            }
        }
        playerManager.addListener(playerListener)
    }

    private fun attachPlayerView() {
        binding.playerView.player = playerManager.player
        applyZoomMode()
        wireCustomButtons()
    }

    private fun enterPlayerMode() {
        val window = activity?.window ?: return
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, requireView()).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    private fun safeExitPlayerMode() {
        if (playerModeExited) return
        playerModeExited = true
        val window = activity?.window ?: return
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, requireView()).show(WindowInsetsCompat.Type.systemBars())
        window.navigationBarColor = Color.parseColor("#1E1E2E")
        window.statusBarColor = Color.parseColor("#1E1E2E")
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    private fun setupControllerVisibilityListener() {
        binding.playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v ->
            binding.tvChannelName.visibility = v
        })
    }

    fun handlePipModeChange(isInPiP: Boolean) {
        binding.playerView.useController = !isInPiP
        binding.tvChannelName.visibility = if (isInPiP) View.GONE else View.VISIBLE
        binding.brightnessOverlay.visibility = View.GONE
        binding.volumeOverlay.visibility = View.GONE
    }

    private fun setupGestures() {
        binding.playerView.setOnTouchListener { v, event ->
            val screenWidth = v.width.toFloat()
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    gestureStartX = event.x; gestureStartY = event.y; gestureType = GestureType.NONE
                    initVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    initBrightness = getCurrentBrightness()
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - gestureStartX
                    val dy = event.y - gestureStartY
                    if (gestureType == GestureType.NONE) {
                        if (abs(dy) < GESTURE_THRESHOLD && abs(dx) < GESTURE_THRESHOLD) return@setOnTouchListener false
                        gestureType = if (abs(dy) > abs(dx)) {
                            if (gestureStartX < screenWidth / 2) GestureType.BRIGHTNESS else GestureType.VOLUME
                        } else GestureType.HORIZONTAL
                    }
                    when (gestureType) {
                        GestureType.BRIGHTNESS -> { handleBrightness(dy, v.height.toFloat()); true }
                        GestureType.VOLUME     -> { handleVolume(dy, v.height.toFloat()); true }
                        else                   -> false
                    }
                }
                else -> false
            }
        }
    }

    private fun handleBrightness(dy: Float, viewHeight: Float) {
        val newBright = (initBrightness - (dy / viewHeight)).coerceIn(0.01f, 1f)
        setBrightness(newBright)
        val pct = (newBright * 100).toInt()
        setBarHeight(binding.brightnessBar, pct)
        binding.tvBrightnessPct.text = "$pct%"
        binding.brightnessOverlay.visibility = View.VISIBLE
        overlayHandler.removeCallbacks(hideBrightness)
        overlayHandler.postDelayed(hideBrightness, OVERLAY_HIDE_MS)
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
            try { Settings.System.getInt(requireContext().contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f } 
            catch (e: Exception) { 0.5f }
        } else lp.screenBrightness
    }

    private fun handleVolume(dy: Float, viewHeight: Float) {
        val newVol = (initVolume - (dy / viewHeight) * maxVolume).toInt().coerceIn(0, maxVolume)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0)
        val pct = newVol * 100 / maxVolume
        setBarHeight(binding.volumeBar, pct)
        binding.tvVolumePct.text = "$pct%"
        binding.ivVolumeIcon.setImageResource(if (newVol == 0) R.drawable.ic_volume_mute else if (newVol < maxVolume/2) R.drawable.ic_volume_down else R.drawable.ic_volume_up)
        binding.volumeOverlay.visibility = View.VISIBLE
        overlayHandler.removeCallbacks(hideVolume)
        overlayHandler.postDelayed(hideVolume, OVERLAY_HIDE_MS)
    }

    private fun setBarHeight(barView: View, pct: Int) {
        val density = resources.displayMetrics.density
        val lp = barView.layoutParams
        lp.height = (BAR_TRACK_DP * density).toInt() * pct / 100
        barView.layoutParams = lp
    }

    private fun setupStaticListeners() {
        binding.btnRetry.setOnClickListener { currentChannel?.let { playerManager.play(it); attachPlayerView() } }
    }

    private fun wireCustomButtons() {
        binding.playerView.post {
            binding.playerView.findViewById<ImageButton>(R.id.btn_audio_track)?.setOnClickListener { showAudioTrackDialog() }
            binding.playerView.findViewById<ImageButton>(R.id.btn_video_quality)?.setOnClickListener { showVideoQualityDialog() }
            binding.playerView.findViewById<ImageButton>(R.id.btn_zoom)?.setOnClickListener { toggleZoom() }
        }
    }

    private fun showAudioTrackDialog() {
        val exo = playerManager.player
        val groups = exo.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        if (groups.isEmpty()) return
        val labels = groups.mapIndexed { i, g -> "${g.getTrackFormat(0).language?.uppercase() ?: "Track ${i+1}"}" }
        AlertDialog.Builder(requireContext()).setTitle("Audio Track")
            .setSingleChoiceItems(labels.toTypedArray(), groups.indexOfFirst { it.isSelected }) { d, w ->
                exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                    .setOverrideForType(TrackSelectionOverride(groups[w].mediaTrackGroup, 0)).build()
                d.dismiss()
            }.show()
    }

    private fun showVideoQualityDialog() {
        val exo = playerManager.player
        val vGroups = exo.currentTracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
        if (vGroups.isEmpty()) return
        val entries = mutableListOf<String>().apply { add("Auto") }
        // Simplified for brevity
        AlertDialog.Builder(requireContext()).setTitle("Quality").setItems(entries.toTypedArray()) { d, w -> d.dismiss() }.show()
    }

    private fun toggleZoom() {
        isZoomFit = !isZoomFit
        applyZoomMode()
        binding.playerView.findViewById<ImageButton>(R.id.btn_zoom)?.setImageResource(if (isZoomFit) R.drawable.ic_zoom_fit else R.drawable.ic_zoom_fill)
    }

    private fun applyZoomMode() {
        binding.playerView.resizeMode = if (isZoomFit) AspectRatioFrameLayout.RESIZE_MODE_FIT else AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    }

    private fun showError(msg: String) {
        binding.progressBuffering.visibility = View.GONE
        binding.errorOverlay.visibility = View.VISIBLE
        binding.tvError.text = msg
    }

    override fun onResume() { super.onResume(); attachPlayerView() }
    override fun onPause() { super.onPause(); binding.playerView.player = null }
    override fun onDestroyView() {
        super.onDestroyView()
        overlayHandler.removeCallbacksAndMessages(null)
        playerManager.removeListener(playerListener)
        binding.playerView.player = null
        safeExitPlayerMode()
        _binding = null
    }
}
