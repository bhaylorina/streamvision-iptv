package com.streamvision.iptv.presentation.ui.player

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
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
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.LocalMediaDrmCallback
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
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
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    state.currentChannel?.let { channel ->
                        if (currentChannel?.id != channel.id) {
                            currentChannel = channel
                            Log.d(TAG, "New channel: ${channel.name} | URL: ${channel.url}")
                            // Release old player and build fresh one for new channel
                            releasePlayer()
                            setupAndPlay(channel)
                        }
                        binding.tvChannelName.text = channel.name
                    }

                    if (state.error != null) {
                        showError(state.error)
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Player setup — one clean path handles both DRM and non-DRM streams
    // -------------------------------------------------------------------------

    private fun setupAndPlay(channel: Channel) {
        binding.errorOverlay.visibility = View.GONE
        binding.progressBuffering.visibility = View.VISIBLE

        // 1. HTTP data source with proper headers
        val httpDataSourceFactory = buildHttpDataSourceFactory(channel)

        // 2. ExoPlayer — with or without DRM session manager
        val playerBuilder = ExoPlayer.Builder(requireContext())

        if (!channel.drmKey.isNullOrBlank()) {
            val drmSessionManager = buildClearKeyDrmSessionManager(channel.drmKey)
            if (drmSessionManager != null) {
                val mediaSourceFactory = DefaultMediaSourceFactory(requireContext())
                    .setDataSourceFactory(httpDataSourceFactory)
                    .setDrmSessionManagerProvider { drmSessionManager }
                playerBuilder.setMediaSourceFactory(mediaSourceFactory)
            } else {
                // DRM parse failed — fall back to plain factory
                playerBuilder.setMediaSourceFactory(
                    DefaultMediaSourceFactory(requireContext())
                        .setDataSourceFactory(httpDataSourceFactory)
                )
            }
        } else {
            playerBuilder.setMediaSourceFactory(
                DefaultMediaSourceFactory(requireContext())
                    .setDataSourceFactory(httpDataSourceFactory)
            )
        }

        player = playerBuilder.build().also { exoPlayer ->
            binding.playerView.player = exoPlayer
            exoPlayer.addListener(playerListener)

            // 3. MediaItem — plain URI (DRM is handled by the session manager above)
            val mediaItem = MediaItem.Builder()
                .setUri(channel.url)
                .build()

            exoPlayer.setMediaItem(mediaItem)
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
        }
    }

    // -------------------------------------------------------------------------
    // HTTP headers
    // -------------------------------------------------------------------------

    private fun buildHttpDataSourceFactory(channel: Channel): DefaultHttpDataSource.Factory {
        val headers = mutableMapOf<String, String>()

        if (!channel.userAgent.isNullOrBlank()) {
            headers["User-Agent"] = channel.userAgent
            Log.d(TAG, "User-Agent: ${channel.userAgent}")
        }
        if (!channel.referer.isNullOrBlank()) {
            headers["Referer"] = channel.referer
            Log.d(TAG, "Referer: ${channel.referer}")
        }
        if (!channel.cookie.isNullOrBlank()) {
            headers["Cookie"] = channel.cookie
            Log.d(TAG, "Cookie: ${channel.cookie.take(40)}…")
        }

        return DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)
            // ✅ Correct API — no reflection needed
            .setDefaultRequestProperties(headers)
    }

    // -------------------------------------------------------------------------
    // ClearKey DRM session manager
    // -------------------------------------------------------------------------

    /**
     * Builds a [DefaultDrmSessionManager] that supplies inline ClearKey JSON to
     * ExoPlayer's DRM subsystem.
     *
     * Key points that were wrong before:
     *  - [MediaItem.DrmConfiguration.setKeySetId] is for offline-licence *restoration*,
     *    not for providing inline ClearKey material.
     *  - ClearKey JSON must use **Base64URL** encoding (URL_SAFE | NO_PADDING),
     *    NOT standard Base64.
     *  - The JSON must include `"type":"temporary"`.
     *  - The [LocalMediaDrmCallback] receives the raw JSON bytes and hands them
     *    straight to the [FrameworkMediaDrm] — this is the correct path.
     */
    private fun buildClearKeyDrmSessionManager(drmKey: String): DefaultDrmSessionManager? {
        return try {
            val parts = drmKey.trim().split(":")
            if (parts.size != 2) {
                Log.e(TAG, "Invalid DRM key format (expected keyId:key hex): $drmKey")
                return null
            }

            val keyIdHex = parts[0]
            val keyHex = parts[1]

            // ClearKey requires Base64URL (URL_SAFE flag) with NO padding
            val keyIdBase64Url = hexToBase64Url(keyIdHex)
            val keyBase64Url = hexToBase64Url(keyHex)

            // Standard EME ClearKey JSON format
            val clearKeyJson = """
                {
                  "keys": [
                    {
                      "kty": "oct",
                      "k":   "$keyBase64Url",
                      "kid": "$keyIdBase64Url"
                    }
                  ],
                  "type": "temporary"
                }
            """.trimIndent()

            Log.d(TAG, "ClearKey JSON: $clearKeyJson")

            val drmCallback = LocalMediaDrmCallback(clearKeyJson.toByteArray(Charsets.UTF_8))

            DefaultDrmSessionManager.Builder()
                .setUuidAndExoMediaDrmProvider(C.CLEARKEY_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
                .setMultiSession(false)
                .build(drmCallback)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to build ClearKey DRM session manager: ${e.message}", e)
            null
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Decode a hex string to bytes, then encode as Base64URL (no padding). */
    private fun hexToBase64Url(hex: String): String {
        val bytes = hexToBytes(hex)
        // URL_SAFE uses '-' and '_' instead of '+' and '/'; NO_PADDING strips '='
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "Hex string must have even length" }
        return ByteArray(hex.length / 2) { i ->
            ((Character.digit(hex[i * 2], 16) shl 4) or
                    Character.digit(hex[i * 2 + 1], 16)).toByte()
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            Log.d(TAG, "Playback state: $state")
            when (state) {
                Player.STATE_BUFFERING -> {
                    binding.progressBuffering.visibility = View.VISIBLE
                    viewModel.updatePlaybackState(isPlaying = false, isBuffering = true)
                }
                Player.STATE_READY -> {
                    binding.progressBuffering.visibility = View.GONE
                    viewModel.updatePlaybackState(
                        isPlaying = player?.isPlaying ?: false,
                        isBuffering = false
                    )
                }
                Player.STATE_ENDED,
                Player.STATE_IDLE -> {
                    binding.progressBuffering.visibility = View.GONE
                }
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

    private fun showError(message: String) {
        binding.progressBuffering.visibility = View.GONE
        binding.errorOverlay.visibility = View.VISIBLE
        binding.tvError.text = message
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    private fun releasePlayer() {
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
        releasePlayer()
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        activity?.window?.let { WindowCompat.setDecorFitsSystemWindows(it, true) }
        _binding = null
    }
}
