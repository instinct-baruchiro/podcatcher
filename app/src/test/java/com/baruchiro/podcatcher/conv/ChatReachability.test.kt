// @story: CONV-1
package com.baruchiro.podcatcher.conv

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Owner rule 2026-11-26: everything in the app must be reachable through the chat. */
class ChatReachabilityTest {

    private val handler = Regex("setOnClickListener|setOnLongClickListener|setOnItemClickListener|setOnItemLongClickListener|setOnCheckedChangeListener|setPositiveButton|setItems|onItemSelected")

    @Test
    fun `every recorded UI action has a chat tool that exists`() {
        for ((feature, tool) in ChatPaths.FEATURES) {
            assertTrue("'$feature' points at unknown chat tool $tool", Tools.SPEC.contains("- $tool {"))
        }
    }

    @Test
    fun `screens gain no new actions without a chat path being recorded`() {
        val dir = File("src/main/java/com/baruchiro/podcatcher/ui")
        for ((file, expected) in ChatPaths.UI_HANDLER_COUNTS) {
            val actual = handler.findAll(File(dir, file).readText()).count()
            assertEquals(
                "$file has $actual click/choice handlers, recorded $expected. Add the chat path " +
                    "(a tool in Tools.kt) and a ChatPaths.FEATURES entry, then update UI_HANDLER_COUNTS.",
                expected, actual
            )
        }
    }
}
