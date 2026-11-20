package com.baruchiro.podcatcher.conv

import org.json.JSONObject

/**
 * Podcast-domain tools the conversation may invoke, and the deterministic
 * executor that applies them. No Android dependencies here: JVM tests drive
 * this against a fake Library (see app/src/test/.../conv).
 *
 * Model protocol: the backend returns a JSON object —
 *   {"say": "טקסט חופשי למשתמש"}                    — conversation only
 *   {"say": "...", "tool": "<name>", "args": {...}} — conversation + action
 * The executor below is the ONLY thing that touches the library; the model
 * never executes anything itself.
 */

data class Pod(
    val feedUrl: String,
    val title: String,
    val autoDownload: Boolean,
    val keep: Int,
    val speed: Double,
    val skipIntroSec: Int,
    val priority: Int = 3,
    val oldestFirst: Boolean = false
)

data class PolicyPatch(
    val autoDownload: Boolean? = null,
    val keep: Int? = null,
    val speed: Double? = null,
    val skipIntroSec: Int? = null,
    val priority: Int? = null,
    val oldestFirst: Boolean? = null
)

interface Library {
    fun list(): List<Pod>
    fun findByName(name: String): Pod?
    fun get(feedUrl: String): Pod?
    /** feedUrl null = the default policy for future subscriptions. Returns the updated Pod or null for defaults. */
    fun applyPolicy(feedUrl: String?, patch: PolicyPatch): Pod?
    fun defaultPolicy(): PolicyPatch
    /** Returns the subscribed podcast's title. Throws on unreachable/invalid feed. */
    fun subscribeByUrl(feedUrl: String): String
    /** Name search; returns up to 5 (title, feedUrl) candidates for the user to pick from. */
    fun searchByName(name: String): List<Pair<String, String>>
}

object Tools {

    val SPEC = """
כלים זמינים (המודל מחזיר JSON בלבד):
- list_podcasts {} — רשימת המנויים וההגדרות שלהם.
- search_podcasts {"name": "..."} — חיפוש פודקאסט לפי שם; מחזיר מועמדים לבחירה.
- subscribe {"feedUrl": "..."} — רישום מנוי לפי קישור פיד.
- get_policy {"feedUrl": "..."} או {} — הגדרות של פודקאסט מסוים או ברירות המחדל.
- set_policy {"feedUrl": "..." (אופציונלי; בלעדיו = ברירת מחדל), "autoDownload": bool, "keep": int, "speed": double, "skipIntroSec": int} — עדכון הגדרות.
- set_queue_rules {"feedUrl": "..." (חובה), "priority": 1-5 (1 מתנגן ראשון, שווים מתחלפים), "oldestFirst": bool (בתור ההאזנה, פרקים ישנים קודם)} — שינוי כללי תור ההאזנה של פודקאסט, רק לפי בקשת המשתמש.
""".trimIndent()

    fun execute(json: JSONObject, lib: Library): String {
        val tool = json.optString("tool")
        if (tool.isEmpty()) return json.optString("say", "")
        val args = json.optJSONObject("args") ?: JSONObject()
        val out = run(tool, args, lib)
        // A follow-up question the model asked next to the action must not vanish: show only its question sentence.
        val q = lastQuestion(json.optString("say"))
        return if (q != null && !out.trimEnd().endsWith("?")) out + "\n" + q else out
    }

    private fun lastQuestion(say: String): String? {
        val t = say.trim()
        if (!t.endsWith("?")) return null
        val start = t.dropLast(1).lastIndexOfAny(charArrayOf('.', '!', '\n', '…')) + 1
        return t.substring(start).trim().ifEmpty { null }
    }

    private fun run(tool: String, args: JSONObject, lib: Library): String {
        return when (tool) {
            "list_podcasts" -> listPodcasts(lib)
            "search_podcasts" -> search(lib, args.optString("name"))
            "subscribe" -> subscribe(lib, args.optString("feedUrl"))
            "get_policy" -> getPolicy(lib, args.optString("feedUrl").ifEmpty { null })
            "set_policy" -> setPolicy(lib, args)
            "set_queue_rules" -> setQueueRules(lib, args)
            else -> "לא מכירה את הכלי '$tool'."
        }
    }

    private fun listPodcasts(lib: Library): String {
        val pods = lib.list()
        if (pods.isEmpty()) return "אין עדיין מנויים. אפשר לחפש בשם או להדביק קישור."
        return "יש לך ${pods.size} מנויים:\n" + pods.joinToString("\n") {
            "- ${it.title} (שמירה ${it.keep}, מהירות ${it.speed}x${if (it.autoDownload) ", הורדה אוטומטית" else ""})"
        }
    }

    private fun search(lib: Library, name: String): String {
        if (name.isBlank()) return "בשביל חיפוש אני צריכה שם."
        val found = lib.searchByName(name)
        if (found.isEmpty()) return "לא מצאתי פודקאסטים בשם '$name'."
        return "מצאתי ${found.size} מועמדים:\n" +
            found.mapIndexed { i, (t, u) -> "${i + 1}. $t\n$u" }.joinToString("\n") +
            "\nאיזה מהם?"
    }

    private fun subscribe(lib: Library, feedUrl: String): String {
        if (!feedUrl.startsWith("http")) return "כדי להירשם אני צריכה קישור פיד מלא."
        return try {
            val title = lib.subscribeByUrl(feedUrl)
            val rules = lib.get(feedUrl)?.let {
                policyLine(PolicyPatch(it.autoDownload, it.keep, it.speed, it.skipIntroSec, it.priority, it.oldestFirst))
            }
            // Read back from the store, never from the model's words: the listener sees the rules actually in force.
            "נרשמת ל'$title'." + (rules?.let { "\nהכללים שהוחלו על הפודקאסט הזה (ברירות המחדל שלך): $it.\nאפשר לשנות לפודקאסט הזה בלבד, למשל: \"ישנים קודם\" להתחיל מהפרק הראשון, \"תוריד אוטומטית\" או \"שמור 10 פרקים\"." } ?: "")
        } catch (e: Exception) {
            "ההרשמה נכשלה: לא הצלחתי לקרוא את הפיד."
        }
    }

    fun describeRules(autoDownload: Boolean, keep: Int, speed: Double, skipIntroSec: Int, priority: Int, oldestFirst: Boolean) =
        policyLine(PolicyPatch(autoDownload, keep, speed, skipIntroSec, priority, oldestFirst))

    private fun policyLine(p: PolicyPatch): String {
        val parts = mutableListOf<String>()
        p.keep?.let { parts.add("שמירת $it פרקים") }
        p.speed?.let { parts.add("מהירות ${it}x") }
        p.skipIntroSec?.let { parts.add("דילוג פתיח $it שניות") }
        p.autoDownload?.let { parts.add(if (it) "הורדה אוטומטית" else "ללא הורדה אוטומטית") }
        p.priority?.let { parts.add("עדיפות $it") }
        p.oldestFirst?.let { parts.add(if (it) "בתור: ישנים קודם" else "בתור: חדשים קודם") }
        return parts.joinToString(", ")
    }

    private fun getPolicy(lib: Library, feedUrl: String?): String {
        if (feedUrl == null) {
            val d = lib.defaultPolicy()
            return "ברירות המחדל: " + (policyLine(d).ifEmpty { "שמירת 3 פרקים, מהירות 1x" })
        }
        val pod = lib.get(feedUrl) ?: return "אין מנוי עם הקישור הזה."
        return "${pod.title}: " + policyLine(PolicyPatch(pod.autoDownload, pod.keep, pod.speed, pod.skipIntroSec))
    }

    /** Queue rules change only here, on the listener's request; the app never edits them on its own. */
    private fun setQueueRules(lib: Library, args: JSONObject): String {
        val feedUrl = args.optString("feedUrl")
        val pod = lib.get(feedUrl) ?: return "אין מנוי עם הקישור הזה."
        val patch = PolicyPatch(
            priority = if (args.has("priority")) args.getInt("priority") else null,
            oldestFirst = if (args.has("oldestFirst")) args.getBoolean("oldestFirst") else null
        )
        if (patch.priority == null && patch.oldestFirst == null) return "לא התבקש שינוי."
        if (patch.priority != null && patch.priority !in 1..5) return "עדיפות חייבת להיות בין 1 ל-5."
        lib.applyPolicy(feedUrl, patch)
        return "עדכנתי את '${pod.title}': ${policyLine(patch)}."
    }

    private fun setPolicy(lib: Library, args: JSONObject): String {
        val feedUrl = args.optString("feedUrl").ifEmpty { null }
        val patch = PolicyPatch(
            autoDownload = if (args.has("autoDownload")) args.getBoolean("autoDownload") else null,
            keep = if (args.has("keep")) args.getInt("keep") else null,
            speed = if (args.has("speed")) args.getDouble("speed") else null,
            skipIntroSec = if (args.has("skipIntroSec")) args.getInt("skipIntroSec") else null
        )
        val line = policyLine(patch)
        if (line.isEmpty()) return "לא התבקש שינוי."
        if (patch.keep != null && patch.keep !in 1..50) return "שמירה חייבת להיות בין 1 ל-50 פרקים."
        if (patch.speed != null && patch.speed !in 0.5..3.0) return "מהירות חייבת להיות בין 0.5 ל-3."
        if (feedUrl != null && lib.get(feedUrl) == null) return "אין מנוי עם הקישור הזה."
        val updated = lib.applyPolicy(feedUrl, patch)
        return if (feedUrl == null) "עדכנתי את ברירות המחדל: $line."
        else "עדכנתי את '${updated?.title}': $line."
    }
}
