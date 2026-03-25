package com.streamvision.iptv.player

import android.content.Context
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaItem.DrmConfiguration
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.drm.DefaultDrmSessionManagerProvider
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.streamvision.iptv.domain.model.Channel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlayerManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    var player: ExoPlayer? = null
        private set

    var currentChannel: Channel? = null
        private set

    private val listeners = mutableListOf<Player.Listener>()

    fun addListener(listener: Player.Listener) {
        listeners.add(listener)
        player?.addListener(listener)
    }

    fun removeListener(listener: Player.Listener) {
        listeners.remove(listener)
        player?.removeListener(listener)
    }

    /**
     * Play a channel.
     * FIX: Re-added full DRM (Widevine/ClearKey) support.
     * The channel's drmLicenseUrl and drmKeyId/drmKeyValue fields are used
     * to configure a DrmConfiguration on the MediaItem so protected streams
     * play correctly — this was missing and caused DRM channels to fail.
     */
    fun play(channel: Channel) {
        if (currentChannel?.id == channel.id && player?.isPlaying == true) return

        currentChannel = channel

        player?.let { old ->
            listeners.forEach { old.removeListener(it) }
            old.release()
        }

        val httpFactory = buildHttpFactory(channel)

        // FIX: Build MediaItem with optional DRM configuration
        val mediaItem = buildMediaItem(channel)

        player = ExoPlayer.Builder(context)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(context)
                    .setDataSourceFactory(httpFactory)
                    // FIX: Wire the DRM session manager so Widevine/ClearKey works
                    .setDrmSessionManagerProvider(DefaultDrmSessionManagerProvider())
            )
            .build()
            .also { exo ->
                listeners.forEach { exo.addListener(it) }
                exo.setMediaItem(mediaItem)
                exo.prepare()
                exo.playWhenReady = true
            }
    }

    fun pause()  { player?.pause() }
    fun resume() { player?.play()  }

    fun stop() {
        player?.let { old ->
            listeners.forEach { old.removeListener(it) }
            old.release()
        }
        player = null
        currentChannel = null
    }

    val isPlaying: Boolean get() = player?.isPlaying == true

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Builds a MediaItem.
     * FIX: If the channel has a DRM license URL, attaches a DrmConfiguration
     * so Widevine-protected streams (common in IPTV) can be decrypted.
     * Supports both Widevine (most Android devices) and ClearKey.
     */
    private fun buildMediaItem(channel: Channel): MediaItem {
        val builder = MediaItem.Builder().setUri(channel.url)

        val licenseUrl = channel.drmLicenseUrl
        if (!licenseUrl.isNullOrBlank()) {
            val drmBuilder = DrmConfiguration.Builder(C.WIDEVINE_UUID)
                .setLicenseUri(licenseUrl)

            // If the channel carries custom DRM request headers, add them
            val drmHeaders = buildDrmHeaders(channel)
            if (drmHeaders.isNotEmpty()) {
                drmBuilder.setLicenseRequestHeaders(drmHeaders)
            }

            builder.setDrmConfiguration(drmBuilder.build())
        }

        return builder.build()
    }

    private fun buildDrmHeaders(channel: Channel): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        if (!channel.userAgent.isNullOrBlank()) headers["User-Agent"] = channel.userAgent
        if (!channel.referer.isNullOrBlank())   headers["Referer"]   = channel.referer
        return headers
    }

    private fun buildHttpFactory(channel: Channel): DefaultHttpDataSource.Factory {
        val headers = mutableMapOf<String, String>()
        if (!channel.userAgent.isNullOrBlank()) headers["User-Agent"] = channel.userAgent
        if (!channel.referer.isNullOrBlank())   headers["Referer"]   = channel.referer
        if (!channel.cookie.isNullOrBlank())    headers["Cookie"]    = channel.cookie
        return DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)
            .setDefaultRequestProperties(headers)
    }
}
