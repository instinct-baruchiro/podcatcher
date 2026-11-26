// @story: QUEUE-1
package com.baruchiro.podcatcher.data

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistTest {

    private val history = QueueSource("history", priority = 1, unplayed = listOf("h1", "h2", "h3"))
    private val news = QueueSource("news", priority = 1, unplayed = listOf("n1"))
    private val science = QueueSource("science", priority = 2, unplayed = listOf("s1", "s2"))

    @Test
    fun `equal-priority podcasts take turns and a lower priority waits until they are exhausted`() {
        val queue = buildQueue(listOf(science, history, news)).map { it.episodeId }

        // history and news alternate while both have episodes; science only after both run out.
        assertEquals(listOf("h1", "n1", "h2", "h3", "s1", "s2"), queue)
    }
}
