package com.pvm.zombiemusiclibrary

import android.content.ContentUris
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.io.File

/**
 * The library is a list the user builds by scanning folders ("Escanear"). It is saved in the app and only changes
 * when the user scans or starts a new one. Android's own music index is used just to find the files and play them.
 */
object Repo {
    @Volatile var library: List<Track> = emptyList()
    @Volatile var mediaIndex: Map<String, Track> = emptyMap()      // every MP3 Android knows: path -> track with its id
    @Volatile var catalog: ZmlData? = null
    @Volatile var libraryLoaded = false

    private lateinit var prefs: SharedPreferences
    private lateinit var appContext: Context
    private var initialized = false

    @Synchronized
    fun init(ctx: Context) {
        if (initialized) return
        appContext = ctx.applicationContext
        prefs = appContext.getSharedPreferences("zml", Context.MODE_PRIVATE)
        initialized = true
    }

    // ---------------- small settings ----------------

    var sortColumn: SortColumn
        get() = SortColumn.values()[prefs.getInt("sortCol", 0).coerceIn(0, SortColumn.values().size - 1)]
        set(v) { prefs.edit().putInt("sortCol", v.ordinal).apply() }

    var sortAsc: Boolean
        get() = prefs.getBoolean("sortAsc", true)
        set(v) { prefs.edit().putBoolean("sortAsc", v).apply() }

    var firstRunAsked: Boolean
        get() = prefs.getBoolean("firstRunAsked", false)
        set(v) { prefs.edit().putBoolean("firstRunAsked", v).apply() }

    var batteryAsked: Boolean
        get() = prefs.getBoolean("batteryAsked", false)
        set(v) { prefs.edit().putBoolean("batteryAsked", v).apply() }

    // ---------------- last song (for the widget and for "play" when nothing is loaded) ----------------

    fun saveLast(t: Track) {
        prefs.edit().putString("lastPath", t.path).putString("lastTitle", t.title).putString("lastArtist", t.artist)
            .putString("lastAlbum", t.album).putString("lastYear", t.year).putLong("lastId", t.id).apply()
    }

    fun lastTrack(): Track? {
        val path = prefs.getString("lastPath", null) ?: return null
        val t = mediaIndex[path] ?: library.firstOrNull { it.path == path }
        if (t != null) return t
        val r = Track()
        r.path = path
        r.title = prefs.getString("lastTitle", "") ?: ""
        r.artist = prefs.getString("lastArtist", "") ?: ""
        r.album = prefs.getString("lastAlbum", "") ?: ""
        r.year = prefs.getString("lastYear", "") ?: ""
        r.id = prefs.getLong("lastId", -1)
        return r
    }

    fun uriOf(t: Track): Uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, t.id)

    // ---------------- Android's music index (read-only) ----------------

    private fun clean(s: String?): String {
        if (s == null) return ""
        val v = s.trim()
        return if (v == "<unknown>") "" else v
    }

    /** Reads every MP3 Android knows about (internal storage, SD card, drives it indexes). Quick and silent. */
    @Synchronized
    fun refreshIndex() {
        val map = HashMap<String, Track>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.YEAR, MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.SIZE
        )
        val selection = "(" + MediaStore.Audio.Media.MIME_TYPE + " = ? OR " + MediaStore.Audio.Media.MIME_TYPE + " = ?) AND " +
            MediaStore.Audio.Media.IS_RINGTONE + " = 0 AND " + MediaStore.Audio.Media.IS_NOTIFICATION + " = 0 AND " +
            MediaStore.Audio.Media.IS_ALARM + " = 0"
        appContext.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, selection, arrayOf("audio/mpeg", "audio/mp3"), null)?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val iArtist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val iAlbum = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val iTitle = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val iYear = c.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
            val iTrack = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
            val iDur = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val iData = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            val iSize = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            while (c.moveToNext()) {
                val path = c.getString(iData) ?: continue
                val t = Track()
                t.id = c.getLong(iId)
                t.path = path
                t.artist = clean(c.getString(iArtist))
                t.album = clean(c.getString(iAlbum))
                t.title = clean(c.getString(iTitle))
                val y = c.getInt(iYear)
                t.year = if (y > 0) y.toString() else ""
                t.trackNo = c.getInt(iTrack) % 1000                       // 1003 = disc 1, track 3
                t.durationMs = c.getLong(iDur)
                t.size = c.getLong(iSize)
                t.buildKey()
                map[path] = t
            }
        }
        mediaIndex = map
    }

    /** Points the saved library tracks at the live entries of the index (so they can be played). */
    @Synchronized
    fun relink() {
        val idx = mediaIndex
        if (idx.isEmpty()) return
        library = library.map { idx[it.path] ?: it }
    }

    // ---------------- the library ----------------

    private fun libraryFile() = File(appContext.filesDir, "library.zml")

    @Synchronized
    fun loadLibrary() {
        try {
            val f = libraryFile()
            library = if (f.exists()) Zml.read(f.readBytes()).tracks else emptyList()
        } catch (e: Exception) {
            library = emptyList()
        }
        relink()
        libraryLoaded = true
    }

    @Synchronized
    fun ensureLoaded() {
        if (libraryLoaded) return
        try { refreshIndex() } catch (e: Exception) { }
        loadLibrary()
    }

    @Synchronized
    fun saveLibrary() {
        try { libraryFile().writeBytes(Zml.write(library)) } catch (e: Exception) { }
    }

    @Synchronized
    fun newLibrary() {
        library = emptyList()
        saveLibrary()
    }

    /** Adds every song found inside [dir] (subfolders included). Returns how many were added. */
    @Synchronized
    fun addFolder(dir: String): Int {
        val prefix = dir.trimEnd('/') + "/"
        val have = HashSet<String>()
        for (t in library) have.add(t.path)
        val added = ArrayList<Track>()
        for (t in mediaIndex.values) if (t.path.startsWith(prefix, ignoreCase = true) && !have.contains(t.path)) added.add(t)
        if (added.isNotEmpty()) {
            library = library + added
            saveLibrary()
        }
        return added.size
    }

    /** "primary:Music/Rock" (what the folder picker returns) -> "/storage/emulated/0/Music/Rock". */
    fun treeUriToPath(uri: Uri): String? {
        return try {
            val docId = DocumentsContract.getTreeDocumentId(uri)
            val i = docId.indexOf(':')
            val vol = if (i >= 0) docId.substring(0, i) else docId
            val rel = if (i >= 0) docId.substring(i + 1) else ""
            val base = if (vol == "primary") "/storage/emulated/0" else "/storage/$vol"
            if (rel.isEmpty()) base else "$base/$rel"
        } catch (e: Exception) { null }
    }

    /** Library songs in the order of the tree (what "next" and "previous" follow). */
    fun orderedLibrary(): List<Track> = Tree.build(library, sortColumn, sortAsc).leaves

    // ---------------- PC catalog ----------------

    private fun catalogFile() = File(appContext.filesDir, "catalog.zml")

    fun loadCatalog() {
        try {
            val f = catalogFile()
            if (f.exists()) catalog = Zml.read(f.readBytes())
        } catch (e: Exception) {
            catalog = null
        }
    }

    /** Reads a .zml chosen by the user and keeps it as the catalog. Returns the number of tracks. */
    fun importCatalog(uri: Uri): Int {
        val bytes = appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IllegalStateException("No se pudo abrir el archivo.")
        val data = Zml.read(bytes)                      // throws a readable message when it is not a library file
        catalogFile().writeBytes(bytes)
        catalog = data
        return data.tracks.size
    }

    fun catalogTracks(): List<Track> = catalog?.tracks ?: emptyList()

    fun catalogCover(t: Track): ByteArray? = catalog?.covers?.get(t.coverId)

    // ---------------- export ----------------

    fun exportZml(): ByteArray = Zml.write(library)

    fun exportTxt(tracks: List<Track>): ByteArray {
        val text = "\uFEFF" + Lists.buildBandList(tracks)       // BOM so Notepad shows accents correctly
        return text.toByteArray(Charsets.UTF_8)
    }
}
