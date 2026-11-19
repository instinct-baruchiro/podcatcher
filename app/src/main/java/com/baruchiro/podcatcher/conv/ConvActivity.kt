package com.baruchiro.podcatcher.conv

import android.app.Activity
import android.app.AlertDialog
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

    private val prefs by lazy { getSharedPreferences("conv_config", MODE_PRIVATE) }
    private val secrets by lazy { SecretStore(this) }

    private fun configuredBackend(): ModelBackend {
        val key = secrets.get("gemini_key").orEmpty()
        return if (key.isBlank()) NoBackend()
        else GeminiBackend(
            apiKey = key,
            model = prefs.getString("gemini_model", "gemini-3.1-flash-lite") ?: "gemini-3.1-flash-lite"
        )
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        backend = configuredBackend()
        lib = AndroidLibrary(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = LinearLayout.LAYOUT_DIRECTION_RTL
        }

        // Header: title + engine settings.
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(12))
        }
        header.addView(TextView(this).apply {
            text = "שיחה"
            textSize = 24f
            setTextColor(getColor(R.color.textPrimary))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        val gear = Button(this).apply {
            text = "⚙"
            setTextColor(getColor(R.color.accent))
            background = getDrawable(R.drawable.bg_button_tonal)
            minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setOnClickListener { showBackendDialog() }
        }
        header.addView(gear)
        root.addView(header)

        messages = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(12))
        }
        scroll = ScrollView(this).apply { addView(messages) }
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        // Bottom input bar.
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = getDrawable(R.drawable.bg_bottom_bar)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        input = EditText(this).apply {
            hint = "מה לשנות?"
            id = R.id.convInput
            background = getDrawable(R.drawable.bg_input)
            setPadding(dp(18), 0, dp(18), 0)
            setSingleLine(true)
        }
        val send = Button(this).apply { text = "שליחה"; id = R.id.convSend }
        row.addView(input, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(10) })
        row.addView(send, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        root.addView(row)
        setContentView(root)

        send.setOnClickListener { onSend() }
        bubble("ספרי לי מה לשנות בהגדרות — למשל כמה פרקים לשמור או באיזה קצב לנגן.", false)
    }

    private fun bubble(text: String, fromUser: Boolean) {
        val tv = TextView(this).apply {
            this.text = text
            textSize = 16f
            setPadding(dp(16), dp(11), dp(16), dp(11))
            background = getDrawable(if (fromUser) R.drawable.bg_bubble_user else R.drawable.bg_bubble_app)
            setTextColor(getColor(if (fromUser) R.color.onAccent else R.color.textPrimary))
            maxWidth = (resources.displayMetrics.widthPixels * 0.8).toInt()
        }
        val lp = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            gravity = if (fromUser) Gravity.START else Gravity.END
            bottomMargin = dp(10)
        }
        messages.addView(tv, lp)
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun showBackendDialog() {
        val pad = dp(20)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, 0) }
        fun field(hintText: String, value: String) = EditText(this).apply {
            hint = hintText
            setText(value)
            background = getDrawable(R.drawable.bg_input)
            setPadding(dp(16), 0, dp(16), 0)
            setSingleLine(true)
        }
        val keyField = field("מפתח API (Gemini)", secrets.get("gemini_key") ?: "")
        val modelField = field("מודל", prefs.getString("gemini_model", "gemini-3.1-flash-lite") ?: "gemini-3.1-flash-lite")
        col.addView(TextView(this).apply {
            text = "המפתח נשמר במכשיר בלבד ומשמש רק לשיחה הזו."
            setTextColor(getColor(R.color.textSecondary))
            textSize = 13f
            setPadding(0, 0, 0, dp(12))
        })
        col.addView(keyField, LinearLayout.LayoutParams(MATCH_PARENT, dp(48)).apply { bottomMargin = dp(10) })
        col.addView(modelField, LinearLayout.LayoutParams(MATCH_PARENT, dp(48)))
        AlertDialog.Builder(this)
            .setTitle("מנוע שיחה")
            .setView(col)
            .setPositiveButton("שמירה") { _, _ ->
                val newKey = keyField.text.toString().trim()
                if (newKey.isBlank()) secrets.remove("gemini_key") else secrets.put("gemini_key", newKey)
                prefs.edit()
                    .putString("gemini_model", modelField.text.toString().trim().ifBlank { "gemini-3.1-flash-lite" })
                    .apply()
                backend = configuredBackend()
                bubble(if (keyField.text.isBlank()) "מנוע השיחה כבוי — נשארו התשובות הקבועות." else "מנוע השיחה חובר.", false)
            }
            .setNegativeButton("ביטול", null)
            .show()
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
