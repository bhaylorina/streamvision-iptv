package com.streamvision.iptv.domain.model

data class Playlist(
    val id: Long = 0,
    val name: String,
    val url: String,
    val channelCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)
