package com.baruchiro.podcatcher.net

import android.util.Xml
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.io.StringReader
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class SearchResult(val title: String, val author: String, val artwork: String, val feedUrl: String)
data class Episode(val title: String, val date: String, val audioUrl: String, val guid: String)
data class FeedInfo(val title: String, val author: String, val artwork: String, val episodes: List<Episode>)

object Discovery {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun search(query: String): List<SearchResult> {
        val url = "https://itunes.apple.com/search?media=podcast&limit=8&term=" +
            URLEncoder.encode(query, "UTF-8")
        val body = client.newCall(Request.Builder().url(url).build()).execute().use {
            if (!it.isSuccessful) throw IOException("search HTTP " + it.code)
            it.body?.string() ?: ""
        }
        val results = JSONObject(body).getJSONArray("results")
        val out = mutableListOf<SearchResult>()
        for (i in 0 until results.length()) {
            val o = results.getJSONObject(i)
            val feed = o.optString("feedUrl")
            if (feed.isNotEmpty()) {
                out.add(
                    SearchResult(
                        o.optString("collectionName"),
                        o.optString("artistName"),
                        o.optString("artworkUrl100"),
                        feed
                    )
                )
            }
        }
        return out
    }

    fun fetchFeed(feedUrl: String, limit: Int = 50): FeedInfo {
        val xml = client.newCall(Request.Builder().url(feedUrl).build()).execute().use {
            if (!it.isSuccessful) throw IOException("feed HTTP " + it.code)
            it.body?.string() ?: throw IOException("empty feed")
        }
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(xml))

        var title = ""
        var author = ""
        var artwork = ""
        val episodes = mutableListOf<Episode>()
        var inItem = false
        var t = ""
        var d = ""
        var a = ""
        var g = ""
        var lastTag = ""

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    lastTag = parser.name
                    when (parser.name) {
                        "item" -> { inItem = true; t = ""; d = ""; a = ""; g = "" }
                        "enclosure" -> if (inItem) {
                            val u = parser.getAttributeValue(null, "url")
                            if (!u.isNullOrEmpty()) a = u
                        }
                        "itunes:image" -> if (!inItem && artwork.isEmpty()) {
                            artwork = parser.getAttributeValue(null, "href") ?: ""
                        }
                    }
                }
                XmlPullParser.TEXT -> {
                    val text = parser.text?.trim() ?: ""
                    if (text.isNotEmpty()) {
                        if (inItem) {
                            when (lastTag) {
                                "title" -> if (t.isEmpty()) t = text
                                "pubDate" -> if (d.isEmpty()) d = text
                                "guid" -> if (g.isEmpty()) g = text
                            }
                        } else {
                            when (lastTag) {
                                "title" -> if (title.isEmpty()) title = text
                                "itunes:author", "managingEditor" -> if (author.isEmpty()) author = text
                                "url" -> if (artwork.isEmpty()) artwork = text
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> if (parser.name == "item" && inItem) {
                    inItem = false
                    if (a.isNotEmpty()) {
                        episodes.add(Episode(t, d.take(16), a, if (g.isNotEmpty()) g else a))
                    }
                }
            }
            if (episodes.size >= limit) break
            event = parser.next()
        }
        if (title.isEmpty() || episodes.isEmpty()) throw IOException("no podcast at link")
        return FeedInfo(title, author, artwork, episodes)
    }
}
