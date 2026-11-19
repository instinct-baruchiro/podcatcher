package com.baruchiro.podcatcher.net

import android.util.Xml
import org.xmlpull.v1.XmlPullParser

// Indirection so the feed-parsing logic can also run on the JVM in tests,
// where android.util.Xml does not exist.
fun newParser(): XmlPullParser = Xml.newPullParser()
