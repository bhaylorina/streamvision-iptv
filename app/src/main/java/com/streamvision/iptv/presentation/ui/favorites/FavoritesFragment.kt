package com.streamvision.iptv.presentation.ui.favorites

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.streamvision.iptv.R
import com.streamvision.iptv.databinding.FragmentFavoritesBinding
import com.streamvision.iptv.domain.model.Channel
import com.streamvision.iptv.presentation.adapter.ChannelAdapter
import com.streamvision.iptv.presentation.viewmodel.ChannelsViewModel
import com.streamvision.iptv.presentation.viewmodel.FavoritesViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class FavoritesFragment : Fragment() {

    private var _binding: FragmentFavoritesBinding? = null
    private val binding get() = _binding!!

    private val viewModel: FavoritesViewModel by viewModels()
    private val channelsViewModel: ChannelsViewModel by activityViewModels()
    private lateinit var channelAdapter: ChannelAdapter

    private var isNavigatingToFullscreen = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFavoritesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        setupSearch()
        setupInlinePlayer()
        observeState()
    }

    private fun setupInlinePlayer() {
        binding.inlinePlayerView.findViewById<ImageButton>(R.id.btn_fullscreen)?.apply {
            setImageResource(R.drawable.ic_fullscreen)
            setOnClickListener {
                if (channelsViewModel.playerManager.currentChannel != null) {
                    isNavigatingToFullscreen = true
                    val action = FavoritesFragmentDirections.actionFavoritesToPlayer(
                        channelsViewModel.playerManager.currentChannel!!.id
                    )
                    findNavController().navigate(action)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        isNavigatingToFullscreen = false
        binding.inlinePlayerView.player = channelsViewModel.playerManager.player
        // Force list update when coming back
        viewModel.setSearchQuery(binding.etSearch.text?.toString() ?: "")
    }

    override fun onPause() {
        super.onPause()
        // If user is switching to Settings or Playlists tab, stop the video!
        if (!isNavigatingToFullscreen) {
            channelsViewModel.playerManager.stop()
        }
        binding.inlinePlayerView.player = null
    }

    private fun setupRecyclerView() {
        channelAdapter = ChannelAdapter(
            onChannelClick = { channel -> 
                viewModel.updateLastWatched(channel.id)
                channelsViewModel.playerManager.play(channel) // Play inline!
            },
            onFavoriteClick = { channel -> viewModel.toggleFavorite(channel.id) }
        )
        binding.rvFavorites.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = channelAdapter
        }
    }

    private fun setupSearch() {
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                viewModel.setSearchQuery(s?.toString() ?: "")
            }
        })
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    binding.progressBar.visibility = if (state.isLoading) View.VISIBLE else View.GONE

                    channelAdapter.submitList(state.filteredFavorites)

                    val showEmpty = state.filteredFavorites.isEmpty() && !state.isLoading
                    binding.emptyState.visibility = if (showEmpty) View.VISIBLE else View.GONE
                    binding.rvFavorites.visibility = if (showEmpty) View.GONE else View.VISIBLE
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
