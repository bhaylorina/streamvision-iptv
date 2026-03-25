package com.streamvision.iptv.player

import android.content.Intent
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * A [MediaSessionService] that wraps [PlayerManager]'s persistent [ExoPlayer] in a
 * [MediaSession].  This keeps playback alive as a foreground service when the user
 * navigates away from the app, and shows a media notification with playback controls.
 *
 * The player itself is owned by [PlayerManager] and is never released here; this service
 * only manages the [MediaSession] wrapper and the foreground lifecycle.
 */
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    @Inject
    lateinit var playerManager: PlayerManager

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        mediaSession = MediaSession.Builder(this, playerManager.player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        // User swiped the app away — stop playback and dismiss the notification
        playerManager.stop()
        stopSelf()
    }

    override fun onDestroy() {
        // Release only the MediaSession wrapper, NOT the underlying player
        // (PlayerManager owns the player for the full app lifetime)
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }
}
