package com.streamvision.iptv.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val url: String,
    val channelCount: Int = 0, // ✅ Ye field add kiya
    val createdAt: Long = System.currentTimeMillis()
)
