package com.baruchiro.podcatcher.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Policy(
    var autoDownload: Boolean = false,
    var keep: Int = 3,
    var speed: Double = 1.0,
    var skipIntroSec: Int = 0,
    /** QUEUE-1: 1 plays first; podcasts sharing a number take turns. */
    var priority: Int = 3,
    /** QUEUE-1: order of unplayed episodes inside this podcast's turns. */
    var oldestFirst: Boolean = false
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
                        skipIntroSec = pol?.optInt("skipIntroSec", 0) ?: 0,
                        priority = pol?.optInt("priority", 3) ?: 3,
                        oldestFirst = pol?.optBoolean("oldestFirst") ?: false
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
                            .put("priority", p.policy.priority)
                            .put("oldestFirst", p.policy.oldestFirst)
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

    /** Default policy applied to new subscriptions (SET-1 global defaults). */
    fun defaultPolicy(ctx: Context): Policy {
        val o = JSONObject(prefs(ctx).getString("default_policy", "{}") ?: "{}")
        return Policy(
            autoDownload = o.optBoolean("autoDownload", false),
            keep = o.optInt("keep", 3),
            speed = o.optDouble("speed", 1.0),
            skipIntroSec = o.optInt("skipIntroSec", 0)
        )
    }

    fun setDefaultPolicy(ctx: Context, p: Policy) {
        prefs(ctx).edit().putString("default_policy", JSONObject()
            .put("autoDownload", p.autoDownload)
            .put("keep", p.keep)
            .put("speed", p.speed)
            .put("skipIntroSec", p.skipIntroSec)
            .toString()).apply()
    }

    /** Episodes already listened to or moved past, per feed (QUEUE-1 leaves them out of the queue). */
    fun playedGuids(ctx: Context, feedUrl: String): Set<String> {
        val arr = JSONObject(prefs(ctx).getString("played", "{}") ?: "{}").optJSONArray(feedUrl) ?: return emptySet()
        return (0 until arr.length()).map { arr.getString(it) }.toSet()
    }

    fun markPlayed(ctx: Context, feedUrl: String, guid: String) {
        val root = JSONObject(prefs(ctx).getString("played", "{}") ?: "{}")
        val arr = root.optJSONArray(feedUrl) ?: JSONArray().also { root.put(feedUrl, it) }
        if ((0 until arr.length()).none { arr.getString(it) == guid }) arr.put(guid)
        prefs(ctx).edit().putString("played", root.toString()).apply()
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

    /** Deletes one downloaded episode file and its mapping. False when it was not downloaded. */
    fun removeDownload(ctx: Context, feedUrl: String, key: String): Boolean {
        val root = downloadsRoot(ctx)
        val pod = root.optJSONObject(feedUrl) ?: return false
        val path = pod.optString(key).ifEmpty { return false }
        runCatching { File(path).delete() }
        pod.remove(key)
        prefs(ctx).edit().putString("downloads", root.toString()).apply()
        return true
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
