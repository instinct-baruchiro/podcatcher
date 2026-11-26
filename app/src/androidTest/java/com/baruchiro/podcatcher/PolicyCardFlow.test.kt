// @story: CONV-2
package com.baruchiro.podcatcher

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.baruchiro.podcatcher.data.Policy
import com.baruchiro.podcatcher.data.Podcast
import com.baruchiro.podcatcher.data.Store
import com.baruchiro.podcatcher.ui.MainActivity
import java.io.File
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * CONV-2 on a real emulator: the settings the conversation reports arrive as a
 * small live card bound to the stored policy — not a reciting paragraph. The
 * card edits the store directly (keep stepper, auto-download toggle), and a
 * change requested in words re-renders the same card to the new state.
 * The render path is driven through the same parse→execute→render code a real
 * backend reply takes (ConvPanel.renderModelOutputForTest), with no model and
 * no secrets, exactly like ConvSliceTest.
 */
@RunWith(AndroidJUnit4::class)
class PolicyCardFlowTest {

    private val pkg = InstrumentationRegistry.getInstrumentation().targetContext.packageName
    private val feedUrl = "https://policy-card.example/feed"
    private lateinit var device: UiDevice
    private lateinit var appContext: Context
    private lateinit var shotsDir: File

    @Before
    fun setUp() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        appContext = InstrumentationRegistry.getInstrumentation().targetContext
        shotsDir = File(appContext.getExternalFilesDir(null), "shots").apply { mkdirs() }
        Store.removePodcast(appContext, feedUrl)
        Store.setDefaultPolicy(appContext, Policy())
        Store.addPodcast(
            appContext,
            Podcast(feedUrl, "כרטיס בדיקה", "", "", Policy(
                autoDownload = false, keep = 5, speed = 1.5, skipIntroSec = 15, priority = 2, oldestFirst = true))
        )
    }

    @After
    fun tearDown() {
        Store.removePodcast(appContext, feedUrl)
        Store.setDefaultPolicy(appContext, Policy())
    }

    private fun shot(name: String) {
        val out = File(shotsDir, "$name.png")
        assertTrue("screenshot $name failed", device.takeScreenshot(out))
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, out.name)
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/podcatcher-shots")
            put(MediaStore.Downloads.MIME_TYPE, "image/png")
        }
        val uri = appContext.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        assertNotNull("MediaStore insert failed for ${out.name}", uri)
        appContext.contentResolver.openOutputStream(uri!!)!!.use { it.write(out.readBytes()) }
    }

    private fun policy(feedUrl: String) = Store.podcasts(appContext).first { it.feedUrl == feedUrl }.policy

    @Test
    fun cardShowsStoredStateEditsDirectlyAndFollowsWords() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            device.wait(Until.hasObject(By.res("com.android.permissioncontroller:id/permission_allow_button")), 3_000)
            device.findObject(By.res("com.android.permissioncontroller:id/permission_allow_button"))?.click()

            // Asking for the podcast's settings renders the card, bound to the stored policy.
            scenario.onActivity {
                it.conv.renderModelOutputForTest(JSONObject("""{"tool":"get_policy","args":{"feedUrl":"$feedUrl"}}"""))
            }
            val keepValue = device.wait(Until.findObject(By.res(pkg, "cardKeepValue")), 5_000)
            assertNotNull("policy card did not render", keepValue)
            assertEquals("5", keepValue!!.text)
            assertNotNull("secondary line missing the stored queue order",
                device.wait(Until.findObject(By.textContains("ישנים קודם")), 3_000))
            assertNotNull("secondary line missing the stored speed",
                device.findObject(By.textContains("מהירות 1.5x")))
            assertTrue("auto-download starts off", !device.findObject(By.res(pkg, "cardAuto")).isChecked)
            shot("card-1-bound-to-store")

            // Direct edit on the card: the keep stepper writes through to the store (5 -> 10).
            device.findObject(By.res(pkg, "cardKeepPlus")).click()
            assertNotNull("card did not rebind to 10",
                device.wait(Until.findObject(By.res(pkg, "cardKeepValue").text("10")), 5_000))
            assertEquals(10, policy(feedUrl).keep)
            shot("card-2-stepped-to-10")

            // Direct edit on the card: the auto-download toggle writes through to the store.
            device.findObject(By.res(pkg, "cardAuto")).click()
            assertNotNull("toggle did not apply",
                device.wait(Until.findObject(By.res(pkg, "cardAuto").checked(true)), 5_000))
            assertTrue(policy(feedUrl).autoDownload)
            shot("card-3-auto-on")

            // A change in words re-renders the same card to the new stored state (no new card).
            scenario.onActivity {
                it.conv.renderModelOutputForTest(JSONObject(
                    """{"say":"","tool":"set_policy","args":{"feedUrl":"$feedUrl","keep":3,"autoDownload":false}}"""))
            }
            assertNotNull("card did not follow the worded change",
                device.wait(Until.findObject(By.res(pkg, "cardKeepValue").text("3")), 5_000))
            assertNotNull("toggle did not follow the worded change",
                device.wait(Until.findObject(By.res(pkg, "cardAuto").checked(false)), 5_000))
            assertEquals(3, policy(feedUrl).keep)
            assertTrue(!policy(feedUrl).autoDownload)
            shot("card-4-words-rebound")

            // The defaults answer renders the same kind of card.
            scenario.onActivity {
                it.conv.renderModelOutputForTest(JSONObject("""{"tool":"get_policy","args":{}}"""))
            }
            assertNotNull("defaults card missing",
                device.wait(Until.findObject(By.textContains("ברירת מחדל לפודקאסט חדש")), 5_000))
            shot("card-5-defaults")
        } finally {
            scenario.close()
        }
    }
}
