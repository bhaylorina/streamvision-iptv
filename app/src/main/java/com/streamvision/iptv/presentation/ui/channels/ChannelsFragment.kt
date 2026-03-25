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
    
    private var isNavigatingToFullscreen = false

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            // Stop playback when pressing Back to return to Playlist list
            viewModel.playerManager.stop()
            viewModel.clearCurrentPlaylist()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
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
        isNavigatingToFullscreen = false 
        binding.inlinePlayerView.player = viewModel.playerManager.player
        viewModel.refreshPlaylists()
    }

    override fun onPause() {
        super.onPause()
        // Stop playback if we are NOT going to Fullscreen (e.g., navigating to Favorites/Settings)
        if (!isNavigatingToFullscreen) {
            viewModel.playerManager.stop()
        }
        binding.inlinePlayerView.player = null
    }

    private fun setupPlaylistRecyclerView() {
        playlistAdapter = PlaylistAdapter(
            onPlaylistClick = { playlist -> viewModel.selectPlaylist(playlist.id) },
            onDeleteClick = { _ ->
                Snackbar.make(binding.root, "Delete playlists from Settings", Snackbar.LENGTH_SHORT).show()
            }
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
                viewModel.playerManager.play(channel)
            },
            onFavoriteClick = { channel -> viewModel.toggleFavorite(channel.id) }
        )
        binding.rvChannels.apply {
            adapter = channelAdapter
            layoutManager = LinearLayoutManager(requireContext())
        }
    }

    private fun setupSearch() {
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchJob?.cancel()
                searchJob = viewLifecycleOwner.lifecycleScope.launch {
                    delay(300)
                    viewModel.setSearchQuery(s?.toString() ?: "")
                }
            }
        })
    }

    private fun setupGroupChipAll() {
        binding.chipAll.setOnClickListener { viewModel.setSelectedGroup(null) }
    }

    private fun setupAddPlaylistButton() {
        binding.btnAddPlaylist.setOnClickListener { showAddPlaylistDialog() }
        binding.btnAddFirstPlaylist.setOnClickListener { showAddPlaylistDialog() }
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setOnRefreshListener {
            if (viewModel.uiState.value.currentPlaylist != null) {
                viewModel.refreshCurrentPlaylist()
            } else {
                binding.swipeRefresh.isRefreshing = false
            }
        }
    }

    private fun observeUiState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state -> renderState(state) }
            }
        }
    }

    private fun renderState(state: ChannelsUiState) {
        binding.progressBar.visibility = if (state.isLoading) View.VISIBLE else View.GONE
        binding.swipeRefresh.isRefreshing = state.isLoading

        val isShowingChannels = state.currentPlaylist != null
        backCallback.isEnabled = isShowingChannels

        binding.headerPlaylist.visibility = if (!isShowingChannels) View.VISIBLE else View.GONE
        binding.inlinePlayerContainer.visibility = if (isShowingChannels) View.VISIBLE else View.GONE
        binding.searchLayout.visibility = if (isShowingChannels) View.VISIBLE else View.GONE
        binding.rvChannels.visibility = if (isShowingChannels) View.VISIBLE else View.GONE
        
        binding.chipGroup.visibility = if (isShowingChannels && state.groups.isNotEmpty()) View.VISIBLE else View.GONE
        binding.emptyState.visibility = if (isShowingChannels && !state.isLoading && state.filteredChannels.isEmpty()) View.VISIBLE else View.GONE
        
        binding.rvPlaylists.visibility = if (!isShowingChannels) View.VISIBLE else View.GONE
        binding.tvNoPlaylists.visibility = if (!isShowingChannels && state.hasNoPlaylists) View.VISIBLE else View.GONE

        if (!isShowingChannels) {
            playlistAdapter.submitList(state.playlists)
        } else {
            channelAdapter.submitList(state.filteredChannels)
            updateGroupChips(state.groups, state.selectedGroup)
        }

        state.error?.let { error ->
            Snackbar.make(binding.root, error, Snackbar.LENGTH_LONG).show()
            viewModel.clearError()
        }
    }

    private fun updateGroupChips(groups: List<String>, selectedGroup: String?) {
        val chipGroup = binding.chipGroup
        while (chipGroup.childCount > 1) chipGroup.removeViewAt(1)
        binding.chipAll.isChecked = selectedGroup == null
        groups.forEach { group ->
            val chip = Chip(requireContext()).apply {
                text = group
                isCheckable = true
                isChecked = group == selectedGroup
                setChipBackgroundColorResource(R.color.surface)
                setOnClickListener { viewModel.setSelectedGroup(group) }
            }
            chipGroup.addView(chip)
        }
    }

    private fun showAddPlaylistDialog() {
        AddPlaylistDialog { name, url ->
            viewModel.addPlaylist(name, url)
        }.show(parentFragmentManager, "AddPlaylistDialog")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        searchJob?.cancel()
    }
}
