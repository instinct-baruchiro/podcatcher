// @story: QUEUE-1
package com.baruchiro.podcatcher

import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.baruchiro.podcatcher.data.Podcast
import com.baruchiro.podcatcher.data.Policy
import com.baruchiro.podcatcher.data.Store
import com.baruchiro.podcatcher.ui.QueueActivity
import java.net.ServerSocket
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * QUEUE-1 on a real emulator with three local podcasts (no live network): strict order between
 * unequal priorities, turns between equals, priority kept after leaving the screen, automatic
 * next at the end of each episode, played episodes never replayed and a skipped one kept.
 */
@RunWith(AndroidJUnit4::class)
class QueueFlowTest {

    private val pkg = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.packageName
    private val ctx: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val server = ServerSocket(0)

    // Three-second silent 8 kHz mono WAV, so each episode ends by itself quickly.
    private val wav = ByteArray(24_000 + 44).also {
        fun le(v: Int, at: Int, n: Int) { for (i in 0 until n) it[at + i] = (v shr (8 * i)).toByte() }
        "RIFF".toByteArray().copyInto(it, 0); le(24_000 + 36, 4, 4)
        "WAVEfmt ".toByteArray().copyInto(it, 8); le(16, 16, 4); le(1, 20, 2); le(1, 22, 2)
        le(8_000, 24, 4); le(8_000, 28, 4); le(1, 32, 2); le(8, 34, 2)
        "data".toByteArray().copyInto(it, 36); le(24_000, 40, 4)
        it.fill(0x80.toByte(), 44, it.size)
    }

    private val feeds = mapOf(
        "alpha" to listOf("A one", "A two"),
        "bravo" to listOf("B one", "B two"),
        "charlie" to listOf("C one")
    )

    private fun feedXml(name: String) = buildString {
        append("<rss><channel><title>${name.replaceFirstChar { it.uppercase() }}</title>")
        feeds.getValue(name).forEach { t ->
            val id = t.replace(' ', '-').lowercase()
            append("<item><title>$t</title><guid>$id</guid>")
            append("<enclosure url=\"http://127.0.0.1:${server.localPort}/audio/$id.wav\" type=\"audio/wav\"/></item>")
        }
        append("</channel></rss>")
    }

    @Before
    fun setUp() {
        ctx.getSharedPreferences("podcatcher", Context.MODE_PRIVATE).edit().clear().commit()
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val s = try { server.accept() } catch (e: Exception) { return@thread }
                thread(isDaemon = true) {
                    s.use {
                        val path = it.getInputStream().bufferedReader().readLine().split(" ")[1]
                        val body = if (path.startsWith("/audio/")) wav else feedXml(path.removePrefix("/")).toByteArray()
                        val type = if (path.startsWith("/audio/")) "audio/wav" else "application/rss+xml"
                        it.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(body); flush()
                        }
                    }
                }
            }
        }
        feeds.keys.forEach { Store.addPodcast(ctx, Podcast("http://127.0.0.1:${server.localPort}/$it", it.replaceFirstChar { c -> c.uppercase() }, "", "")) }
        val priorities = mapOf("alpha" to 1, "bravo" to 2)
        Store.podcasts(ctx).forEach { p ->
            val prio = priorities[p.feedUrl.substringAfterLast('/')] ?: return@forEach
            Store.updatePolicy(ctx, p.feedUrl, Policy(priority = prio))
        }
    }

    @After
    fun tearDown() {
        server.close()
        // Leave a clean library for the other flows.
        ctx.getSharedPreferences("podcatcher", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun openQueue() {
        ctx.startActivity(Intent(ctx, QueueActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue("queue did not load", device.wait(Until.hasObject(By.textStartsWith("1. ")), 20_000))
    }

    private fun playedCount() = Store.podcasts(ctx).sumOf { Store.playedGuids(ctx, it.feedUrl).size }

    @Test
    fun queuePlaysInPriorityOrderAndNeverReplays() {
        openQueue()
        // Charlie starts at the default priority 3; one tap up makes it equal to Bravo.
        device.findObject(By.desc("העלאת עדיפות לCharlie")).click()
        assertTrue(device.wait(Until.hasObject(By.text("Charlie\nעדיפות 2")), 3_000))
        // The change reached disk, not just memory (what a process restart would reload).
        val prefsFile = java.io.File(ctx.dataDir, "shared_prefs/podcatcher.xml")
        for (i in 1..10) { if (prefsFile.exists() && prefsFile.readText().split("priority&quot;:2").size == 3) break; Thread.sleep(300) }
        assertEquals("priorities not on disk", 3, prefsFile.readText().split("priority&quot;:2").size)
        device.pressBack()
        openQueue()
        assertTrue("priority lost after leaving the screen", device.hasObject(By.text("Charlie\nעדיפות 2")))

        // Alpha (1) first, then Bravo and Charlie (2) take turns, Bravo's second episode last.
        device.findObject(By.res(pkg, "queuePlay")).click()
        val titles = Regex("description=(A one|A two|B one|B two|C one)")
        val heard = mutableListOf<String>()
        var skipped = false
        val deadline = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < deadline && heard.lastOrNull() != "B two") {
            Thread.sleep(400)
            titles.find(device.executeShellCommand("dumpsys media_session"))?.groupValues?.get(1)
                ?.takeIf { it != heard.lastOrNull() }?.let { heard.add(it) }
            // Skip past the first episode with the next key; it must not count as played.
            if (!skipped && heard.size == 1) { skipped = true; device.executeShellCommand("input keyevent KEYCODE_MEDIA_NEXT") }
        }
        assertEquals(listOf("A one", "A two", "B one", "C one", "B two"), heard)

        // Episodes that finish on their own count as played; the skipped one stays unplayed and
        // is the only thing a rebuilt queue offers.
        for (i in 1..20) { if (playedCount() == 4) break; Thread.sleep(1_000) }
        Thread.sleep(2_000)
        assertEquals(4, playedCount())
        device.pressBack()
        openQueue()
        assertTrue("queue should hold only the skipped episode", device.hasObject(By.textStartsWith("1. A one")))
        assertTrue("played episodes came back", !device.hasObject(By.textStartsWith("2. ")))
    }
}
