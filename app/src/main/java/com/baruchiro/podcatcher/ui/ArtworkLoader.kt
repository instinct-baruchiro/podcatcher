package com.baruchiro.podcatcher.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import java.net.URL
import java.util.concurrent.Executors

class ArtworkLoader {
    private val cache = LruCache<String, Bitmap>(40)
    private val pool = Executors.newFixedThreadPool(2)

    fun into(view: ImageView, url: String) {
        view.setImageDrawable(null)
        if (url.isEmpty()) return
        cache.get(url)?.let { view.setImageBitmap(it); return }
        view.tag = url
        pool.execute {
            try {
                val bmp = BitmapFactory.decodeStream(URL(url).openStream()) ?: return@execute
                cache.put(url, bmp)
                view.post { if (view.tag == url) view.setImageBitmap(bmp) }
            } catch (_: Exception) {
                // Missing artwork is cosmetic.
            }
        }
    }
}
