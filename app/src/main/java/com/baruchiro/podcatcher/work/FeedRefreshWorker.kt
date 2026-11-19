package com.baruchiro.podcatcher.work

import android.app.DownloadManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.baruchiro.podcatcher.data.Store
import com.baruchiro.podcatcher.net.Discovery
import java.io.File
import java.util.concurrent.TimeUnit

// Hourly (subject to OS battery scheduling): for each podcast whose policy asks
// for automatic downloads, fetch the feed, download unseen episodes up to the
// policy count, then post one summary notification.
class FeedRefreshWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        val ctx = applicationContext
        var newCount = 0
        for (p in Store.podcasts(ctx)) {
            if (!p.policy.autoDownload) continue
            try {
                val feed = Discovery.fetchFeed(p.feedUrl, p.policy.keep)
                val fresh = feed.episodes.takeWhile { it.guid != p.lastSeenGuid }
                if (fresh.isEmpty()) continue
                val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_PODCASTS) ?: continue
                for (ep in fresh.take(p.policy.keep)) {
                    if (Store.downloadedPath(ctx, ep.guid) != null) continue
                    val dest = File(dir, ep.guid.filter { it.isLetterOrDigit() }.take(48) + ".mp3")
                    dm.enqueue(
                        DownloadManager.Request(Uri.parse(ep.audioUrl))
                            .setTitle(ep.title)
                            .setDestinationUri(Uri.fromFile(dest))
                            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    )
                    Store.setDownloaded(ctx, ep.guid, dest.absolutePath)
                    newCount++
                }
                Store.markSeen(ctx, p.feedUrl, feed.episodes.first().guid)
            } catch (_: Exception) {
                // A failing feed is retried on the next cycle.
            }
        }
        if (newCount > 0) postSummary(ctx, newCount)
        return Result.success()
    }

    private fun postSummary(ctx: Context, count: Int) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel("new_episodes", "פרקים חדשים", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val n = Notification.Builder(ctx, "new_episodes")
            .setContentTitle("פודקאסטים")
            .setContentText("הורדו $count פרקים חדשים")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .build()
        nm.notify(1, n)
    }

    companion object {
        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<FeedRefreshWorker>(1, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()
            WorkManager.getInstance(ctx)
                .enqueueUniquePeriodicWork("feed_refresh", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
