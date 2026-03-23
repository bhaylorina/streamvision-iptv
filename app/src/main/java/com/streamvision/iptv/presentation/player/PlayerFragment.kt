package com.streamvision.iptv.presentation.ui.player

import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.LayoutInflater
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

    companion object {
        private const val TAG = "PlayerFragment"
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
        setupStaticListeners()
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
    // Static listeners (retry + back — don't need player to exist)
    // -------------------------------------------------------------------------

    private fun setupStaticListeners() {
        binding.btnRetry.setOnClickListener {
            currentChannel?.let { ch -> releasePlayer(); setupAndPlay(ch) }
        }
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    releasePlayer(); findNavController().popBackStack()
                }
            }
        )
    }

    // -------------------------------------------------------------------------
    // Wire the 3 custom buttons AFTER PlayerView has inflated its controller.
    // We use playerView.post{} because the controller view is inflated lazily.
    // These 3 buttons live inside exo_player_control_view.xml.
    // -------------------------------------------------------------------------

    private fun wireCustomButtons() {
        binding.playerView.post {
            val audioBtn = binding.playerView.findViewById<ImageButton>(R.id.btn_audio_track)
            val qualBtn  = binding.playerView.findViewById<ImageButton>(R.id.btn_video_quality)
            val zoomBtn  = binding.playerView.findViewById<ImageButton>(R.id.btn_zoom)

            audioBtn?.setOnClickListener { showAudioTrackDialog() }
            qualBtn?.setOnClickListener  { showVideoQualityDialog() }
            zoomBtn?.setOnClickListener  { toggleZoom() }

            Log.d(TAG, "Custom buttons wired: audio=$audioBtn quality=$qualBtn zoom=$zoomBtn")
        }
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
        binding.errorOverlay.visibility      = View.GONE
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

        player = playerBuilder.build().also { exo ->
            binding.playerView.player = exo
            exo.addListener(playerListener)
            exo.setMediaItem(MediaItem.Builder().setUri(channel.url).build())
            exo.prepare()
            exo.playWhenReady = true
        }

        applyZoomMode()

        // Wire custom buttons after PlayerView has set up its controller
        wireCustomButtons()
    }

    // -------------------------------------------------------------------------
    // HTTP headers
    // -------------------------------------------------------------------------

    private fun buildHttpDataSourceFactory(channel: Channel): DefaultHttpDataSource.Factory {
        val h = mutableMapOf<String, String>()
        if (!channel.userAgent.isNullOrBlank()) h["User-Agent"] = channel.userAgent
        if (!channel.referer.isNullOrBlank())   h["Referer"]     = channel.referer
        if (!channel.cookie.isNullOrBlank())    h["Cookie"]      = channel.cookie
        return DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)
            .setDefaultRequestProperties(h)
    }

    // -------------------------------------------------------------------------
    // ClearKey DRM
    // -------------------------------------------------------------------------

    private fun buildClearKeyDrmSessionManager(drmKey: String): DefaultDrmSessionManager? {
        return try {
            val p = drmKey.trim().split(":")
            if (p.size != 2) return null
            val json = """{"keys":[{"kty":"oct","k":"${hexToBase64Url(p[1])}","kid":"${hexToBase64Url(p[0])}"}],"type":"temporary"}"""
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
        val exo    = player ?: return
        val groups = exo.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }

        if (groups.isEmpty()) {
            AlertDialog.Builder(requireContext())
                .setTitle("Audio Track")
                .setMessage("No audio tracks available.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val labels = groups.mapIndexed { i, g ->
            val f    = g.getTrackFormat(0)
            val lang = f.language?.uppercase() ?: "Track ${i + 1}"
            val ch   = if (f.channelCount > 0) " ${f.channelCount}ch" else ""
            "$lang$ch"
        }
        val current = groups.indexOfFirst { it.isSelected }

        AlertDialog.Builder(requireContext())
            .setTitle("Audio Track")
            .setSingleChoiceItems(labels.toTypedArray(), current) { dlg, which ->
                exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                    .setOverrideForType(TrackSelectionOverride(groups[which].mediaTrackGroup, 0))
                    .build()
                dlg.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // -------------------------------------------------------------------------
    // Video Quality dialog
    // -------------------------------------------------------------------------

    private fun showVideoQualityDialog() {
        val exo    = player ?: return
        val vGroups = exo.currentTracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }

        if (vGroups.isEmpty()) {
            AlertDialog.Builder(requireContext())
                .setTitle("Video Quality")
                .setMessage("No video tracks available.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        data class E(val gi: Int, val ti: Int, val label: String, val bps: Int)

        val entries = mutableListOf<E>()
        vGroups.forEachIndexed { gi, g ->
            for (ti in 0 until g.length) {
                val f = g.getTrackFormat(ti)
                entries.add(
                    E(gi, ti,
                        "${if (f.height > 0) "${f.height}p" else "Track ${gi+1}"}${if (f.frameRate > 0) " ${f.frameRate.toInt()}fps" else ""}${if (f.bitrate > 0) " (${f.bitrate/1000}kbps)" else ""}",
                        f.bitrate)
                )
            }
        }
        entries.sortByDescending { it.bps }

        val labels  = mutableListOf("Auto") + entries.map { it.label }
        val current = entries.indexOfFirst { e -> vGroups[e.gi].isTrackSelected(e.ti) }

        AlertDialog.Builder(requireContext())
            .setTitle("Video Quality")
            .setSingleChoiceItems(labels.toTypedArray(), if (current >= 0) current + 1 else 0) { dlg, which ->
                if (which == 0) {
                    exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                        .clearOverridesOfType(C.TRACK_TYPE_VIDEO).build()
                } else {
                    val e = entries[which - 1]
                    exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                        .setOverrideForType(TrackSelectionOverride(vGroups[e.gi].mediaTrackGroup, e.ti))
                        .build()
                }
                dlg.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // -------------------------------------------------------------------------
    // Zoom toggle
    // -------------------------------------------------------------------------

    private fun toggleZoom() {
        isZoomFit = !isZoomFit
        applyZoomMode()
        // Update the icon inside the controller view
        binding.playerView.findViewById<ImageButton>(R.id.btn_zoom)
            ?.setImageResource(if (isZoomFit) R.drawable.ic_zoom_fit else R.drawable.ic_zoom_fill)
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

        // Auto-select highest bitrate video track
        override fun onTracksChanged(tracks: Tracks) {
            val exo     = player ?: return
            val vGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
            if (vGroups.isEmpty()) return

            var bestG   = vGroups[0]; var bestT = 0; var bestBps = -1
            vGroups.forEach { g ->
                for (ti in 0 until g.length) {
                    val bps = g.getTrackFormat(ti).bitrate
                    if (bps > bestBps) { bestBps = bps; bestG = g; bestT = ti }
                }
            }
            if (!bestG.isTrackSelected(bestT)) {
                exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                    .setOverrideForType(TrackSelectionOverride(bestG.mediaTrackGroup, bestT)).build()
                Log.d(TAG, "Auto-selected ${bestBps / 1000}kbps video")
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
                    viewModel.updatePlaybackState(
                        isPlaying   = player?.isPlaying ?: false,
                        isBuffering = false
                    )
                }
                else -> binding.progressBuffering.visibility = View.GONE
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            viewModel.updatePlaybackState(isPlaying = isPlaying)
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
