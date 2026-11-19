package com.baruchiro.podcatcher.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Policy(
    var autoDownload: Boolean = false,
    var keep: Int = 3,
    var speed: Double = 1.0,
    var skipIntroSec: Int = 0
)

data class Podcast(
    var feedUrl: String,
    var title: String,
    var author: String,
    var artwork: String,
    var policy: Policy = Policy(),
    var lastSeenGuid: String = ""
)

object Store {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("podcatcher", Context.MODE_PRIVATE)

    fun podcasts(ctx: Context): MutableList<Podcast> {
        val arr = JSONArray(prefs(ctx).getString("library", "[]") ?: "[]")
        val out = mutableListOf<Podcast>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val pol = o.optJSONObject("policy")
            out.add(
                Podcast(
                    feedUrl = o.getString("feedUrl"),
                    title = o.optString("title"),
                    author = o.optString("author"),
                    artwork = o.optString("artwork"),
                    policy = Policy(
                        autoDownload = pol?.optBoolean("autoDownload") ?: false,
                        keep = pol?.optInt("keep", 3) ?: 3,
                        speed = pol?.optDouble("speed", 1.0) ?: 1.0,
                        skipIntroSec = pol?.optInt("skipIntroSec", 0) ?: 0
                    ),
                    lastSeenGuid = o.optString("lastSeenGuid")
                )
            )
        }
        return out
    }

    fun savePodcasts(ctx: Context, list: List<Podcast>) {
        val arr = JSONArray()
        for (p in list) {
            arr.put(
                JSONObject()
                    .put("feedUrl", p.feedUrl)
                    .put("title", p.title)
                    .put("author", p.author)
                    .put("artwork", p.artwork)
                    .put(
                        "policy", JSONObject()
                            .put("autoDownload", p.policy.autoDownload)
                            .put("keep", p.policy.keep)
                            .put("speed", p.policy.speed)
                            .put("skipIntroSec", p.policy.skipIntroSec)
                    )
                    .put("lastSeenGuid", p.lastSeenGuid)
            )
        }
        prefs(ctx).edit().putString("library", arr.toString()).apply()
    }

    fun addPodcast(ctx: Context, p: Podcast): Boolean {
        val list = podcasts(ctx)
        if (list.any { it.feedUrl == p.feedUrl }) return false
        list.add(p)
        savePodcasts(ctx, list)
        return true
    }

    fun removePodcast(ctx: Context, feedUrl: String) {
        savePodcasts(ctx, podcasts(ctx).filterNot { it.feedUrl == feedUrl })
    }

    fun updatePolicy(ctx: Context, feedUrl: String, policy: Policy) {
        val list = podcasts(ctx)
        list.find { it.feedUrl == feedUrl }?.policy = policy
        savePodcasts(ctx, list)
    }

    fun markSeen(ctx: Context, feedUrl: String, guid: String) {
        val list = podcasts(ctx)
        list.find { it.feedUrl == feedUrl }?.lastSeenGuid = guid
        savePodcasts(ctx, list)
    }

    private fun downloadsRoot(ctx: Context) = JSONObject(prefs(ctx).getString("downloads", "{}") ?: "{}")

    fun setDownloaded(ctx: Context, feedUrl: String, key: String, path: String) {
        val root = downloadsRoot(ctx)
        val pod = root.optJSONObject(feedUrl) ?: JSONObject().also { root.put(feedUrl, it) }
        pod.put(key, path)
        prefs(ctx).edit().putString("downloads", root.toString()).apply()
    }

    fun downloadedPath(ctx: Context, feedUrl: String, key: String): String? {
        val p = downloadsRoot(ctx).optJSONObject(feedUrl)?.optString(key) ?: return null
        if (p.isEmpty()) return null
        val f = File(p)
        return if (f.exists() && f.length() > 0) p else null
    }

    // Enforces the keep-N policy: deletes files and mappings for episodes that
    // are no longer within the podcast's newest keepGuids.
    fun pruneDownloads(ctx: Context, feedUrl: String, keepGuids: Set<String>) {
        val root = downloadsRoot(ctx)
        val pod = root.optJSONObject(feedUrl) ?: return
        val stale = pod.keys().asSequence().filterNot { it in keepGuids }.toList()
        if (stale.isEmpty()) return
        for (k in stale) {
            runCatching { File(pod.getString(k)).delete() }
            pod.remove(k)
        }
        prefs(ctx).edit().putString("downloads", root.toString()).apply()
    }
}
