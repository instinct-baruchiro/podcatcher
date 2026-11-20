package com.baruchiro.podcatcher.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.baruchiro.podcatcher.R
import com.baruchiro.podcatcher.telemetry.Analytics
import com.baruchiro.podcatcher.telemetry.ConversationLog
import java.util.concurrent.Executors

/** REL-1: who made the app, what is measured, and what is stored about conversations. */
class AboutActivity : Activity() {

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val analytics = Analytics.get(this)
        val log = ConversationLog(getSharedPreferences("telemetry", MODE_PRIVATE))
        val version = packageManager.getPackageInfo(packageName, 0).versionName

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = LinearLayout.LAYOUT_DIRECTION_RTL
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }
        fun text(t: String, style: Int? = null) = TextView(this).apply {
            this.text = t
            if (style != null) setTextAppearance(style) else { setTextColor(getColor(R.color.textSecondary)); textSize = 15f }
            setPadding(0, 0, 0, dp(10))
        }.also { col.addView(it, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)) }

        text("אודות", R.style.PodTitle)
        text("פודקאסטים, גרסה $version")
        text("נוצר על ידי ברוך אודם.").setTextColor(getColor(R.color.textPrimary))
        text("baruchiro.com").apply {
            setTextColor(getColor(R.color.accent))
            setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://baruchiro.com"))) }
        }

        text("סטטיסטיקות שימוש", R.style.PodSection)
        text("נשלחים אירועי שימוש כלליים, כמו פתיחת האפליקציה והוספת פודקאסט, יחד עם מזהה אקראי של ההתקנה הזו. לא נשלח תוכן שיחות או שמות פרקים. שירות הניתוח (PostHog) עשוי לשמור גם כתובת IP ומיקום משוער. השליחה כבויה כברירת מחדל, ואפשר להפעיל ולכבות בכל עת.")
        col.addView(CheckBox(this).apply {
            text = "שליחת אירועי שימוש"
            setTextColor(getColor(R.color.textPrimary))
            id = R.id.analyticsSwitch
            isChecked = analytics.enabled
            setOnCheckedChangeListener { _, on -> analytics.enabled = on }
        })

        text("שיחות", R.style.PodSection)
        if (log.available) {
            text("בעוד הסכמה, השיחות נשמרות לצורך בקרה ושיפור השירות. אפשר למחוק אותן כאן בכל עת.")
            text("מחק את השיחות שלי").apply {
                setTextColor(getColor(R.color.accent))
                setOnClickListener {
                    Executors.newSingleThreadExecutor().execute {
                        val ok = log.deleteMine()
                        runOnUiThread { this.text = if (ok) "השיחות נמחקו." else "המחיקה נכשלה, נסו שוב." }
                    }
                }
            }
        } else {
            text("כרגע השיחות לא נשמרות מחוץ למכשיר.")
        }

        setContentView(ScrollView(this).apply { addView(col) })
        analytics.capture("podcatcher_about_open")
    }
}
