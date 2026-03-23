package com.streamvision.iptv.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamvision.iptv.domain.model.Channel
import com.streamvision.iptv.domain.model.Playlist
import com.streamvision.iptv.domain.usecase.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ChannelsUiState(
    val channels: List<Channel> = emptyList(),
    val filteredChannels: List<Channel> = emptyList(),
    val groups: List<String> = emptyList(),
    val selectedGroup: String? = null,
    val searchQuery: String = "",
    val isShowingChannels: Boolean = false, // ✅ Fixed: val + comma (was var, missing comma)
    val isLoading: Boolean = false,
    val error: String? = null,
    val currentPlaylist: Playlist? = null
)

@HiltViewModel
class ChannelsViewModel @Inject constructor(
    private val getChannelsUseCase: GetChannelsUseCase,
    private val getChannelGroupsUseCase: GetChannelGroupsUseCase,
    private val searchChannelsUseCase: SearchChannelsUseCase,
    private val toggleFavoriteUseCase: ToggleFavoriteUseCase,
    private val updateLastWatchedUseCase: UpdateLastWatchedUseCase,
    private val getPlaylistsUseCase: GetPlaylistsUseCase,
    private val addPlaylistUseCase: AddPlaylistUseCase,
    private val getPlaylistByIdUseCase: GetPlaylistByIdUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChannelsUiState())
    val uiState: StateFlow<ChannelsUiState> = _uiState.asStateFlow()

    private var currentPlaylistId: Long? = null

    init {
        loadPlaylists()
    }

    private fun loadPlaylists() {
        viewModelScope.launch {
            getPlaylistsUseCase().collect { playlists ->
                if (playlists.isNotEmpty() && currentPlaylistId == null) {
                    loadChannels(playlists.first().id)
                }
                _uiState.update { it.copy(currentPlaylist = playlists.firstOrNull()) }
            }
        }
    }

    fun loadChannels(playlistId: Long) {
        currentPlaylistId = playlistId
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            val playlist = getPlaylistByIdUseCase(playlistId)
            _uiState.update { it.copy(currentPlaylist = playlist) }

            getChannelsUseCase(playlistId).collect { channels ->
                _uiState.update { state ->
                    state.copy(
                        channels = channels,
                        filteredChannels = filterChannels(channels, state.selectedGroup, state.searchQuery),
                        isLoading = false
                    )
                }
            }
        }

        viewModelScope.launch {
            getChannelGroupsUseCase(playlistId).collect { groups ->
                _uiState.update { it.copy(groups = groups) }
            }
        }
    }

    fun setSelectedGroup(group: String?) {
        _uiState.update { state ->
            state.copy(
                selectedGroup = group,
                filteredChannels = filterChannels(state.channels, group, state.searchQuery)
            )
        }
    }

    fun setSearchQuery(query: String) {
        _uiState.update { state ->
            state.copy(
                searchQuery = query,
                filteredChannels = filterChannels(state.channels, state.selectedGroup, query)
            )
        }
    }

    private fun filterChannels(channels: List<Channel>, group: String?, query: String): List<Channel> {
        return channels.filter { channel ->
            val matchesGroup = group == null || channel.group == group
            val matchesQuery = query.isEmpty() || channel.name.contains(query, ignoreCase = true)
            matchesGroup && matchesQuery
        }
    }

    fun toggleFavorite(channelId: Long) {
        viewModelScope.launch {
            toggleFavoriteUseCase(channelId)
        }
    }

    fun updateLastWatched(channelId: Long) {
        viewModelScope.launch {
            updateLastWatchedUseCase(channelId)
        }
    }

    fun addPlaylist(name: String, url: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val playlistId = addPlaylistUseCase(name, url)
                loadChannels(playlistId)
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message, isLoading = false) }
            }
        }
    }

    // ✅ ADDED: Called when user opens the player — marks that we came from channel list
    fun onChannelSelected(channelId: Long) {
        updateLastWatched(channelId)
        _uiState.update { it.copy(isShowingChannels = true) }
    }

    // ✅ ADDED: Called when back is pressed from player — returns to channel list
    // This was causing "Unresolved reference: clearCurrentPlaylist" build error
    fun clearCurrentPlaylist() {
        _uiState.update { it.copy(isShowingChannels = false) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
