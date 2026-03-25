package com.streamvision.iptv.player

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Base64
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaItem.DrmConfiguration
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
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
    // Persistent HTTP factory for network requests
    private val httpDataSourceFactory = DefaultHttpDataSource.Factory()
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(15_000)
        .setReadTimeoutMs(15_000)

    /**
     * Wrap the HTTP factory in a DefaultDataSource.Factory.
     * This allows ExoPlayer to handle "data:" URIs (needed for ClearKey) 
     * and local file assets in addition to HTTP.
     */
    private val dataSourceFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)

    val player: ExoPlayer by lazy {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(context)
                    .setDataSourceFactory(dataSourceFactory)
            )
            .build()
    }

    var currentChannel: Channel? = null
        private set

    fun addListener(listener: Player.Listener)    { player.addListener(listener) }
    fun removeListener(listener: Player.Listener) { player.removeListener(listener) }

    fun play(channel: Channel) {
        if (currentChannel?.id == channel.id && player.isPlaying) return

        currentChannel = channel

        // Update headers on the underlying HTTP factory
        httpDataSourceFactory.setDefaultRequestProperties(buildHeaders(channel))

        player.setMediaItem(buildMediaItem(channel))
        player.prepare()
        player.playWhenReady = true

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

    private fun buildMediaItem(channel: Channel): MediaItem {
        val builder = MediaItem.Builder().setUri(channel.url)

        if (!channel.drmLicenseUrl.isNullOrBlank()) {
            // Widevine
            val drmBuilder = DrmConfiguration.Builder(C.WIDEVINE_UUID)
                .setLicenseUri(channel.drmLicenseUrl)
            
            val headers = buildHeaders(channel)
            if (headers.isNotEmpty()) drmBuilder.setLicenseRequestHeaders(headers)
            
            builder.setDrmConfiguration(drmBuilder.build())
        } else if (!channel.drmKey.isNullOrBlank()) {
            // ClearKey
            val parts = channel.drmKey.split(":")
            if (parts.size == 2) {
                try {
                    val kid = hexToBase64Url(parts[0])
                    val key = hexToBase64Url(parts[1])

                    // Construct the JWK Set JSON
                    val jwk = """{"keys":[{"kty":"oct","k":"$key","kid":"$kid"}],"type":"temporary"}"""
                    val base64Jwk = Base64.encodeToString(jwk.toByteArray(), Base64.NO_PADDING or Base64.NO_WRAP)
                    
                    // The "data:" URI tells ExoPlayer to read the license from this string
                    val dataUri = "data:application/json;base64,$base64Jwk"

                    builder.setDrmConfiguration(
                        DrmConfiguration.Builder(C.CLEARKEY_UUID)
                            .setLicenseUri(dataUri)
                            .build()
                    )
                } catch (e: Exception) {
                    Log.e("PlayerManager", "ClearKey parsing failed", e)
                }
            }
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

    private fun hexToBase64Url(hex: String): String {
        val bytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }
}
