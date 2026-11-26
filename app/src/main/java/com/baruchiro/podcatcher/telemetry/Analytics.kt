package com.baruchiro.podcatcher.telemetry

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors

/**
 * REL-1 usage analytics: events only (names plus coarse properties, never
 * conversation text or episode content), sent to the owner's existing PostHog project
 * with its public write-only project token. A random per-install id, no person profiles, no identify call. The provider may still store IP and geolocation server-side.
 * On by default (owner decision 2026-11-20), but nothing is sent until the first-run chat has shown the notice
 * and recorded an explicit choice; the listener can switch it off there or in the About screen at any time.
 */
class Analytics(
    private val prefs: android.content.SharedPreferences,
    private val endpoint: String = ENDPOINT,
    private val token: String = PROJECT_TOKEN,
    private val version: String,
    private val build: String,
    private val client: OkHttpClient = OkHttpClient()
) {
    private val io = Executors.newSingleThreadExecutor()

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(v) { prefs.edit().putBoolean(KEY_ENABLED, v).apply() }

    /** True once the listener has been shown the notice and a choice (either way) is stored. */
    val asked: Boolean get() = prefs.contains(KEY_ENABLED)

    private fun installId(): String =
        prefs.getString(KEY_ID, null) ?: UUID.randomUUID().toString().also { prefs.edit().putString(KEY_ID, it).apply() }

    fun capture(event: String, props: Map<String, Any> = emptyMap()) {
        if (!asked || !enabled || endpoint.isBlank()) return
        val body = JSONObject()
            .put("api_key", token)
            .put("event", event)
            .put("distinct_id", installId())
            .put("properties", JSONObject(props).apply {
                put("app", "podcatcher")
                put("app_version", version)
                put("build", build)
                if (build == "debug") put("internal_test", true)
                put("\$process_person_profile", false)
                put("\$lib", "podcatcher-android")
            })
        io.execute {
            runCatching {
                client.newCall(Request.Builder().url(endpoint).post(body.toString().toRequestBody("application/json".toMediaType())).build())
                    .execute().close()
            }
        }
    }

    /** Waits for queued sends; for tests. */
    fun flush() { io.submit {}.get() }

    companion object {
        const val ENDPOINT = "https://us.i.posthog.com/i/v0/e/"
        // Public write-only project token, the same one the owner's sites ship in the page source.
        const val PROJECT_TOKEN = "phc_dK2c0paBXFZNzJOLAg1FFrjoNCNbdWiH4VQS9Z9YXX7"
        private const val KEY_ENABLED = "analytics_enabled"
        private const val KEY_ID = "analytics_install_id"

        @Volatile private var shared: Analytics? = null

        /** Process-wide instance; under instrumented tests it has no endpoint, so CI never sends real events. */
        fun get(ctx: Context): Analytics {
            return shared ?: synchronized(this) {
                shared ?: Analytics(
                    ctx.applicationContext.getSharedPreferences("telemetry", Context.MODE_PRIVATE),
                    endpoint = if (underTest()) "" else ENDPOINT,
                    version = ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?",
                    build = if (ctx.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) "debug" else "release"
                ).also { shared = it }
            }
        }

        fun runningUnderTest() = underTest()

        private fun underTest() = runCatching { Class.forName("androidx.test.platform.app.InstrumentationRegistry") }.isSuccess
    }
}
