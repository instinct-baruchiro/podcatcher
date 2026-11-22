package com.baruchiro.podcatcher

import android.content.ContentValues
import android.content.Intent
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.baruchiro.podcatcher.conv.SecretStore
import java.io.File
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device error path with NO real credential: a dummy key makes the chat
 * build the real GeminiBackend and attempt a real HTTPS call, which Google
 * rejects (401). Proves UI -> SecretStore -> backend -> network -> honest
 * error bubble end to end, without spending quota or embedding a secret.
 */
@RunWith(AndroidJUnit4::class)
class ConvErrorPathTest {

    private val pkg = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.packageName

    @Test
    fun invalidKeyProducesHonestErrorBubble() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val secrets = SecretStore(appContext)
        try {
            secrets.put("gemini_key", "dummy-ci-not-a-real-key")
            appContext.startActivity(
                appContext.packageManager.getLaunchIntentForPackage(pkg)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            device.wait(Until.hasObject(By.res("com.android.permissioncontroller:id/permission_allow_button")), 3_000)
            device.findObject(By.res("com.android.permissioncontroller:id/permission_allow_button"))?.click()
            device.findObject(By.desc("פתיחת השיחה")).click()
            val input = device.wait(Until.findObject(By.res(pkg, "convInput")), 5_000)
            assertNotNull("chat input not found", input)
            input!!.text = "בדיקת חיבור"
            device.findObject(By.res(pkg, "convSend")).click()

            val error = device.wait(Until.findObject(By.textContains("המנוע לא זמין")), 60_000)
            val out = File(appContext.getExternalFilesDir(null), "shots/conv-invalid-key.png")
            out.parentFile?.mkdirs()
            assertTrue("screenshot failed", device.takeScreenshot(out))
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, out.name)
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/podcatcher-shots")
                put(MediaStore.Downloads.MIME_TYPE, "image/png")
            }
            val uri = appContext.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            appContext.contentResolver.openOutputStream(uri!!)!!.use { it.write(out.readBytes()) }
            assertNotNull("expected the honest engine-error bubble", error)
        } finally {
            secrets.remove("gemini_key")
        }
    }
}
