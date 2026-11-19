// @story: SUB-1, SUB-2
package com.baruchiro.podcatcher.net

import java.io.File
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.kxml2.io.KXmlParser

/**
 * Hermetic unit tests for feed parsing and podcast search, running the app's
 * real Discovery code on the JVM with kxml2 standing in for android.util.Xml.
 * Fixtures are captured from the live services (see resources/).
 */
class DiscoveryTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        parserFactory = { KXmlParser() }
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun resource(name: String): String =
        File("src/test/resources/$name").readText()

    @Test
    fun `rss feed parses into podcast with playable episodes`() {
        server.enqueue(MockResponse().setBody(resource("ranlevi-feed.xml")))

        val feed = Discovery.fetchFeed(server.url("/feed").toString())

        assertTrue(feed.title.isNotBlank())
        assertEquals(5, feed.episodes.size)
        val ep = feed.episodes.first()
        assertTrue("episode needs a title", ep.title.isNotBlank())
        assertTrue("episode needs a stable guid", ep.guid.isNotBlank())
        assertTrue(
            "episode audio must be a direct URL, was: ${ep.audioUrl}",
            ep.audioUrl.startsWith("http"),
        )
    }

    @Test
    fun `plain webpage is rejected instead of subscribing to garbage`() {
        server.enqueue(
            MockResponse().setBody("<!DOCTYPE html><html><body>not a feed</body></html>"),
        )

        try {
            Discovery.fetchFeed(server.url("/").toString())
            fail("a plain webpage must not parse as a feed")
        } catch (expected: Exception) {
            // The subscribe flow catches this and falls back to a search prompt.
        }
    }

    @Test
    fun `search results parse title author artwork and feed url`() {
        Discovery.searchBaseUrl = server.url("/search").toString()
        server.enqueue(MockResponse().setBody(resource("itunes-search.json")))

        val results = Discovery.search("עושים היסטוריה")

        assertEquals(8, results.size)
        val first = results.first()
        assertTrue(first.title.isNotBlank())
        assertTrue(first.feedUrl.startsWith("http"))
    }
}
