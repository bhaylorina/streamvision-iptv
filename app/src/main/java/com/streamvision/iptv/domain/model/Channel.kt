package com.streamvision.iptv.domain.model

data class Channel(
    val id: Long = 0,
    val name: String,
    val url: String,
    val logo: String? = null,
    val group: String? = null,
    val playlistId: Long = 0,
    val isFavorite: Boolean = false,
    val lastWatched: Long? = null,
    val drmLicenseUrl: String? = null,
    val drmKey: String? = null
)
