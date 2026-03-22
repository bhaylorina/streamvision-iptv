package com.streamvision.iptv.presentation.ui.player

import android.app.Activity
import android.content.pm.ActivityInfo
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
import androidx.media3.exoplayer.ExoPlayer
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
                            // Log all channel info for debugging
                            Log.d(TAG, "=== Channel Info ===")
                            Log.d(TAG, "Name: ${channel.name}")
                            Log.d(TAG, "URL: ${channel.url}")
                            Log.d(TAG, "DRM Key: ${channel.drmKey}")
                            Log.d(TAG, "DRM License URL: ${channel.drmLicenseUrl}")
                            Log.d(TAG, "UserAgent: ${channel.userAgent}")
                            Log.d(TAG, "Cookie: ${channel.cookie}")
                            Log.d(TAG, "Referer: ${channel.referer}")
                        }
                        
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
        Log.d(TAG, "=== Playing Channel ===")
        Log.d(TAG, "Name: ${channel.name}")
        Log.d(TAG, "URL: ${channel.url}")
        
        binding.errorOverlay.visibility = View.GONE
        binding.progressBuffering.visibility = View.VISIBLE
        
        val mediaItem = MediaItem.fromUri(channel.url)
        player?.apply {
            setMediaItem(mediaItem)
            prepare()
            playWhenReady = true
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
        _binding = null
    }
}
