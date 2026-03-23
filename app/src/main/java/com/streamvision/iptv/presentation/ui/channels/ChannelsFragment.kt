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
import com.streamvision.iptv.databinding.FragmentChannelsBinding
import com.streamvision.iptv.presentation.viewmodel.ChannelsViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class ChannelsFragment : Fragment() {

    private var _binding: FragmentChannelsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ChannelsViewModel by viewModels()

    private lateinit var channelsAdapter: ChannelsAdapter

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

        setupRecyclerView()
        setupSearch()
        setupGroupFilter()
        setupBackNavigation()   // ✅ Back press handler
        observeUiState()
    }

    // ✅ FIX: Intercept back press to go Channel List instead of Playlist screen
    private fun setupBackNavigation() {
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) {
            if (viewModel.uiState.value.isShowingChannels) {
                // Coming back from Player → return to Channel List
                viewModel.clearCurrentPlaylist()
                // Pop only the player from back stack, stay on ChannelsFragment
                findNavController().popBackStack()
            } else {
                // Already on channel list → go back to Playlist screen normally
                isEnabled = false
                requireActivity().onBackPressedDispatcher.onBackPressed()
            }
        }
    }

    private fun setupRecyclerView() {
        channelsAdapter = ChannelsAdapter(
            onChannelClick = { channel ->
                // ✅ Use onChannelSelected instead of updateLastWatched directly
                // This sets isShowingChannels = true so back press works correctly
                viewModel.onChannelSelected(channel.id)
                navigateToPlayer(channel.id)
            },
            onFavoriteClick = { channel ->
                viewModel.toggleFavorite(channel.id)
            }
        )
        binding.recyclerViewChannels.adapter = channelsAdapter
    }

    private fun setupSearch() {
        binding.searchView.setOnQueryTextListener(object :
            androidx.appcompat.widget.SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean = false
            override fun onQueryTextChange(newText: String?): Boolean {
                viewModel.setSearchQuery(newText ?: "")
                return true
            }
        })
    }

    private fun setupGroupFilter() {
        binding.chipGroupFilter.setOnCheckedStateChangeListener { group, _ ->
            val checkedId = group.checkedChipId
            if (checkedId == View.NO_ID) {
                viewModel.setSelectedGroup(null)
            }
            // Individual chip click is handled in observeUiState → chips setup
        }
    }

    private fun observeUiState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->

                    // Loading indicator
                    binding.progressBar.visibility =
                        if (state.isLoading) View.VISIBLE else View.GONE

                    // Channel list
                    channelsAdapter.submitList(state.filteredChannels)

                    // Empty state
                    binding.textViewEmpty.visibility =
                        if (!state.isLoading && state.filteredChannels.isEmpty()) View.VISIBLE
                        else View.GONE

                    // Current playlist title
                    state.currentPlaylist?.let {
                        binding.textViewPlaylistName.text = it.name
                    }

                    // Error
                    state.error?.let { error ->
                        showError(error)
                        viewModel.clearError()
                    }

                    // Group filter chips
                    setupGroupChips(state.groups, state.selectedGroup)
                }
            }
        }
    }

    private fun setupGroupChips(groups: List<String>, selectedGroup: String?) {
        binding.chipGroupFilter.removeAllViews()

        // "All" chip
        val allChip = com.google.android.material.chip.Chip(requireContext()).apply {
            text = "All"
            isCheckable = true
            isChecked = selectedGroup == null
            setOnClickListener { viewModel.setSelectedGroup(null) }
        }
        binding.chipGroupFilter.addView(allChip)

        // Group chips
        groups.forEach { group ->
            val chip = com.google.android.material.chip.Chip(requireContext()).apply {
                text = group
                isCheckable = true
                isChecked = group == selectedGroup
                setOnClickListener { viewModel.setSelectedGroup(group) }
            }
            binding.chipGroupFilter.addView(chip)
        }
    }

    private fun navigateToPlayer(channelId: Long) {
        val action = ChannelsFragmentDirections
            .actionChannelsFragmentToPlayerFragment(channelId)
        findNavController().navigate(action)
    }

    private fun showError(message: String) {
        com.google.android.material.snackbar.Snackbar
            .make(binding.root, message, com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
