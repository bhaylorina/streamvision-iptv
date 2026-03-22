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
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
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
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
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
                            Log.d(TAG, "=== Channel Info ===")
                            Log.d(TAG, "Name: ${channel.name}")
                            Log.d(TAG, "URL: ${channel.url}")
                            Log.d(TAG, "Cookie: ${channel.cookie}")
                            Log.d(TAG, "UserAgent: ${channel.userAgent}")
                            Log.d(TAG, "Referer: ${channel.referer}")
                        }
                        
                        // Initialize player with headers
                        if (player == null) {
                            setupPlayer(channel)
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
    
    private fun setupPlayer(channel: Channel) {
        // Build custom data source factory with HTTP headers
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(15000)
        
        // Add custom headers
        val headers = mutableMapOf<String, String>()
        if (!channel.cookie.isNullOrBlank()) {
            headers["Cookie"] = channel.cookie
            Log.d(TAG, "Setting Cookie header: ${channel.cookie.take(30)}...")
        }
        if (!channel.userAgent.isNullOrBlank()) {
            headers["User-Agent"] = channel.userAgent
            Log.d(TAG, "Setting User-Agent: ${channel.userAgent}")
        }
        if (!channel.referer.isNullOrBlank()) {
            headers["Referer"] = channel.referer
            Log.d(TAG, "Setting Referer: ${channel.referer}")
        }
        
        // Use reflection to set headers (workaround for API)
        try {
            val method = httpDataSourceFactory.javaClass.getMethod("setHttpRequestHeaders", Map::class.java)
            method.invoke(httpDataSourceFactory, headers)
            Log.d(TAG, "Applied HTTP headers via reflection: ${headers.keys}")
        } catch (e: Exception) {
            Log.w(TAG, "Could not set headers via reflection: ${e.message}")
        }
        
        // Create media source factory with custom data source
        val mediaSourceFactory = DefaultMediaSourceFactory(requireContext())
            .setDataSourceFactory(httpDataSourceFactory)
        
        player = ExoPlayer.Builder(requireContext())
            .setMediaSourceFactory(mediaSourceFactory)
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

    private fun playChannel(channel: Channel) {
        Log.d(TAG, "=== Playing Channel ===")
        Log.d(TAG, "Name: ${channel.name}")
        Log.d(TAG, "URL: ${channel.url}")
        Log.d(TAG, "Cookie: ${channel.cookie}")
        Log.d(TAG, "DRM Key: ${channel.drmKey}")
        
        binding.errorOverlay.visibility = View.GONE
        binding.progressBuffering.visibility = View.VISIBLE
        
        // Build media item with DRM if key exists
        val mediaItem = buildMediaItem(channel)
        
        player?.apply {
            setMediaItem(mediaItem)
            prepare()
            playWhenReady = true
        }
    }
    
    private fun buildMediaItem(channel: Channel): MediaItem {
        var url = channel.url
        
        // Add cookie as URL parameter (works for most servers)
        if (!channel.cookie.isNullOrBlank()) {
            val separator = if (url.contains("?")) "&" else "?"
            url = "$url${separator}Cookie=${channel.cookie}"
            Log.d(TAG, "Added Cookie to URL: ${channel.cookie.take(30)}...")
        }
        
        Log.d(TAG, "Final URL: ${url.take(80)}...")
        return MediaItem.fromUri(url)
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
