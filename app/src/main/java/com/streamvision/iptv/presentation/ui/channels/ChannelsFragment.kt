package com.streamvision.iptv.presentation.ui.channels

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.chip.Chip
import com.google.android.material.snackbar.Snackbar
import com.streamvision.iptv.R
import com.streamvision.iptv.databinding.FragmentChannelsBinding
import com.streamvision.iptv.domain.model.Channel
import com.streamvision.iptv.presentation.adapter.ChannelAdapter
import com.streamvision.iptv.presentation.adapter.PlaylistAdapter
import com.streamvision.iptv.presentation.viewmodel.ChannelsUiState
import com.streamvision.iptv.presentation.viewmodel.ChannelsViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@AndroidEntryPoint
class ChannelsFragment : Fragment() {

    private var _binding: FragmentChannelsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ChannelsViewModel by activityViewModels()

    private lateinit var channelAdapter: ChannelAdapter
    private lateinit var playlistAdapter: PlaylistAdapter
    private var searchJob: Job? = null
    
    // Safety flag so we don't kill playback if user pressed "Fullscreen"
    private var isNavigatingToFullscreen = false

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            // STOP playback when exiting back to Playlists Screen
            viewModel.playerManager.stop()
            viewModel.clearCurrentPlaylist()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentChannelsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)

        setupPlaylistRecyclerView()
        setupChannelRecyclerView()
        setupSearch()
        setupGroupChipAll()
        setupAddPlaylistButton()
        setupSwipeRefresh()
        setupInlinePlayer()
        observeUiState()
    }

    private fun setupInlinePlayer() {
        // Setup Fullscreen Transition Button
        binding.inlinePlayerView.findViewById<ImageButton>(R.id.btn_fullscreen)?.apply {
            setImageResource(R.drawable.ic_fullscreen)
            setOnClickListener {
                if (viewModel.playerManager.currentChannel != null) {
                    isNavigatingToFullscreen = true
                    val bundle = Bundle().apply { putLong("channelId", viewModel.playerManager.currentChannel!!.id) }
                    findNavController().navigate(R.id.action_channels_to_player, bundle)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        isNavigatingToFullscreen = false // Reset state
        binding.inlinePlayerView.player = viewModel.playerManager.player
        viewModel.refreshPlaylists()
    }

    override fun onPause() {
        super.onPause()
        // If user changed tabs to Favorites/Settings, STOP playing!
        if (!isNavigatingToFullscreen) {
            viewModel.playerManager.stop()
        }
        binding.inlinePlayerView.player = null
    }

    private fun setupPlaylistRecyclerView() {
        playlistAdapter = PlaylistAdapter(
            onPlaylistClick = { playlist -> viewModel.selectPlaylist(playlist.id) },
            onDeleteClick = { _ -> Snackbar.make(binding.root, "Delete playlists from Settings", Snackbar.LENGTH_SHORT).show() }
        )
        binding.rvPlaylists.apply {
            adapter = playlistAdapter
            layoutManager = LinearLayoutManager(requireContext())
        }
    }

    private fun setupChannelRecyclerView() {
        channelAdapter = ChannelAdapter(
            onChannelClick = { channel -> 
                viewModel.onChannelSelected(channel.id)
                viewModel.playerManager.play(channel) // Play in TOP inline player
            },
            onFavoriteClick = { channel -> viewModel.toggleFavorite(channel.id) }
        )
        binding.rvChannels.apply {
            adapter = channelAdapter
            layoutManager = LinearLayoutManager(requireContext())
        }
    }
    
    // ...[Keep your setupSearch(), setupGroupChipAll(), setupAddPlaylistButton(), etc.] ...

    private fun renderState(state: ChannelsUiState) {
        val isShowingChannels = state.currentPlaylist != null
        backCallback.isEnabled = isShowingChannels

        binding.headerPlaylist.visibility = if (!isShowingChannels) View.VISIBLE else View.GONE
        binding.inlinePlayerContainer.visibility = if (isShowingChannels) View.VISIBLE else View.GONE
        binding.searchLayout.visibility = if (isShowingChannels) View.VISIBLE else View.GONE
        binding.rvChannels.visibility = if (isShowingChannels) View.VISIBLE else View.GONE
        binding.rvPlaylists.visibility = if (!isShowingChannels) View.VISIBLE else View.GONE
        
        // ... [Rest of your UI bindings] ...
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        searchJob?.cancel()
    }
}
