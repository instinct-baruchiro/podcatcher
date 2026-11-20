package com.baruchiro.podcatcher.conv

import com.baruchiro.podcatcher.data.Podcast
import com.baruchiro.podcatcher.data.Policy
import com.baruchiro.podcatcher.data.Store
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Real-model slice, run MANUALLY with GEMINI_API_KEY in the environment:
 *   real Hebrew request -> real Gemini reply -> real Tools executor ->
 *   real Store persistence (Robolectric SharedPreferences) -> restart check.
 * Skipped when no key is present (CI never has one), so it is not a CI gate.
 * What this proves: the model produces executable tool JSON for a real
 * Hebrew request and the deterministic executor persists the rule for real.
 * What it does NOT prove: on-device pixels and Keystore storage (those are
 * covered by instrumented tests / the on-device demo).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RealGeminiRobolectricTest {

    @Test
    fun hebrewRuleChange_realModel_realExecutor_realPersistence() = runBlocking {
        val key = System.getenv("GEMINI_API_KEY")
        if (key.isNullOrBlank()) {
            println("SKIP: GEMINI_API_KEY not set (expected on CI)")
            return@runBlocking
        }

        val c = RuntimeEnvironment.getApplication()
        val feedUrl = "https://real-demo.example/feed"
        Store.removePodcast(c, feedUrl)
        Store.addPodcast(c, Podcast(feedUrl, "הפודקאסט שלי", "", "", Policy(keep = 5)))
        Store.setDefaultPolicy(c, Policy(keep = 5))

        val backend = GeminiBackend(apiKey = key, model = "gemini-3.1-flash-lite")
        val lib = AndroidLibrary(c)
        val ask = "מעכשיו שמור רק 3 פרקים מכל פודקאסט"
        val raw = backend.reply(listOf(ConvMessage(true, ask)), Tools.SPEC)
        val evidence = StringBuilder()
        evidence.append("ASK: $ask\nMODEL_RAW: $raw\n")

        val confirmation = Tools.execute(JSONObject(raw), lib)
        evidence.append("EXECUTOR_CONFIRMATION: $confirmation\n")

        // Simulated restart: fresh read of persisted state from the store.
        val c2 = RuntimeEnvironment.getApplication()
        val persisted = Store.podcasts(c2).first { it.feedUrl == feedUrl }.policy.keep
        val defaultKeep = Store.defaultPolicy(c2).keep
        evidence.append("PERSISTED podcastKeep=$persisted defaultKeep=$defaultKeep\n")
        java.io.File("build/real-gemini-evidence.txt").writeText(evidence.toString(), Charsets.UTF_8)
        assertTrue(
            "expected keep=3 persisted on the podcast or as default, got podcast=$persisted default=$defaultKeep",
            persisted == 3 || defaultKeep == 3
        )
        Store.removePodcast(c2, feedUrl)
        Store.setDefaultPolicy(c2, Policy())
    }

    /** Owner's flow: name search, pick 1, subscribe. The confirmation must state the rules actually stored. */
    @Test
    fun subscribeFlow_confirmationStatesStoredRules() = runBlocking {
        val key = System.getenv("GEMINI_API_KEY")
        if (key.isNullOrBlank()) {
            println("SKIP: GEMINI_API_KEY not set (expected on CI)")
            return@runBlocking
        }
        val c = RuntimeEnvironment.getApplication()
        Store.setDefaultPolicy(c, Policy())
        val backend = GeminiBackend(apiKey = key, model = "gemini-3.1-flash-lite")
        val lib = AndroidLibrary(c)
        val history = mutableListOf<ConvMessage>()
        val evidence = StringBuilder()
        var last = ""
        for (user in listOf("הוסף פודקאסט מסילת ישרים הרב הלל", "1")) {
            history.add(ConvMessage(true, user))
            val raw = backend.reply(history, Tools.SPEC)
            last = Tools.execute(JSONObject(raw), lib)
            history.add(ConvMessage(false, last))
            evidence.append("USER: $user\nMODEL_RAW: $raw\nCONFIRMATION: $last\n")
        }
        val stored = Store.podcasts(c).firstOrNull()
        evidence.append("STORED: " + (stored?.let { "keep=${it.policy.keep} auto=${it.policy.autoDownload} prio=${it.policy.priority}" } ?: "none") + "\n")
        java.io.File("build/real-gemini-subscribe-evidence.txt").writeText(evidence.toString(), Charsets.UTF_8)
        assertTrue("no subscription was stored", stored != null)
        assertTrue("confirmation must state the keep rule, got: $last", last.contains("שמירת ${stored!!.policy.keep} פרקים"))
        assertTrue("confirmation must state the download rule, got: $last",
            last.contains(if (stored.policy.autoDownload) "הורדה אוטומטית" else "ללא הורדה אוטומטית"))
        Store.removePodcast(c, stored.feedUrl)
        Store.setDefaultPolicy(c, Policy())
    }
}
