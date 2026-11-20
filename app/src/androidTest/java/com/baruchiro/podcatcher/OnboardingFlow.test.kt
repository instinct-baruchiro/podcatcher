// @story: REL-1, CONV-1
package com.baruchiro.podcatcher

import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.baruchiro.podcatcher.data.Store
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** First-run chat on a device: states the default rules in force, records only the usage-stats choice. */
@RunWith(AndroidJUnit4::class)
class OnboardingFlowTest {

    @Test
    fun firstRunChatShowsDefaultsAndRecordsOnlyTheStatsChoice() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val ctx = inst.targetContext
        val pkg = ctx.packageName
        val device = UiDevice.getInstance(inst)
        ctx.getSharedPreferences("conv_config", Context.MODE_PRIVATE).edit()
            .putBoolean("onboarding_force", true).remove("onboarding_done").commit()
        ctx.getSharedPreferences("telemetry", Context.MODE_PRIVATE).edit().remove("analytics_enabled").commit()
        Store.setDefaultPolicy(ctx, com.baruchiro.podcatcher.data.Policy())

        ctx.startActivity(ctx.packageManager.getLaunchIntentForPackage(pkg)!!.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK))
        val done = device.wait(Until.findObject(By.res(pkg, "onboardDone")), 15_000)
        assertNotNull("first-run chat missing", done)
        shot(device, ctx, "onb-1-stats")
        assertTrue("stats choice must be preselected", device.findObject(By.res(pkg, "onboardStats")).isChecked)
        // The first bubble (default rules) sits above in the drawer: scroll up to it.
        device.findObject(By.scrollable(true))?.scroll(androidx.test.uiautomator.Direction.UP, 1.0f)
        shot(device, ctx, "onb-1b-defaults")
        assertNotNull("default rules not shown", device.wait(Until.findObject(By.textContains("ברירות המחדל האלה")), 5_000))
        device.findObject(By.scrollable(true))?.scroll(androidx.test.uiautomator.Direction.DOWN, 1.0f)
        // Nothing is chosen for the listener: the default rules are unchanged, and nothing is recorded before Done.
        assertEquals(com.baruchiro.podcatcher.data.Policy(), Store.defaultPolicy(ctx))
        assertTrue(!ctx.getSharedPreferences("telemetry", Context.MODE_PRIVATE).contains("analytics_enabled"))
        device.findObject(By.res(pkg, "onboardStats")).click() // opt out before continuing
        done.click()
        assertNotNull("summary missing", device.wait(Until.findObject(By.textContains("כבויות")), 5_000))
        shot(device, ctx, "onb-2-opted-out")
        assertEquals(false, ctx.getSharedPreferences("telemetry", Context.MODE_PRIVATE).getBoolean("analytics_enabled", true))
        assertEquals(com.baruchiro.podcatcher.data.Policy(), Store.defaultPolicy(ctx))
        // Leave no trace: other device tests expect a fresh install (analytics unchosen, first-run chat not forced).
        ctx.getSharedPreferences("telemetry", Context.MODE_PRIVATE).edit().remove("analytics_enabled").commit()
        ctx.getSharedPreferences("conv_config", Context.MODE_PRIVATE).edit().remove("onboarding_done").commit()
        ctx.getSharedPreferences("conv_config", Context.MODE_PRIVATE).edit().remove("onboarding_force").commit()
        Store.setDefaultPolicy(ctx, com.baruchiro.podcatcher.data.Policy())
    }

    private fun shot(device: UiDevice, ctx: Context, name: String) {
        val dir = File(ctx.getExternalFilesDir(null), "shots").also { it.mkdirs() }
        val f = File(dir, "$name.png")
        assertTrue(device.takeScreenshot(f))
        val v = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME, f.name)
            put(android.provider.MediaStore.Downloads.RELATIVE_PATH, "Download/podcatcher-shots")
            put(android.provider.MediaStore.Downloads.MIME_TYPE, "image/png")
        }
        val uri = ctx.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)!!
        ctx.contentResolver.openOutputStream(uri)!!.use { it.write(f.readBytes()) }
    }
}
