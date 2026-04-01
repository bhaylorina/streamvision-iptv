package com.streamvision.iptv.player

import android.content.Context
import android.content.Intent
import android.os.PowerManager
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

    private val wakeLock: PowerManager.WakeLock by lazy {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        powerManager.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE,
            "StreamVision::PlayerWakeLock"
        ).apply {
            setReferenceCounted(false)
        }
    }

    val player: ExoPlayer by lazy {
        ExoPlayer.Builder(context).build()
    }

    var currentChannel: Channel? = null
        private set

    fun addListener(listener: Player.Listener)    { player.addListener(listener) }
    fun removeListener(listener: Player.Listener) { player.removeListener(listener) }

    fun play(channel: Channel) {
        // PREVENT RESTARTING SEAMLESSLY IF SAME CHANNEL IS CLICKED
        if (currentChannel?.id == channel.id) {
            if (player.playbackState == Player.STATE_IDLE) {
                player.prepare()
            }
            player.playWhenReady = true
            acquireWakeLock()
            return 
        }

        currentChannel = channel

        httpFactory.setDefaultRequestProperties(buildHeaders(channel))

        val msf = DefaultMediaSourceFactory(context).setDataSourceFactory(httpFactory)
        
        if (!channel.drmKey.isNullOrBlank()) {
            val drmManager = buildClearKeyDrmSessionManager(channel.drmKey)
            if (drmManager != null) {
                msf.setDrmSessionManagerProvider { drmManager }
            }
        }

        val mediaItemBuilder = MediaItem.Builder().setUri(channel.url)
        
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
        
        acquireWakeLock()

        val intent = Intent(context, PlaybackService::class.java)
        try {
            // Media3's MediaSessionService automatically handles foreground state when playback starts.
            // Using startForegroundService here causes crashes (ForegroundServiceDidNotStartInTimeException)
            // if buffering takes longer than 5 seconds. A regular startService is safe because this method
            // is only triggered from the UI (when the app is already in the foreground).
            context.startService(intent)
        } catch (e: Exception) {
            Log.e("PlayerManager", "Failed to start PlaybackService", e)
        }
    }

    fun pause()  { 
        player.pause() 
        releaseWakeLock()
    }
    
    fun resume() { 
        player.play() 
        acquireWakeLock()
    }

    fun stop() {
        player.stop()
        player.clearMediaItems()
        currentChannel = null
        releaseWakeLock()
        context.stopService(Intent(context, PlaybackService::class.java))
    }

    val isPlaying: Boolean get() = player.isPlaying
    
    fun acquireWakeLock() {
        if (!wakeLock.isHeld) {
            wakeLock.acquire(10 * 60 * 60 * 1000L) // 10 hours max, will be released when stopped
            Log.d("PlayerManager", "WakeLock acquired")
        }
    }
    
    fun releaseWakeLock() {
        if (wakeLock.isHeld) {
            wakeLock.release()
            Log.d("PlayerManager", "WakeLock released")
        }
    }

    private fun buildHeaders(channel: Channel): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        if (!channel.userAgent.isNullOrBlank()) headers["User-Agent"] = channel.userAgent
        if (!channel.referer.isNullOrBlank())   headers["Referer"]   = channel.referer
        if (!channel.cookie.isNullOrBlank())    headers["Cookie"]    = channel.cookie
        return headers
    }

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
