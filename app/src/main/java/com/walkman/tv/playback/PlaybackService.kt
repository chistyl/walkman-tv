package com.walkman.tv.playback

import androidx.media3.session.MediaSession
import android.content.Intent
import androidx.media3.session.MediaSessionService
import com.walkman.tv.App

/**
 * Publishes the shared ExoPlayer as a MediaSession so the TV remote / system media controls
 * (play/pause/next/prev) and background audio work. The player itself is owned by
 * [PlaybackController]; this service only wraps it in a session.
 */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        runCatching {
            val player = App.container.playbackController.player
            session = MediaSession.Builder(this, player).build()
        }.onFailure {
            android.util.Log.e("PlaybackService", "MediaSession init failed", it)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /**
     * Keep the foreground media service alive when the user leaves the app or removes its task.
     * Only stop it when playback is no longer active.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        val playbackActive = player != null &&
            player.playWhenReady &&
            player.playbackState != androidx.media3.common.Player.STATE_IDLE &&
            player.playbackState != androidx.media3.common.Player.STATE_ENDED
        if (playbackActive) {
            android.util.Log.d("PlaybackService", "Task removed; continuing background playback")
        } else {
            stopSelf()
        }
    }

    override fun onDestroy() {
        // Do not release the player here — it is app-owned by PlaybackController.
        session?.release()
        session = null
        runCatching { App.container.playbackController.onPlaybackServiceDestroyed() }
        super.onDestroy()
    }
}
