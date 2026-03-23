package com.streamvision.iptv.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamvision.iptv.domain.model.Channel
import com.streamvision.iptv.domain.model.Playlist
import com.streamvision.iptv.domain.usecase.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class ChannelsUiState(
    val channels: List<Channel> = emptyList(),
    val filteredChannels: List<Channel> = emptyList(),
    val groups: List<String> = emptyList(),
    val selectedGroup: String? = null,
    val searchQuery: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val currentPlaylist: Playlist? = null,
    val playlistsLoaded: Boolean = false,
    val hasNoPlaylists: Boolean = false
)

// ✅ @HiltViewModel हटा दिया
class ChannelsViewModel(
    private val getChannelsUseCase: GetChannelsUseCase,
    private val getChannelGroupsUseCase: GetChannelGroupsUseCase,
    private val toggleFavoriteUseCase: ToggleFavoriteUseCase,
    private val updateLastWatchedUseCase: UpdateLastWatchedUseCase,
    private val getPlaylistsUseCase: GetPlaylistsUseCase,
    private val addPlaylistUseCase: AddPlaylistUseCase,
    private val getPlaylistByIdUseCase: GetPlaylistByIdUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChannelsUiState())
    val uiState: StateFlow<ChannelsUiState> = _uiState.asStateFlow()

    init {
        loadPlaylists()
    }

    private fun loadPlaylists() {
        viewModelScope.launch {
            getPlaylistsUseCase()
                .onEach { playlists ->
                    _uiState.update {
                        it.copy(
                            playlistsLoaded = true,
                            hasNoPlaylists = playlists.isEmpty()
                        )
                    }
                }
                .launchIn(viewModelScope)
        }
    }

    fun refreshPlaylists() {
        loadPlaylists()
    }

    fun loadChannels(playlistId: Long) {
        viewModelScope.launch {
            _uiState.update { 
                it.copy(
                    isLoading = true, 
                    error = null,
                    searchQuery = "",
                    selectedGroup = null
                ) 
            }

            val playlist = getPlaylistByIdUseCase(playlistId)
            
            getChannelsUseCase(playlistId).collect { channels ->
                _uiState.update { state ->
                    state.copy(
                        currentPlaylist = playlist,
                        channels = channels,
                        filteredChannels = filterChannels(channels, null, ""),
                        groups = emptyList(),
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

    fun onChannelSelected(channelId: Long) {
        viewModelScope.launch {
            updateLastWatchedUseCase(channelId)
        }
    }

    fun clearCurrentPlaylist() {
        _uiState.update { 
            it.copy(
                currentPlaylist = null,
                searchQuery = "",
                selectedGroup = null
            ) 
        }
    }

    fun addPlaylist(name: String, url: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val playlistId = addPlaylistUseCase(name, url)
                loadChannels(playlistId)
                _uiState.update { it.copy(isLoading = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message, isLoading = false) }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
