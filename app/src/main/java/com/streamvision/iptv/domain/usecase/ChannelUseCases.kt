package com.streamvision.iptv.domain.usecase

import com.streamvision.iptv.domain.model.Channel
import com.streamvision.iptv.domain.repository.ChannelRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class GetChannelsUseCase @Inject constructor(
    private val channelRepository: ChannelRepository
) {
    operator fun invoke(playlistId: Long): Flow<List<Channel>> {
        return channelRepository.getChannelsByPlaylist(playlistId)
    }
}

class GetFavoriteChannelsUseCase @Inject constructor(
    private val channelRepository: ChannelRepository
) {
    operator fun invoke(): Flow<List<Channel>> {
        return channelRepository.getFavoriteChannels()
    }
}

class GetRecentChannelsUseCase @Inject constructor(
    private val channelRepository: ChannelRepository
) {
    operator fun invoke(): Flow<List<Channel>> {
        return channelRepository.getRecentChannels()
    }
}

class SearchChannelsUseCase @Inject constructor(
    private val channelRepository: ChannelRepository
) {
    operator fun invoke(query: String): Flow<List<Channel>> {
        return channelRepository.searchChannels(query)
    }
}

class GetChannelGroupsUseCase @Inject constructor(
    private val channelRepository: ChannelRepository
) {
    operator fun invoke(playlistId: Long): Flow<List<String>> {
        return channelRepository.getChannelGroups(playlistId)
    }
}

class ToggleFavoriteUseCase @Inject constructor(
    private val channelRepository: ChannelRepository
) {
    suspend operator fun invoke(channelId: Long) {
        channelRepository.toggleFavorite(channelId)
    }
}

class UpdateLastWatchedUseCase @Inject constructor(
    private val channelRepository: ChannelRepository
) {
    suspend operator fun invoke(channelId: Long) {
        channelRepository.updateLastWatched(channelId)
    }
}

class GetChannelByIdUseCase @Inject constructor(
    private val channelRepository: ChannelRepository
) {
    suspend operator fun invoke(id: Long): Channel? {
        return channelRepository.getChannelById(id)
    }
}
