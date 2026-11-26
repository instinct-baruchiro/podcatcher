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

/** CONV-2: a live settings card to render in the chat. It binds to the stored
 *  policy of feedUrl (null = the defaults), never to the model's words. */
data class CardSpec(val feedUrl: String?, val title: String)

/** One episode row for chat: n is its position in the feed, 1 = newest. */
data class EpRow(val n: Int, val title: String, val date: String, val downloaded: Boolean, val played: Boolean)

/** Executor output: what to say, plus an optional live card to render (CONV-2). */
data class ExecResult(val text: String, val card: CardSpec? = null)

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
    /** Opens the in-app import steps (OPML from Podcast Addict etc). False when no screen can show them. */
    fun startImport(): Boolean = false
    /** Unsubscribes. False when the feed is not subscribed. */
    fun removePodcast(feedUrl: String): Boolean = false
    /** screen: "queue" (listening queue: order, priorities, play), "about", "podcast" (episodes of feedUrl: play, download). */
    fun openScreen(screen: String, feedUrl: String?): Boolean = false
    /** action: pause, resume, next, previous. False when nothing is playing or no player is attached. */
    fun playback(action: String): Boolean = false
    /** Usage-events switch from the About screen. */
    fun setAnalytics(enabled: Boolean): Boolean = false
    /** Newest-first episodes of a subscribed podcast (null when the feed can't be read). */
    fun episodes(feedUrl: String, limit: Int): List<EpRow>? = null
    /** Episode n (1 = newest). Each returns false when the episode or the player is unavailable. */
    fun playEpisode(feedUrl: String, n: Int): Boolean = false
    fun downloadEpisode(feedUrl: String, n: Int): Boolean = false
    fun deleteDownload(feedUrl: String, n: Int): Boolean = false
    fun markPlayed(feedUrl: String, n: Int): Boolean = false
    /** Plays the listening queue (QUEUE-1 order) from its first item. */
    fun playQueue(): Boolean = false
    /** Seeks the current episode: relative=true moves by seconds (negative = back), else jumps to that second. */
    fun seek(seconds: Int, relative: Boolean): Boolean = false
}

/** Standing rule (owner, 2026-11-26): every user-facing action must be reachable through the chat.
 *  Each UI action maps to the chat tool that does it. ChatReachabilityTest fails when a screen gains
 *  actions that are not recorded here, so new features cannot ship without a chat path. */
object ChatPaths {
    /** screen file -> (number of click/choice handlers recorded when last audited) */
    val UI_HANDLER_COUNTS = mapOf(
        "AboutActivity.kt" to 3, "MainActivity.kt" to 9, "PlayerBar.kt" to 1, "PodcastActivity.kt" to 8, "QueueActivity.kt" to 1
    )
    /** UI action -> chat tool. */
    val FEATURES = mapOf(
        "add podcast by name or link" to "search_podcasts",
        "pick a search result" to "subscribe",
        "open podcast / episodes (play, download)" to "open_screen",
        "remove podcast" to "remove_podcast",
        "open listening queue (order, priority, play)" to "open_screen",
        "open about" to "open_screen",
        "import subscriptions" to "import_subscriptions",
        "per-podcast and default settings" to "set_policy",
        "queue priority and order" to "set_queue_rules",
        "show current settings" to "get_policy",
        "list subscriptions" to "list_podcasts",
        "player pause/resume/next/previous" to "playback",
        "usage events switch" to "set_analytics",
        "list a podcast's episodes" to "list_episodes",
        "play an episode" to "play_episode",
        "download an episode" to "download_episode",
        "delete a download" to "delete_download",
        "mark an episode played" to "mark_played",
        "play the listening queue" to "play_queue",
        "seek within an episode" to "seek"
    )
}

object Tools {

    val SPEC = """
כלים זמינים (המודל מחזיר JSON בלבד):
- list_podcasts {} — רשימת המנויים וההגדרות שלהם.
- search_podcasts {"name": "..."} — חיפוש פודקאסט לפי שם; מחזיר מועמדים לבחירה.
- subscribe {"feedUrl": "..."} — רישום מנוי לפי קישור פיד; האישור מציג כרטיס חי עם הכללים שהוחלו, אל תדקלמי אותם בטקסט.
- import_subscriptions {} — כשהמשתמש רוצה לייבא/להעביר מנויים מאפליקציה אחרת (Podcast Addict, Pocket Casts ועוד): פותח את חלון הייבוא באפליקציה עם השלבים. אל תגידי שאי אפשר לייבא.
- remove_podcast {"feedUrl": "...", "confirmed": bool} — ביטול מנוי. קודם שאלי את המשתמש לאישור; רק אחרי "כן" מפורש שלחי confirmed=true.
- open_screen {"screen": "queue"|"about"|"podcast", "feedUrl": "..." (רק ל-podcast)} — פותח מסך: תור ההאזנה (סדר, עדיפויות, ניגון מהתור), אודות, או פרקי פודקאסט (ניגון והורדה של פרק).
- list_episodes {"feedUrl": "...", "limit": 10} — פרקי פודקאסט, מהחדש לישן (מספר 1 = החדש ביותר), עם סימון הורד/הושמע.
- play_episode {"feedUrl": "...", "number": n} — מנגן פרק לפי המספר מ-list_episodes.
- download_episode {"feedUrl": "...", "number": n} — הורדת פרק למכשיר.
- delete_download {"feedUrl": "...", "number": n} — מחיקת ההורדה של פרק.
- mark_played {"feedUrl": "...", "number": n} — סימון פרק כהושמע (יוצא מהתור).
- play_queue {} — מנגן את תור ההאזנה מההתחלה (סדר לפי העדיפויות).
- seek {"seconds": n, "relative": true|false} — מדלג בפרק הנוכחי (relative: קדימה/אחורה בשניות, שלילי = אחורה; אחרת קופץ לשנייה הזו).
- playback {"action": "pause"|"resume"|"next"|"previous"} — שליטה בנגן.
- set_analytics {"enabled": bool} — הפעלה או כיבוי של שליחת אירועי שימוש (בלי תוכן שיחה).
- get_policy {"feedUrl": "..."} או {} — מציג בצ'אט כרטיס חי עם ההגדרות השמורות בפועל של פודקאסט מסוים או של ברירות המחדל. הכרטיס מדבר בעד עצמו — אל תדקלמי את ההגדרות בטקסט.
- set_policy {"feedUrl": "..." (אופציונלי; בלעדיו = ברירת מחדל), "autoDownload": bool, "keep": int, "speed": double, "skipIntroSec": int} — עדכון הגדרות.
- set_queue_rules {"feedUrl": "..." (חובה), "priority": 1-5 (1 מתנגן ראשון, שווים מתחלפים), "oldestFirst": bool (בתור ההאזנה, פרקים ישנים קודם)} — שינוי כללי תור ההאזנה של פודקאסט, רק לפי בקשת המשתמש.
""".trimIndent()

    fun execute(json: JSONObject, lib: Library): ExecResult {
        val tool = json.optString("tool")
        if (tool.isEmpty()) return ExecResult(json.optString("say", ""))
        val args = json.optJSONObject("args") ?: JSONObject()
        val out = run(tool, args, lib)
        // A follow-up question the model asked next to the action must not vanish: show only its question sentence.
        val q = lastQuestion(json.optString("say"))
        val text = if (q != null && !out.text.trimEnd().endsWith("?")) out.text + "\n" + q else out.text
        return ExecResult(text, out.card)
    }

    private fun lastQuestion(say: String): String? {
        val t = say.trim()
        if (!t.endsWith("?")) return null
        val start = t.dropLast(1).lastIndexOfAny(charArrayOf('.', '!', '\n', '…')) + 1
        return t.substring(start).trim().ifEmpty { null }
    }

    private fun run(tool: String, args: JSONObject, lib: Library): ExecResult {
        return when (tool) {
            "list_podcasts" -> ExecResult(listPodcasts(lib))
            "search_podcasts" -> ExecResult(search(lib, args.optString("name")))
            "subscribe" -> subscribe(lib, args.optString("feedUrl"))
            "get_policy" -> getPolicy(lib, args.optString("feedUrl").ifEmpty { null })
            "set_policy" -> ExecResult(setPolicy(lib, args))
            "set_queue_rules" -> ExecResult(setQueueRules(lib, args))
            "import_subscriptions" -> ExecResult(importSubscriptions(lib))
            "remove_podcast" -> ExecResult(removePodcast(lib, args.optString("feedUrl"), args.optBoolean("confirmed", false)))
            "open_screen" -> ExecResult(openScreen(lib, args.optString("screen"), args.optString("feedUrl").ifEmpty { null }))
            "playback" -> ExecResult(playback(lib, args.optString("action")))
            "list_episodes" -> ExecResult(listEpisodes(lib, args.optString("feedUrl"), args.optInt("limit", 10)))
            "play_episode" -> ExecResult(episodeAction(lib, args, "ננגן", lib::playEpisode))
            "download_episode" -> ExecResult(episodeAction(lib, args, "מוריד", lib::downloadEpisode))
            "delete_download" -> ExecResult(episodeAction(lib, args, "מחקתי את ההורדה של", lib::deleteDownload))
            "mark_played" -> ExecResult(episodeAction(lib, args, "סימנתי כהושמע:", lib::markPlayed))
            "play_queue" -> ExecResult(if (lib.playQueue()) "מנגן את התור מההתחלה." else "אין מה לנגן בתור או שהנגן לא זמין.")
            "seek" -> ExecResult(seek(lib, args))
            "set_analytics" -> ExecResult(setAnalytics(lib, args.optBoolean("enabled", true)))
            else -> ExecResult("לא מכירה את הכלי '$tool'.")
        }
    }

    private fun removePodcast(lib: Library, feedUrl: String, confirmed: Boolean): String {
        val pod = lib.get(feedUrl) ?: return "לא מצאתי מנוי כזה."
        if (!confirmed) return "להסיר את '${pod.title}' מהמנויים? תאשר ואסיר."
        return if (lib.removePodcast(feedUrl)) "הסרתי את '${pod.title}'." else "ההסרה לא הצליחה."
    }

    private fun openScreen(lib: Library, screen: String, feedUrl: String?): String {
        if (screen !in setOf("queue", "about", "podcast")) return "איזה מסך לפתוח: תור, אודות או פודקאסט?"
        if (screen == "podcast" && (feedUrl == null || lib.get(feedUrl) == null)) return "איזה פודקאסט לפתוח?"
        val label = when (screen) { "queue" -> "תור ההאזנה"; "about" -> "אודות"; else -> lib.get(feedUrl!!)!!.title }
        return if (lib.openScreen(screen, feedUrl)) "פתחתי: $label." else "לא הצלחתי לפתוח את המסך."
    }

    private fun listEpisodes(lib: Library, feedUrl: String, limit: Int): String {
        val pod = lib.get(feedUrl) ?: return "איזה פודקאסט? אני צריכה אחד מהמנויים."
        val rows = lib.episodes(feedUrl, limit.coerceIn(1, 20)) ?: return "לא הצלחתי לקרוא את הפיד."
        if (rows.isEmpty()) return "אין פרקים ב'${pod.title}'."
        return "הפרקים של '${pod.title}' (1 = החדש ביותר):\n" + rows.joinToString("\n") {
            "${it.n}. ${it.title}${if (it.downloaded) " [הורד]" else ""}${if (it.played) " [הושמע]" else ""}"
        }
    }

    private fun episodeAction(lib: Library, args: JSONObject, verb: String, act: (String, Int) -> Boolean): String {
        val feedUrl = args.optString("feedUrl")
        val pod = lib.get(feedUrl) ?: return "איזה פודקאסט?"
        val n = args.optInt("number", 0)
        if (n < 1) return "איזה פרק? אפשר לבקש קודם את רשימת הפרקים."
        return if (act(feedUrl, n)) "$verb פרק $n של '${pod.title}'." else "לא הצלחתי: הפרק לא קיים, כבר במצב הזה, או שהנגן לא זמין."
    }

    private fun seek(lib: Library, args: JSONObject): String {
        if (!args.has("seconds")) return "כמה שניות לדלג?"
        val sec = args.optInt("seconds")
        val relative = args.optBoolean("relative", true)
        return if (lib.seek(sec, relative)) "עשיתי." else "אין כרגע מה לנגן."
    }

    private fun playback(lib: Library, action: String): String {
        val label = when (action) { "pause" -> "עצרתי"; "resume" -> "ממשיכה לנגן"; "next" -> "עברתי לפרק הבא"; "previous" -> "חזרתי לפרק הקודם"; else -> return "איזו פעולה: עצירה, המשך, הבא או קודם?" }
        return if (lib.playback(action)) label else "אין כרגע מה לנגן."
    }

    private fun setAnalytics(lib: Library, enabled: Boolean): String =
        if (lib.setAnalytics(enabled)) (if (enabled) "שליחת אירועי השימוש הופעלה (בלי תוכן שיחה)." else "שליחת אירועי השימוש כבויה.")
        else "לא הצלחתי לשנות את ההגדרה."

    private fun importSubscriptions(lib: Library): String {
        val opened = lib.startImport()
        return (if (opened) "פתחתי את חלון הייבוא. " else "") +
            "ככה מעבירים את המנויים מ-Podcast Addict:\n" +
            "1. ב-Podcast Addict: הגדרות, גיבוי, שחזור וסנכרון, ייצוא OPML (מנויים בלבד), ושומרים את הקובץ בטלפון.\n" +
            "2. כאן: לוחצים על \"בחירת קובץ\" בחלון הייבוא ובוחרים את הקובץ.\n" +
            "עוברים המנויים בלבד; התקדמות, עדיפויות והגדרות לכל פודקאסט לא עוברים, ואפשר לשנות אותם בשיחה."
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

    private fun subscribe(lib: Library, feedUrl: String): ExecResult {
        if (!feedUrl.startsWith("http")) return ExecResult("כדי להירשם אני צריכה קישור פיד מלא.")
        return try {
            val title = lib.subscribeByUrl(feedUrl)
            val pod = lib.get(feedUrl)
            // CONV-2: the stored rules show on a live card, read back from the store, never recited from the model's words.
            if (pod != null) ExecResult(
                "נרשמת ל'$title'.\nהכללים שהוחלו מוצגים על הכרטיס — אפשר לשנות עליו או במילים, לפודקאסט הזה בלבד (למשל \"ישנים קודם בתור\" — מתוך 15 הפרקים האחרונים שלא נשמעו, לא מהפרק הראשון של הפודקאסט).",
                CardSpec(feedUrl, pod.title)
            ) else ExecResult("נרשמת ל'$title'.")
        } catch (e: Exception) {
            ExecResult("ההרשמה נכשלה: לא הצלחתי לקרוא את הפיד.")
        }
    }

    /** The rules that matter on subscribe: download, retention, queue order. */
    fun essentialRules(autoDownload: Boolean, keep: Int, oldestFirst: Boolean) =
        policyLine(PolicyPatch(autoDownload, keep, null, null, null, oldestFirst))

    /** DL-3 keep choices the card stepper walks through (CONV-2). */
    val KEEP_STEPS = intArrayOf(1, 3, 5, 10)

    /** The next keep choice in direction dir (+1/-1), clamped at the ends. */
    fun nextKeepStep(current: Int, dir: Int): Int =
        if (dir > 0) KEEP_STEPS.firstOrNull { it > current } ?: current
        else KEEP_STEPS.lastOrNull { it < current } ?: current

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

    private fun getPolicy(lib: Library, feedUrl: String?): ExecResult {
        if (feedUrl == null) {
            return ExecResult(
                "ברירות המחדל מוצגות על הכרטיס — אפשר לשנות עליו או במילים.",
                CardSpec(null, "ברירת מחדל לפודקאסט חדש")
            )
        }
        val pod = lib.get(feedUrl) ?: return ExecResult("אין מנוי עם הקישור הזה.")
        return ExecResult(
            "ההגדרות של '${pod.title}' מוצגות על הכרטיס — אפשר לשנות עליו או במילים.",
            CardSpec(feedUrl, pod.title)
        )
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
