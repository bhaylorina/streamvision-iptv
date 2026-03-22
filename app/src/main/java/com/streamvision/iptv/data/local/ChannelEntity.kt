package com.streamvision.iptv.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "channels")
data class ChannelEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val url: String,
    val logo: String?,
    val groupName: String?,
    val playlistId: Long,
    val isFavorite: Boolean = false,
    val lastWatched: Long? = null,
    val drmLicenseUrl: String? = null,
    val drmKey: String? = null,
    val userAgent: String? = null,
    val cookie: String? = null,
    val referer: String? = null
)
