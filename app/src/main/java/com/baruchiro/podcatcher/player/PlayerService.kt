package com.baruchiro.podcatcher.player

import android.content.Intent
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.baruchiro.podcatcher.data.Store
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

// Hosts the single ExoPlayer so playback continues with the screen off and
// shows the standard media notification (play/pause/seek) automatically.
class PlayerService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(androidx.media3.common.AudioAttributes.DEFAULT, /* handleAudioFocus= */ true)
            .build()
        player.addListener(QueueListener(player))
        session = MediaSession.Builder(this, player).build()
    }

    /**
     * Sequential listening: an episode that finished counts as played (a skipped one stays unplayed), and
     * each new item starts with its own podcast's speed and intro skip.
     */
    private inner class QueueListener(private val player: ExoPlayer) : Player.Listener {
        private var currentId: String? = null

        private fun markPlayed(id: String?) {
            val (feed, guid) = id?.split('\n', limit = 2)?.takeIf { it.size == 2 } ?: return
            Store.markPlayed(this@PlayerService, feed, guid)
        }

        override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
            val finished = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
            val moved = finished || reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK
            // Only an episode that ran to its end counts as played; skipping past it leaves it unplayed.
            if (finished) markPlayed(currentId)
            currentId = item?.mediaId
            val feed = item?.mediaId?.substringBefore('\n') ?: return
            val policy = Store.podcasts(this@PlayerService).firstOrNull { it.feedUrl == feed }?.policy ?: return
            player.setPlaybackSpeed(policy.speed.toFloat())
            if (moved && policy.skipIntroSec > 0) player.seekTo(policy.skipIntroSec * 1000L)
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) markPlayed(currentId)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady) stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        session?.player?.release()
        session?.release()
        session = null
        super.onDestroy()
    }
}
