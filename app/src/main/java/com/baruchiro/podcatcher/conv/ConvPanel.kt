package com.baruchiro.podcatcher.conv

import com.baruchiro.podcatcher.telemetry.ConversationLog

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.view.Gravity
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.baruchiro.podcatcher.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.abs

/**
 * CONV-1 conversation, always present at the bottom of the screen: a compact
 * composer that expands (tap or pull the handle up, or just send) into a
 * conversation drawer above it. It drives the app's own settings through the
 * Tools executor; playback stays button-driven (owner direction). Without an
 * API key the NoBackend answers honestly instead of faking a reply.
 */
class ConvPanel(private val activity: Activity, hostView: ViewGroup) {

    private val history = mutableListOf<ConvMessage>()
    private var backend: ModelBackend
    private val lib = AndroidLibrary(activity)
    private val scope = CoroutineScope(Dispatchers.Main)
    private val prefs = activity.getSharedPreferences("conv_config", Activity.MODE_PRIVATE)
    private val secrets = SecretStore(activity)
    private val convLog = ConversationLog(
        activity.getSharedPreferences("telemetry", Activity.MODE_PRIVATE),
        channel = if (activity.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) "test" else "prod"
    )
    private val appVersion = activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: "?"

    private fun store(role: String, text: String) {
        if (convLog.consent != ConversationLog.Consent.GRANTED) return
        Thread { convLog.record(role, text, appVersion) }.start()
    }

    /** REL-1: nothing is stored before the listener has seen this and agreed. */
    private fun showNotice(then: () -> Unit) {
        android.app.AlertDialog.Builder(activity)
            .setTitle("שמירת שיחות")
            .setMessage("השיחות עם האפליקציה נשמרות לצורך בקרה ושיפור השירות, ואפשר למחוק אותן באודות. בלי הסכמה, השיחה ממשיכה לעבוד והשיחות לא נשמרות.")
            .setPositiveButton("מסכים") { _, _ -> convLog.consent = ConversationLog.Consent.GRANTED; then() }
            .setNegativeButton("לא מסכים") { _, _ -> convLog.consent = ConversationLog.Consent.DECLINED; then() }
            .setCancelable(false)
            .show()
    }

    private val host: ViewGroup = hostView
    private val sheet: LinearLayout
    private val messages: LinearLayout
    private val scroll: ScrollView
    private val input: EditText

    var expanded = false
        private set

    private fun dp(v: Int): Int = (v * activity.resources.displayMetrics.density).toInt()

    private fun configuredBackend(): ModelBackend {
        val key = secrets.get("gemini_key").orEmpty()
        return if (key.isBlank()) NoBackend()
        else GeminiBackend(
            apiKey = key,
            model = prefs.getString("gemini_model", DEFAULT_MODEL) ?: DEFAULT_MODEL
        )
    }

    init {
        backend = configuredBackend()
        sheet = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            background = activity.getDrawable(R.drawable.bg_sheet)
        }

        // Handle: tap toggles, pulling up expands, pulling down collapses.
        val handle = LinearLayout(activity).apply {
            gravity = Gravity.CENTER
            contentDescription = "מגירת השיחה"
        }
        handle.addView(View(activity).apply { background = activity.getDrawable(R.drawable.bg_handle) },
            LinearLayout.LayoutParams(dp(36), dp(4)))
        val gestures = GestureDetector(activity, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapUp(e: MotionEvent): Boolean { setExpanded(!expanded); return true }
            override fun onScroll(a: MotionEvent?, b: MotionEvent, dx: Float, dy: Float): Boolean {
                val total = (b.y - (a?.y ?: b.y))
                if (abs(total) > dp(24)) setExpanded(total < 0)
                return true
            }
        })
        handle.setOnTouchListener { v, e -> gestures.onTouchEvent(e).also { if (e.action == MotionEvent.ACTION_UP) v.performClick() } }
        sheet.addView(handle, LinearLayout.LayoutParams(MATCH_PARENT, dp(24)))

        messages = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(8))
        }
        scroll = ScrollView(activity).apply {
            addView(messages)
            visibility = View.GONE
        }
        sheet.addView(scroll, drawerParams(keyboardOpen = false))

        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(2), dp(12), dp(10))
        }
        input = EditText(activity).apply {
            hint = "מה לשנות?"
            id = R.id.convInput
            background = activity.getDrawable(R.drawable.bg_input)
            setPadding(dp(18), 0, dp(18), 0)
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnFocusChangeListener { _, focused -> if (focused) setExpanded(true) }
            setOnClickListener { setExpanded(true) }
            setOnEditorActionListener { _, _, _ -> onSend(); true }
        }
        val send = iconButton(R.drawable.ic_send, "שליחה", primary = true).apply {
            id = R.id.convSend
            setOnClickListener { onSend() }
        }
        val settings = iconButton(R.drawable.ic_tune, "הגדרות מנוע השיחה", primary = false).apply {
            setOnClickListener { showBackendDialog() }
        }
        row.addView(settings, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(8) })
        row.addView(input, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(8) })
        row.addView(send, LinearLayout.LayoutParams(dp(48), dp(48)))
        sheet.addView(row)

        host.addView(sheet, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        // With the keyboard up there is no room for the page and the drawer
        // together: the conversation takes the whole area above the keyboard.
        activity.window.decorView.viewTreeObserver.addOnGlobalLayoutListener { applyKeyboardMode() }
        bubble("ספרי לי מה לשנות בהגדרות — למשל כמה פרקים לשמור או באיזה קצב לנגן.", false)
    }

    private fun drawerParams(keyboardOpen: Boolean) =
        if (keyboardOpen) LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
        else LinearLayout.LayoutParams(MATCH_PARENT, (activity.resources.displayMetrics.heightPixels * DRAWER_HEIGHT).toInt())

    private var keyboardMode = false

    private fun applyKeyboardMode() {
        val frame = android.graphics.Rect().also { activity.window.decorView.getWindowVisibleDisplayFrame(it) }
        val screen = activity.resources.displayMetrics.heightPixels
        val keyboardOpen = expanded && screen - frame.bottom > screen * 0.15
        if (keyboardOpen == keyboardMode) return
        keyboardMode = keyboardOpen
        val root = host.parent as ViewGroup
        root.getChildAt(0).visibility = if (keyboardOpen) View.GONE else View.VISIBLE
        host.layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, if (keyboardOpen) 0 else WRAP_CONTENT, if (keyboardOpen) 1f else 0f)
        sheet.layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, if (keyboardOpen) MATCH_PARENT else WRAP_CONTENT)
        scroll.layoutParams = drawerParams(keyboardOpen)
    }

    private fun iconButton(icon: Int, label: String, primary: Boolean) = ImageButton(
        activity, null, 0, if (primary) R.style.PodIcon_Primary else R.style.PodIcon
    ).apply {
        setImageResource(icon)
        contentDescription = label
    }

    fun setExpanded(open: Boolean) {
        if (open == expanded) return
        expanded = open
        scroll.visibility = if (open) View.VISIBLE else View.GONE
        if (open) scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        else {
            input.clearFocus()
            val imm = activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            imm.hideSoftInputFromWindow(input.windowToken, 0)
        }
    }

    private fun bubble(text: String, fromUser: Boolean) {
        val tv = TextView(activity).apply {
            this.text = text
            textSize = 16f
            setPadding(dp(16), dp(11), dp(16), dp(11))
            background = activity.getDrawable(if (fromUser) R.drawable.bg_bubble_user else R.drawable.bg_bubble_app)
            setTextColor(activity.getColor(if (fromUser) R.color.onAccent else R.color.textPrimary))
            maxWidth = (activity.resources.displayMetrics.widthPixels * 0.8).toInt()
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
        val col = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, 0) }
        fun field(hintText: String, value: String) = EditText(activity).apply {
            hint = hintText
            setText(value)
            background = activity.getDrawable(R.drawable.bg_input)
            setPadding(dp(16), 0, dp(16), 0)
            setSingleLine(true)
        }
        val keyField = field("מפתח API (Gemini)", secrets.get("gemini_key") ?: "")
        val modelField = field("מודל", prefs.getString("gemini_model", DEFAULT_MODEL) ?: DEFAULT_MODEL)
        col.addView(TextView(activity).apply {
            text = "המפתח נשמר במכשיר בלבד ומשמש רק לשיחה הזו."
            setTextColor(activity.getColor(R.color.textSecondary))
            textSize = 13f
            setPadding(0, 0, 0, dp(12))
        })
        col.addView(keyField, LinearLayout.LayoutParams(MATCH_PARENT, dp(48)).apply { bottomMargin = dp(10) })
        col.addView(modelField, LinearLayout.LayoutParams(MATCH_PARENT, dp(48)))
        AlertDialog.Builder(activity)
            .setTitle("מנוע שיחה")
            .setView(col)
            .setPositiveButton("שמירה") { _, _ ->
                val newKey = keyField.text.toString().trim()
                if (newKey.isBlank()) secrets.remove("gemini_key") else secrets.put("gemini_key", newKey)
                prefs.edit()
                    .putString("gemini_model", modelField.text.toString().trim().ifBlank { DEFAULT_MODEL })
                    .apply()
                backend = configuredBackend()
                setExpanded(true)
                bubble(if (keyField.text.isBlank()) "מנוע השיחה כבוי — נשארו התשובות הקבועות." else "מנוע השיחה חובר.", false)
            }
            .setNegativeButton("ביטול", null)
            .show()
    }

    private fun onSend() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        if (convLog.needsNotice()) { showNotice { onSend() }; return }
        input.text.clear()
        setExpanded(true)
        history.add(ConvMessage(true, text))
        bubble(text, true)
        store("user", text)
        scope.launch {
            val raw = withContext(Dispatchers.IO) { backend.reply(history, Tools.SPEC) }
            val json = runCatching { JSONObject(raw) }.getOrElse {
                JSONObject().put("say", "התקבלה תשובה לא תקינה מהמנוע.")
            }
            val confirmation = withContext(Dispatchers.IO) { Tools.execute(json, lib) }
            if (confirmation.isNotBlank()) {
                history.add(ConvMessage(false, confirmation))
                bubble(confirmation, false)
                store("app", confirmation)
            }
        }
    }

    private companion object {
        const val DEFAULT_MODEL = "gemini-3.1-flash-lite"
        const val DRAWER_HEIGHT = 0.30
    }
}
