// @story: IMPORT-1, CONV-1
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
import com.baruchiro.podcatcher.ui.MainActivity
import java.io.File
import org.json.JSONObject
import org.junit.After
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
class ImportViaChatTest {

    private val pkg = InstrumentationRegistry.getInstrumentation().targetContext.packageName
    private lateinit var device: UiDevice
    private lateinit var appContext: Context
    private lateinit var shotsDir: File

    @Before
    fun setUp() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        appContext = InstrumentationRegistry.getInstrumentation().targetContext
        shotsDir = File(appContext.getExternalFilesDir(null), "shots").apply { mkdirs() }
    }

    @After
    fun tearDown() {
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
    fun importRequestInChatOpensTheImportSteps() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            device.wait(Until.hasObject(By.res("com.android.permissioncontroller:id/permission_allow_button")), 3_000)
            device.findObject(By.res("com.android.permissioncontroller:id/permission_allow_button"))?.click()

            // The model reply for "לייבא מפודקאסט אדיקט" is the import tool; same execute path as a real reply.
            scenario.onActivity {
                it.conv.renderModelOutputForTest(JSONObject("""{"say":"","tool":"import_subscriptions","args":{}}"""))
            }
            assertNotNull("import dialog did not open from chat",
                device.wait(Until.findObject(By.text("ייבוא מאפליקציה אחרת")), 8_000))
            assertNotNull("file picker button missing", device.findObject(By.text("בחירת קובץ")))
            shot("import-1-dialog-from-chat")
            device.findObject(By.text("ביטול")).click()
            assertNotNull("chat walkthrough missing",
                device.wait(Until.findObject(By.textContains("OPML")), 5_000))
            shot("import-2-chat-walkthrough")
        } finally {
            scenario.close()
        }
    }
}
