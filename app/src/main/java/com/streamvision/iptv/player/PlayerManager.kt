package com.streamvision.iptv.player

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaItem.DrmConfiguration
import androidx.media3.common.C
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
    /**
     * A single, persistent HTTP factory whose default request properties are updated
     * per channel so we don't need to recreate the ExoPlayer for each channel change.
     */
    private val httpFactory: DefaultHttpDataSource.Factory = DefaultHttpDataSource.Factory()
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(15_000)
        .setReadTimeoutMs(15_000)

    /**
     * Single persistent ExoPlayer instance – created lazily on the calling (main) thread.
     */
    val player: ExoPlayer by lazy {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(context)
                    .setDataSourceFactory(httpFactory)
                    .setDrmSessionManagerProvider(DefaultDrmSessionManagerProvider())
            )
            .build()
    }

    var currentChannel: Channel? = null
        private set

    fun addListener(listener: Player.Listener)    { player.addListener(listener) }
    fun removeListener(listener: Player.Listener) { player.removeListener(listener) }

    /**
     * Start playing [channel].  If the same channel is already playing, this is a no-op.
     * Updates HTTP headers on the shared factory before loading the new MediaItem so that
     * cookies / user-agent / referer are sent correctly without recreating the player.
     */
    fun play(channel: Channel) {
        if (currentChannel?.id == channel.id && player.isPlaying) return

        currentChannel = channel

        // Update per-channel request headers on the shared factory
        httpFactory.setDefaultRequestProperties(buildHeaders(channel))

        player.setMediaItem(buildMediaItem(channel))
        player.prepare()
        player.playWhenReady = true

        // Start foreground service so playback survives app background
        val intent = Intent(context, PlaybackService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun pause()  { player.pause() }
    fun resume() { player.play() }

    fun stop() {
        player.stop()
        player.clearMediaItems()
        currentChannel = null
        context.stopService(Intent(context, PlaybackService::class.java))
    }

    val isPlaying: Boolean get() = player.isPlaying

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private fun buildMediaItem(channel: Channel): MediaItem {
        val builder = MediaItem.Builder().setUri(channel.url)

        val licenseUrl = channel.drmLicenseUrl
        if (!licenseUrl.isNullOrBlank()) {
            val drmBuilder = DrmConfiguration.Builder(C.WIDEVINE_UUID)
                .setLicenseUri(licenseUrl)

            val drmHeaders = buildHeaders(channel)
            if (drmHeaders.isNotEmpty()) {
                drmBuilder.setLicenseRequestHeaders(drmHeaders)
            }

            builder.setDrmConfiguration(drmBuilder.build())
        }

        return builder.build()
    }

    private fun buildHeaders(channel: Channel): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        if (!channel.userAgent.isNullOrBlank()) headers["User-Agent"] = channel.userAgent
        if (!channel.referer.isNullOrBlank())   headers["Referer"]   = channel.referer
        if (!channel.cookie.isNullOrBlank())    headers["Cookie"]    = channel.cookie
        return headers
    }
}
