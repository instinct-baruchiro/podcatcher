package com.baruchiro.podcatcher.ui

import android.app.Activity
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.baruchiro.podcatcher.R
import com.baruchiro.podcatcher.data.Podcast
import com.baruchiro.podcatcher.data.Store
import com.baruchiro.podcatcher.net.Discovery
import com.baruchiro.podcatcher.net.Episode
import com.baruchiro.podcatcher.player.PlayerService
import com.google.common.util.concurrent.ListenableFuture
import java.io.File
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors

class PodcastActivity : Activity() {
    private val io = Executors.newSingleThreadExecutor()
    private val artwork = ArtworkLoader()
    private lateinit var feedUrl: String
    private lateinit var podcast: Podcast
    private var episodes: List<Episode> = emptyList()
    private lateinit var status: TextView
    private lateinit var listView: ListView
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var bindingPolicy = false

    // Refresh episode rows when a system download completes.
    private val dlReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (::listView.isInitialized) listView.adapter = EpisodeAdapter()
        }
    }
    private val mainExecutor = java.util.concurrent.Executor { r -> android.os.Handler(android.os.Looper.getMainLooper()).post(r) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        feedUrl = intent.getStringExtra("feedUrl") ?: run { finish(); return }
        val found = Store.podcasts(this).find { it.feedUrl == feedUrl } ?: run { finish(); return }
        podcast = found
        setContentView(R.layout.activity_podcast)

        status = findViewById(R.id.status)
        listView = findViewById(R.id.episodes)
        findViewById<TextView>(R.id.ptitle).text = podcast.title
        artwork.into(findViewById(R.id.art), podcast.artwork)

        bindingPolicy = true
        val autoCheck = findViewById<CheckBox>(R.id.autoCheck)
        autoCheck.isChecked = podcast.policy.autoDownload
        val keepSpinner = findViewById<Spinner>(R.id.keepSpinner)
        val speedSpinner = findViewById<Spinner>(R.id.speedSpinner)
        val skipSpinner = findViewById<Spinner>(R.id.skipSpinner)
        keepSpinner.adapter = ArrayAdapter.createFromResource(this, R.array.keep_options, android.R.layout.simple_spinner_dropdown_item)
        speedSpinner.adapter = ArrayAdapter.createFromResource(this, R.array.speed_options, android.R.layout.simple_spinner_dropdown_item)
        skipSpinner.adapter = ArrayAdapter.createFromResource(this, R.array.skip_options, android.R.layout.simple_spinner_dropdown_item)
        keepSpinner.setSelection(listOf(1, 3, 5, 10).indexOf(podcast.policy.keep).coerceAtLeast(0))
        speedSpinner.setSelection(listOf(1.0, 1.25, 1.5, 1.75, 2.0).indexOf(podcast.policy.speed).coerceAtLeast(0))
        skipSpinner.setSelection(listOf(0, 15, 30, 60).indexOf(podcast.policy.skipIntroSec).coerceAtLeast(0))
        bindingPolicy = false

        autoCheck.setOnCheckedChangeListener { _, checked ->
            if (!bindingPolicy) savePolicy(autoDownload = checked)
        }
        val listener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                if (bindingPolicy) return
                when (parent?.id) {
                    R.id.keepSpinner -> savePolicy(keep = listOf(1, 3, 5, 10)[pos])
                    R.id.speedSpinner -> savePolicy(speed = listOf(1.0, 1.25, 1.5, 1.75, 2.0)[pos])
                    R.id.skipSpinner -> savePolicy(skipIntroSec = listOf(0, 15, 30, 60)[pos])
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        keepSpinner.onItemSelectedListener = listener
        speedSpinner.onItemSelectedListener = listener
        skipSpinner.onItemSelectedListener = listener

        loadEpisodes()

        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        // ACTION_DOWNLOAD_COMPLETE comes from the downloads provider app, not the
        // system server, so NOT_EXPORTED would silently never receive it (API 33+).
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(dlReceiver, filter, Context.RECEIVER_EXPORTED)
        else registerReceiver(dlReceiver, filter)
    }

    override fun onDestroy() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        unregisterReceiver(dlReceiver)
        super.onDestroy()
    }

    private fun savePolicy(
        autoDownload: Boolean = podcast.policy.autoDownload,
        keep: Int = podcast.policy.keep,
        speed: Double = podcast.policy.speed,
        skipIntroSec: Int = podcast.policy.skipIntroSec
    ) {
        podcast.policy.autoDownload = autoDownload
        podcast.policy.keep = keep
        podcast.policy.speed = speed
        podcast.policy.skipIntroSec = skipIntroSec
        Store.updatePolicy(this, feedUrl, podcast.policy)
        say("המדיניות נשמרה.")
    }

    private fun say(msg: String) = runOnUiThread {
        status.text = msg
        status.visibility = if (msg.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun loadEpisodes() {
        say("טוען פרקים…")
        io.execute {
            try {
                episodes = Discovery.fetchFeed(feedUrl).episodes
                runOnUiThread { say(""); listView.adapter = EpisodeAdapter() }
            } catch (e: Exception) {
                runOnUiThread { say("לא הצלחתי לטעון את הפיד.") }
            }
        }
    }

    private fun play(ep: Episode) {
        say("מתחבר לנגן…")
        controllerFuture?.let { MediaController.releaseFuture(it) }
        val token = SessionToken(this, ComponentName(this, PlayerService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            try {
                val c = future.get()
                val local = Store.downloadedPath(this, feedUrl, ep.guid)
                val uri = local?.let { Uri.fromFile(File(it)) } ?: Uri.parse(ep.audioUrl)
                val item = MediaItem.Builder().setUri(uri).setMediaMetadata(
                    androidx.media3.common.MediaMetadata.Builder()
                        .setTitle(ep.title)
                        .setArtist(podcast.title)
                        .build()
                ).build()
                c.setMediaItem(item, podcast.policy.skipIntroSec * 1000L)
                c.setPlaybackSpeed(podcast.policy.speed.toFloat())
                c.prepare()
                c.play()
                say((if (local != null) "מנגן מהמכשיר: " else "מנגן: ") + ep.title)
            } catch (e: Exception) {
                say("הניגון נכשל.")
            }
        }, mainExecutor)
    }

    private fun download(ep: Episode) {
        if (Store.downloadedPath(this, feedUrl, ep.guid) != null) { say("הפרק כבר במכשיר."); return }
        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val dest = File(getExternalFilesDir(Environment.DIRECTORY_PODCASTS),
            ep.guid.filter { it.isLetterOrDigit() }.take(48) + ".mp3")
        dm.enqueue(
            DownloadManager.Request(Uri.parse(ep.audioUrl))
                .setTitle(ep.title)
                .setDestinationUri(Uri.fromFile(dest))
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        )
        Store.setDownloaded(this, feedUrl, ep.guid, dest.absolutePath)
        say("מוריד: " + ep.title)
        listView.adapter = EpisodeAdapter()
    }

    private fun formatDate(raw: String): String = try {
        val parsed = ZonedDateTime.parse(raw.trim(), DateTimeFormatter.RFC_1123_DATE_TIME)
        parsed.format(DateTimeFormatter.ofPattern("EEEE, d 'ב'MMMM yyyy", Locale("he")))
    } catch (e: Exception) { raw }

    inner class EpisodeAdapter : BaseAdapter() {
        override fun getCount() = episodes.size
        override fun getItem(p: Int) = episodes[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, convert: View?, parent: ViewGroup): View {
            val v = convert ?: layoutInflater.inflate(R.layout.row_episode, parent, false)
            val ep = episodes[p]
            v.findViewById<TextView>(R.id.title).text = ep.title
            v.findViewById<TextView>(R.id.date).text = formatDate(ep.date)
            v.findViewById<Button>(R.id.play).setOnClickListener { play(ep) }
            val dl = v.findViewById<Button>(R.id.download)
            val saved = Store.downloadedPath(this@PodcastActivity, feedUrl, ep.guid) != null
            dl.text = if (saved) "במכשיר" else "הורדה"
            dl.isEnabled = !saved
            dl.setOnClickListener { download(ep) }
            return v
        }
    }
}
