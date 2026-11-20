package com.baruchiro.podcatcher.telemetry

import android.content.SharedPreferences
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * REL-1 conversation storage client. Stores nothing unless the listener has agreed after
 * seeing the notice, and nothing at all while no endpoint is configured (none is deployed
 * yet, so [baseUrl] is empty and the app never contacts a server for this).
 *
 * Protocol with the future relay: POST /register gives this install its own secret;
 * POST /messages and DELETE /mine carry it as a bearer token. The Gemini key is never sent.
 */
class ConversationLog(
    private val prefs: SharedPreferences,
    private val baseUrl: String = BASE_URL,
    /** "test" for debug builds (never real collection), "prod" for release. */
    private val channel: String = "prod",
    private val client: OkHttpClient = OkHttpClient()
) {
    enum class Consent { UNSET, GRANTED, DECLINED }

    val available get() = baseUrl.isNotBlank()

    var consent: Consent
        get() = Consent.valueOf(prefs.getString(KEY_CONSENT, Consent.UNSET.name)!!)
        set(v) { prefs.edit().putString(KEY_CONSENT, v.name).apply() }

    /** The notice must be shown before the first message is stored. */
    fun needsNotice() = available && consent == Consent.UNSET

    /** Returns true when the message was stored. Failures never affect the conversation. */
    fun record(role: String, text: String, version: String): Boolean {
        if (!available || consent != Consent.GRANTED) return false
        return runCatching {
            val secret = secret() ?: return false
            val body = JSONObject().put("role", role).put("text", text).put("app_version", version).put("channel", channel)
            call("POST", "/messages", secret, body.toString())
        }.getOrDefault(false)
    }

    /** Deletes everything stored for this install; true when the server confirmed. */
    fun deleteMine(): Boolean {
        if (!available) return true
        val secret = prefs.getString(KEY_SECRET, null) ?: return true
        val ok = runCatching { call("DELETE", "/mine", secret, null) }.getOrDefault(false)
        if (ok) prefs.edit().remove(KEY_SECRET).apply()
        return ok
    }

    private fun secret(): String? {
        prefs.getString(KEY_SECRET, null)?.let { return it }
        val registered = client.newCall(Request.Builder().url(baseUrl + "/register").post("{}".toRequestBody(JSON)).build())
            .execute().use { if (it.isSuccessful) JSONObject(it.body!!.string()).optString("secret") else "" }
        if (registered.isBlank()) return null
        prefs.edit().putString(KEY_SECRET, registered).apply()
        return registered
    }

    private fun call(method: String, path: String, secret: String, json: String?): Boolean {
        val req = Request.Builder().url(baseUrl + path).header("Authorization", "Bearer $secret")
            .method(method, json?.toRequestBody(JSON)).build()
        return client.newCall(req).execute().use { it.isSuccessful }
    }

    companion object {
        /** Empty until the relay is deployed and approved. */
        const val BASE_URL = ""
        private const val KEY_CONSENT = "conv_log_consent"
        private const val KEY_SECRET = "conv_log_secret"
        private val JSON = "application/json".toMediaType()
    }
}
