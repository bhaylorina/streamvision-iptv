package com.streamvision.iptv.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamvision.iptv.domain.model.Channel
import com.streamvision.iptv.domain.usecase.GetChannelByIdUseCase
import com.streamvision.iptv.domain.usecase.UpdateLastWatchedUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PlayerUiState(
    val currentChannel: Channel? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val error: String? = null,
    val playbackPosition: Long = 0L,
    val duration: Long = 0L
)

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val getChannelByIdUseCase: GetChannelByIdUseCase,
    private val updateLastWatchedUseCase: UpdateLastWatchedUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    fun loadChannel(channelId: Long) {
        viewModelScope.launch {
            val channel = getChannelByIdUseCase(channelId)
            _uiState.value = PlayerUiState(currentChannel = channel)
            updateLastWatchedUseCase(channelId)
        }
    }

    fun setCurrentChannel(channel: Channel) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(currentChannel = channel)
            updateLastWatchedUseCase(channel.id)
        }
    }

    fun updatePlaybackState(isPlaying: Boolean, isBuffering: Boolean = false) {
        _uiState.value = _uiState.value.copy(
            isPlaying = isPlaying,
            isBuffering = isBuffering
        )
    }

    fun setError(error: String?) {
        _uiState.value = _uiState.value.copy(error = error)
    }

    fun updateProgress(position: Long, duration: Long) {
        _uiState.value = _uiState.value.copy(
            playbackPosition = position,
            duration = duration
        )
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}
