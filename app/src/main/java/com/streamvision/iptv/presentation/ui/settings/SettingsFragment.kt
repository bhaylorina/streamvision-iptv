package com.streamvision.iptv.presentation.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.streamvision.iptv.R
import com.streamvision.iptv.databinding.FragmentSettingsBinding
import com.streamvision.iptv.presentation.adapter.PlaylistAdapter
import com.streamvision.iptv.presentation.viewmodel.ChannelsViewModel
import com.streamvision.iptv.presentation.viewmodel.SettingsViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val settingsViewModel: SettingsViewModel by viewModels()
    private val channelsViewModel: ChannelsViewModel by activityViewModels()
    private lateinit var playlistAdapter: PlaylistAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        setupClickListeners()
        observeState()
    }

    private fun setupRecyclerView() {
        playlistAdapter = PlaylistAdapter(
            onPlaylistClick = { playlist ->
                channelsViewModel.loadChannels(playlist.id)
                Snackbar.make(binding.root, "Switched to ${playlist.name}", Snackbar.LENGTH_SHORT).show()
            },
            onDeleteClick = { playlist ->
                showDeleteConfirmation(playlist.id, playlist.name)
            }
        )
        binding.rvPlaylists.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = playlistAdapter
        }
    }

    private fun setupClickListeners() {
        binding.btnAddPlaylist.setOnClickListener {
            showAddPlaylistDialog()
        }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                settingsViewModel.uiState.collect { state ->
                    playlistAdapter.submitList(state.playlists)
                    
                    binding.tvNoPlaylists.visibility = if (state.playlists.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
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
                    channelsViewModel.addPlaylist(name, url)
                } else {
                    Snackbar.make(binding.root, R.string.no_url_provided, Snackbar.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
