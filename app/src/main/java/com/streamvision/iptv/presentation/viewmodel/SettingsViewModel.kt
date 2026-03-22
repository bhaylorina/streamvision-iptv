package com.streamvision.iptv.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamvision.iptv.domain.model.Playlist
import com.streamvision.iptv.domain.usecase.DeletePlaylistUseCase
import com.streamvision.iptv.domain.usecase.GetPlaylistsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val playlists: List<Playlist> = emptyList(),
    val isLoading: Boolean = false
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val getPlaylistsUseCase: GetPlaylistsUseCase,
    private val deletePlaylistUseCase: DeletePlaylistUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        loadPlaylists()
    }

    fun loadPlaylists() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            getPlaylistsUseCase().collect { playlists ->
                _uiState.update { it.copy(playlists = playlists, isLoading = false) }
            }
        }
    }

    fun deletePlaylist(id: Long) {
        viewModelScope.launch {
            deletePlaylistUseCase(id)
        }
    }
}
