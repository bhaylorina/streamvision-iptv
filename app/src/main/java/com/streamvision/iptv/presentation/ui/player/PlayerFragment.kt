package com.streamvision.iptv.presentation.ui.player

import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
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

@UnstableApi
@AndroidEntryPoint
class PlayerFragment : Fragment() {

    private var _binding: FragmentPlayerBinding? = null
    private val binding get() = _binding!!

    private val viewModel: PlayerViewModel by viewModels()
    private val args: PlayerFragmentArgs by navArgs()

    private var player: ExoPlayer? = null

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
                        showError(error.message ?: getString(R.string.error_playback))
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
        // Check if DRM is needed (clearkey)
        val hasDrm = channel.drmKey != null && channel.drmKey!!.contains(":")
        
        return if (hasDrm) {
            try {
                // Parse ClearKey: format is key1:key2 (kid:key)
                val keys = channel.drmKey!!.split(":")
                if (keys.size >= 2) {
                    val kid = keys[0].trim()
                    val key = keys[1].trim()
                    
                    // Build JSON for ClearKey
                    val clearKeyJson = """{"keys":[{"kty":"oct","k":"$key","kid":"$kid"}],"type":"temporary"}"""
                    
                    // ClearKey UUID: e2719d58-e985-11e3-ac10-0800200c9a66
                    val clearKeyUuid = java.util.UUID.fromString("e2719d58-e985-11e3-ac10-0800200c9a66")
                    
                    val drmConfiguration = MediaItem.DrmConfiguration.Builder(clearKeyUuid)
                        .setLicenseRequestHeaders(
                            mapOf(
                                "Content-Type" to "application/json",
                                "ClearKey" to clearKeyJson
                            )
                        )
                        .build()
                    
                    MediaItem.Builder()
                        .setUri(channel.url)
                        .setDrmConfiguration(drmConfiguration)
                        .build()
                } else {
                    MediaItem.fromUri(channel.url)
                }
            } catch (e: Exception) {
                MediaItem.fromUri(channel.url)
            }
        } else {
            MediaItem.fromUri(channel.url)
        }
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
