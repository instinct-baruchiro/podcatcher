package com.baruchiro.podcatcher.conv

import android.content.Context
import com.baruchiro.podcatcher.data.Policy
import com.baruchiro.podcatcher.data.Podcast
import com.baruchiro.podcatcher.data.Store
import com.baruchiro.podcatcher.net.Discovery

/** Binds the pure-Kotlin Tools executor to the app's real Store + Discovery. */
class AndroidLibrary(private val ctx: Context) : Library {

    private fun Pod.toData() = Podcast(feedUrl, title, "", "", Policy(autoDownload, keep, speed, skipIntroSec))
    private fun Podcast.toConv() = Pod(feedUrl, title, policy.autoDownload, policy.keep, policy.speed, policy.skipIntroSec)

    override fun list(): List<Pod> = Store.podcasts(ctx).map { it.toConv() }

    override fun findByName(name: String): Pod? =
        Store.podcasts(ctx).firstOrNull { it.title.contains(name, ignoreCase = true) }?.toConv()

    override fun get(feedUrl: String): Pod? =
        Store.podcasts(ctx).firstOrNull { it.feedUrl == feedUrl }?.toConv()

    override fun applyPolicy(feedUrl: String?, patch: PolicyPatch): Pod? {
        if (feedUrl == null) {
            val cur = Store.defaultPolicy(ctx)
            Store.setDefaultPolicy(ctx, Policy(
                autoDownload = patch.autoDownload ?: cur.autoDownload,
                keep = patch.keep ?: cur.keep,
                speed = patch.speed ?: cur.speed,
                skipIntroSec = patch.skipIntroSec ?: cur.skipIntroSec
            ))
            return null
        }
        val pod = Store.podcasts(ctx).firstOrNull { it.feedUrl == feedUrl } ?: return null
        val p = pod.policy
        Store.updatePolicy(ctx, feedUrl, Policy(
            autoDownload = patch.autoDownload ?: p.autoDownload,
            keep = patch.keep ?: p.keep,
            speed = patch.speed ?: p.speed,
            skipIntroSec = patch.skipIntroSec ?: p.skipIntroSec
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
}
