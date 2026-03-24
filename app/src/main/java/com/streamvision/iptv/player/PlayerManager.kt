package com.streamvision.iptv.player

import android.content.Context
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
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

    // Listeners that UI layers can register
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
     * Play a channel. If it's already playing this channel, do nothing.
     * If a different channel → stop and start the new one.
     */
    fun play(channel: Channel) {
        if (currentChannel?.id == channel.id && player?.isPlaying == true) return

        currentChannel = channel

        // Release old player
        player?.let { old ->
            listeners.forEach { old.removeListener(it) }
            old.release()
        }

        val httpFactory = buildHttpFactory(channel)

        player = ExoPlayer.Builder(context)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(context).setDataSourceFactory(httpFactory)
            )
            .build()
            .also { exo ->
                listeners.forEach { exo.addListener(it) }
                exo.setMediaItem(MediaItem.Builder().setUri(channel.url).build())
                exo.prepare()
                exo.playWhenReady = true
            }
    }

    fun pause() { player?.pause() }
    fun resume() { player?.play() }

    fun stop() {
        player?.let { old ->
            listeners.forEach { old.removeListener(it) }
            old.release()
        }
        player = null
        currentChannel = null
    }

    val isPlaying: Boolean get() = player?.isPlaying == true

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
