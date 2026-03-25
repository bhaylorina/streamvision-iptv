package com.streamvision.iptv.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamvision.iptv.domain.model.Channel
import com.streamvision.iptv.domain.model.Playlist
import com.streamvision.iptv.domain.usecase.*
import com.streamvision.iptv.player.PlayerManager
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
    val isLoading: Boolean = false,
    val error: String? = null,
    val currentPlaylist: Playlist? = null,
    val playlists: List<Playlist> = emptyList(),
    val playlistsLoaded: Boolean = false,
    val hasNoPlaylists: Boolean = false
)

@HiltViewModel
class ChannelsViewModel @Inject constructor(
    private val getChannelsUseCase: GetChannelsUseCase,
    private val getChannelGroupsUseCase: GetChannelGroupsUseCase,
    private val toggleFavoriteUseCase: ToggleFavoriteUseCase,
    private val updateLastWatchedUseCase: UpdateLastWatchedUseCase,
    private val getPlaylistsUseCase: GetPlaylistsUseCase,
    private val addPlaylistUseCase: AddPlaylistUseCase,
    private val getPlaylistByIdUseCase: GetPlaylistByIdUseCase,
    private val refreshPlaylistUseCase: RefreshPlaylistUseCase,
    val playerManager: PlayerManager
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
                            playlists       = playlists,
                            playlistsLoaded = true,
                            hasNoPlaylists  = playlists.isEmpty()
                        )
                    }
                }
                .launchIn(viewModelScope)
        }
    }

    fun refreshPlaylists() {
        loadPlaylists()
    }

    fun selectPlaylist(playlistId: Long) {
        _uiState.update { it.copy(channels = emptyList(), filteredChannels = emptyList()) }
        loadChannels(playlistId)
    }

    fun loadChannels(playlistId: Long) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(isLoading = true, error = null, searchQuery = "", selectedGroup = null)
            }

            val playlist = getPlaylistByIdUseCase(playlistId)

            getChannelsUseCase(playlistId).collect { channels ->
                _uiState.update { state ->
                    state.copy(
                        currentPlaylist  = playlist,
                        channels         = channels,
                        filteredChannels = filterChannels(channels, null, ""),
                        groups           = emptyList(),
                        isLoading        = false
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

    /** Re-downloads the playlist from the network, refreshing all channels. */
    fun refreshCurrentPlaylist() {
        val playlistId = _uiState.value.currentPlaylist?.id ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                refreshPlaylistUseCase(playlistId)
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Refresh failed: ${e.message}") }
            } finally {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun setSelectedGroup(group: String?) {
        _uiState.update { state ->
            state.copy(
                selectedGroup    = group,
                filteredChannels = filterChannels(state.channels, group, state.searchQuery)
            )
        }
    }

    fun setSearchQuery(query: String) {
        _uiState.update { state ->
            state.copy(
                searchQuery      = query,
                filteredChannels = filterChannels(state.channels, state.selectedGroup, query)
            )
        }
    }

    private fun filterChannels(
        channels: List<Channel>,
        group: String?,
        query: String
    ): List<Channel> = channels.filter { channel ->
        val matchesGroup = group == null || channel.group == group
        val matchesQuery = query.isEmpty() || channel.name.contains(query, ignoreCase = true)
        matchesGroup && matchesQuery
    }

    fun toggleFavorite(channelId: Long) {
        viewModelScope.launch { toggleFavoriteUseCase(channelId) }
    }

    fun onChannelSelected(channelId: Long) {
        viewModelScope.launch { updateLastWatchedUseCase(channelId) }
    }

    fun updateLastWatched(channelId: Long) {
        viewModelScope.launch { updateLastWatchedUseCase(channelId) }
    }

    fun clearCurrentPlaylist() {
        _uiState.update {
            it.copy(currentPlaylist = null, searchQuery = "", selectedGroup = null)
        }
    }

    fun addPlaylist(name: String, url: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val playlistId = addPlaylistUseCase(name, url)
                loadChannels(playlistId)
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = "Failed to load playlist: ${e.message ?: "Unknown error"}"
                    )
                }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
