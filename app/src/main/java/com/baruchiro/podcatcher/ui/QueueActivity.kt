package com.baruchiro.podcatcher.ui

import android.app.Activity
import android.content.ComponentName
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.baruchiro.podcatcher.R
import com.baruchiro.podcatcher.data.Podcast
import com.baruchiro.podcatcher.data.QueueItem
import com.baruchiro.podcatcher.data.QueueSource
import com.baruchiro.podcatcher.data.Store
import com.baruchiro.podcatcher.data.buildQueue
import com.baruchiro.podcatcher.net.Discovery
import com.baruchiro.podcatcher.net.Episode
import com.baruchiro.podcatcher.player.PlayerService
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * QUEUE-1 screen: shows the fixed rules (podcast priorities, 1 plays first, equals take turns),
 * lets the listener move a podcast up or down, and plays the resulting queue in order.
 */
class QueueActivity : Activity() {
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var body: LinearLayout
    private lateinit var status: TextView
    private var controller: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
    private val mainExecutor = Executor { r -> android.os.Handler(android.os.Looper.getMainLooper()).post(r) }

    private var episodes = mapOf<String, List<Episode>>()
    private var queue = listOf<QueueItem>()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = LinearLayout.LAYOUT_DIRECTION_RTL
            setPadding(dp(20), dp(20), dp(20), dp(12))
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply {
            text = "תור ההאזנה"; setTextAppearance(R.style.PodTitle)
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        header.addView(icon(R.drawable.ic_play, "ניגון התור", primary = true) { play(0) }
            .apply { id = R.id.queuePlay }, LinearLayout.LayoutParams(dp(48), dp(48)))
        root.addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(12) })
        status = TextView(this).apply { setTextColor(getColor(R.color.textSecondary)); textSize = 14f }
        root.addView(status)
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        setContentView(root)
        loadEpisodes()
    }

    override fun onDestroy() {
        controller?.let { MediaController.releaseFuture(it) }
        super.onDestroy()
    }

    private fun icon(res: Int, label: String, primary: Boolean = false, onClick: () -> Unit) = ImageButton(
        this, null, 0, if (primary) R.style.PodIcon_Primary else R.style.PodIcon
    ).apply { setImageResource(res); contentDescription = label; setOnClickListener { onClick() } }

    private fun loadEpisodes() {
        status.text = "טוען פרקים…"
        io.execute {
            val pods = Store.podcasts(this)
            val loaded = pods.associate { p ->
                p.feedUrl to runCatching { Discovery.fetchFeed(p.feedUrl).episodes }.getOrDefault(emptyList())
            }
            runOnUiThread { episodes = loaded; status.text = ""; render() }
        }
    }

    private fun sources(pods: List<Podcast>) = pods.map { p ->
        val played = Store.playedGuids(this, p.feedUrl)
        val unplayed = episodes[p.feedUrl].orEmpty().filter { it.guid !in played }.take(UNPLAYED_PER_PODCAST)
        QueueSource(p.feedUrl, p.policy.priority, (if (p.policy.oldestFirst) unplayed.reversed() else unplayed).map { it.guid })
    }

    private fun render() {
        val pods = Store.podcasts(this)
        queue = buildQueue(sources(pods))
        body.removeAllViews()
        body.addView(section("עדיפויות (1 מנגן ראשון, שוות מתחלפות)"))
        pods.sortedBy { it.policy.priority }.forEach { p -> body.addView(podcastRow(p)) }
        body.addView(section("הפרקים הבאים"))
        if (queue.isEmpty()) body.addView(label("אין פרקים שלא הושמעו."))
        queue.take(SHOWN).forEachIndexed { i, item ->
            val ep = episodes[item.podcastId]?.firstOrNull { it.guid == item.episodeId } ?: return@forEachIndexed
            val owner = pods.first { it.feedUrl == item.podcastId }
            body.addView(queueRow(i, ep, owner))
        }
    }

    private fun section(t: String) = TextView(this).apply {
        text = t; setTextAppearance(R.style.PodSection); setPadding(0, dp(14), 0, dp(6))
    }

    private fun label(t: String) = TextView(this).apply { text = t; setTextColor(getColor(R.color.textSecondary)) }

    private fun podcastRow(p: Podcast) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        background = getDrawable(R.drawable.bg_card_static)
        setPadding(dp(14), dp(8), dp(10), dp(8))
        addView(TextView(context).apply {
            text = "${p.title}\nעדיפות ${p.policy.priority}"
            setTextColor(getColor(R.color.textPrimary)); textSize = 15f
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addView(icon(R.drawable.ic_up, "העלאת עדיפות ל${p.title}") { setPriority(p, p.policy.priority - 1) },
            LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(8) })
        addView(icon(R.drawable.ic_down, "הורדת עדיפות ל${p.title}") { setPriority(p, p.policy.priority + 1) },
            LinearLayout.LayoutParams(dp(40), dp(40)))
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(8) }
    }

    private fun queueRow(index: Int, ep: Episode, owner: Podcast) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(4), dp(6), dp(4), dp(6))
        addView(TextView(context).apply {
            text = "${index + 1}. ${ep.title}\n${owner.title}"
            setTextColor(getColor(R.color.textPrimary)); textSize = 14f; maxLines = 3
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addView(icon(R.drawable.ic_play, "ניגון מכאן") { play(index) }, LinearLayout.LayoutParams(dp(40), dp(40)))
    }

    private fun setPriority(p: Podcast, value: Int) {
        p.policy.priority = value.coerceIn(1, 5)
        Store.updatePolicy(this, p.feedUrl, p.policy)
        render()
    }

    private fun play(from: Int) {
        if (queue.isEmpty()) { status.text = "אין מה לנגן."; return }
        val pods = Store.podcasts(this)
        val items = queue.mapNotNull { q ->
            val ep = episodes[q.podcastId]?.firstOrNull { it.guid == q.episodeId } ?: return@mapNotNull null
            val owner = pods.firstOrNull { it.feedUrl == q.podcastId } ?: return@mapNotNull null
            val local = Store.downloadedPath(this, q.podcastId, ep.guid)
            MediaItem.Builder()
                .setMediaId(q.podcastId + "\n" + ep.guid)
                .setUri(local?.let { Uri.fromFile(File(it)) } ?: Uri.parse(ep.audioUrl))
                .setMediaMetadata(MediaMetadata.Builder().setTitle(ep.title).setArtist(owner.title).build())
                .build()
        }
        controller?.let { MediaController.releaseFuture(it) }
        val future = MediaController.Builder(this, SessionToken(this, ComponentName(this, PlayerService::class.java))).buildAsync()
        controller = future
        future.addListener({
            try {
                val c = future.get()
                val first = pods.first { it.feedUrl == queue[from].podcastId }.policy
                c.setMediaItems(items, from, first.skipIntroSec * 1000L)
                c.setPlaybackSpeed(first.speed.toFloat())
                c.prepare(); c.play()
                com.baruchiro.podcatcher.telemetry.Analytics.get(this).capture("podcatcher_queue_played", mapOf("items" to items.size))
                status.text = "מנגן מהתור"
            } catch (e: Exception) {
                status.text = "הניגון נכשל."
            }
        }, mainExecutor)
    }

    private companion object {
        const val UNPLAYED_PER_PODCAST = 15
        const val SHOWN = 20
    }
}
