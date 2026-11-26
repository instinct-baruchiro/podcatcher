// @story: IMPORT-1
package com.baruchiro.podcatcher.data

import org.junit.Assert.assertEquals
import org.junit.Test

class OpmlTest {
    @Test
    fun `reads feed links from nested outlines, skips folders and duplicates`() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <opml version="2.0"><body>
              <outline text="Podcasts">
                <outline type="rss" text="Show A" xmlUrl="https://a.example/feed.xml"/>
                <outline type="rss" title="Show B" text="x" xmlUrl="https://b.example/rss"/>
                <outline type="rss" text="Show A again" xmlUrl="https://a.example/feed.xml"/>
              </outline>
              <outline text="No feed here"/>
            </body></opml>"""
        val feeds = Opml.parse(xml.byteInputStream())

        assertEquals(
            listOf(OpmlFeed("Show A", "https://a.example/feed.xml"), OpmlFeed("Show B", "https://b.example/rss")),
            feeds
        )
    }
}
