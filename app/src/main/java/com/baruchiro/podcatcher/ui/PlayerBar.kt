package com.baruchiro.podcatcher.ui

import android.app.Activity
import android.content.ComponentName
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.baruchiro.podcatcher.R
import com.baruchiro.podcatcher.player.PlayerService
import com.google.common.util.concurrent.ListenableFuture
import java.util.Locale
import java.util.concurrent.Executor

/**
 * PLAY-4: a media player that is always part of the app — a fixed bar at the
 * bottom of the listening screens (library, episodes, queue), above the
 * conversation composer, present even before anything plays. It mirrors the
 * shared PlayerService session: current episode and podcast, play/pause,
 * elapsed/total time and progress. The host activity calls [release] from
 * onDestroy.
 */
class PlayerBar(private val activity: Activity, host: ViewGroup) {

    private val handler = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { r -> handler.post(r) }
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    private val toggle: ImageButton
    private val title: TextView
    private val time: TextView
    private val progress: ProgressBar

    private fun dp(v: Int): Int = (v * activity.resources.displayMetrics.density).toInt()

    private val ticker = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, 500)
        }
    }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = update()
        override fun onMediaItemTransition(item: MediaItem?, reason: Int) = update()
        override fun onPlaybackStateChanged(playbackState: Int) = update()
        override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) = update()
    }

    init {
        val bar = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            background = activity.getDrawable(R.drawable.bg_bottom_bar)
        }
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(6))
        }
        toggle = ImageButton(activity, null, 0, R.style.PodIcon_Primary).apply {
            id = R.id.playerToggle
            setImageResource(R.drawable.ic_play)
            contentDescription = "ניגון"
            alpha = 0.4f
            setOnClickListener { onToggle() }
        }
        title = TextView(activity).apply {
            id = R.id.playerTitle
            text = "עוד לא מתנגן כלום"
            textSize = 15f
            setTextColor(activity.getColor(R.color.textPrimary))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        time = TextView(activity).apply {
            id = R.id.playerTime
            textSize = 12f
            setTextColor(activity.getColor(R.color.textSecondary))
        }
        val texts = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(title)
            addView(time)
        }
        row.addView(toggle, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(10) })
        row.addView(texts, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        bar.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            id = R.id.playerProgress
            max = 1000
            progress = 0
        }
        bar.addView(progress, LinearLayout.LayoutParams(MATCH_PARENT, dp(3)))
        host.addView(bar, ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        connect()
    }

    private fun connect() {
        val token = SessionToken(activity, ComponentName(activity, PlayerService::class.java))
        val future = MediaController.Builder(activity, token).buildAsync()
        controllerFuture = future
        future.addListener({
            try {
                val c = future.get()
                controller = c
                c.addListener(listener)
                update()
            } catch (e: Exception) {
                // No session: the bar stays in its idle state.
            }
        }, mainExecutor)
    }

    fun release() {
        handler.removeCallbacks(ticker)
        controller?.removeListener(listener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controller = null
    }

    private fun onToggle() {
        val c = controller ?: return
        if (c.currentMediaItem == null) return
        if (c.isPlaying) c.pause() else c.play()
    }

    private fun startTicker() {
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    private fun update() {
        val c = controller ?: return
        val item = c.currentMediaItem
        if (item == null) {
            title.text = "עוד לא מתנגן כלום"
            time.text = ""
            progress.progress = 0
            toggle.alpha = 0.4f
            toggle.setImageResource(R.drawable.ic_play)
            toggle.contentDescription = "ניגון"
            handler.removeCallbacks(ticker)
            return
        }
        val meta = item.mediaMetadata
        title.text = listOfNotNull(meta.title, meta.artist)
            .map { it.toString() }
            .filter { it.isNotBlank() }
            .joinToString(" · ")
            .ifBlank { "מתנגן" }
        toggle.alpha = 1f
        val playing = c.isPlaying
        toggle.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        toggle.contentDescription = if (playing) "השהיה" else "ניגון"
        if (playing) startTicker() else {
            handler.removeCallbacks(ticker)
            updateProgress()
        }
    }

    private fun updateProgress() {
        val c = controller ?: return
        if (c.currentMediaItem == null) return
        val dur = c.duration
        val pos = c.currentPosition.coerceAtLeast(0)
        if (dur != C.TIME_UNSET && dur > 0) {
            progress.progress = (pos * 1000L / dur).toInt().coerceIn(0, 1000)
            time.text = fmt(pos) + " / " + fmt(dur)
        } else {
            progress.progress = 0
            time.text = fmt(pos)
        }
    }

    private fun fmt(ms: Long): String {
        val totalSec = (ms / 1000).coerceAtLeast(0)
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }
}
