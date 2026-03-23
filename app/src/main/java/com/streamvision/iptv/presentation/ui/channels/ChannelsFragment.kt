package com.streamvision.iptv.presentation.ui.channels

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.addCallback
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.chip.Chip
import com.google.android.material.snackbar.Snackbar
import com.streamvision.iptv.R
import com.streamvision.iptv.databinding.FragmentChannelsBinding
import com.streamvision.iptv.presentation.adapter.ChannelAdapter
import com.streamvision.iptv.presentation.viewmodel.ChannelsViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@AndroidEntryPoint
class ChannelsFragment : Fragment() {

    private var _binding: FragmentChannelsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ChannelsViewModel by viewModels()
    private lateinit var channelAdapter: ChannelAdapter
    private var searchJob: Job? = null

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

        setupChannelRecyclerView()
        setupSearch()
        setupGroupChipAll()
        setupButtons()
        setupBackNavigation()
        setupSwipeRefresh()
        observeUiState()
    }

    private fun setupChannelRecyclerView() {
        channelAdapter = ChannelAdapter(
            onChannelClick = { channel ->
                viewModel.onChannelSelected(channel.id)
                navigateToPlayer(channel.id)
            },
            onFavoriteClick = { channel ->
                viewModel.toggleFavorite(channel.id)
            }
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
                // Debounce search to avoid filtering on every keystroke
                searchJob?.cancel()
                searchJob = viewLifecycleOwner.lifecycleScope.launch {
                    delay(300)
                    viewModel.setSearchQuery(s?.toString() ?: "")
                }
            }
        })
    }

    private fun setupGroupChipAll() {
        binding.chipAll.setOnClickListener {
            viewModel.setSelectedGroup(null)
        }
    }

    private fun setupButtons() {
        binding.btnBackToPlaylists.setOnClickListener {
            // Trigger state change via ViewModel
            viewModel.clearCurrentPlaylist()
        }
        binding.btnAddPlaylist.setOnClickListener {
            showAddPlaylistDialog()
        }
        binding.btnAddFirstPlaylist.setOnClickListener {
            showAddPlaylistDialog()
        }
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setOnRefreshListener {
            val playlistId = viewModel.uiState.value.currentPlaylist?.id
            if (playlistId != null) {
                viewModel.loadChannels(playlistId)
            } else {
                binding.swipeRefresh.isRefreshing = false
            }
        }
    }

    private fun setupBackNavigation() {
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) {
            val currentState = viewModel.uiState.value
            when {
                // If inside a playlist, go back to playlist list
                currentState.currentPlaylist != null -> {
                    viewModel.clearCurrentPlaylist()
                }
                // Otherwise, let system handle back press (exit app or previous fragment)
                else -> {
                    isEnabled = false
                    requireActivity().onBackPressedDispatcher.onBackPressed()
                }
            }
        }
    }

    private fun observeUiState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    renderState(state)
                }
            }
        }
    }

    /**
     * Single source of truth for UI rendering.
     * No ViewModel actions should be called here.
     */
    private fun renderState(state: ChannelsViewModel.UiState) {
        // Loading
        binding.progressBar.visibility = if (state.isLoading) View.VISIBLE else View.GONE
        binding.swipeRefresh.isRefreshing = state.isLoading

        // Determine View Mode
        val isShowingChannels = state.currentPlaylist != null

        // --- Common Elements ---
        binding.tvPlaylistName.text = if (isShowingChannels) {
            state.currentPlaylist.name
        } else {
            getString(R.string.playlists)
        }

        // --- Channel List View Visibility ---
        binding.rvChannels.visibility = if (isShowingChannels) View.VISIBLE else View.GONE
        binding.searchLayout.visibility = if (isShowingChannels) View.VISIBLE else View.GONE
        binding.btnBackToPlaylists.visibility = if (isShowingChannels) View.VISIBLE else View.GONE
        binding.scrollGroups.visibility = if (isShowingChannels && state.groups.isNotEmpty()) View.VISIBLE else View.GONE
        
        binding.emptyState.visibility = if (isShowingChannels && !state.isLoading && state.filteredChannels.isEmpty()) {
            View.VISIBLE
        } else {
            View.GONE
        }

        // --- Playlist List View Visibility ---
        binding.rvPlaylists.visibility = if (!isShowingChannels) View.VISIBLE else View.GONE
        binding.tvNoPlaylists.visibility = if (!isShowingChannels && state.hasNoPlaylists) View.VISIBLE else View.GONE

        // --- Data Updates ---
        if (isShowingChannels) {
            channelAdapter.submitList(state.filteredChannels)
            updateGroupChips(state.groups, state.selectedGroup)
        }

        // --- Error Handling ---
        state.error?.let { error ->
            // .toString() ensures no overload ambiguity
            Snackbar.make(binding.root, error.toString(), Snackbar.LENGTH_LONG).show()
            viewModel.clearError()
        }
    }

    private fun updateGroupChips(groups: List<String>, selectedGroup: String?) {
        val chipGroup = binding.chipGroup

        // Remove dynamic chips — keep only chip_all at index 0
        while (chipGroup.childCount > 1) {
            chipGroup.removeViewAt(1)
        }

        binding.chipAll.isChecked = selectedGroup == null

        groups.forEach { group ->
            val chip = Chip(requireContext()).apply {
                text = group
                isCheckable = true
                isChecked = group == selectedGroup
                setChipBackgroundColorResource(R.color.surface)
                setCheckedIconTintResource(R.color.primary_accent)
                setOnClickListener { viewModel.setSelectedGroup(group) }
            }
            chipGroup.addView(chip)
        }
    }

    private fun navigateToPlayer(channelId: Long) {
        val bundle = Bundle().apply { putLong("channelId", channelId) }
        findNavController().navigate(R.id.action_channels_to_player, bundle)
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
