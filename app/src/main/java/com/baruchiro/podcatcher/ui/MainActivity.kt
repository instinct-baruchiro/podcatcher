package com.baruchiro.podcatcher.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageButton
import android.widget.EditText
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import com.baruchiro.podcatcher.R
import com.baruchiro.podcatcher.telemetry.Analytics
import com.baruchiro.podcatcher.conv.ConvPanel
import com.baruchiro.podcatcher.data.Opml
import com.baruchiro.podcatcher.data.Podcast
import com.baruchiro.podcatcher.data.Store
import com.baruchiro.podcatcher.net.Discovery
import com.baruchiro.podcatcher.work.FeedRefreshWorker
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val io = Executors.newSingleThreadExecutor()
    private val artwork = ArtworkLoader()
    private lateinit var input: EditText
    private lateinit var status: TextView
    private lateinit var listView: ListView
    internal lateinit var conv: ConvPanel
    private lateinit var playerBar: PlayerBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        input = findViewById(R.id.intake)
        status = findViewById(R.id.status)
        listView = findViewById(R.id.podcasts)
        findViewById<ImageButton>(R.id.go).setOnClickListener { intake() }
        findViewById<ImageButton>(R.id.queueOpen).setOnClickListener {
            startActivity(Intent(this, QueueActivity::class.java))
        }
        findViewById<ImageButton>(R.id.aboutOpen).setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }
        findViewById<ImageButton>(R.id.importOpen).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("ייבוא מאפליקציה אחרת")
                .setMessage(
                    "מעבירים את המנויים בקובץ OPML:\n\n" +
                        "1. ב-Podcast Addict: הגדרות, גיבוי, שחזור וסנכרון, ייצוא OPML (מנויים בלבד). שומרים את הקובץ בטלפון.\n" +
                        "2. באפליקציות אחרות (Pocket Casts, AntennaPod ועוד): מחפשים ייצוא OPML או ייצוא מנויים.\n" +
                        "3. לוחצים על \"בחירת קובץ\" ובוחרים אותו.\n\n" +
                        "מועברים המנויים בלבד, עם כללי ברירת המחדל שלך. התקדמות בפרקים, עדיפויות והגדרות לכל פודקאסט לא עוברים, אפשר לשנות אותם בשיחה."
                )
                .setPositiveButton("בחירת קובץ") { _, _ ->
                    startActivityForResult(
                        Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),
                        REQ_IMPORT
                    )
                }
                .setNegativeButton("ביטול", null)
                .show()
        }
        Analytics.get(this).capture("podcatcher_app_open")
        playerBar = PlayerBar(this, findViewById(R.id.playerHost))
        conv = ConvPanel(this, findViewById(R.id.convHost))
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        FeedRefreshWorker.schedule(this)
    }

    @Deprecated("Result of the OPML file picker")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode == REQ_IMPORT && resultCode == RESULT_OK && uri != null) importOpml(uri)
    }

    private fun importOpml(uri: android.net.Uri) {
        say("קורא את הקובץ…")
        io.execute {
            val feeds = try {
                contentResolver.openInputStream(uri)!!.use { Opml.parse(it) }
            } catch (e: Exception) {
                runOnUiThread { say("לא הצלחתי לקרוא את הקובץ. צריך קובץ OPML של המינויים (ב-Podcast Addict: הגדרות, גיבוי ושחזור, ייצוא OPML).") }
                return@execute
            }
            var added = 0; var existing = 0; var failed = 0
            feeds.forEachIndexed { i, f ->
                say("מייבא ${i + 1}/${feeds.size}: ${f.title}")
                try {
                    val info = Discovery.fetchFeed(f.feedUrl, 1)
                    if (Store.addPodcast(this, Podcast(f.feedUrl, info.title, info.author, info.artwork, Store.defaultPolicy(this)))) added++ else existing++
                } catch (e: Exception) { failed++ }
            }
            Analytics.get(this).capture("podcatcher_opml_import")
            runOnUiThread {
                say("יובאו $added פודקאסטים" + (if (existing > 0) ", $existing כבר היו בספרייה" else "") + (if (failed > 0) ", $failed לא נטענו" else "") + ".")
                render()
            }
        }
    }

    @Deprecated("Back closes the open conversation drawer first")
    override fun onBackPressed() {
        if (conv.expanded) conv.setExpanded(false) else super.onBackPressed()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onDestroy() {
        if (::playerBar.isInitialized) playerBar.release()
        super.onDestroy()
    }

    private fun say(msg: String) = runOnUiThread {
        status.text = msg
        status.visibility = if (msg.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun intake() {
        val q = input.text.toString().trim()
        if (q.isEmpty()) return
        val urlish = Regex("^(https?://\\S+|[\\w-]+(\\.[\\w-]+)+(/\\S*)?)\$").matches(q)
        if (urlish) {
            val url = if (q.startsWith("http")) q else "https://$q"
            say("בודק את הקישור…")
            io.execute {
                try {
                    val feed = Discovery.fetchFeed(url, 1)
                    val added = Store.addPodcast(this, Podcast(url, feed.title, feed.author, feed.artwork))
                    if (added) Analytics.get(this).capture("podcatcher_podcast_added")
                    runOnUiThread {
                        if (added) {
                            input.setText("")
                            say("נוסף לספרייה: ${feed.title}")
                            render()
                        } else say("הפודקאסט הזה כבר בספרייה.")
                    }
                } catch (e: Exception) {
                    runOnUiThread { searchFlow(deriveTerm(url), linkFallback = true) }
                }
            }
        } else searchFlow(q, linkFallback = false)
    }

    private fun deriveTerm(url: String): String = try {
        val u = java.net.URL(url)
        val bits = u.path.split("/").filter { it.isNotEmpty() }.take(2)
            .map { it.replace(Regex("[-_]"), " ").replace(Regex("\\.[a-z0-9]+$", RegexOption.IGNORE_CASE), "").trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
        (bits + " " + u.host.removePrefix("www.").substringBefore(".")).trim()
    } catch (e: Exception) { "" }

    private fun searchFlow(term: String, linkFallback: Boolean) {
        if (term.isEmpty()) {
            say("לא הצלחתי לזהות פודקאסט מהקישור. נסה קישור ישיר לפיד (RSS) או חיפוש בשם.")
            return
        }
        say("מחפש…")
        io.execute {
            try {
                val results = Discovery.search(term)
                runOnUiThread {
                    if (results.isEmpty()) {
                        say(if (linkFallback) "לא הצלחתי לזהות פודקאסט מהקישור. נסה קישור ישיר לפיד (RSS) או חיפוש בשם." else "לא נמצאו תוצאות.")
                        return@runOnUiThread
                    }
                    say("")
                    val names = results.map { "${it.title} · ${it.author}" }.toTypedArray()
                    AlertDialog.Builder(this)
                        .setTitle("בחר פודקאסט")
                        .setItems(names) { _, which ->
                            val r = results[which]
                            val added = Store.addPodcast(this, Podcast(r.feedUrl, r.title, r.author, r.artwork))
                    if (added) Analytics.get(this).capture("podcatcher_podcast_added")
                            say(if (added) "נוסף לספרייה: ${r.title}" else "הפודקאסט הזה כבר בספרייה.")
                            input.setText("")
                            render()
                        }
                        .setNegativeButton("ביטול", null)
                        .show()
                }
            } catch (e: Exception) {
                runOnUiThread { say("החיפוש נכשל. נסה שוב.") }
            }
        }
    }

    private fun render() {
        val list = Store.podcasts(this)
        listView.adapter = PodcastAdapter(list)
        listView.setOnItemClickListener { _, _, pos, _ ->
            startActivity(Intent(this, PodcastActivity::class.java).putExtra("feedUrl", list[pos].feedUrl))
        }
        listView.setOnItemLongClickListener { _, _, pos, _ ->
            AlertDialog.Builder(this)
                .setTitle("להסיר את ${list[pos].title}?")
                .setPositiveButton("הסרה") { _, _ -> Store.removePodcast(this, list[pos].feedUrl); render() }
                .setNegativeButton("ביטול", null)
                .show()
            true
        }
        if (list.isEmpty()) say("הספרייה ריקה. חפש בשם או הדבק קישור לפיד למעלה כדי להוסיף.")
    }

    inner class PodcastAdapter(private val items: List<Podcast>) : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(p: Int) = items[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, convert: View?, parent: ViewGroup): View {
            val v = convert ?: layoutInflater.inflate(R.layout.row_podcast, parent, false)
            val item = items[p]
            v.findViewById<TextView>(R.id.title).text = item.title
            v.findViewById<TextView>(R.id.subtitle).text = buildString {
                append(item.author)
                if (item.policy.autoDownload) append(" · הורדה אוטומטית")
                if (item.policy.speed != 1.0) append(" · ×${item.policy.speed}")
                if (item.policy.skipIntroSec > 0) append(" · דילוג ${item.policy.skipIntroSec}ש׳")
            }
            artwork.into(v.findViewById(R.id.art), item.artwork)
            return v
        }
    }

    private companion object { const val REQ_IMPORT = 7 }
}
