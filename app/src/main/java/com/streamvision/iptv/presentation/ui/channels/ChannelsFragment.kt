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
import com.streamvision.iptv.presentation.viewmodel.ChannelsUiState
import com.streamvision.iptv.presentation.viewmodel.ChannelsViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ✅ @AndroidEntryPoint हटा दिया
class ChannelsFragment : Fragment() {

    private var _binding: FragmentChannelsBinding? = null
    private val binding get() = _binding!!

    // ✅ Simple ViewModel initialization
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

    override fun onResume() {
        super.onResume()
        viewModel.refreshPlaylists()
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
                    viewModel.clearCurrentPlaylist()
                }
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

    private fun renderState(state: ChannelsUiState) {
        binding.progressBar.visibility = if (state.isLoading) View.VISIBLE else View.GONE
        binding.swipeRefresh.isRefreshing = state.isLoading

        val isShowingChannels = state.currentPlaylist != null

        binding.tvPlaylistName.text = if (isShowingChannels) {
            state.currentPlaylist?.name ?: getString(R.string.playlists)
        } else {
            getString(R.string.playlists)
        }

        binding.rvChannels.visibility = if (isShowingChannels) View.VISIBLE else View.GONE
        binding.searchLayout.visibility = if (isShowingChannels) View.VISIBLE else View.GONE
        binding.btnBackToPlaylists.visibility = if (isShowingChannels) View.VISIBLE else View.GONE
        binding.scrollGroups.visibility = if (isShowingChannels && state.groups.isNotEmpty()) View.VISIBLE else View.GONE
        
        binding.emptyState.visibility = if (isShowingChannels && !state.isLoading && state.filteredChannels.isEmpty()) {
            View.VISIBLE
        } else {
            View.GONE
        }

        binding.rvPlaylists.visibility = if (!isShowingChannels) View.VISIBLE else View.GONE
        binding.tvNoPlaylists.visibility = if (!isShowingChannels && state.hasNoPlaylists) View.VISIBLE else View.GONE

        if (isShowingChannels) {
            channelAdapter.submitList(state.filteredChannels)
            updateGroupChips(state.groups, state.selectedGroup)
        }

        state.error?.let { error ->
            Snackbar.make(binding.root, error.toString(), Snackbar.LENGTH_LONG).show()
            viewModel.clearError()
        }
    }

    private fun updateGroupChips(groups: List<String>, selectedGroup: String?) {
        val chipGroup = binding.chipGroup

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
