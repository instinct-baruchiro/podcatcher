package com.baruchiro.podcatcher

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.baruchiro.podcatcher.telemetry.Analytics
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith

/** TEMPORARY, one-shot: app-code receipt check against the real endpoint, labelled internal_test. Reverted after use. */
@RunWith(AndroidJUnit4::class)
class AnalyticsReceiptTempTest {
    @Test
    fun oneLabelledSyntheticEvent() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = ctx.getSharedPreferences("analytics-receipt-temp", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val id = "emu-" + System.currentTimeMillis()
        val a = Analytics(prefs, version = "trial-0.1", build = "debug")
        a.capture("podcatcher_smoke_test_app", mapOf("smoke_id" to id))
        a.flush()
        val f = File(ctx.getExternalFilesDir(null), "receipt-id.txt").also { it.writeText(id) }
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME, f.name)
            put(android.provider.MediaStore.Downloads.RELATIVE_PATH, "Download/podcatcher-shots")
            put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/plain")
        }
        val uri = ctx.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
        ctx.contentResolver.openOutputStream(uri)!!.use { it.write(id.toByteArray()) }
    }
}
