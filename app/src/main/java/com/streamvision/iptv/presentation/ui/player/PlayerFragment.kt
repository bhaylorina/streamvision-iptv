package com.streamvision.iptv.presentation.ui.player

import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsetsController
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.DrmSessionManager
import androidx.media3.exoplayer.drm.HttpMediaDrmCallback
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.streamvision.iptv.R
import com.streamvision.iptv.databinding.FragmentPlayerBinding
import com.streamvision.iptv.domain.model.Channel
import com.streamvision.iptv.presentation.viewmodel.PlayerViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.util.UUID

@UnstableApi
@AndroidEntryPoint
class PlayerFragment : Fragment() {

    private var _binding: FragmentPlayerBinding? = null
    private val binding get() = _binding!!

    private val viewModel: PlayerViewModel by viewModels()
    private val args: PlayerFragmentArgs by navArgs()

    private var player: ExoPlayer? = null

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
        setupPlayer()
        setupClickListeners()
        observeState()
        
        // Load channel
        viewModel.loadChannel(args.channelId)
    }

    private fun hideSystemUI() {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        
        val window = activity?.window ?: return
        WindowCompat.setDecorFitsSystemWindows(window, false)
        
        val controller = WindowInsetsControllerCompat(window, requireView())
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun setupPlayer() {
        player = ExoPlayer.Builder(requireContext())
            .build()
            .also { exoPlayer ->
                binding.playerView.player = exoPlayer
                
                exoPlayer.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        Log.d(TAG, "Playback state: $state")
                        when (state) {
                            Player.STATE_BUFFERING -> {
                                binding.progressBuffering.visibility = View.VISIBLE
                                viewModel.updatePlaybackState(isPlaying = false, isBuffering = true)
                            }
                            Player.STATE_READY -> {
                                binding.progressBuffering.visibility = View.GONE
                                viewModel.updatePlaybackState(isPlaying = exoPlayer.isPlaying, isBuffering = false)
                            }
                            Player.STATE_ENDED -> {
                                binding.progressBuffering.visibility = View.GONE
                            }
                            Player.STATE_IDLE -> {
                                binding.progressBuffering.visibility = View.GONE
                            }
                        }
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        viewModel.updatePlaybackState(isPlaying = isPlaying)
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        Log.e(TAG, "Player error: ${error.message}", error)
                        showError("${error.message}\n\nError Code: ${error.errorCode}")
                    }
                })
            }
    }

    private fun setupClickListeners() {
        binding.btnRetry.setOnClickListener {
            viewModel.uiState.value.currentChannel?.let { channel ->
                playChannel(channel)
            }
        }
        
        // Handle back press
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
                        // Only play if not already playing this URL
                        if (player?.currentMediaItem?.localConfiguration?.uri?.toString() != channel.url) {
                            playChannel(channel)
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

    private fun playChannel(channel: Channel) {
        Log.d(TAG, "Playing channel: ${channel.name}")
        Log.d(TAG, "URL: ${channel.url}")
        Log.d(TAG, "DRM Key: ${channel.drmKey}")
        Log.d(TAG, "DRM License URL: ${channel.drmLicenseUrl}")
        
        binding.errorOverlay.visibility = View.GONE
        binding.progressBuffering.visibility = View.VISIBLE
        
        val mediaItem = buildMediaItem(channel)
        player?.apply {
            setMediaItem(mediaItem)
            prepare()
            playWhenReady = true
        }
    }

    private fun buildMediaItem(channel: Channel): MediaItem {
        // Check if DRM is needed
        val hasDrmKey = !channel.drmKey.isNullOrBlank()
        val hasLicenseUrl = !channel.drmLicenseUrl.isNullOrBlank()
        
        Log.d(TAG, "Building media item - hasDrmKey: $hasDrmKey, hasLicenseUrl: $hasLicenseUrl")
        
        // Build HTTP data source factory with headers if needed
        // For now, we'll use default - but headers would be added here
        
        // If we have a DRM key, try DRM
        if (hasDrmKey) {
            try {
                val drmKey = channel.drmKey!!
                val parts = drmKey.split(":")
                
                if (parts.size >= 2) {
                    val kidHex = parts[0].trim()
                    val keyHex = parts[1].trim()
                    
                    Log.d(TAG, "KID (hex): $kidHex")
                    Log.d(TAG, "KEY (hex): $keyHex")
                    
                    // Convert hex to bytes then base64 for ClearKey
                    val kidBytes = hexStringToByteArray(kidHex)
                    val keyBytes = hexStringToByteArray(keyHex)
                    val kidBase64 = android.util.Base64.encodeToString(kidBytes, android.util.Base64.NO_WRAP)
                    val keyBase64 = android.util.Base64.encodeToString(keyBytes, android.util.Base64.NO_WRAP)
                    
                    Log.d(TAG, "KID (base64): $kidBase64")
                    Log.d(TAG, "KEY (base64): $keyBase64")
                    
                    // Build ClearKey JSON - proper format
                    val clearKeyJson = """{"keys":[{"kty":"oct","k":"$keyBase64","kid":"$kidBase64"}],"type":"temporary"}"""
                    
                    Log.d(TAG, "ClearKey JSON: $clearKeyJson")
                    
                    // ClearKey UUID
                    val clearKeyUuid = UUID.fromString("e2719d58-e985-11e3-ac10-0800200c9a66")
                    
                    // Build DRM configuration - use offline keys for ClearKey
                    val drmBuilder = MediaItem.DrmConfiguration.Builder(clearKeyUuid)
                    
                    // For Jio/ClearKey streams, we need offline key
                    // The key format in playlist is already the actual key
                    // Use setLicenseUri for key server if available
                    if (hasLicenseUrl) {
                        Log.d(TAG, "Using license URL: ${channel.drmLicenseUrl}")
                        drmBuilder.setLicenseUri(channel.drmLicenseUrl)
                    }
                    
                    val drmConfiguration = drmBuilder.build()
                    
                    val mediaItemBuilder = MediaItem.Builder()
                        .setUri(channel.url)
                        .setDrmConfiguration(drmConfiguration)
                    
                    Log.d(TAG, "MediaItem built with ClearKey DRM")
                    
                    return mediaItemBuilder.build()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error building DRM media item", e)
            }
        }
        
        // Try without DRM (for non-DRM streams or as fallback)
        Log.d(TAG, "Using regular media item without DRM")
        return MediaItem.fromUri(channel.url)
    }
    
    private fun hexStringToByteArray(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    private fun showError(message: String) {
        binding.progressBuffering.visibility = View.GONE
        binding.errorOverlay.visibility = View.VISIBLE
        binding.tvError.text = message
    }

    private fun releasePlayer() {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        
        val window = activity?.window ?: return
        WindowCompat.setDecorFitsSystemWindows(window, true)
        
        player?.let { exoPlayer ->
            exoPlayer.release()
        }
        player = null
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        releasePlayer()
        _binding = null
    }
}
