package com.baruchiro.podcatcher.net

import android.util.Xml
import org.xmlpull.v1.XmlPullParser

// Indirection so feed parsing is testable on the JVM, where android.util.Xml
// does not exist: unit tests replace parserFactory (e.g. with kxml2).
var parserFactory: () -> XmlPullParser = { Xml.newPullParser() }

fun newParser(): XmlPullParser = parserFactory()
