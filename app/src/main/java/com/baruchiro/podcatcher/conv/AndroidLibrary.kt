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
}
