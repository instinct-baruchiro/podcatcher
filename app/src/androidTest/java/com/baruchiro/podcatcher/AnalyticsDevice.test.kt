// @story: REL-1
package com.baruchiro.podcatcher

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.baruchiro.podcatcher.telemetry.Analytics
import java.net.ServerSocket
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The app's real analytics code on a device: opt-out stops network traffic, opt-in sends. Loopback only. */
@RunWith(AndroidJUnit4::class)
class AnalyticsDeviceTest {

    private fun requestsWithin(server: ServerSocket, ms: Int): List<String> {
        server.soTimeout = ms
        val seen = mutableListOf<String>()
        try {
            while (true) {
                server.accept().use { s ->
                    val head = s.getInputStream().bufferedReader().readLine().orEmpty()
                    s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    seen.add(head)
                }
            }
        } catch (_: SocketTimeoutException) {
        }
        return seen
    }

    @Test
    fun optOutSendsNothingAndOptInSendsToConfiguredEndpoint() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = ctx.getSharedPreferences("analytics-device-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        ServerSocket(0).use { server ->
            val analytics = Analytics(prefs, "http://127.0.0.1:${server.localPort}/e", "phc_test", "t", "debug")
            analytics.enabled = false
            analytics.capture("podcatcher_app_open")
            analytics.flush()
            assertEquals("opted-out app still hit the network", emptyList<String>(), requestsWithin(server, 1_500))

            analytics.enabled = true
            analytics.capture("podcatcher_app_open")
            val seen = requestsWithin(server, 5_000)
            assertTrue("opted-in app sent nothing: $seen", seen.size == 1 && seen[0].startsWith("POST /e"))
        }
    }
}
