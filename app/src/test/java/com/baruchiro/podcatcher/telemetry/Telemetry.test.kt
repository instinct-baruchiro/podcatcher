// @story: REL-1
package com.baruchiro.podcatcher.telemetry

import android.content.SharedPreferences
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryTest {

    private class MemPrefs : SharedPreferences {
        val m = mutableMapOf<String, Any?>()
        override fun getString(k: String, d: String?) = m[k] as? String ?: d
        override fun getBoolean(k: String, d: Boolean) = m[k] as? Boolean ?: d
        override fun edit() = object : SharedPreferences.Editor {
            override fun putString(k: String, v: String?) = apply { m[k] = v }
            override fun putBoolean(k: String, v: Boolean) = apply { m[k] = v }
            override fun remove(k: String) = apply { m.remove(k) }
            override fun apply() {}
            override fun commit() = true
            override fun clear() = apply { m.clear() }
            override fun putStringSet(k: String, v: MutableSet<String>?) = this
            override fun putInt(k: String, v: Int) = this
            override fun putLong(k: String, v: Long) = this
            override fun putFloat(k: String, v: Float) = this
        }
        override fun getAll() = m.toMutableMap()
        override fun getStringSet(k: String, d: MutableSet<String>?) = d
        override fun getInt(k: String, d: Int) = d
        override fun getLong(k: String, d: Long) = d
        override fun getFloat(k: String, d: Float) = d
        override fun contains(k: String) = m.containsKey(k)
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }

    @Test
    fun `analytics sends events without person profiles only while switched on`() {
        MockWebServer().use { server ->
            server.start()
            val analytics = Analytics(MemPrefs(), server.url("/e").toString(), "phc_test", "0.1", "debug")

            assertFalse("analytics must start off", analytics.enabled)
            analytics.capture("podcatcher_app_open")
            analytics.flush()
            assertEquals(0, server.requestCount)

            analytics.enabled = true
            analytics.capture("podcatcher_queue_played", mapOf("items" to 5))
            analytics.flush()
            val sent = JSONObject(server.takeRequest().body.readUtf8())
            assertEquals("podcatcher_queue_played", sent.getString("event"))
            assertEquals("phc_test", sent.getString("api_key"))
            assertFalse(sent.getJSONObject("properties").getBoolean("\$process_person_profile"))
            assertEquals("podcatcher", sent.getJSONObject("properties").getString("app"))
        }
    }

    @Test
    fun `conversations are stored only after consent and can be deleted`() {
        MockWebServer().use { server ->
            server.start()
            val log = ConversationLog(MemPrefs(), server.url("").toString().trimEnd('/'), "test")
            assertTrue(log.needsNotice())

            // No consent yet: nothing leaves the device.
            assertFalse(log.record("user", "hi", "0.1"))
            assertEquals(0, server.requestCount)

            log.consent = ConversationLog.Consent.GRANTED
            server.enqueue(MockResponse().setBody("""{"secret":"s1"}"""))
            server.enqueue(MockResponse().setResponseCode(200))
            assertTrue(log.record("user", "hi", "0.1"))
            assertEquals("/register", server.takeRequest().path)
            val msg = server.takeRequest()
            assertEquals("Bearer s1", msg.getHeader("Authorization"))
            assertEquals("test", JSONObject(msg.body.readUtf8()).getString("channel"))

            server.enqueue(MockResponse().setResponseCode(200))
            assertTrue(log.deleteMine())
            val del = server.takeRequest()
            assertEquals("DELETE", del.method)
            assertEquals("/mine", del.path)
        }
    }

    @Test
    fun `declining or having no endpoint stores nothing`() {
        val noServer = ConversationLog(MemPrefs(), "")
        assertFalse(noServer.needsNotice())
        noServer.consent = ConversationLog.Consent.GRANTED
        assertFalse(noServer.record("user", "hi", "0.1"))
    }
}
