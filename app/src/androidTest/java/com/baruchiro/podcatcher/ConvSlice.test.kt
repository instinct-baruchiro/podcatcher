// @story: CONV-1, SET-1
package com.baruchiro.podcatcher

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.baruchiro.podcatcher.conv.AndroidLibrary
import com.baruchiro.podcatcher.conv.Tools
import com.baruchiro.podcatcher.data.Policy
import com.baruchiro.podcatcher.data.Podcast
import com.baruchiro.podcatcher.data.Store
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * CONV-1 vertical slice on a real emulator, NO model and NO secrets:
 *  - the deterministic executor applies a Hebrew keep-last-N rule to the real
 *    Store through the AndroidLibrary binding, and a fresh Store read (the
 *    same state the app sees after a restart, since Store re-reads
 *    SharedPreferences on every call) shows the persisted rule;
 *  - the chat screen opens, accepts Hebrew input, and with no API key
 *    configured answers honestly that no engine is attached (never fakes).
 * The model-side Hebrew understanding is proven separately once a backend is
 * activated (pending owner privacy consent).
 */
@RunWith(AndroidJUnit4::class)
class ConvSliceTest {

    private val pkg = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.packageName
    private val feedUrl = "https://conv-slice.example/feed"
    private lateinit var device: UiDevice
    private lateinit var appContext: Context
    private lateinit var shotsDir: File

    @Before
    fun setUp() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        appContext = InstrumentationRegistry.getInstrumentation().targetContext
        shotsDir = File(appContext.getExternalFilesDir(null), "shots").apply { mkdirs() }
        Store.removePodcast(appContext, feedUrl)
        Store.addPodcast(appContext, Podcast(feedUrl, "פודקאסט בדיקה", "", "", Policy()))
    }

    @After
    fun tearDown() {
        // Leave the shared on-device Store exactly as found: other instrumented
        // tests (ScreenshotFlowTest) run in the same app storage and must not
        // see this seeded podcast or the changed default policy.
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

    @Test
    fun hebrewRulePersistsAndSurvivesRestart() {
        val lib = AndroidLibrary(appContext)

        // What the model is expected to emit for "תשמור ב׳פודקאסט בדיקה׳ רק 3 פרקים אחרונים"
        val json = JSONObject()
            .put("say", "הגדרתי שמירת 3 פרקים")
            .put("tool", "set_policy")
            .put("args", JSONObject().put("feedUrl", feedUrl).put("keep", 3))

        val confirmation = Tools.execute(json, lib).text
        assertTrue("confirmation should name the podcast: $confirmation", confirmation.contains("פודקאסט בדיקה"))

        // Fresh read from storage = the state visible after an app restart.
        val persisted = Store.podcasts(appContext).first { it.feedUrl == feedUrl }
        assertEquals(3, persisted.policy.keep)

        // Global default slice (SET-1): rule without a podcast target.
        val global = JSONObject()
            .put("tool", "set_policy")
            .put("args", JSONObject().put("keep", 4))
        Tools.execute(global, lib)
        assertEquals(4, Store.defaultPolicy(appContext).keep)
    }

    @Test
    fun chatUiAcceptsHebrewAndAnswersHonestlyWithoutEngine() {
        appContext.startActivity(
            appContext.packageManager.getLaunchIntentForPackage(pkg)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        device.wait(Until.hasObject(By.res("com.android.permissioncontroller:id/permission_allow_button")), 3_000)
        device.findObject(By.res("com.android.permissioncontroller:id/permission_allow_button"))?.click()
        val fab = device.wait(Until.findObject(By.desc("פתיחת השיחה")), 5_000)
        assertNotNull("chat FAB not found", fab)
        fab!!.click()
        val input = device.wait(Until.findObject(By.res(pkg, "convInput")), 5_000)
        assertNotNull("chat input not found", input)
        input!!.text = "תשמור רק 3 פרקים אחרונים"
        device.findObject(By.res(pkg, "convSend")).click()

        val honest = device.wait(Until.findObject(By.textContains("עדיין לא חובר מנוע שיחה")), 5_000)
        shot("conv-no-backend")
        assertNotNull("NoBackend honest reply should appear", honest)
    }
}
