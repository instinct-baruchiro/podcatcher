// @story: CONV-1, CONV-2, QUEUE-1
package com.baruchiro.podcatcher.conv

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CONV-1 tool layer + executor, driven by a TESTS-ONLY stub backend. The stub
 * pattern-matches Hebrew phrases; it exists to prove the wire between a model
 * reply and a deterministic settings change. It is not a real model and never
 * ships in the app (app/src/test only).
 */
class ToolsTest {

    private class FakeLibrary : Library {
        val pods = mutableListOf(
            Pod("https://a.example/feed", "עושים היסטוריה", false, 3, 1.0, 0)
        )
        var defaults = PolicyPatch(false, 3, 1.0, 0)
        val subscribed = mutableListOf<String>()

        override fun list() = pods.toList()
        override fun findByName(name: String) = pods.firstOrNull { it.title.contains(name) }
        override fun get(feedUrl: String) = pods.firstOrNull { it.feedUrl == feedUrl }
        override fun applyPolicy(feedUrl: String?, patch: PolicyPatch): Pod? {
            if (feedUrl == null) {
                defaults = PolicyPatch(
                    patch.autoDownload ?: defaults.autoDownload!!,
                    patch.keep ?: defaults.keep!!,
                    patch.speed ?: defaults.speed!!,
                    patch.skipIntroSec ?: defaults.skipIntroSec!!
                )
                return null
            }
            val i = pods.indexOfFirst { it.feedUrl == feedUrl }
            if (i < 0) return null
            val p = pods[i]
            pods[i] = Pod(p.feedUrl, p.title,
                patch.autoDownload ?: p.autoDownload,
                patch.keep ?: p.keep,
                patch.speed ?: p.speed,
                patch.skipIntroSec ?: p.skipIntroSec,
                patch.priority ?: p.priority,
                patch.oldestFirst ?: p.oldestFirst)
            return pods[i]
        }
        override fun defaultPolicy() = defaults
        override fun subscribeByUrl(feedUrl: String): String {
            subscribed.add(feedUrl)
            pods.add(Pod(feedUrl, "פודקאסט חדש", false, 3, 1.0, 0))
            return "פודקאסט חדש"
        }
        override fun searchByName(name: String) =
            listOf("עושים היסטוריה" to "https://a.example/feed", "מבט" to "https://b.example/feed")
    }

    /** TESTS-ONLY stub: stands in for a model returning the Tools JSON protocol. */
    private fun stubModelReply(userText: String): String = when {
        userText.contains("מהירות") && userText.contains("1.5") ->
            """{"say":"משנה מהירות","tool":"set_policy","args":{"feedUrl":"https://a.example/feed","speed":1.5}}"""
        userText.contains("לשמור") ->
            """{"say":"","tool":"set_policy","args":{"feedUrl":"https://a.example/feed","keep":5}}"""
        userText.contains("ברירת מחדל") ->
            """{"say":"","tool":"set_policy","args":{"keep":10}}"""
        userText.contains("עדיפות") ->
            """{"say":"","tool":"set_queue_rules","args":{"feedUrl":"https://a.example/feed","priority":1,"oldestFirst":true}}"""
        userText.contains("מה יש לי") ->
            """{"say":"","tool":"list_podcasts","args":{}}"""
        userText.contains("חפשי") ->
            """{"say":"","tool":"search_podcasts","args":{"name":"היסטוריה"}}"""
        userText.contains("הירשמי") ->
            """{"say":"","tool":"subscribe","args":{"feedUrl":"https://c.example/feed"}}"""
        else -> """{"say":"לא הבנתי, אפשר לנסח מחדש?"}"""
    }

    private fun turn(lib: Library, userText: String): String =
        Tools.execute(JSONObject(stubModelReply(userText)), lib).text

    @Test
    fun setPolicyPerPodcast() {
        val lib = FakeLibrary()
        val reply = turn(lib, "תשני מהירות ל-1.5 בעושים היסטוריה")
        assertEquals(1.5, lib.get("https://a.example/feed")!!.speed, 0.001)
        assertTrue(reply.contains("מהירות 1.5x"))
        assertTrue(reply.contains("עושים היסטוריה"))
    }

    @Test
    fun queueRulesChangeOnlyOnRequestAndStayInRange() {
        val lib = FakeLibrary()
        assertEquals(3, lib.get("https://a.example/feed")!!.priority)

        val reply = turn(lib, "תני עדיפות ראשונה לעושים היסטוריה")
        assertEquals(1, lib.get("https://a.example/feed")!!.priority)
        assertTrue(lib.get("https://a.example/feed")!!.oldestFirst)
        assertTrue(reply.contains("עדיפות 1"))

        val tooHigh = Tools.execute(JSONObject("""{"tool":"set_queue_rules","args":{"feedUrl":"https://a.example/feed","priority":9}}"""), lib)
        assertEquals(1, lib.get("https://a.example/feed")!!.priority)
        assertTrue(tooHigh.text.contains("בין 1 ל-5"))
    }

    @Test
    fun setPolicyKeep() {
        val lib = FakeLibrary()
        val reply = turn(lib, "לשמור 5 פרקים")
        assertEquals(5, lib.get("https://a.example/feed")!!.keep)
        assertTrue(reply.contains("שמירת 5 פרקים"))
    }

    @Test
    fun setDefaultPolicy() {
        val lib = FakeLibrary()
        val reply = turn(lib, "שני ברירת מחדל לשמירת 10")
        assertEquals(10, lib.defaultPolicy().keep)
        assertTrue(reply.contains("ברירות המחדל"))
        assertEquals(3, lib.get("https://a.example/feed")!!.keep) // existing subs untouched
    }

    @Test
    fun listPodcasts() {
        val lib = FakeLibrary()
        val reply = turn(lib, "מה יש לי במנויים?")
        assertTrue(reply.contains("עושים היסטוריה"))
        assertTrue(reply.contains("1 מנויים"))
    }

    @Test
    fun searchShowsCandidates() {
        val lib = FakeLibrary()
        val reply = turn(lib, "חפשי היסטוריה")
        assertTrue(reply.contains("2 מועמדים"))
        assertTrue(reply.contains("https://a.example/feed"))
    }

    @Test
    fun subscribeByUrl() {
        val lib = FakeLibrary()
        val result = Tools.execute(JSONObject(stubModelReply("הירשמי לפודקאסט בקישור הזה")), lib)
        assertTrue(lib.subscribed.contains("https://c.example/feed"))
        assertTrue(result.text.contains("נרשמת"))
        // CONV-2: the stored rules render as a live card bound to the new podcast, not a recited paragraph.
        assertEquals(CardSpec("https://c.example/feed", "פודקאסט חדש"), result.card)
        assertTrue(result.text, !result.text.contains("שמירת 3 פרקים"))
        assertTrue("per-podcast change hint missing: ${result.text}", result.text.contains("לפודקאסט הזה בלבד"))
        // A follow-up question the model asked next to the action is not dropped; its confirmation claim is not echoed.
        val asked = Tools.execute(JSONObject("""{"say":"נרשמתי בהצלחה. האם תרצה שאוריד פרקים?","tool":"subscribe","args":{"feedUrl":"https://d.example/feed"}}"""), lib)
        assertTrue(asked.text, asked.text.trimEnd().endsWith("האם תרצה שאוריד פרקים?") && !asked.text.contains("נרשמתי בהצלחה"))
    }

    @Test
    fun policyAnswersCarryLiveCard() {
        val lib = FakeLibrary()
        // Asking for a podcast's settings returns the live card, not a recited paragraph.
        val pod = Tools.execute(JSONObject("""{"tool":"get_policy","args":{"feedUrl":"https://a.example/feed"}}"""), lib)
        assertEquals(CardSpec("https://a.example/feed", "עושים היסטוריה"), pod.card)
        assertTrue(pod.text, !pod.text.contains("שמירת"))
        // Same for the defaults.
        val defaults = Tools.execute(JSONObject("""{"tool":"get_policy","args":{}}"""), lib)
        assertEquals(CardSpec(null, "ברירת מחדל לפודקאסט חדש"), defaults.card)
        // A change in words carries no new card: visible cards rebind to the stored state.
        val change = Tools.execute(JSONObject("""{"tool":"set_policy","args":{"feedUrl":"https://a.example/feed","keep":5}}"""), lib)
        assertEquals(null, change.card)
        assertEquals(5, lib.get("https://a.example/feed")!!.keep)
        // An unknown podcast is an honest error, no card.
        val missing = Tools.execute(JSONObject("""{"tool":"get_policy","args":{"feedUrl":"https://nope.example/feed"}}"""), lib)
        assertEquals(null, missing.card)
        assertTrue(missing.text.contains("אין מנוי"))
    }

    @Test
    fun keepStepperWalksTheStoryChoices() {
        assertEquals(3, Tools.nextKeepStep(1, +1))
        assertEquals(5, Tools.nextKeepStep(3, +1))
        assertEquals(10, Tools.nextKeepStep(5, +1))
        assertEquals(10, Tools.nextKeepStep(10, +1)) // clamps at the top
        assertEquals(5, Tools.nextKeepStep(10, -1))
        assertEquals(1, Tools.nextKeepStep(3, -1))
        assertEquals(1, Tools.nextKeepStep(1, -1))  // clamps at the bottom
        assertEquals(10, Tools.nextKeepStep(50, -1)) // a word-set value steps to the nearest choice
        assertEquals(1, Tools.nextKeepStep(0, +1))
    }

    @Test
    fun invalidKeepRejected() {
        val lib = FakeLibrary()
        val reply = Tools.execute(
            JSONObject("""{"tool":"set_policy","args":{"feedUrl":"https://a.example/feed","keep":500}}"""), lib)
        assertTrue(reply.text.contains("בין 1 ל-50"))
        assertEquals(3, lib.get("https://a.example/feed")!!.keep)
    }

    @Test
    fun unknownPodcastRejected() {
        val lib = FakeLibrary()
        val reply = Tools.execute(
            JSONObject("""{"tool":"set_policy","args":{"feedUrl":"https://nope.example/feed","keep":5}}"""), lib)
        assertTrue(reply.text.contains("אין מנוי"))
    }

    @Test
    fun unknownToolRejected() {
        val lib = FakeLibrary()
        val reply = Tools.execute(JSONObject("""{"tool":"delete_everything","args":{}}"""), lib)
        assertTrue(reply.text.contains("לא מכירה"))
    }

    @Test
    fun plainConversationPassesThrough() {
        val lib = FakeLibrary()
        assertEquals("לא הבנתי, אפשר לנסח מחדש?", turn(lib, "מה שלומך"))
    }
}
