package com.baruchiro.podcatcher.data

import org.w3c.dom.Element
import java.io.InputStream
import javax.xml.parsers.DocumentBuilderFactory

data class OpmlFeed(val title: String, val feedUrl: String)

/** Reads the feed links from an OPML subscriptions export (Podcast Addict, Pocket Casts, AntennaPod...). */
object Opml {
    fun parse(input: InputStream): List<OpmlFeed> {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        val doc = factory.newDocumentBuilder().parse(input)
        val nodes = doc.getElementsByTagName("outline")
        val seen = LinkedHashMap<String, OpmlFeed>()
        for (i in 0 until nodes.length) {
            val e = nodes.item(i) as Element
            val url = e.getAttribute("xmlUrl").trim()
            if (!url.startsWith("http", ignoreCase = true) || url in seen) continue
            val title = e.getAttribute("title").ifBlank { e.getAttribute("text") }.trim()
            seen[url] = OpmlFeed(title.ifBlank { url }, url)
        }
        return seen.values.toList()
    }
}
