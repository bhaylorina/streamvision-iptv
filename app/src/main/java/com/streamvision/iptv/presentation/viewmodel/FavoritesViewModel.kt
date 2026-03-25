package com.streamvision.iptv.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamvision.iptv.domain.model.Channel
import com.streamvision.iptv.domain.usecase.GetFavoriteChannelsUseCase
import com.streamvision.iptv.domain.usecase.ToggleFavoriteUseCase
import com.streamvision.iptv.domain.usecase.UpdateLastWatchedUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FavoritesUiState(
    val favorites: List<Channel> = emptyList(),
    val filteredFavorites: List<Channel> = emptyList(),
    val searchQuery: String = "",
    val isLoading: Boolean = false
)

@HiltViewModel
class FavoritesViewModel @Inject constructor(
    private val getFavoriteChannelsUseCase: GetFavoriteChannelsUseCase,
    private val toggleFavoriteUseCase: ToggleFavoriteUseCase,
    private val updateLastWatchedUseCase: UpdateLastWatchedUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(FavoritesUiState())
    val uiState: StateFlow<FavoritesUiState> = _uiState.asStateFlow()

    init {
        loadFavorites()
    }

    private fun loadFavorites() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            getFavoriteChannelsUseCase().collect { favorites ->
                _uiState.update { state ->
                    state.copy(
                        favorites         = favorites,
                        filteredFavorites = filterFavorites(favorites, state.searchQuery),
                        isLoading         = false
                    )
                }
            }
        }
    }

    fun setSearchQuery(query: String) {
        _uiState.update { state ->
            state.copy(
                searchQuery       = query,
                filteredFavorites = filterFavorites(state.favorites, query)
            )
        }
    }

    private fun filterFavorites(favorites: List<Channel>, query: String): List<Channel> =
        if (query.isBlank()) favorites
        else favorites.filter { it.name.contains(query, ignoreCase = true) }

    fun toggleFavorite(channelId: Long) {
        viewModelScope.launch { toggleFavoriteUseCase(channelId) }
    }

    fun updateLastWatched(channelId: Long) {
        viewModelScope.launch { updateLastWatchedUseCase(channelId) }
    }
}
