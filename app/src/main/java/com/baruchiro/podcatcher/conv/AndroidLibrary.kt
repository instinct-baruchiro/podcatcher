package com.baruchiro.podcatcher.conv

import android.content.Context
import com.baruchiro.podcatcher.data.Policy
import com.baruchiro.podcatcher.data.Podcast
import com.baruchiro.podcatcher.data.Store
import com.baruchiro.podcatcher.net.Discovery

/** Binds the pure-Kotlin Tools executor to the app's real Store + Discovery. */
class AndroidLibrary(private val ctx: Context) : Library {

    private fun Pod.toData() = Podcast(feedUrl, title, "", "", Policy(autoDownload, keep, speed, skipIntroSec))
    private fun Podcast.toConv() = Pod(feedUrl, title, policy.autoDownload, policy.keep, policy.speed, policy.skipIntroSec, policy.priority, policy.oldestFirst)

    override fun list(): List<Pod> = Store.podcasts(ctx).map { it.toConv() }

    override fun findByName(name: String): Pod? =
        Store.podcasts(ctx).firstOrNull { it.title.contains(name, ignoreCase = true) }?.toConv()

    override fun get(feedUrl: String): Pod? =
        Store.podcasts(ctx).firstOrNull { it.feedUrl == feedUrl }?.toConv()

    override fun applyPolicy(feedUrl: String?, patch: PolicyPatch): Pod? {
        if (feedUrl == null) {
            val cur = Store.defaultPolicy(ctx)
            Store.setDefaultPolicy(ctx, cur.copy(
                autoDownload = patch.autoDownload ?: cur.autoDownload,
                keep = patch.keep ?: cur.keep,
                speed = patch.speed ?: cur.speed,
                skipIntroSec = patch.skipIntroSec ?: cur.skipIntroSec
            ))
            return null
        }
        val pod = Store.podcasts(ctx).firstOrNull { it.feedUrl == feedUrl } ?: return null
        val p = pod.policy
        Store.updatePolicy(ctx, feedUrl, p.copy(
            autoDownload = patch.autoDownload ?: p.autoDownload,
            keep = patch.keep ?: p.keep,
            speed = patch.speed ?: p.speed,
            skipIntroSec = patch.skipIntroSec ?: p.skipIntroSec,
            priority = patch.priority ?: p.priority,
            oldestFirst = patch.oldestFirst ?: p.oldestFirst
        ))
        return pod.toConv()
    }

    override fun defaultPolicy(): PolicyPatch {
        val d = Store.defaultPolicy(ctx)
        return PolicyPatch(d.autoDownload, d.keep, d.speed, d.skipIntroSec)
    }

    override fun subscribeByUrl(feedUrl: String): String {
        val info = Discovery.fetchFeed(feedUrl, limit = 1)
        val added = Store.addPodcast(ctx, Podcast(feedUrl, info.title, info.author, info.artwork, Store.defaultPolicy(ctx)))
        return if (added) info.title else info.title + " (כבר במנויים)"
    }

    override fun searchByName(name: String): List<Pair<String, String>> =
        Discovery.search(name).take(5).map { it.title to it.feedUrl }

    override fun startImport(): Boolean {
        val main = ctx as? com.baruchiro.podcatcher.ui.MainActivity ?: return false
        main.runOnUiThread { main.showImportHelp() }
        return true
    }

    private val main get() = ctx as? com.baruchiro.podcatcher.ui.MainActivity

    override fun removePodcast(feedUrl: String): Boolean {
        if (Store.podcasts(ctx).none { it.feedUrl == feedUrl }) return false
        Store.removePodcast(ctx, feedUrl)
        main?.let { m -> m.runOnUiThread { m.refreshLibrary() } }
        return true
    }

    override fun openScreen(screen: String, feedUrl: String?): Boolean {
        val m = main ?: return false
        val intent = when (screen) {
            "queue" -> android.content.Intent(m, com.baruchiro.podcatcher.ui.QueueActivity::class.java)
            "about" -> android.content.Intent(m, com.baruchiro.podcatcher.ui.AboutActivity::class.java)
            "podcast" -> android.content.Intent(m, com.baruchiro.podcatcher.ui.PodcastActivity::class.java).putExtra("feedUrl", feedUrl)
            else -> return false
        }
        m.runOnUiThread { m.startActivity(intent) }
        return true
    }

    override fun playback(action: String): Boolean = main?.playerCommand(action) ?: false

    override fun setAnalytics(enabled: Boolean): Boolean {
        com.baruchiro.podcatcher.telemetry.Analytics.get(ctx).enabled = enabled
        return true
    }

    private fun episode(feedUrl: String, n: Int) =
        runCatching { Discovery.fetchFeed(feedUrl, 50).episodes.getOrNull(n - 1) }.getOrNull()

    override fun episodes(feedUrl: String, limit: Int): List<EpRow>? {
        val eps = runCatching { Discovery.fetchFeed(feedUrl, 50).episodes }.getOrNull() ?: return null
        val played = Store.playedGuids(ctx, feedUrl)
        return eps.take(limit).mapIndexed { i, e ->
            EpRow(i + 1, e.title, e.date, Store.downloadedPath(ctx, feedUrl, e.guid) != null, e.guid in played)
        }
    }

    private fun mediaItem(feedUrl: String, podTitle: String, e: com.baruchiro.podcatcher.net.Episode): androidx.media3.common.MediaItem {
        val local = Store.downloadedPath(ctx, feedUrl, e.guid)
        return androidx.media3.common.MediaItem.Builder()
            .setUri(local?.let { android.net.Uri.fromFile(java.io.File(it)) } ?: android.net.Uri.parse(e.audioUrl))
            .setMediaId(feedUrl + "\n" + e.guid)
            .setMediaMetadata(androidx.media3.common.MediaMetadata.Builder().setTitle(e.title).setArtist(podTitle).build())
            .build()
    }

    override fun playEpisode(feedUrl: String, n: Int): Boolean {
        val pod = Store.podcasts(ctx).firstOrNull { it.feedUrl == feedUrl } ?: return false
        val e = episode(feedUrl, n) ?: return false
        return main?.playItems(listOf(mediaItem(feedUrl, pod.title, e)), 0, pod.policy.skipIntroSec * 1000L, pod.policy.speed.toFloat()) ?: false
    }

    override fun downloadEpisode(feedUrl: String, n: Int): Boolean {
        val e = episode(feedUrl, n) ?: return false
        if (Store.downloadedPath(ctx, feedUrl, e.guid) != null) return false
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
        val dest = java.io.File(ctx.getExternalFilesDir(android.os.Environment.DIRECTORY_PODCASTS),
            e.guid.filter { it.isLetterOrDigit() }.take(48) + ".mp3")
        dm.enqueue(
            android.app.DownloadManager.Request(android.net.Uri.parse(e.audioUrl))
                .setTitle(e.title)
                .setDestinationUri(android.net.Uri.fromFile(dest))
                .setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        )
        Store.setDownloaded(ctx, feedUrl, e.guid, dest.absolutePath)
        return true
    }

    override fun deleteDownload(feedUrl: String, n: Int): Boolean {
        val e = episode(feedUrl, n) ?: return false
        return Store.removeDownload(ctx, feedUrl, e.guid)
    }

    override fun markPlayed(feedUrl: String, n: Int): Boolean {
        val e = episode(feedUrl, n) ?: return false
        if (e.guid in Store.playedGuids(ctx, feedUrl)) return false
        Store.markPlayed(ctx, feedUrl, e.guid)
        return true
    }

    override fun playQueue(): Boolean {
        val pods = Store.podcasts(ctx)
        val feeds = pods.associate { p -> p.feedUrl to (runCatching { Discovery.fetchFeed(p.feedUrl).episodes }.getOrNull() ?: emptyList()) }
        val sources = pods.map { p ->
            val played = Store.playedGuids(ctx, p.feedUrl)
            val unplayed = feeds[p.feedUrl].orEmpty().filter { it.guid !in played }.take(15)
            com.baruchiro.podcatcher.data.QueueSource(p.feedUrl, p.policy.priority, (if (p.policy.oldestFirst) unplayed.reversed() else unplayed).map { it.guid })
        }
        val queue = com.baruchiro.podcatcher.data.buildQueue(sources)
        val items = queue.mapNotNull { q ->
            val e = feeds[q.podcastId]?.firstOrNull { it.guid == q.episodeId } ?: return@mapNotNull null
            mediaItem(q.podcastId, pods.first { it.feedUrl == q.podcastId }.title, e)
        }
        val first = pods.firstOrNull { it.feedUrl == queue.firstOrNull()?.podcastId }?.policy ?: return false
        return main?.playItems(items, 0, first.skipIntroSec * 1000L, first.speed.toFloat()) ?: false
    }

    override fun seek(seconds: Int, relative: Boolean): Boolean = main?.seekPlayer(seconds, relative) ?: false
}
