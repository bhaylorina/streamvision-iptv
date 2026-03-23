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

    // ✅ Back press: Player → ChannelList → PlaylistList
    private fun setupBackNavigation() {
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) {
            when {
                viewModel.uiState.value.isShowingChannels -> {
                    // Returning from player — stay on channel list, just reset flag
                    viewModel.clearCurrentPlaylist()
                }
                binding.rvChannels.visibility == View.VISIBLE -> {
                    // On channel list → go back to playlist list
                    showPlaylistView()
                }
                else -> {
                    // On playlist list → exit normally
                    isEnabled = false
                    requireActivity().onBackPressedDispatcher.onBackPressed()
                }
            }
        }
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
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                viewModel.setSearchQuery(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun setupGroupChipAll() {
        binding.chipAll.setOnClickListener {
            viewModel.setSelectedGroup(null)
        }
    }

    private fun setupButtons() {
        binding.btnBackToPlaylists.setOnClickListener {
            showPlaylistView()
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

    private fun observeUiState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->

                    // Loading
                    binding.progressBar.visibility =
                        if (state.isLoading) View.VISIBLE else View.GONE
                    binding.swipeRefresh.isRefreshing = state.isLoading

                    if (state.playlistsLoaded) {
                        if (state.currentPlaylist != null) {
                            // ─── CHANNEL LIST VIEW ───────────────────────────
                            showChannelView()

                            binding.tvPlaylistName.text = state.currentPlaylist.name

                            channelAdapter.submitList(state.filteredChannels)

                            // Empty state for channels (no results)
                            binding.emptyState.visibility =
                                if (!state.isLoading && state.filteredChannels.isEmpty())
                                    View.VISIBLE else View.GONE

                            updateGroupChips(state.groups, state.selectedGroup)

                        } else {
                            // ─── PLAYLIST LIST VIEW ──────────────────────────
                            showPlaylistView()

                            // ✅ THIS was the missing piece:
                            // Show "No playlists" empty state in center when list is empty
                            binding.tvNoPlaylists.visibility =
                                if (state.hasNoPlaylists) View.VISIBLE else View.GONE

                            binding.rvPlaylists.visibility =
                                if (!state.hasNoPlaylists) View.VISIBLE else View.GONE
                        }
                    }

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

        binding.scrollGroups.visibility =
            if (groups.isNotEmpty()) View.VISIBLE else View.GONE
    }

    /** Switch to channel list UI */
    private fun showChannelView() {
        binding.rvPlaylists.visibility = View.GONE
        binding.tvNoPlaylists.visibility = View.GONE
        binding.rvChannels.visibility = View.VISIBLE
        binding.btnBackToPlaylists.visibility = View.VISIBLE
        binding.searchLayout.visibility = View.VISIBLE
        binding.scrollGroups.visibility = View.VISIBLE
    }

    /** Switch back to playlist list UI */
    private fun showPlaylistView() {
        binding.rvChannels.visibility = View.GONE
        binding.emptyState.visibility = View.GONE
        binding.searchLayout.visibility = View.GONE
        binding.scrollGroups.visibility = View.GONE
        binding.btnBackToPlaylists.visibility = View.GONE
        binding.tvPlaylistName.text = getString(R.string.playlists)
        viewModel.clearCurrentPlaylist()
        // tv_no_playlists and rv_playlists visibility
        // is handled in observeUiState based on hasNoPlaylists
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
