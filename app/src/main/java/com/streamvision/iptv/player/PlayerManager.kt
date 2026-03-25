package com.streamvision.iptv.player

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Base64
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.LocalMediaDrmCallback
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.streamvision.iptv.domain.model.Channel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlayerManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val httpFactory: DefaultHttpDataSource.Factory = DefaultHttpDataSource.Factory()
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(15_000)
        .setReadTimeoutMs(15_000)

    val player: ExoPlayer by lazy {
        ExoPlayer.Builder(context).build()
    }

    var currentChannel: Channel? = null
        private set

    fun addListener(listener: Player.Listener)    { player.addListener(listener) }
    fun removeListener(listener: Player.Listener) { player.removeListener(listener) }

    fun play(channel: Channel) {
        if (currentChannel?.id == channel.id && player.isPlaying) return

        currentChannel = channel

        // Set headers
        httpFactory.setDefaultRequestProperties(buildHeaders(channel))

        // Exact DRM Logic from your reference file
        val msf = DefaultMediaSourceFactory(context).setDataSourceFactory(httpFactory)
        
        if (!channel.drmKey.isNullOrBlank()) {
            val drmManager = buildClearKeyDrmSessionManager(channel.drmKey)
            if (drmManager != null) {
                msf.setDrmSessionManagerProvider { drmManager }
            }
        }

        val mediaItemBuilder = MediaItem.Builder().setUri(channel.url)
        
        // Widevine fallback (if any)
        if (!channel.drmLicenseUrl.isNullOrBlank()) {
            mediaItemBuilder.setDrmConfiguration(
                MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID)
                    .setLicenseUri(channel.drmLicenseUrl)
                    .setLicenseRequestHeaders(buildHeaders(channel))
                    .build()
            )
        }

        val mediaSource = msf.createMediaSource(mediaItemBuilder.build())

        player.setMediaSource(mediaSource)
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

    private fun buildHeaders(channel: Channel): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        if (!channel.userAgent.isNullOrBlank()) headers["User-Agent"] = channel.userAgent
        if (!channel.referer.isNullOrBlank())   headers["Referer"]   = channel.referer
        if (!channel.cookie.isNullOrBlank())    headers["Cookie"]    = channel.cookie
        return headers
    }

    // Exact DRM parsing logic from your reference
    private fun buildClearKeyDrmSessionManager(drmKey: String): DefaultDrmSessionManager? {
        return try {
            val parts = drmKey.trim().split(":")
            if (parts.size != 2) return null
            val keyIdB64 = hexToBase64Url(parts[0])
            val keyB64   = hexToBase64Url(parts[1])
            val json = """{"keys":[{"kty":"oct","k":"$keyB64","kid":"$keyIdB64"}],"type":"temporary"}"""
            val callback = LocalMediaDrmCallback(json.toByteArray(Charsets.UTF_8))
            DefaultDrmSessionManager.Builder()
                .setUuidAndExoMediaDrmProvider(C.CLEARKEY_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
                .setMultiSession(false)
                .build(callback)
        } catch (e: Exception) {
            Log.e("PlayerManager", "DRM setup failed: ${e.message}", e)
            null
        }
    }

    private fun hexToBase64Url(hex: String): String =
        Base64.encodeToString(hexToBytes(hex), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "Hex string must have even length" }
        return ByteArray(hex.length / 2) { i ->
            ((Character.digit(hex[i * 2], 16) shl 4) or Character.digit(hex[i * 2 + 1], 16)).toByte()
        }
    }
}
