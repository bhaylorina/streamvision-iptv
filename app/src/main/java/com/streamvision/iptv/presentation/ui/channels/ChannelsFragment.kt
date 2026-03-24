package com.streamvision.iptv.presentation.ui.channels

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.Player
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

    // ✅ Back callback: when in channel list → go back to playlist list
    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            viewModel.clearCurrentPlaylist()
            // ✅ Do NOT hide mini player here — stream keeps running
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _binding?.miniPlayer?.btnMiniPlayPause?.setImageResource(
                if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
            )
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

        setupMiniPlayer()
        setupPlaylistRecyclerView()
        setupChannelRecyclerView()
        setupSearch()
        setupGroupChipAll()
        setupAddPlaylistButton()
        setupSwipeRefresh()
        observeUiState()

        viewModel.playerManager.addListener(playerListener)

        // ✅ Restore mini player if something is already playing
        viewModel.playerManager.currentChannel?.let { channel ->
            showMiniPlayer(channel)
            binding.miniPlayer.miniPlayerView.player = viewModel.playerManager.player
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshPlaylists()

        // ✅ Re-attach player view when returning from fullscreen PlayerFragment
        viewModel.playerManager.currentChannel?.let { channel ->
            binding.miniPlayer.miniPlayerView.player = viewModel.playerManager.player
            showMiniPlayer(channel)
        }
    }

    override fun onPause() {
        super.onPause()
        // ✅ Detach view only — stream keeps running
        binding.miniPlayer.miniPlayerView.player = null
    }

    private fun setupMiniPlayer() {
        // ✅ Start hidden — showMiniPlayer() will reveal it when needed
        binding.miniPlayer.root.visibility = View.GONE

        binding.miniPlayer.btnMiniFullscreen.setOnClickListener {
            viewModel.playerManager.currentChannel?.let { channel ->
                binding.miniPlayer.miniPlayerView.player = null
                navigateToPlayer(channel.id)
            }
        }

        binding.miniPlayer.btnMiniPlayPause.setOnClickListener {
            val pm = viewModel.playerManager
            if (pm.isPlaying) {
                pm.pause()
                binding.miniPlayer.btnMiniPlayPause.setImageResource(R.drawable.ic_play)
            } else {
                pm.resume()
                binding.miniPlayer.btnMiniPlayPause.setImageResource(R.drawable.ic_pause)
            }
        }

        binding.miniPlayer.btnMiniClose.setOnClickListener {
            // ✅ User explicitly closed — stop player and hide
            viewModel.playerManager.stop()
            binding.miniPlayer.root.visibility = View.GONE
        }

        binding.miniPlayer.miniPlayerView.setOnClickListener {
            viewModel.playerManager.currentChannel?.let { channel ->
                binding.miniPlayer.miniPlayerView.player = null
                navigateToPlayer(channel.id)
            }
        }
    }

    private fun playInMiniPlayer(channel: Channel) {
        viewModel.playerManager.play(channel)
        binding.miniPlayer.miniPlayerView.player = viewModel.playerManager.player
        showMiniPlayer(channel)
    }

    private fun showMiniPlayer(channel: Channel) {
        binding.miniPlayer.root.visibility = View.VISIBLE
        binding.miniPlayer.tvMiniTitle.text = channel.name
        binding.miniPlayer.btnMiniPlayPause.setImageResource(
            if (viewModel.playerManager.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        )
    }

    private fun setupPlaylistRecyclerView() {
        playlistAdapter = PlaylistAdapter(
            onPlaylistClick = { playlist -> viewModel.selectPlaylist(playlist.id) },
            onDeleteClick = { _ ->
                Snackbar.make(binding.root, "Delete playlist from Settings screen", Snackbar.LENGTH_SHORT).show()
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
                playInMiniPlayer(channel)
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
            val playlistId = viewModel.uiState.value.currentPlaylist?.id
            if (playlistId != null) viewModel.loadChannels(playlistId)
            else binding.swipeRefresh.isRefreshing = false
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

        // ✅ Enable back callback only when inside a playlist (channel list view)
        backCallback.isEnabled = isShowingChannels

        binding.headerPlaylist.visibility = if (!isShowingChannels) View.VISIBLE else View.GONE
        binding.searchLayout.visibility = if (isShowingChannels) View.VISIBLE else View.GONE
        binding.rvChannels.visibility = if (isShowingChannels) View.VISIBLE else View.GONE
        binding.chipGroup.visibility =
            if (isShowingChannels && state.groups.isNotEmpty()) View.VISIBLE else View.GONE
        binding.emptyState.visibility =
            if (isShowingChannels && !state.isLoading && state.filteredChannels.isEmpty())
                View.VISIBLE else View.GONE
        binding.rvPlaylists.visibility = if (!isShowingChannels) View.VISIBLE else View.GONE
        binding.tvNoPlaylists.visibility =
            if (!isShowingChannels && state.hasNoPlaylists) View.VISIBLE else View.GONE

        // ✅ FIXED: Mini player is ALWAYS visible if something is playing,
        //    regardless of whether we're on playlist list or channel list
        val hasActivePlaying = viewModel.playerManager.currentChannel != null
        if (hasActivePlaying) {
            binding.miniPlayer.root.visibility = View.VISIBLE
        }
        // ✅ Do NOT hide mini player here — only btnMiniClose should hide it

        if (!isShowingChannels) {
            playlistAdapter.submitList(state.playlists)
        } else {
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
        binding.miniPlayer.miniPlayerView.player = null
        viewModel.playerManager.removeListener(playerListener)
        _binding = null
        searchJob?.cancel()
    }
}

