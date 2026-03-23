package com.streamvision.iptv.presentation.ui.player

import android.content.pm.ActivityInfo
import android.os.Bundle
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

    // Zoom state: true = FIT (default), false = FILL
    private var isZoomFit = true

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
        hideSystemUI()
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
                    releasePlayer()
                    findNavController().popBackStack()
                }
            }
        )

        // The 3 buttons live inside the custom controller layout inside PlayerView.
        // We must look them up via PlayerView's own view hierarchy.
        binding.playerView.post {
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

        applyZoomMode() // restore zoom preference on new player
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
    // 1. Audio Track dialog
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

    // -------------------------------------------------------------------------
    // 2. Video Quality dialog  (default = highest bitrate via onTracksChanged)
    // -------------------------------------------------------------------------

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

    // -------------------------------------------------------------------------
    // 3. Zoom / Resize toggle  (FIT ↔ FILL)
    // -------------------------------------------------------------------------

    private fun toggleZoom() {
        isZoomFit = !isZoomFit
        applyZoomMode()
        val iconRes = if (isZoomFit) R.drawable.ic_zoom_fit else R.drawable.ic_zoom_fill
        binding.playerView.findViewById<android.widget.ImageButton>(R.id.btn_zoom)
            ?.setImageResource(iconRes)
    }

    private fun applyZoomMode() {
        binding.playerView.resizeMode = if (isZoomFit)
            AspectRatioFrameLayout.RESIZE_MODE_FIT   // nothing cropped (default)
        else
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM  // fills screen, edges may crop
    }

    // -------------------------------------------------------------------------
    // Player listener
    // -------------------------------------------------------------------------

    private val playerListener = object : Player.Listener {

        /** Auto-select the highest-bitrate video track when tracks become available. */
        override fun onTracksChanged(tracks: Tracks) {
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
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        activity?.window?.let { WindowCompat.setDecorFitsSystemWindows(it, true) }
        _binding = null
    }
}

