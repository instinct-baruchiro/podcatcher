package com.baruchiro.podcatcher.conv

import org.json.JSONObject

data class ConvMessage(val fromUser: Boolean, val text: String)

/**
 * Pluggable model backend for the conversation. The app ships with
 * [NoBackend], which answers honestly that no engine is attached; a real
 * backend (owner's choice — see STORIES.md CONV-1) implements this interface
 * and returns the Tools JSON protocol. Test-only stubs live under
 * app/src/androidTest and are never shipped in the app.
 */
interface ModelBackend {
    /** Returns raw model output expected to be a Tools JSON object. */
    suspend fun reply(history: List<ConvMessage>, toolSpec: String): String
}

class NoBackend : ModelBackend {
    override suspend fun reply(history: List<ConvMessage>, toolSpec: String): String =
        JSONObject().put(
            "say",
            "עדיין לא חובר מנוע שיחה לאפליקציה. כרגע אפשר לנהל את ההגדרות ידנית במסך הפודקאסט."
        ).toString()
}
