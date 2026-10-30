package com.hamzawy.radio

import android.Manifest
import android.app.UiModeManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.common.util.concurrent.ListenableFuture

/**
 * Home screen: the station grid, the now-playing bar and the "Appearance" / "Connect phone" buttons.
 * Playback itself lives in [PlaybackService], so closing this screen does not stop the radio.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var background: ImageView
    private lateinit var dim: View
    private lateinit var logo: ImageView
    private lateinit var list: RecyclerView
    private lateinit var adapter: StationAdapter
    private lateinit var btnAppearance: View
    private lateinit var btnConnect: View
    private lateinit var nowPlaying: View
    private lateinit var npBadge: TextView
    private lateinit var npName: TextView
    private lateinit var npStatus: TextView
    private lateinit var btnPrev: ImageView
    private lateinit var btnPlay: ImageView
    private lateinit var btnNext: ImageView
    private lateinit var btnStop: ImageView

    private var stations: List<Station> = emptyList()
    private var current: Station? = null
    private var pending: Station? = null
    private var accent: Int = ThemeStore.DEFAULT_ACCENT
    private var shownThemeId: String? = null

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var phoneLink: PhoneLink? = null

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            refreshPlayerUi()
        }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        background = findViewById(R.id.background)
        dim = findViewById(R.id.dim)
        logo = findViewById(R.id.logo)
        list = findViewById(R.id.stationList)
        btnAppearance = findViewById(R.id.btnAppearance)
        btnConnect = findViewById(R.id.btnConnect)
        nowPlaying = findViewById(R.id.nowPlaying)
        npBadge = findViewById(R.id.npBadge)
        npName = findViewById(R.id.npName)
        npStatus = findViewById(R.id.npStatus)
        btnPrev = findViewById(R.id.btnPrev)
        btnPlay = findViewById(R.id.btnPlay)
        btnNext = findViewById(R.id.btnNext)
        btnStop = findViewById(R.id.btnStop)

        adapter = StationAdapter(this, { s -> play(s) }, { s -> onStationLongClick(s) })
        val dm = resources.displayMetrics
        val widthDp = dm.widthPixels / dm.density
        val spans = ((widthDp - 96f) / 260f).toInt().coerceIn(1, 5)
        val layoutManager = GridLayoutManager(this, spans)
        layoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int = if (adapter.isHeader(position)) spans else 1
        }
        list.layoutManager = layoutManager
        list.adapter = adapter

        for (v in listOf<View>(btnAppearance, btnConnect, btnPrev, btnPlay, btnNext, btnStop)) {
            Ui.focusScale(v)
        }
        btnAppearance.setOnClickListener { openAppearance() }
        btnConnect.setOnClickListener { phoneLink?.show() }
        btnPlay.setOnClickListener { togglePlay() }
        btnPrev.setOnClickListener { playRelative(-1) }
        btnNext.setOnClickListener { playRelative(1) }
        btnStop.setOnClickListener { stopPlayback() }

        phoneLink = PhoneLink(
            this,
            allowImage = true,
            allowStation = true,
            imageHandler = { bytes -> addThemeFromPhone(bytes) },
            stationHandler = { name, url -> addStationFromPhone(name, url) }
        )

        val lastId = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_LAST, null)
        stations = StationRepository.load(this)
        adapter.submit(stations)
        current = stations.firstOrNull { it.id == lastId }

        applyTheme()
        refreshPlayerUi()
        focusStation(current?.id)
        askNotificationPermission()
    }

    override fun onStart() {
        super.onStart()
        applyTheme()
        reloadStations(false)

        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener(
            Runnable {
                try {
                    val c = future.get()
                    controller = c
                    c.addListener(playerListener)
                    val wanted = pending
                    if (wanted != null) {
                        pending = null
                        play(wanted)
                    }
                    refreshPlayerUi()
                } catch (e: Exception) {
                    // The playback service could not be reached; buttons keep working once it can.
                }
            },
            ContextCompat.getMainExecutor(this)
        )
    }

    override fun onStop() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        controller = null
        super.onStop()
    }

    override fun onDestroy() {
        phoneLink?.dismiss()
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_MENU -> {
                openAppearance()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                togglePlay()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                resume()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                controller?.pause()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_STOP -> {
                stopPlayback()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_CHANNEL_UP -> {
                playRelative(1)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                playRelative(-1)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    // ---------------------------------------------------------------- theme

    private fun applyTheme() {
        val theme = ThemeStore.selected(this)
        accent = theme.accent
        dim.alpha = ThemeStore.dimAlpha(this)
        if (shownThemeId != theme.id) {
            shownThemeId = theme.id
            ThemeStore.loadBackgroundAsync(this, theme) { bmp ->
                if (bmp != null && shownThemeId == theme.id && !isDestroyed) {
                    background.setImageBitmap(bmp)
                    background.alpha = 0f
                    background.animate().alpha(1f).setDuration(250).start()
                }
            }
        }
        styleControls()
        adapter.setAccent(accent)
    }

    private fun styleControls() {
        btnAppearance.background = Ui.cardBackground(this, accent, 14f)
        btnConnect.background = Ui.cardBackground(this, accent, 14f)
        nowPlaying.background = Ui.panel(this)
        for (b in listOf(btnPrev, btnPlay, btnNext, btnStop)) {
            b.background = Ui.circleBackground(this, accent)
        }
        logo.setColorFilter(accent)
    }

    private fun openAppearance() {
        startActivity(Intent(this, AppearanceActivity::class.java))
    }

    // ---------------------------------------------------------------- stations

    private fun reloadStations(force: Boolean) {
        val fresh = StationRepository.load(this)
        if (!force && fresh == stations) return
        stations = fresh
        adapter.submit(fresh)
    }

    private fun focusStation(id: String?) {
        val pos = if (id != null) adapter.positionOf(id) else adapter.firstItemPosition()
        if (pos < 0) return
        list.scrollToPosition(pos)
        list.post {
            list.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus()
        }
    }

    private fun onStationLongClick(s: Station) {
        if (!s.custom) {
            Toast.makeText(this, R.string.builtin_station, Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_station_title)
            .setMessage(getString(R.string.delete_station_message, s.name))
            .setPositiveButton(R.string.delete) { _, _ ->
                StationRepository.removeCustom(this, s.id)
                reloadStations(true)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun addStationFromPhone(name: String, url: String) {
        val s = StationRepository.addCustom(this, name, url)
        reloadStations(true)
        Toast.makeText(this, getString(R.string.station_added, s.name), Toast.LENGTH_SHORT).show()
        play(s)
        focusStation(s.id)
    }

    private fun addThemeFromPhone(bytes: ByteArray) {
        Thread {
            val theme = ThemeStore.addFromBytes(this, bytes)
            runOnUiThread {
                if (!isDestroyed) {
                    if (theme == null) {
                        Toast.makeText(this, R.string.theme_failed, Toast.LENGTH_LONG).show()
                    } else {
                        ThemeStore.select(this, theme.id)
                        applyTheme()
                        Toast.makeText(this, R.string.theme_added, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }.start()
    }

    // ---------------------------------------------------------------- playback

    private fun mediaItemFor(s: Station): MediaItem {
        val extras = Bundle()
        extras.putStringArrayList(PlaybackService.EXTRA_URLS, ArrayList(s.urls))
        extras.putLong(PlaybackService.EXTRA_NONCE, System.nanoTime())
        val metadata = MediaMetadata.Builder()
            .setTitle(s.name)
            .setArtist(getString(R.string.app_name))
            .setExtras(extras)
            .build()
        return MediaItem.Builder()
            .setMediaId(s.id)
            .setUri(s.urls[0])
            .setMediaMetadata(metadata)
            .build()
    }

    private fun play(s: Station) {
        current = s
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_LAST, s.id).apply()
        val c = controller
        if (c == null) {
            // The connection to the playback service is still being set up.
            pending = s
            renderNowPlaying(s, getString(R.string.status_connecting), true)
            adapter.setPlaying(s.id)
            return
        }
        c.setMediaItem(mediaItemFor(s))
        c.prepare()
        c.play()
        adapter.setPlaying(s.id)
        // The real state arrives through the player listener a moment later.
        renderNowPlaying(s, getString(R.string.status_connecting), true)
    }

    private fun resume() {
        val c = controller
        if (c == null || c.currentMediaItem == null) {
            val s = current ?: stations.firstOrNull()
            if (s != null) play(s)
        } else if (c.playbackState == Player.STATE_IDLE) {
            current?.let { play(it) }
        } else {
            c.play()
        }
    }

    private fun togglePlay() {
        val c = controller
        if (c != null && c.currentMediaItem != null && c.playWhenReady && c.playbackState != Player.STATE_IDLE) {
            c.pause()
        } else {
            resume()
        }
    }

    private fun stopPlayback() {
        val c = controller ?: return
        c.stop()
        c.clearMediaItems()
        adapter.setPlaying(null)
        refreshPlayerUi()
    }

    private fun playRelative(delta: Int) {
        if (stations.isEmpty()) return
        val idx = stations.indexOfFirst { it.id == current?.id }
        val next = if (idx < 0) 0 else (idx + delta + stations.size) % stations.size
        play(stations[next])
    }

    // ---------------------------------------------------------------- now-playing bar

    private fun refreshPlayerUi() {
        val c = controller
        val item = c?.currentMediaItem
        if (c == null || item == null) {
            renderNowPlaying(current, getString(R.string.status_idle), false)
            if (pending == null) adapter.setPlaying(null)
            return
        }

        val playing = stations.firstOrNull { it.id == item.mediaId }
        if (playing != null) current = playing
        adapter.setPlaying(item.mediaId)

        val title = c.mediaMetadata.title?.toString()
        val song = if (!title.isNullOrBlank() && title != current?.name) title else null

        val status: String
        if (c.playerError != null) {
            status = getString(R.string.status_error)
        } else if (c.isPlaying) {
            status = song ?: getString(R.string.status_playing)
        } else if (c.playWhenReady) {
            status = getString(R.string.status_connecting)
        } else {
            status = getString(R.string.status_paused)
        }
        renderNowPlaying(current, status, c.playWhenReady)
    }

    private fun renderNowPlaying(station: Station?, status: String, showPause: Boolean) {
        if (station != null) {
            npName.text = station.name
            npBadge.visibility = View.VISIBLE
            npBadge.text = if (station.badge.isNotEmpty()) station.badge else station.name.take(1)
            npBadge.background = Ui.circle(Ui.monogramColor(station.id))
        } else {
            npName.text = getString(R.string.app_name)
            npBadge.visibility = View.INVISIBLE
        }
        npStatus.text = status
        btnPlay.setImageResource(if (showPause) R.drawable.ic_pause else R.drawable.ic_play)
    }

    // ---------------------------------------------------------------- notification permission

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33 || isTelevision()) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun isTelevision(): Boolean {
        val ui = getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
        return ui.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
    }

    private companion object {
        const val PREFS = "main"
        const val KEY_LAST = "last_station"
    }
}
