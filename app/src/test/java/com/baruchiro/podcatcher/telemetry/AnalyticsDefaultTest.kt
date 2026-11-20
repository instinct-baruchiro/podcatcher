// @story: REL-1
package com.baruchiro.podcatcher.telemetry

import android.content.Context
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Owner decision 2026-11-20: usage events are on by default. But nothing is sent before the first-run chat has
 * recorded a choice, and a choice made earlier (including an opt-out) is never overwritten by the new default.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AnalyticsDefaultTest {

    private fun analytics(server: MockWebServer, name: String): Analytics {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences(name, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        return Analytics(prefs, server.url("/e").toString(), "phc_test", "t", "debug")
    }

    @Test
    fun earlierOptOutIsKept() {
        MockWebServer().use { server ->
            val a = analytics(server, "opt-out")
            a.enabled = false // the listener turned it off in About before this update
            assertFalse("an earlier opt-out was overwritten by the new default", a.enabled)
            a.capture("podcatcher_app_open"); a.flush()
            assertEquals(0, server.requestCount)
        }
    }
}
