package com.pvm.zombiemusiclibrary

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.drawable.Icon as GIcon
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.widget.Toast
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** Plays the music in the background (with its notification), also with the screen off. */
class PlayerService : Service(), AudioManager.OnAudioFocusChangeListener {

    interface Listener { fun onPlayerChanged() }

    inner class LocalBinder : Binder() {
        val service: PlayerService get() = this@PlayerService
    }

    companion object {
        const val CHANNEL = "zml_playback"
        const val NOTIF_ID = 7
        const val ACT_PLAY_PAUSE = "com.pvm.zombiemusiclibrary.PLAY_PAUSE"
        const val ACT_NEXT = "com.pvm.zombiemusiclibrary.NEXT"
        const val ACT_PREV = "com.pvm.zombiemusiclibrary.PREV"
        const val ACT_STOP = "com.pvm.zombiemusiclibrary.STOP"
    }

    private val binder = LocalBinder()
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newSingleThreadExecutor()

    private var mp: MediaPlayer? = null
    private var prepared = false
    private lateinit var session: MediaSession
    private lateinit var nm: NotificationManager
    private lateinit var audio: AudioManager
    private var focus: AudioFocusRequest? = null
    private var inForeground = false
    private var switching = false                           // a track is being prepared: stay in the foreground meanwhile
    private var resumeOnFocus = false
    private var cover: Bitmap? = null
    private var token = 0                                   // identifies the track whose cover is being loaded

    var queue: List<Track> = emptyList()
        private set
    var index: Int = -1
        private set
    var playing: Boolean = false
        private set

    val current: Track? get() = queue.getOrNull(index)
    val coverBitmap: Bitmap? get() = cover
    val hasMedia: Boolean get() = current != null
    val position: Int get() = try { if (prepared) mp?.currentPosition ?: 0 else 0 } catch (e: Exception) { 0 }
    val duration: Int get() = try { if (prepared) mp?.duration ?: 0 else 0 } catch (e: Exception) { 0 }

    fun addListener(l: Listener) { if (!listeners.contains(l)) listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }
    private fun changed() {
        for (l in listeners) l.onPlayerChanged()
        try { PlayerWidget.update(this, this) } catch (e: Exception) { }
    }

    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY && playing) pause()
        }
    }

    // ---------------- lifecycle ----------------

    override fun onCreate() {
        super.onCreate()
        Repo.init(this)
        nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        audio = getSystemService(AUDIO_SERVICE) as AudioManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Reproducción", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Controles de la música que está sonando"
            setShowBadge(false)
        })
        session = MediaSession(this, "ZombieMusicLibrary")
        session.setCallback(object : MediaSession.Callback() {
            override fun onPlay() { togglePlayPause(forcePlay = true) }
            override fun onPause() { pause() }
            override fun onSkipToNext() { next() }
            override fun onSkipToPrevious() { previous() }
            override fun onStop() { stopPlayback() }
            override fun onSeekTo(pos: Long) { seekTo(pos.toInt()) }
        })
        session.isActive = true
        val f = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(noisy, f, Context.RECEIVER_NOT_EXPORTED) else registerReceiver(noisy, f)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACT_PLAY_PAUSE -> togglePlayPause()
            ACT_NEXT -> next()
            ACT_PREV -> previous()
            ACT_STOP -> stopPlayback()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        try { unregisterReceiver(noisy) } catch (e: Exception) { }
        release()
        session.release()
        pool.shutdownNow()
        super.onDestroy()
    }

    // ---------------- commands ----------------

    fun playQueue(list: List<Track>, start: Int) {
        if (start < 0 || start >= list.size) return
        queue = list
        index = start
        startCurrent()
    }

    fun togglePlayPause(forcePlay: Boolean = false) {
        val m = mp
        if (m == null) {
            if (hasMedia) startCurrent() else resumeLast()
            return
        }
        if (playing && !forcePlay) pause() else resume()
    }

    /** Nothing loaded (for example after "stop" or from the widget): play the last song again, from its start. */
    fun resumeLast() {
        pool.execute {
            Repo.ensureLoaded()
            val last = Repo.lastTrack()
            val q = Repo.orderedLibrary()
            var list = q
            var idx = if (last != null) q.indexOfFirst { it.path == last.path } else -1
            if (idx < 0) {
                val alone = if (last != null) Repo.mediaIndex[last.path] else null
                if (alone != null) { list = listOf(alone); idx = 0 }
                else if (q.isNotEmpty()) idx = 0
                else { main.post { Toast.makeText(this, "Tocá Escanear en la app para elegir tu música.", Toast.LENGTH_LONG).show() }; return@execute }
            }
            val l = list
            val i = idx
            main.post { playQueue(l, i) }
        }
    }

    private fun resume() {
        val m = mp ?: return
        if (!prepared || !requestFocus()) return
        m.start()
        playing = true
        refresh(true)
    }

    fun pause() {
        val m = mp ?: return
        if (prepared && playing) { m.pause(); playing = false; refresh(false) }
    }

    /** Next track. Returns false when it already is the last one. */
    fun next(): Boolean {
        if (index + 1 >= queue.size) return false
        index++
        startCurrent()
        return true
    }

    /** Always the previous track (on the first one it starts again). */
    fun previous() {
        if (queue.isEmpty()) return
        if (index > 0) index--
        startCurrent()
    }

    fun stopPlayback() {
        release()
        queue = emptyList()
        index = -1
        playing = false
        switching = false
        cover = null
        inForeground = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        nm.cancel(NOTIF_ID)
        session.setPlaybackState(PlaybackState.Builder().setState(PlaybackState.STATE_STOPPED, 0, 1f).build())
        abandonFocus()
        changed()
    }

    fun seekTo(ms: Int) {
        val m = mp ?: return
        if (prepared) {
            m.seekTo(ms)
            updateSession()
            changed()
        }
    }

    // ---------------- playback ----------------

    private fun release() {
        try { mp?.setOnCompletionListener(null); mp?.setOnPreparedListener(null); mp?.setOnErrorListener(null) } catch (e: Exception) { }
        try { mp?.release() } catch (e: Exception) { }
        mp = null
        prepared = false
    }

    private fun startCurrent() {
        val t = current ?: return
        Repo.saveLast(t)
        if (t.id < 0) {
            Toast.makeText(this, "No se encuentra el archivo: " + Util.dash(t.title), Toast.LENGTH_SHORT).show()
            if (!next()) stopPlayback()
            return
        }
        release()
        playing = false
        switching = true
        cover = null
        token++
        val my = token
        val m = MediaPlayer()
        mp = m
        try {
            m.setAudioAttributes(AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).setUsage(AudioAttributes.USAGE_MEDIA).build())
            m.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK)
            m.setOnPreparedListener {
                prepared = true
                switching = false
                if (requestFocus()) { it.start(); playing = true }
                refresh(true)
            }
            m.setOnCompletionListener { if (!next()) stopPlayback() }
            m.setOnErrorListener { _, _, _ ->
                switching = false
                Toast.makeText(this, "No se pudo reproducir: " + Util.dash(t.title), Toast.LENGTH_SHORT).show()
                if (!next()) stopPlayback()
                true
            }
            m.setDataSource(this, Repo.uriOf(t))
            m.prepareAsync()
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo abrir: " + Util.dash(t.title), Toast.LENGTH_SHORT).show()
            if (!next()) stopPlayback()
            return
        }
        refresh(false)
        pool.execute { loadCover(t, my) }
    }

    private fun loadCover(t: Track, my: Int) {
        val bmp = try {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(this, Repo.uriOf(t))
                r.embeddedPicture?.let { ThumbLoader.decode(it, 640) }
            } finally { r.release() }
        } catch (e: Throwable) { null }
        main.post {
            if (my == token && bmp != null) { cover = bmp; updateSession(); if (inForeground || playing) nm.notify(NOTIF_ID, buildNotification()); changed() }
        }
    }

    // ---------------- audio focus ----------------

    private fun requestFocus(): Boolean {
        val req = focus ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).setUsage(AudioAttributes.USAGE_MEDIA).build())
            .setOnAudioFocusChangeListener(this).build()
        focus = req
        return audio.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        focus?.let { audio.abandonAudioFocusRequest(it) }
    }

    override fun onAudioFocusChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> { resumeOnFocus = false; pause() }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> { resumeOnFocus = playing; pause() }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> mp?.setVolume(0.3f, 0.3f)
            AudioManager.AUDIOFOCUS_GAIN -> {
                mp?.setVolume(1f, 1f)
                if (resumeOnFocus) { resumeOnFocus = false; resume() }
            }
        }
    }

    // ---------------- session + notification ----------------

    private fun refresh(foreground: Boolean) {
        updateSession()
        val n = buildNotification()
        if (playing || switching) {
            if (!inForeground) {
                try {
                    startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                    inForeground = true
                } catch (e: Exception) {
                    nm.notify(NOTIF_ID, n)
                }
            } else nm.notify(NOTIF_ID, n)
        } else if (hasMedia) {
            if (inForeground) { stopForeground(STOP_FOREGROUND_DETACH); inForeground = false }
            nm.notify(NOTIF_ID, n)
        }
        changed()
    }

    private fun updateSession() {
        val t = current
        val md = MediaMetadata.Builder()
        if (t != null) {
            md.putString(MediaMetadata.METADATA_KEY_TITLE, Util.dash(t.title))
            md.putString(MediaMetadata.METADATA_KEY_ARTIST, Util.dash(t.artist))
            md.putString(MediaMetadata.METADATA_KEY_ALBUM, Util.dash(t.album))
            md.putLong(MediaMetadata.METADATA_KEY_DURATION, if (duration > 0) duration.toLong() else t.durationMs)
            cover?.let { md.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) }
        }
        session.setMetadata(md.build())
        val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_STOP or PlaybackState.ACTION_SEEK_TO
        session.setPlaybackState(
            PlaybackState.Builder().setActions(actions)
                .setState(if (playing) PlaybackState.STATE_PLAYING else if (switching) PlaybackState.STATE_BUFFERING else PlaybackState.STATE_PAUSED, position.toLong(), 1f).build()
        )
    }

    private fun action(icon: Icon, title: String, act: String, code: Int): Notification.Action {
        val pi = PendingIntent.getService(this, code, Intent(this, PlayerService::class.java).setAction(act), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Action.Builder(GIcon.createWithBitmap(Icons.bitmap(icon, 96)), title, pi).build()
    }

    private fun buildNotification(): Notification {
        val t = current
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE)
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(GIcon.createWithBitmap(Icons.bitmap(Icon.NOTE, 96)))
            .setContentTitle(if (t != null) Util.dash(t.title) else "Zombie Music Library")
            .setContentText(if (t != null) Util.dash(t.artist) else "")
            .setSubText(if (t != null && t.album.isNotEmpty()) t.album else null)
            .setContentIntent(open)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(playing)
            .addAction(action(Icon.PREV, "Retroceder", ACT_PREV, 1))
            .addAction(action(if (playing) Icon.PAUSE else Icon.PLAY, if (playing) "Pausar" else "Reproducir", ACT_PLAY_PAUSE, 2))
            .addAction(action(Icon.NEXT, "Adelantar", ACT_NEXT, 3))
            .addAction(action(Icon.STOP, "Detener", ACT_STOP, 4))
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
        cover?.let { b.setLargeIcon(it) }
        return b.build()
    }
}
