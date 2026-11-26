// @story: SUB-1, LIB-1, PLAY-1, PLAY-2, PLAY-4, DL-1, DL-2, QUEUE-1, REL-1, CONV-3
package com.baruchiro.podcatcher

import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.File
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end flow on a real emulator/device, capturing a screenshot at each
 * stage into the app-specific external files dir (pulled by CI as an
 * artifact). Uses the live "עושים היסטוריה" feed, so it requires network.
 */
@RunWith(AndroidJUnit4::class)
class ScreenshotFlowTest {

    private val pkg = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.packageName
    private val feedUrl = "https://www.ranlevi.com/feed/mh_network_feed"
    private lateinit var device: UiDevice
    private lateinit var appContext: android.content.Context
    private lateinit var shotsDir: File

    @Before
    fun setUp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        device = UiDevice.getInstance(instrumentation)
        // Screenshots land app-private, then get published to Downloads via
        // MediaStore so CI can adb pull them (shell cannot see Android/data).
        appContext = instrumentation.targetContext
        shotsDir = File(appContext.getExternalFilesDir(null), "shots")
        shotsDir.mkdirs()
    }

    private fun shot(name: String) {
        val out = File(shotsDir, "$name.png")
        assertTrue("screenshot $name failed", device.takeScreenshot(out))
        publish(out)
    }

    /** On download timeout: DownloadManager status/reason/progress + file listing as artifact evidence. */
    private fun dumpDownloadState() {
        val sb = StringBuilder()
        val dir = appContext.getExternalFilesDir(Environment.DIRECTORY_PODCASTS)
        sb.append("files: ").append(dir?.listFiles()?.joinToString { "${it.name} ${it.length()}B" } ?: "none").append('\n')
        val dm = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val c = dm.query(DownloadManager.Query())
        while (c.moveToNext()) {
            val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
            val soFar = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            val uri = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_URI))
            sb.append("status=$status reason=$reason soFar=$soFar total=$total uri=$uri\n")
        }
        c.close()
        File(shotsDir, "download-status.txt").also { it.writeText(sb.toString()); publish(it) }
        shot("fail-download")
    }

    private fun publish(f: File) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, f.name)
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/podcatcher-shots")
            put(MediaStore.Downloads.MIME_TYPE, if (f.name.endsWith(".png")) "image/png" else "text/plain")
        }
        val uri = appContext.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        assertNotNull("MediaStore insert failed for ${f.name}", uri)
        appContext.contentResolver.openOutputStream(uri!!)!!.use { it.write(f.readBytes()) }
    }

    private fun res(id: String) = By.res(pkg, id)

    /** STATE_PLAYING in dumpsys media_session: prints as state=PLAYING(3) on API 34+, state=3 on older. */
    private fun mediaSessionDump(): String = device.executeShellCommand("dumpsys media_session")

    private fun isPlaying(dump: String): Boolean =
        dump.contains(Regex("state=PlaybackState\\s*\\{[^}]*state=(?:PLAYING\\(3\\)|3)(?=[,}\\s])"))

    /** Polls playback state; on timeout writes dumpsys + logcat evidence into the shots dir. */
    private fun awaitPlaying(seconds: Int, tag: String): Boolean {
        var dump = ""
        for (i in 1..seconds) {
            Thread.sleep(1_000)
            dump = mediaSessionDump()
            if (isPlaying(dump)) return true
        }
        File(shotsDir, "dumpsys-media-session-$tag.txt").also { it.writeText(dump); publish(it) }
        val logcat = device.executeShellCommand("logcat -d -t 400")
            .lines()
            .filter { it.contains("podcatcher", true) || it.contains("ExoPlayer", true) || it.contains("Media3", true) || it.contains("MediaSession", true) }
            .joinToString("\n")
        File(shotsDir, "logcat-$tag.txt").also { it.writeText(logcat); publish(it) }
        shot("fail-$tag")
        return false
    }

    @Test
    fun subscribePlayAndDownload() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // QueueFlow runs earlier and leaves a loaded session in the app process;
        // clear it through a real controller (never force-stop under instrumentation)
        // so the launch below starts from the same state a fresh install shows.
        // A MediaController answers only on the thread whose looper built it, so the
        // build and every call happen on one dedicated HandlerThread.
        val mediaThread = android.os.HandlerThread("screenshot-flow-media").apply { start() }
        val mediaHandler = android.os.Handler(mediaThread.looper)
        val cleared = java.util.concurrent.CountDownLatch(1)
        mediaHandler.post {
            val token = androidx.media3.session.SessionToken(context,
                android.content.ComponentName(context, com.baruchiro.podcatcher.player.PlayerService::class.java))
            val future = androidx.media3.session.MediaController.Builder(context, token).buildAsync()
            future.addListener({
                try {
                    val c = future.get()
                    c.stop()
                    c.clearMediaItems()
                } catch (e: Exception) { /* no session to clear: already idle */ }
                androidx.media3.session.MediaController.releaseFuture(future)
                cleared.countDown()
            }, { r -> mediaHandler.post(r) })
        }
        cleared.await(20, java.util.concurrent.TimeUnit.SECONDS)
        mediaThread.quitSafely()
        // Launch the app.
        val launch = context.packageManager.getLaunchIntentForPackage(pkg)!!
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(launch)
        device.wait(Until.hasObject(By.pkg(pkg).depth(0)), 10_000)

        // POST_NOTIFICATIONS dialog on API 33+ — allow so the media
        // notification can appear.
        device.wait(Until.hasObject(By.res("com.android.permissioncontroller:id/permission_allow_button")), 3_000)
        device.findObject(By.res("com.android.permissioncontroller:id/permission_allow_button"))?.click()

        device.wait(Until.hasObject(res("intake")), 10_000)
        shot("01-library-empty")

        // PLAY-4: the player bar is part of the app from launch, idle before anything plays.
        assertTrue("player bar missing at launch", device.hasObject(res("playerToggle")))
        assertTrue("player bar should be idle before anything plays",
            device.hasObject(By.textContains("עוד לא מתנגן")))
        // CONV-3: the conversation stays closed and quiet at launch — no default-state messages.
        assertTrue("conversation must not speak at launch",
            !device.hasObject(By.textContains("ברירת מחדל לפודקאסט חדש")))
        shot("01a-player-idle")

        // REL-1: the header info icon opens the About screen with the creator credit.
        device.findObject(res("aboutOpen")).click()
        assertTrue("about screen did not open", device.wait(Until.hasObject(By.textContains("ברוך אודם")), 5_000))
        assertTrue("analytics switch missing", device.hasObject(res("analyticsSwitch")))
        assertTrue("analytics is on by default (owner decision 2026-11-20)", device.findObject(res("analyticsSwitch")).isChecked)
        assertTrue("but nothing is recorded as chosen before the first-run chat",
            !appContext.getSharedPreferences("telemetry", Context.MODE_PRIVATE).contains("analytics_enabled"))
        shot("01b-about")
        device.pressBack()
        device.wait(Until.hasObject(res("intake")), 5_000)

        // Conversation engine settings: the key is typed on the device, never bundled.
        device.findObject(By.desc("פתיחת השיחה")).click()
        device.findObject(By.desc("הגדרות מנוע השיחה")).click()
        assertTrue("settings dialog did not open", device.wait(Until.hasObject(By.text("מנוע שיחה")), 5_000))
        shot("01c-conversation-settings")
        device.pressBack()
        device.wait(Until.hasObject(res("intake")), 5_000)

        // Subscribe by pasting the feed link.
        device.findObject(res("intake")).text = feedUrl
        device.findObject(res("go")).click()

        // The library row appears once the feed is fetched (live network).
        val list = device.wait(Until.findObject(res("podcasts")), 10_000)
        assertNotNull("library list missing", list)
        val added = device.wait(Until.hasObject(By.res(pkg, "art")), 90_000)
        assertTrue("podcast did not appear in the library", added)
        // Artwork loads asynchronously; let the tile settle before the shot.
        Thread.sleep(3_000)
        shot("02-library-subscribed")

        // The conversation is a composer at the bottom of the library; focusing it
        // opens the drawer above the keyboard without covering the composer, and
        // Back (keyboard first, then drawer) returns to the collapsed bar.
        device.findObject(res("convInput")).click()
        assertTrue("drawer did not open above the keyboard", device.wait(Until.hasObject(By.desc("מגירת השיחה")), 3_000))
        Thread.sleep(1_000)
        shot("02b-conversation-keyboard")
        val displayHeight = device.displayHeight
        assertTrue("composer hidden by keyboard",
            device.findObject(res("convSend")).visibleBounds.bottom < displayHeight)
        device.pressBack()
        device.pressBack()
        assertTrue("library not back after closing drawer", device.wait(Until.hasObject(res("podcasts")), 3_000))
        shot("02c-conversation-collapsed")

        // Open the podcast; episode rows carry a play button.
        device.findObjects(By.res(pkg, "title")).first().click()
        val hasPlay = device.wait(Until.hasObject(res("play")), 90_000)
        assertTrue("episode list did not load", hasPlay)
        shot("03-episodes")

        // Play the first episode and confirm real playback state.
        device.findObjects(res("play")).first().click()
        val playing = awaitPlaying(45, "stream")
        shot("04-playing")

        // PLAY-4: the persistent player bar follows the session here on the episodes screen too.
        val barTitle = device.wait(Until.findObject(res("playerTitle")), 5_000)
        assertNotNull("player bar missing while playing", barTitle)
        assertTrue("player bar should name the playing episode, not the idle state",
            barTitle.text.isNotBlank() && !barTitle.text.contains("עוד לא מתנגן"))
        device.findObject(res("playerToggle")).click()
        Thread.sleep(2_000)
        assertTrue("player bar pause did not pause the session", !isPlaying(mediaSessionDump()))
        shot("04c-player-paused")
        device.findObject(res("playerToggle")).click()
        assertTrue("player bar resume did not restart the session", awaitPlaying(20, "resume"))
        device.findObject(By.desc("פתיחת השיחה")).click()
        device.findObject(res("convInput")).click()
        Thread.sleep(1_000)
        shot("04b-conversation-while-playing")
        device.pressBack()
        device.pressBack()
        assertTrue("player never reached STATE_PLAYING", playing)

        // The media notification should be present in the shade.
        device.openNotification()
        Thread.sleep(1_500)
        shot("05-notification-shade")
        device.pressBack()

        // Download the first episode; the row flips to "במכשיר" only after the
        // file is actually on disk (guarded by the download-complete receiver).
        device.findObjects(res("download")).first().click()
        var onDevice = false
        for (i in 1..180) {
            Thread.sleep(1_000)
            // The row is rebound while the download progresses; a stale handle just means "look again".
            val done = try {
                device.findObjects(res("download")).firstOrNull()?.contentDescription == "במכשיר"
            } catch (e: androidx.test.uiautomator.StaleObjectException) { false }
            if (done) { onDevice = true; break }
        }
        if (!onDevice) dumpDownloadState()
        assertTrue("download never completed on device", onDevice)
        shot("06-downloaded")

        // Play again now that the file is local; playback must still work.
        device.findObjects(res("play")).first().click()
        val localPlaying = awaitPlaying(45, "local")
        shot("07-playing-downloaded")
        assertTrue("local file playback never started", localPlaying)

        // QUEUE-1: the queue screen plays the podcast's unplayed episodes one after another.
        device.pressBack()
        device.wait(Until.hasObject(res("queueOpen")), 5_000)
        device.findObject(res("queueOpen")).click()
        assertTrue("queue screen did not open", device.wait(Until.hasObject(By.text("תור ההאזנה")), 5_000))
        assertTrue("queue has no episodes", device.wait(Until.hasObject(By.textStartsWith("1. ")), 60_000))
        shot("08-queue")
        device.findObject(res("queuePlay")).click()
        assertTrue("queue playback never started", awaitPlaying(45, "queue"))
        val dumpBefore = mediaSessionDump()
        File(shotsDir, "dumpsys-queue-before.txt").also { it.writeText(dumpBefore); publish(it) }
        val activeItem = { dump: String -> Regex("active item id=(\\d+)").find(dump)?.groupValues?.get(1)?.toInt() }
        val queueSize = Regex("queueTitle=null, size=(\\d+)").findAll(dumpBefore).maxOfOrNull { it.groupValues[1].toInt() } ?: 0
        assertTrue("session queue has $queueSize items, expected several", queueSize > 1)
        device.executeShellCommand("input keyevent KEYCODE_MEDIA_NEXT")
        Thread.sleep(3_000)
        val dumpAfter = mediaSessionDump()
        File(shotsDir, "dumpsys-queue-after.txt").also { it.writeText(dumpAfter); publish(it) }
        assertTrue("next did not move to the following episode", activeItem(dumpAfter) == (activeItem(dumpBefore) ?: -1) + 1)
        shot("09-queue-next")
    }
}
