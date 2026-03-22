package com.streamvision.iptv.presentation.ui.channels

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.streamvision.iptv.R
import com.streamvision.iptv.databinding.FragmentChannelsBinding
import com.streamvision.iptv.domain.model.Channel
import com.streamvision.iptv.domain.model.Playlist
import com.streamvision.iptv.presentation.adapter.ChannelAdapter
import com.streamvision.iptv.presentation.adapter.PlaylistAdapter
import com.streamvision.iptv.presentation.viewmodel.ChannelsViewModel
import com.streamvision.iptv.presentation.viewmodel.SettingsViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class ChannelsFragment : Fragment() {

    private var _binding: FragmentChannelsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ChannelsViewModel by activityViewModels()
    private val settingsViewModel: SettingsViewModel by viewModels()
    private lateinit var channelAdapter: ChannelAdapter
    private lateinit var playlistAdapter: PlaylistAdapter

    private var showingPlaylists = true

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
        setupRecyclerViews()
        setupSearch()
        setupChips()
        setupClickListeners()
        observeState()
        showPlaylists()
    }

    private fun setupRecyclerViews() {
        channelAdapter = ChannelAdapter(
            onChannelClick = { channel -> navigateToPlayer(channel) },
            onFavoriteClick = { channel -> viewModel.toggleFavorite(channel.id) }
        )
        binding.rvChannels.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = channelAdapter
        }

        playlistAdapter = PlaylistAdapter(
            onPlaylistClick = { playlist -> onPlaylistSelected(playlist) },
            onDeleteClick = { playlist ->
                showDeleteConfirmation(playlist.id, playlist.name)
            }
        )
        binding.rvPlaylists.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = playlistAdapter
        }
    }

    private fun setupSearch() {
        binding.etSearch.doAfterTextChanged { text ->
            viewModel.setSearchQuery(text?.toString() ?: "")
        }
    }

    private fun setupChips() {
        binding.chipAll.setOnClickListener {
            viewModel.setSelectedGroup(null)
        }
    }

    private fun setupClickListeners() {
        binding.btnAddPlaylist.setOnClickListener {
            showAddPlaylistDialog()
        }
        binding.btnAddFirstPlaylist.setOnClickListener {
            showAddPlaylistDialog()
        }
        binding.btnBackToPlaylists.setOnClickListener {
            showPlaylists()
        }
        binding.swipeRefresh.setOnRefreshListener {
            if (showingPlaylists) {
                settingsViewModel.loadPlaylists()
            } else {
                viewModel.uiState.value.currentPlaylist?.let { playlist ->
                    viewModel.loadChannels(playlist.id)
                }
            }
            binding.swipeRefresh.isRefreshing = false
        }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    binding.progressBar.visibility = if (state.isLoading) View.VISIBLE else View.GONE
                    
                    channelAdapter.submitList(state.filteredChannels)

                    // Update playlist name
                    binding.tvPlaylistName.text = state.currentPlaylist?.name ?: getString(R.string.playlists)

                    // Update empty state
                    val showEmpty = state.filteredChannels.isEmpty() && !state.isLoading && !showingPlaylists
                    binding.emptyState.visibility = if (showEmpty) View.VISIBLE else View.GONE
                    binding.rvChannels.visibility = if (showEmpty || showingPlaylists) View.GONE else View.VISIBLE

                    // Update group chips
                    updateGroupChips(state.groups, state.selectedGroup)

                    // Show error if any
                    state.error?.let { error ->
                        Snackbar.make(binding.root, error, Snackbar.LENGTH_LONG).show()
                        viewModel.clearError()
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                settingsViewModel.uiState.collect { state ->
                    playlistAdapter.submitList(state.playlists)
                    binding.tvNoPlaylists.visibility = if (state.playlists.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
    }

    private fun showPlaylists() {
        showingPlaylists = true
        binding.rvPlaylists.visibility = View.VISIBLE
        binding.rvChannels.visibility = View.GONE
        binding.chipGroup.visibility = View.GONE
        binding.etSearch.visibility = View.GONE
        binding.btnBackToPlaylists.visibility = View.GONE
        binding.tvPlaylistName.text = getString(R.string.playlists)
    }

    private fun onPlaylistSelected(playlist: Playlist) {
        showingPlaylists = false
        binding.rvPlaylists.visibility = View.GONE
        binding.rvChannels.visibility = View.VISIBLE
        binding.chipGroup.visibility = View.VISIBLE
        binding.etSearch.visibility = View.VISIBLE
        binding.btnBackToPlaylists.visibility = View.VISIBLE
        viewModel.loadChannels(playlist.id)
    }

    private fun showDeleteConfirmation(playlistId: Long, playlistName: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.remove)
            .setMessage("Remove playlist \"$playlistName\"?")
            .setPositiveButton(R.string.remove) { _, _ ->
                settingsViewModel.deletePlaylist(playlistId)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun updateGroupChips(groups: List<String>, selectedGroup: String?) {
        val chipGroup = binding.chipGroup
        val childCount = chipGroup.childCount
        if (childCount > 1) {
            for (i in childCount - 1 downTo 1) {
                chipGroup.removeViewAt(i)
            }
        }

        groups.forEach { group ->
            val chip = Chip(requireContext()).apply {
                text = group
                isCheckable = true
                isChecked = group == selectedGroup
                setChipBackgroundColorResource(R.color.surface)
                setOnClickListener {
                    viewModel.setSelectedGroup(group)
                }
            }
            chipGroup.addView(chip)
        }
    }

    private fun navigateToPlayer(channel: Channel) {
        viewModel.updateLastWatched(channel.id)
        val action = ChannelsFragmentDirections.actionChannelsToPlayer(channel.id)
        findNavController().navigate(action)
    }

    private fun showAddPlaylistDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_playlist, null)
        val etName = dialogView.findViewById<TextInputEditText>(R.id.et_playlist_name)
        val etUrl = dialogView.findViewById<TextInputEditText>(R.id.et_playlist_url)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.add_playlist)
            .setView(dialogView)
            .setPositiveButton(R.string.add) { _, _ ->
                val name = etName.text?.toString()?.trim()
                val url = etUrl.text?.toString()?.trim()
                if (!name.isNullOrEmpty() && !url.isNullOrEmpty()) {
                    viewModel.addPlaylist(name, url)
                } else {
                    Snackbar.make(binding.root, R.string.no_url_provided, Snackbar.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
