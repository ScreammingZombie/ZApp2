package com.pvm.zombiemusiclibrary

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** A thin line that shows how far the song is, at the top of the mini player. */
class ThinBar(ctx: Context, private val pal: Palette) : View(ctx) {
    var fraction = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    private val p = Paint()
    override fun onDraw(canvas: Canvas) {
        p.color = if (pal.dark) Color.rgb(62, 62, 62) else Color.rgb(205, 205, 200)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
        p.color = pal.accent
        canvas.drawRect(0f, 0f, width * fraction, height.toFloat(), p)
    }
}

class MainActivity : Activity(), PlayerService.Listener {

    private companion object {
        const val REQ_PERMS = 1
        const val REQ_FOLDER = 9
        const val REQ_LOAD = 10
        const val REQ_EXPORT_ZML = 11
        const val REQ_EXPORT_TXT_LIB = 12
        const val REQ_EXPORT_TXT_CAT = 13
    }

    private lateinit var pal: Palette
    private lateinit var body: FrameLayout
    private lateinit var library: TreeListView
    private lateinit var catalogView: TreeListView
    private lateinit var settings: SettingsView
    private lateinit var nowPlaying: NowPlayingView
    private lateinit var content: FrameLayout
    private lateinit var btnNew: ZButton
    private lateinit var btnScan: ZButton
    private lateinit var btnCatalog: ZButton
    private lateinit var btnLoad: ZButton
    private lateinit var btnGear: ZButton
    private lateinit var mini: LinearLayout
    private lateinit var miniThumb: ImageView
    private lateinit var miniTitle: TextView
    private lateinit var miniSub: TextView
    private lateinit var miniPlay: ZButton
    private lateinit var miniBar: ThinBar
    private var tab = 0                                   // 0 library, 1 catalog, 2 settings
    private var service: PlayerService? = null
    private val main = Handler(Looper.getMainLooper())
    private var placeholder: Bitmap? = null
    private var miniShownId = Long.MIN_VALUE
    private var pendingShowPlaying = false
    private var libraryReady = false

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val s = (binder as PlayerService.LocalBinder).service
            service = s
            s.addListener(this@MainActivity)
            onPlayerChanged()
            applyShowPlaying()
        }

        override fun onServiceDisconnected(name: ComponentName?) { service = null }
    }

    private val ticker = object : Runnable {
        override fun run() {
            val s = service
            if (s != null && s.hasMedia) {
                val d = s.duration
                val p = s.position
                miniBar.fraction = if (d > 0) p.toFloat() / d else 0f
                if (nowPlaying.visibility == View.VISIBLE) nowPlaying.setProgress(p, d)
            }
            main.postDelayed(this, 250)
        }
    }

    // ------------------------------------------------------------------ creation

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pal = Palette.from(this)
        Repo.init(this)
        placeholder = Assets.bitmap(this, "placeholder.png")
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        buildUi()
        if (Build.VERSION.SDK_INT >= 33) {
            // Android 13+ (also 16): the system back gesture asks this callback first
            onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) {
                if (!handleBack()) finish()
            }
        }
        pendingShowPlaying = intent?.getBooleanExtra("show_playing", false) == true

        // load the saved library and the catalog (nothing is scanned by itself; Android's index is only read to find the files)
        library.setBusy("Cargando…")
        Thread {
            try { if (hasMediaPermission()) Repo.refreshIndex() } catch (e: Exception) { }
            Repo.loadLibrary()
            Repo.loadCatalog()
            runOnUiThread {
                libraryReady = true
                library.setBusy(null)
                refreshLibrary()
                refreshCatalog()
                applyShowPlaying()
            }
        }.start()

        startService(Intent(this, PlayerService::class.java))
        bindService(Intent(this, PlayerService::class.java), conn, BIND_AUTO_CREATE)

        if (!Repo.firstRunAsked) {                         // first time: ask for the permissions right away
            Repo.firstRunAsked = true
            askPermissions()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent?.getBooleanExtra("show_playing", false) == true) {
            pendingShowPlaying = true
            applyShowPlaying()
        }
    }

    private fun stack(top: ZButton, bottom: ZButton): LinearLayout {
        val l = LinearLayout(this)
        l.orientation = LinearLayout.VERTICAL
        l.addView(top, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { bottomMargin = dp(3) })
        l.addView(bottom, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(3) })
        return l
    }

    private fun button(text: String, accent: Boolean, click: () -> Unit): ZButton {
        val b = ZButton(this, pal)
        b.text = text
        b.accent = accent
        b.textSizeSp = 14f
        b.setOnClickListener { click() }
        return b
    }

    private fun buildUi() {
        val root = FrameLayout(this)
        root.setBackgroundColor(pal.back)

        body = FrameLayout(this)
        body.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
                v.setPadding(b.left, b.top, b.right, b.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        root.addView(body, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        val column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL
        body.addView(column, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        column.addView(BannerView(this, pal), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // top buttons: two stacked pairs and the gear, filling the whole width
        btnNew = button("Nueva biblioteca", true) { confirmNewLibrary() }
        btnScan = button("Escanear", true) { chooseFolder() }
        btnCatalog = button("Catálogo", false) { showTab(if (tab == 1) 0 else 1) }
        btnLoad = button("Cargar biblioteca", true) { loadLibraryFile() }
        btnGear = ZButton(this, pal)
        btnGear.icon = Icon.GEAR
        btnGear.setOnClickListener { showTab(if (tab == 2) 0 else 2) }
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.setPadding(dp(10), dp(6), dp(10), dp(2))
        bar.addView(stack(btnNew, btnScan), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 3f))
        bar.addView(stack(btnCatalog, btnLoad), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 3f).apply { leftMargin = dp(6) })
        bar.addView(btnGear, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.4f).apply { leftMargin = dp(6) })
        column.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(104)))

        // the three screens
        content = FrameLayout(this)
        library = TreeListView(this, pal, false)
        library.onTrackClick = { rows, pos -> play(rows, pos) }
        library.onTrackLongClick = { t -> showDetails(t, false) }
        catalogView = TreeListView(this, pal, true)
        catalogView.onTrackClick = { rows, pos -> onCatalogClick(rows, pos) }
        catalogView.onTrackLongClick = { t -> showDetails(t, true) }
        settings = SettingsView(this, pal)
        val mp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        content.addView(library, mp)
        content.addView(catalogView, FrameLayout.LayoutParams(mp))
        content.addView(settings, FrameLayout.LayoutParams(mp))
        column.addView(content, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // mini player: song on top, the four buttons (symbols only) underneath
        mini = LinearLayout(this)
        mini.orientation = LinearLayout.VERTICAL
        mini.setBackgroundColor(pal.panel)
        mini.visibility = View.GONE
        miniBar = ThinBar(this, pal)
        mini.addView(miniBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(3)))
        val miniRow = LinearLayout(this)
        miniRow.orientation = LinearLayout.HORIZONTAL
        miniRow.gravity = Gravity.CENTER_VERTICAL
        miniRow.setPadding(dp(10), dp(6), dp(10), dp(2))
        miniThumb = ImageView(this)
        miniThumb.scaleType = ImageView.ScaleType.CENTER_CROP
        miniRow.addView(miniThumb, LinearLayout.LayoutParams(dp(44), dp(44)))
        val texts = LinearLayout(this)
        texts.orientation = LinearLayout.VERTICAL
        miniTitle = TextView(this)
        miniTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); miniTitle.setTextColor(pal.fore)
        miniTitle.typeface = android.graphics.Typeface.DEFAULT_BOLD
        miniTitle.maxLines = 1; miniTitle.ellipsize = android.text.TextUtils.TruncateAt.END
        miniSub = TextView(this)
        miniSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); miniSub.setTextColor(pal.dim)
        miniSub.maxLines = 1; miniSub.ellipsize = android.text.TextUtils.TruncateAt.END
        texts.addView(miniTitle); texts.addView(miniSub)
        miniRow.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(10) })
        miniRow.setOnClickListener { showNowPlaying(true) }
        mini.addView(miniRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val controls = LinearLayout(this)
        controls.orientation = LinearLayout.HORIZONTAL
        controls.setPadding(dp(10), dp(2), dp(10), dp(8))
        fun mb(icon: Icon, accent: Boolean, click: () -> Unit): ZButton {
            val b = ZButton(this, pal)
            b.icon = icon
            b.accent = accent
            b.setOnClickListener { click() }
            controls.addView(b, LinearLayout.LayoutParams(0, dp(42), 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
            return b
        }
        mb(Icon.PREV, false) { service?.previous() }
        miniPlay = mb(Icon.PLAY, true) { service?.togglePlayPause() }
        mb(Icon.STOP, false) { service?.stopPlayback() }
        mb(Icon.NEXT, false) { if (service?.next() == false) toast("Es el último tema de la lista.") }
        mini.addView(controls, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        column.addView(mini, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // now playing, over everything
        nowPlaying = NowPlayingView(this, pal)
        nowPlaying.visibility = View.GONE
        nowPlaying.onClose = { showNowPlaying(false) }
        nowPlaying.onPrev = { service?.previous() }
        nowPlaying.onPlayPause = { service?.togglePlayPause() }
        nowPlaying.onStop = { service?.stopPlayback(); showNowPlaying(false) }
        nowPlaying.onNext = { if (service?.next() == false) toast("Es el último tema de la lista.") }
        nowPlaying.onSeek = { f -> service?.let { s -> if (s.duration > 0) s.seekTo((f * s.duration).toInt()) } }
        body.addView(nowPlaying, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        // splash screen
        val splash = ImageView(this)
        splash.setBackgroundColor(Color.BLACK)
        splash.scaleType = ImageView.ScaleType.FIT_CENTER
        splash.setImageBitmap(Assets.bitmap(this, "splash.png"))
        splash.setPadding(dp(24), dp(24), dp(24), dp(24))
        splash.isClickable = true
        root.addView(splash, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        main.postDelayed({
            splash.animate().alpha(0f).setDuration(400).withEndAction { splash.visibility = View.GONE }.start()
        }, 2500)

        setContentView(root)
        showTab(0)
    }

    // ------------------------------------------------------------------ screens

    private fun showTab(i: Int) {
        tab = i
        library.visibility = if (i == 0) View.VISIBLE else View.GONE
        catalogView.visibility = if (i == 1) View.VISIBLE else View.GONE
        settings.visibility = if (i == 2) View.VISIBLE else View.GONE
        btnCatalog.active = i == 1
        btnGear.active = i == 2
        if (i == 0) library.syncSort()
        if (i == 1) catalogView.syncSort()
        if (i == 2) settings.refreshPermissions()
    }

    fun refreshLibrary() {
        library.setTracks(Repo.library)
        library.setEmptyMessage(
            if (!hasMediaPermission()) "Para empezar, dale permiso de música (botón del engranaje)."
            else "La biblioteca está vacía.\nTocá \"Escanear\" para elegir una carpeta con tu música."
        )
    }

    private fun refreshCatalog() {
        catalogView.setTracks(Repo.catalogTracks())
        catalogView.setEmptyMessage("Todavía no cargaste ninguna biblioteca.\nTocá \"Cargar biblioteca\" y elegí un archivo .zml (por ejemplo el de la PC).")
    }

    // ------------------------------------------------------------------ new library / scan / load

    private fun confirmNewLibrary() {
        AlertDialog.Builder(this)
            .setTitle("Nueva biblioteca")
            .setMessage("Se vacía la lista actual y empezás una desde cero. Los archivos de música NO se borran.")
            .setPositiveButton("Vaciar") { _, _ ->
                Repo.newLibrary()
                refreshLibrary()
                showTab(0)
                toast("Biblioteca vacía. Tocá Escanear para agregar carpetas.")
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun chooseFolder() {
        if (!hasMediaPermission()) { toast("Primero hace falta el permiso de música."); askPermissions(); return }
        showTab(0)
        try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_FOLDER)
        } catch (e: Exception) {
            toast("No se pudo abrir el selector de carpetas.")
        }
    }

    private fun scanFolder(uri: Uri) {
        val path = Repo.treeUriToPath(uri)
        if (path == null) { toast("No se pudo leer esa carpeta."); return }
        library.setBusy("Agregando temas…")
        Thread {
            var added = 0
            try {
                Repo.refreshIndex()
                added = Repo.addFolder(path)
            } catch (e: Exception) { }
            runOnUiThread {
                library.setBusy(null)
                refreshLibrary()
                toast(if (added > 0) "Se agregaron $added temas." else "No se encontraron temas nuevos en esa carpeta.")
            }
        }.start()
    }

    private fun loadLibraryFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "*/*"
        startActivityForResult(i, REQ_LOAD)
    }

    private fun onCatalogClick(rows: List<Track>, pos: Int) {
        val t = rows[pos]
        val phone = Repo.mediaIndex[t.path]
        if (phone == null) { showDetails(t, true); return }       // not on this phone: just show its data
        val queue = rows.mapNotNull { Repo.mediaIndex[it.path] }
        val idx = queue.indexOf(phone)
        if (idx >= 0) play(queue, idx)
    }

    private fun showDetails(t: Track, fromCatalog: Boolean) {
        val lay = LinearLayout(this)
        lay.orientation = LinearLayout.VERTICAL
        lay.setPadding(dp(20), dp(12), dp(20), dp(4))
        val bytes = if (fromCatalog) Repo.catalogCover(t) else null
        val bmp = if (bytes != null) ThumbLoader.decode(bytes, 600) else null
        if (bmp != null) {
            val cover = ImageView(this)
            cover.scaleType = ImageView.ScaleType.FIT_CENTER
            cover.setImageBitmap(bmp)
            lay.addView(cover, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(220)))
        }
        val tv = TextView(this)
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        tv.setPadding(0, dp(12), 0, 0)
        tv.text = "Año:  " + Util.dash(t.year) + "\nArtista:  " + Util.dash(t.artist) + "\nDisco:  " + Util.dash(t.album) +
            "\nTema:  " + Util.dash(t.title) + "\n\n" + (if (fromCatalog) "Ubicación original:\n" else "Ubicación:\n") + Util.dash(t.path)
        lay.addView(tv)
        AlertDialog.Builder(this).setView(lay).setPositiveButton("Cerrar", null).show()
    }

    // ------------------------------------------------------------------ playback

    private fun play(rows: List<Track>, pos: Int) {
        val s = service
        if (s == null) { toast("El reproductor todavía se está iniciando, probá de nuevo."); return }
        if (!hasNotificationPermission() && Build.VERSION.SDK_INT >= 33) askPermissions()
        s.playQueue(rows, pos)
    }

    private fun showNowPlaying(show: Boolean) {
        nowPlaying.visibility = if (show) View.VISIBLE else View.GONE
        if (show) { nowPlaying.showTrack(service?.current); onPlayerChanged() }
    }

    /** Coming from the widget (double tap on the cover): open the list on the song that is playing. */
    private fun applyShowPlaying() {
        if (!pendingShowPlaying || !libraryReady) return
        val s = service ?: return
        pendingShowPlaying = false
        val t: Track? = s.current ?: Repo.lastTrack()
        showNowPlaying(false)
        showTab(0)
        if (t != null) library.revealTrack(t)
    }

    override fun onPlayerChanged() {
        val s = service ?: return
        val t = s.current
        if (t == null) {
            mini.visibility = View.GONE
            if (nowPlaying.visibility == View.VISIBLE) nowPlaying.visibility = View.GONE
            miniShownId = Long.MIN_VALUE
            return
        }
        mini.visibility = View.VISIBLE
        miniTitle.text = Util.dash(t.title)
        miniSub.text = Util.dash(t.artist) + "  ·  " + Util.dash(t.album)
        miniPlay.icon = if (s.playing) Icon.PAUSE else Icon.PLAY
        if (miniShownId != t.id) {
            miniShownId = t.id
            ThumbLoader.load(miniThumb, "m" + t.id, placeholder) {
                contentResolver.loadThumbnail(Repo.uriOf(t), android.util.Size(128, 128), null)
            }
        }
        nowPlaying.setPlaying(s.playing)
        if (nowPlaying.visibility == View.VISIBLE) nowPlaying.showTrack(t)
    }

    // ------------------------------------------------------------------ permissions

    fun hasMediaPermission(): Boolean {
        val p = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    }

    fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun askPermissions() {
        val list = ArrayList<String>()
        if (!hasMediaPermission()) list.add(if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE)
        if (!hasNotificationPermission()) list.add(Manifest.permission.POST_NOTIFICATIONS)
        if (list.isNotEmpty()) requestPermissions(list.toTypedArray(), REQ_PERMS)
        else askIgnoreBatteryOnce()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        settings.refreshPermissions()
        if (hasMediaPermission()) {
            Thread {
                try { Repo.refreshIndex(); Repo.relink() } catch (e: Exception) { }
                runOnUiThread { refreshLibrary() }
            }.start()
        } else refreshLibrary()
        askIgnoreBatteryOnce()                              // right after the permissions: also ask for "no battery limits"
    }

    private fun askIgnoreBatteryOnce() {
        if (Repo.batteryAsked) return
        Repo.batteryAsked = true
        askIgnoreBattery()
    }

    fun askIgnoreBattery() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            } else toast("Ya está sin restricciones de batería.")
        } catch (e: Exception) {
            openBatterySettings()
        }
    }

    fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    fun openBatterySettings() {
        try {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (e: Exception) {
            openAppSettings()
        }
    }

    // ------------------------------------------------------------------ export

    private fun createDoc(req: Int, name: String) {
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "application/octet-stream"
        i.putExtra(Intent.EXTRA_TITLE, name)
        startActivityForResult(i, req)
    }

    fun exportZml() {
        if (Repo.library.isEmpty()) { toast("La biblioteca está vacía."); return }
        createDoc(REQ_EXPORT_ZML, "BibliotecaTelefono.zml")
    }

    fun exportTxt(fromCatalog: Boolean) {
        if (fromCatalog && Repo.catalog == null) { toast("Primero cargá una biblioteca en Catálogo."); return }
        if (!fromCatalog && Repo.library.isEmpty()) { toast("La biblioteca está vacía."); return }
        createDoc(if (fromCatalog) REQ_EXPORT_TXT_CAT else REQ_EXPORT_TXT_LIB, if (fromCatalog) "Catalogo.txt" else "Biblioteca telefono.txt")
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        if (requestCode == REQ_FOLDER) { scanFolder(uri); return }
        Thread {
            try {
                when (requestCode) {
                    REQ_LOAD -> {
                        val n = Repo.importCatalog(uri)
                        runOnUiThread { refreshCatalog(); showTab(1); toast("Biblioteca cargada: $n temas") }
                    }
                    REQ_EXPORT_ZML -> {
                        val bytes = Repo.exportZml()
                        contentResolver.openOutputStream(uri, "w")?.use { it.write(bytes) }
                        runOnUiThread { toast("Biblioteca exportada") }
                    }
                    REQ_EXPORT_TXT_LIB, REQ_EXPORT_TXT_CAT -> {
                        val tracks = if (requestCode == REQ_EXPORT_TXT_CAT) Repo.catalogTracks() else Repo.library
                        contentResolver.openOutputStream(uri, "w")?.use { it.write(Repo.exportTxt(tracks)) }
                        runOnUiThread { toast("Lista exportada") }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { toast("No se pudo: " + (e.message ?: "error")) }
            }
        }.start()
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onStart() {
        super.onStart()
        main.post(ticker)
    }

    override fun onStop() {
        main.removeCallbacks(ticker)
        super.onStop()
    }

    override fun onDestroy() {
        service?.removeListener(this)
        try { unbindService(conn) } catch (e: Exception) { }
        super.onDestroy()
    }

    /** Back button: close the player screen, then go back to the library; true when it did something. */
    private fun handleBack(): Boolean {
        if (nowPlaying.visibility == View.VISIBLE) { showNowPlaying(false); return true }
        if (tab != 0) { showTab(0); return true }
        return false
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!handleBack()) @Suppress("DEPRECATION") super.onBackPressed()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
