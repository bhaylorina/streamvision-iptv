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
import kotlinx.coroutines.launch

@AndroidEntryPoint
class ChannelsFragment : Fragment() {

    private var _binding: FragmentChannelsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ChannelsViewModel by viewModels()

    // ✅ Correct class name: ChannelAdapter (from ChannelAdapter.kt)
    private lateinit var channelAdapter: ChannelAdapter

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

    // ✅ FIX: Back press correctly handles Player → ChannelList → PlaylistList
    private fun setupBackNavigation() {
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) {
            when {
                // Coming back from Player → stay on channel list, just clear the flag
                viewModel.uiState.value.isShowingChannels -> {
                    viewModel.clearCurrentPlaylist()
                }
                // On channel list → go back to playlist view
                binding.rvChannels.visibility == View.VISIBLE -> {
                    showPlaylistView()
                }
                // On playlist view → default back (exit or go up)
                else -> {
                    isEnabled = false
                    requireActivity().onBackPressedDispatcher.onBackPressed()
                }
            }
        }
    }

    private fun setupChannelRecyclerView() {
        // ✅ Correct constructor params: onChannelClick + onFavoriteClick
        channelAdapter = ChannelAdapter(
            onChannelClick = { channel ->
                viewModel.onChannelSelected(channel.id) // sets isShowingChannels = true
                navigateToPlayer(channel.id)
            },
            onFavoriteClick = { channel ->
                viewModel.toggleFavorite(channel.id)
            }
        )
        // ✅ Correct ID: rv_channels (from fragment_channels.xml)
        binding.rvChannels.apply {
            adapter = channelAdapter
            layoutManager = LinearLayoutManager(requireContext())
        }
    }

    private fun setupSearch() {
        // ✅ Correct ID: et_search inside search_layout TextInputLayout
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                viewModel.setSearchQuery(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun setupGroupChipAll() {
        // ✅ Correct ID: chip_all — the static "All" chip declared in XML
        binding.chipAll.setOnClickListener {
            viewModel.setSelectedGroup(null)
        }
    }

    private fun setupButtons() {
        // ✅ Correct ID: btn_back_to_playlists
        binding.btnBackToPlaylists.setOnClickListener {
            showPlaylistView()
        }

        // ✅ Correct ID: btn_add_playlist (top-right icon button)
        binding.btnAddPlaylist.setOnClickListener {
            showAddPlaylistDialog()
        }

        // ✅ Correct ID: btn_add_first_playlist (inside tv_no_playlists empty state)
        binding.btnAddFirstPlaylist.setOnClickListener {
            showAddPlaylistDialog()
        }
    }

    private fun setupSwipeRefresh() {
        // ✅ Correct ID: swipe_refresh
        binding.swipeRefresh.setOnRefreshListener {
            val playlistId = viewModel.uiState.value.currentPlaylist?.id
            if (playlistId != null) {
                viewModel.loadChannels(playlistId)
            } else {
                binding.swipeRefresh.isRefreshing = false
            }
        }
    }

    private fun observeUiState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->

                    // ✅ Correct ID: progress_bar
                    binding.progressBar.visibility =
                        if (state.isLoading) View.VISIBLE else View.GONE
                    binding.swipeRefresh.isRefreshing = state.isLoading

                    // Show channel view when a playlist is loaded
                    if (state.currentPlaylist != null) {
                        showChannelView()
                    }

                    // Submit channels to adapter
                    channelAdapter.submitList(state.filteredChannels)

                    // ✅ Correct ID: tv_playlist_name
                    binding.tvPlaylistName.text =
                        state.currentPlaylist?.name ?: getString(R.string.playlists)

                    // ✅ Correct ID: empty_state (channels empty state)
                    binding.emptyState.visibility =
                        if (!state.isLoading
                            && state.filteredChannels.isEmpty()
                            && state.currentPlaylist != null
                        ) View.VISIBLE else View.GONE

                    // Dynamic group chips
                    updateGroupChips(state.groups, state.selectedGroup)

                    // Error snackbar
                    state.error?.let { error ->
                        Snackbar.make(binding.root, error, Snackbar.LENGTH_LONG).show()
                        viewModel.clearError()
                    }
                }
            }
        }
    }

    private fun updateGroupChips(groups: List<String>, selectedGroup: String?) {
        // ✅ Correct IDs: chip_group (ChipGroup), chip_all (static chip at index 0)
        val chipGroup = binding.chipGroup

        // Remove dynamic chips — keep only chip_all (index 0)
        while (chipGroup.childCount > 1) {
            chipGroup.removeViewAt(1)
        }

        // Sync "All" chip checked state
        binding.chipAll.isChecked = selectedGroup == null

        // Add one chip per group
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

        // ✅ Correct IDs: scroll_groups, search_layout
        val showFilters = binding.rvChannels.visibility == View.VISIBLE
        binding.scrollGroups.visibility =
            if (showFilters && groups.isNotEmpty()) View.VISIBLE else View.GONE
        binding.searchLayout.visibility =
            if (showFilters) View.VISIBLE else View.GONE
    }

    /** Show the channel list UI */
    private fun showChannelView() {
        // ✅ Correct IDs from fragment_channels.xml
        binding.rvPlaylists.visibility = View.GONE
        binding.tvNoPlaylists.visibility = View.GONE
        binding.rvChannels.visibility = View.VISIBLE
        binding.btnBackToPlaylists.visibility = View.VISIBLE
        binding.searchLayout.visibility = View.VISIBLE
        binding.scrollGroups.visibility = View.VISIBLE
    }

    /** Show the playlist list UI (back from channel list) */
    private fun showPlaylistView() {
        // ✅ Correct IDs from fragment_channels.xml
        binding.rvChannels.visibility = View.GONE
        binding.emptyState.visibility = View.GONE
        binding.searchLayout.visibility = View.GONE
        binding.scrollGroups.visibility = View.GONE
        binding.btnBackToPlaylists.visibility = View.GONE
        binding.rvPlaylists.visibility = View.VISIBLE
        binding.tvPlaylistName.text = getString(R.string.playlists)
        viewModel.clearCurrentPlaylist()
    }

    // ✅ Correct action ID: action_channels_to_player (from nav_graph.xml)
    // ✅ Correct argument name: "channelId" as Long (from playerFragment argument in nav_graph)
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
