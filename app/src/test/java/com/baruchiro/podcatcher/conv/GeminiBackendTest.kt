package com.baruchiro.podcatcher.conv

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** JVM test of the real Gemini REST client against a mock server (no network). */
class GeminiBackendTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }

    private fun geminiReply(text: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(
            JSONObject().put("candidates", org.json.JSONArray().put(
                JSONObject().put("content", JSONObject()
                    .put("parts", org.json.JSONArray().put(JSONObject().put("text", text))))))
            .toString()
        )

    @Test
    fun `a 503 is retried and the next good reply is used`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(geminiReply(JSONObject().put("say", "חזרתי").toString()))
        val backend = GeminiBackend("k", "m", server.url("/v1beta").toString())
        val out = backend.reply(listOf(ConvMessage(true, "היי")), Tools.SPEC)
        assertEquals("חזרתי", JSONObject(out).getString("say"))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `persistent 503 ends in a clear message after three tries`() = runBlocking {
        repeat(3) { server.enqueue(MockResponse().setResponseCode(503)) }
        val backend = GeminiBackend("k", "m", server.url("/v1beta").toString())
        val say = JSONObject(backend.reply(listOf(ConvMessage(true, "היי")), Tools.SPEC)).getString("say")
        assertTrue(say.contains("עמוס") && say.contains("503"))
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `sends tool spec, history and key, returns model text`() = runBlocking {
        server.enqueue(geminiReply(JSONObject().put("say", "בסדר").toString()))
        val backend = GeminiBackend("test-key", "gemini-3.1-flash-lite", server.url("/v1beta").toString())

        val out = backend.reply(
            listOf(ConvMessage(true, "הגבל כל פודקאסט ל-3 פרקים")),
            Tools.SPEC
        )
        assertEquals("בסדר", JSONObject(out).getString("say"))

        val req = server.takeRequest()
        assertTrue(req.path.orEmpty().contains("/v1beta/models/gemini-3.1-flash-lite:generateContent"))
        assertEquals("test-key", req.getHeader("x-goog-api-key"))
        val body = JSONObject(req.body.readUtf8())
        val sys = body.getJSONObject("system_instruction").getJSONArray("parts").getJSONObject(0).getString("text")
        assertTrue(sys.contains("set_policy"))
        val contents = body.getJSONArray("contents")
        assertEquals("user", contents.getJSONObject(0).getString("role"))
        assertEquals("הגבל כל פודקאסט ל-3 פרקים",
            contents.getJSONObject(0).getJSONArray("parts").getJSONObject(0).getString("text"))
    }

    private class FakeLib : Library {
        var defaults = PolicyPatch(true, 5, 1.0, 0)
        override fun list() = emptyList<Pod>()
        override fun findByName(name: String) = null
        override fun get(feedUrl: String) = null
        override fun applyPolicy(feedUrl: String?, patch: PolicyPatch): Pod? {
            defaults = PolicyPatch(
                patch.autoDownload ?: defaults.autoDownload!!,
                patch.keep ?: defaults.keep!!,
                patch.speed ?: defaults.speed!!,
                patch.skipIntroSec ?: defaults.skipIntroSec!!
            )
            return null
        }
        override fun defaultPolicy() = defaults
        override fun subscribeByUrl(feedUrl: String) = "x"
        override fun searchByName(name: String) = emptyList<Pair<String, String>>()
    }

    @Test
    fun `model tool call executes end to end`() = runBlocking {
        val toolCall = JSONObject()
            .put("say", "הגדרתי ברירת מחדל")
            .put("tool", "set_policy")
            .put("args", JSONObject().put("keep", 3))
        server.enqueue(geminiReply(toolCall.toString()))
        val backend = GeminiBackend("k", "m", server.url("/v1beta").toString())

        val lib = FakeLib()
        val raw = backend.reply(listOf(ConvMessage(true, "תשמור לכל היותר 3 פרקים")), Tools.SPEC)
        val confirmation = Tools.execute(JSONObject(raw), lib).text
        assertTrue(confirmation.contains("ברירות המחדל"))
        assertEquals(3, lib.defaultPolicy().keep)
    }

    @Test
    fun `http error degrades honestly`() = runBlocking {
        repeat(3) { server.enqueue(MockResponse().setResponseCode(429)) }
        val backend = GeminiBackend("k", "m", server.url("/v1beta").toString())
        val out = JSONObject(backend.reply(listOf(ConvMessage(true, "x")), Tools.SPEC))
        assertTrue(out.getString("say").contains("429"))
    }
}
