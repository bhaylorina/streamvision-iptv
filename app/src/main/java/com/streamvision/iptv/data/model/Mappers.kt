package com.streamvision.iptv.data.model

import com.streamvision.iptv.data.local.ChannelEntity
import com.streamvision.iptv.data.local.PlaylistEntity
import com.streamvision.iptv.domain.model.Channel
import com.streamvision.iptv.domain.model.Playlist

fun ChannelEntity.toDomain(): Channel {
    return Channel(
        id = id,
        name = name,
        url = url,
        logo = logo,
        group = groupName,
        playlistId = playlistId,
        isFavorite = isFavorite,
        lastWatched = lastWatched,
        drmLicenseUrl = drmLicenseUrl,
        drmKey = drmKey
    )
}

fun Channel.toEntity(): ChannelEntity {
    return ChannelEntity(
        id = id,
        name = name,
        url = url,
        logo = logo,
        groupName = group,
        playlistId = playlistId,
        isFavorite = isFavorite,
        lastWatched = lastWatched,
        drmLicenseUrl = drmLicenseUrl,
        drmKey = drmKey
    )
}

fun PlaylistEntity.toDomain(): Playlist {
    return Playlist(
        id = id,
        name = name,
        url = url,
        createdAt = createdAt
    )
}

fun Playlist.toEntity(): PlaylistEntity {
    return PlaylistEntity(
        id = id,
        name = name,
        url = url,
        createdAt = createdAt
    )
}
