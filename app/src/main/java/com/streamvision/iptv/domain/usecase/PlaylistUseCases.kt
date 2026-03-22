package com.streamvision.iptv.domain.usecase

import com.streamvision.iptv.data.repository.PlaylistRepositoryImpl
import com.streamvision.iptv.domain.model.Playlist
import com.streamvision.iptv.domain.repository.PlaylistRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class GetPlaylistsUseCase @Inject constructor(
    private val playlistRepository: PlaylistRepository
) {
    operator fun invoke(): Flow<List<Playlist>> {
        return playlistRepository.getAllPlaylists()
    }
}

class AddPlaylistUseCase @Inject constructor(
    private val playlistRepository: PlaylistRepositoryImpl
) {
    suspend operator fun invoke(name: String, url: String): Long {
        return playlistRepository.addPlaylist(name, url)
    }
}

class DeletePlaylistUseCase @Inject constructor(
    private val playlistRepository: PlaylistRepository
) {
    suspend operator fun invoke(id: Long) {
        playlistRepository.deletePlaylist(id)
    }
}

class GetPlaylistByIdUseCase @Inject constructor(
    private val playlistRepository: PlaylistRepository
) {
    suspend operator fun invoke(id: Long): Playlist? {
        return playlistRepository.getPlaylistById(id)
    }
}
