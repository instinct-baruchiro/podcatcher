package com.baruchiro.podcatcher.conv

import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Gemini API backend (generateContent REST). The API key is never shipped in
 * the app: the owner enters it once in the conversation screen and it lives in
 * app-private storage. baseUrl is overridable for JVM tests (MockWebServer).
 */
class GeminiBackend(
    private val apiKey: String,
    private val model: String = "gemini-2.5-flash",
    private val baseUrl: String = "https://generativelanguage.googleapis.com/v1beta"
) : ModelBackend {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    override suspend fun reply(history: List<ConvMessage>, toolSpec: String): String {
        val system = """
את המנוע השיחתי של אפליקציית פודקאסטים. תפקידך: לנהל שיחה בעברית על הגדרות האפליקציה בלבד — מנויים, הורדות, שמירת פרקים, מהירות, דילוג פתיח. השיחה אינה עוסקת בתוכן פרקים, והשמעה נשארת בכפתורים.
ענה תמיד ב-JSON טהור בלבד (בלי markdown, בלי הסברים מסביב), באחד משני מבנים:
{"say": "תשובה חופשית בעברית"}
{"say": "מה שתגידי למשתמש", "tool": "<שם כלי>", "args": {...}}
אל תמציאי קישורי פיד; אם המשתמש נתן שם בלבד, השתמשי ב-search_podcasts והציגי מועמדים. אל תבצעי subscribe לפני שהמשתמש בחר מועמד או נתן קישור.
$toolSpec
""".trimIndent()

        val contents = JSONArray()
        for (m in history) {
            contents.put(JSONObject()
                .put("role", if (m.fromUser) "user" else "model")
                .put("parts", JSONArray().put(JSONObject().put("text", m.text))))
        }

        val body = JSONObject()
            .put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", contents)
            .put("generationConfig", JSONObject()
                .put("responseMimeType", "application/json")
                .put("temperature", 0.2))

        val req = Request.Builder()
            .url("$baseUrl/models/$model:generateContent")
            .header("x-goog-api-key", apiKey)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val parsed = client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                return JSONObject().put("say", "המנוע לא זמין כרגע (HTTP ${resp.code}).").toString()
            }
            JSONObject(resp.body?.string() ?: "")
        }
        val text = parsed.optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
            ?.optJSONObject(0)
            ?.optString("text")
        return text?.takeIf { it.isNotBlank() }
            ?: JSONObject().put("say", "המנוע לא החזיר תשובה.").toString()
    }
}
