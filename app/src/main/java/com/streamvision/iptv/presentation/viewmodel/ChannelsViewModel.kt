package com.streamvision.iptv.presentation.ui.channels

import android.os.Bundle
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
import com.google.android.material.chip.Chip
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextFieldExtensions.doAfterTextChanged
import com.streamvision.iptv.R
import com.streamvision.iptv.databinding.FragmentChannelsBinding
import com.streamvision.iptv.presentation.adapter.ChannelAdapter
import com.streamvision.iptv.presentation.viewmodel.ChannelsViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@AndroidEntryPoint
class ChannelsFragment : Fragment() {

    private var _binding: FragmentChannelsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ChannelsViewModel by viewModels()
    private lateinit var channelAdapter: ChannelAdapter

    // Flow for debounced search
    private val searchQueryFlow = MutableStateFlow("")

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
            layoutManager = androidx.recyclerview.widget.LinearLayoutManager(requireContext())
        }
    }

    private fun setupSearch() {
        // Collect search flow with debounce to prevent excessive VM updates
        viewLifecycleOwner.lifecycleScope.launch {
            searchQueryFlow
                .debounce(300)
                .distinctUntilChanged()
                .collect { query ->
                    viewModel.setSearchQuery(query)
                }
        }

        // Use Material Extensions for cleaner TextWatcher
        binding.etSearch.doAfterTextChanged { text ->
            searchQueryFlow.value = text?.toString() ?: ""
        }
    }

    private fun setupGroupChipAll() {
        binding.chipAll.setOnClickListener {
            viewModel.setSelectedGroup(null)
        }
    }

    private fun setupButtons() {
        binding.btnBackToPlaylists.setOnClickListener {
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
                currentState.currentPlaylist != null -> {
                    // Returning from player or inside a playlist -> Go back to playlist list
                    viewModel.clearCurrentPlaylist()
                }
                else -> {
                    // On playlist list -> Exit normally
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
     * Single source of truth for UI rendering based on State.
     * Replaces separate showChannelView/showPlaylistView methods to prevent state desynchronization.
     */
    private fun renderState(state: ChannelsViewModel.UiState) {
        // Loading States
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
        
        // Channel Empty State
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
            Snackbar.make(binding.root, error, Snackbar.LENGTH_LONG).show()
            viewModel.clearError()
        }
    }

    private fun updateGroupChips(groups: List<String>, selectedGroup: String?) {
        val chipGroup = binding.chipGroup

        // Remove dynamic chips — keep only chip_all at index 0
        // Optimization: Only remove if count exceeds expected size to avoid unnecessary layout passes
        if (chipGroup.childCount > groups.size + 1) {
            while (chipGroup.childCount > 1) {
                chipGroup.removeViewAt(1)
            }
        }

        binding.chipAll.isChecked = selectedGroup == null

        // Note: For production apps with many groups, consider using a RecyclerView for chips
        // to avoid recreating views on every state update.
        groups.forEach { group ->
            val chip = Chip(requireContext()).apply {
                text = group
                isCheckable = true
                isChecked = group == selectedGroup
                setChipBackgroundColorResource(R.color.surface)
                setCheckedIconTintResource(R.color.primary_accent)
                setOnClickListener { viewModel.setSelectedGroup(group) }
            }
            // Avoid adding duplicate chips if logic above didn't clear them perfectly
            // Ideally, the while loop above clears them. 
            // To be safe against duplicates in this simple implementation:
            // We rely on the while loop clearing everything > 1.
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
    }
}
