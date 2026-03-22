package com.streamvision.iptv.data.repository

import com.streamvision.iptv.data.local.ChannelDao
import com.streamvision.iptv.data.model.toDomain
import com.streamvision.iptv.data.model.toEntity
import com.streamvision.iptv.domain.model.Channel
import com.streamvision.iptv.domain.repository.ChannelRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChannelRepositoryImpl @Inject constructor(
    private val channelDao: ChannelDao
) : ChannelRepository {

    override fun getChannelsByPlaylist(playlistId: Long): Flow<List<Channel>> {
        return channelDao.getChannelsByPlaylist(playlistId).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override fun getFavoriteChannels(): Flow<List<Channel>> {
        return channelDao.getFavoriteChannels().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override fun getRecentChannels(): Flow<List<Channel>> {
        return channelDao.getRecentChannels().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override fun searchChannels(query: String): Flow<List<Channel>> {
        return channelDao.searchChannels(query).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override fun getChannelGroups(playlistId: Long): Flow<List<String>> {
        return channelDao.getChannelGroups(playlistId)
    }

    override suspend fun getChannelById(id: Long): Channel? {
        return channelDao.getChannelById(id)?.toDomain()
    }

    override suspend fun toggleFavorite(channelId: Long) {
        val channel = channelDao.getChannelById(channelId)
        channel?.let {
            channelDao.updateFavoriteStatus(channelId, !it.isFavorite)
        }
    }

    override suspend fun updateLastWatched(channelId: Long) {
        channelDao.updateLastWatched(channelId, System.currentTimeMillis())
    }

    override suspend fun saveChannels(channels: List<Channel>, playlistId: Long) {
        channelDao.deleteChannelsByPlaylist(playlistId)
        val entities = channels.map { it.copy(playlistId = playlistId).toEntity() }
        channelDao.insertChannels(entities)
    }

    override suspend fun deleteChannelsByPlaylist(playlistId: Long) {
        channelDao.deleteChannelsByPlaylist(playlistId)
    }
}
