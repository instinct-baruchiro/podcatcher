package com.baruchiro.podcatcher.conv

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.baruchiro.podcatcher.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * CONV-1 conversation screen: Hebrew chat that drives the app's own settings
 * through the Tools executor. Playback stays button-driven (owner direction);
 * this screen only manages subscriptions and policies. Until a real model
 * backend is chosen, NoBackend says so honestly instead of faking a reply.
 */
class ConvActivity : Activity() {

    private val history = mutableListOf<ConvMessage>()
    private lateinit var backend: ModelBackend
    private lateinit var lib: AndroidLibrary
    private lateinit var messages: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private val scope = CoroutineScope(Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        backend = NoBackend()
        lib = AndroidLibrary(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = LinearLayout.LAYOUT_DIRECTION_RTL
        }
        messages = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll = ScrollView(this).apply { addView(messages) }
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        input = EditText(this).apply { hint = "מה לשנות?"; id = R.id.convInput }
        val send = Button(this).apply { text = "שליחה"; id = R.id.convSend }
        row.addView(input, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        row.addView(send, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        root.addView(row)
        setContentView(root)

        send.setOnClickListener { onSend() }
    }

    private fun bubble(text: String, fromUser: Boolean) {
        val tv = TextView(this).apply {
            this.text = text
            textSize = 16f
            setPadding(24, 16, 24, 16)
            setBackgroundColor(if (fromUser) 0xFFE3F2FD.toInt() else Color.TRANSPARENT)
            gravity = if (fromUser) Gravity.START else Gravity.END
        }
        messages.addView(tv, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun onSend() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        input.text.clear()
        history.add(ConvMessage(true, text))
        bubble(text, true)
        scope.launch {
            val raw = withContext(Dispatchers.IO) { backend.reply(history, Tools.SPEC) }
            val json = runCatching { JSONObject(raw) }.getOrElse {
                JSONObject().put("say", "התקבלה תשובה לא תקינה מהמנוע.")
            }
            val confirmation = withContext(Dispatchers.IO) { Tools.execute(json, lib) }
            if (confirmation.isNotBlank()) {
                history.add(ConvMessage(false, confirmation))
                bubble(confirmation, false)
            }
        }
    }
}
