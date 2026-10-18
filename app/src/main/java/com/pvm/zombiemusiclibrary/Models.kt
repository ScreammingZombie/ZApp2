package com.pvm.zombiemusiclibrary

import java.text.Collator
import java.text.Normalizer
import java.util.Locale
import java.util.TreeMap

/** One song. For songs of the phone [id] is the MediaStore id; for the PC catalog it is -1. */
class Track(
    var id: Long = -1,
    var path: String = "",
    var artist: String = "",
    var album: String = "",
    var title: String = "",
    var year: String = "",
    var trackNo: Int = 0,
    var coverId: String = "",
    var size: Long = 0,
    var durationMs: Long = 0
) {
    var searchKey: String = ""

    val folder: String get() = path.substringBeforeLast('/', "")

    fun buildKey() {
        searchKey = Util.normalize("$artist $album $title $year $path")
    }
}

object Util {
    private val marks = Regex("\\p{Mn}+")

    fun normalize(s: String): String =
        Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(marks, "")

    fun dash(s: String?): String = if (s.isNullOrEmpty()) "-" else s

    /** 291:45:10 (hours only, no days) */
    fun formatTotal(ms: Long): String {
        val sec = maxOf(0L, ms) / 1000
        return String.format(Locale.ROOT, "%d:%02d:%02d", sec / 3600, (sec / 60) % 60, sec % 60)
    }

    /** 03:45 */
    fun formatTime(ms: Long): String {
        val sec = maxOf(0L, ms) / 1000
        return String.format(Locale.ROOT, "%02d:%02d", sec / 60, sec % 60)
    }

    fun displayName(path: String): String = path.substringAfterLast('/')
}

enum class SortColumn { ARTIST, YEAR, ALBUM, TITLE }

object Sorting {
    private val collator: Collator = Collator.getInstance(Locale.forLanguageTag("es")).apply { strength = Collator.PRIMARY }

    fun compareText(a: String, b: String): Int = collator.compare(a, b)

    // empty values always go last, in both directions
    private fun str(a: String, b: String, asc: Boolean): Int {
        val ea = a.isEmpty()
        val eb = b.isEmpty()
        if (ea && eb) return 0
        if (ea) return 1
        if (eb) return -1
        val r = collator.compare(a, b)
        return if (asc) r else -r
    }

    private fun num(a: String, b: String, asc: Boolean): Int {
        val x = a.toIntOrNull()
        val y = b.toIntOrNull()
        if (x == null && y == null) return 0
        if (x == null) return 1
        if (y == null) return -1
        val r = x.compareTo(y)
        return if (asc) r else -r
    }

    fun comparator(col: SortColumn, asc: Boolean): Comparator<Track> = Comparator { a, b ->
        var r = when (col) {
            SortColumn.ARTIST -> {
                var x = str(a.artist, b.artist, asc)
                if (x == 0) x = num(a.year, b.year, true)
                if (x == 0) x = str(a.album, b.album, true)
                x
            }
            SortColumn.YEAR -> {
                var x = num(a.year, b.year, asc)
                if (x == 0) x = str(a.artist, b.artist, true)
                if (x == 0) x = str(a.album, b.album, true)
                x
            }
            SortColumn.ALBUM -> {
                var x = str(a.album, b.album, asc)
                if (x == 0) x = str(a.artist, b.artist, true)
                if (x == 0) x = num(a.year, b.year, true)
                x
            }
            SortColumn.TITLE -> {
                var x = str(a.title, b.title, asc)
                if (x == 0) x = str(a.artist, b.artist, true)
                x
            }
        }
        if (r == 0) r = a.trackNo.compareTo(b.trackNo)
        if (r == 0) r = str(a.title, b.title, true)
        if (r == 0) r = a.path.compareTo(b.path)
        r
    }
}

data class Stats(val bands: Int, val discs: Int, val totalMs: Long)

object Lists {
    /** Keeps the tracks that contain every word of the query (ignoring case and accents). */
    fun filter(tracks: List<Track>, query: String): List<Track> {
        val words = Util.normalize(query).trim().split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return tracks
        return tracks.filter { t -> words.all { t.searchKey.contains(it) } }
    }

    fun stats(rows: List<Track>): Stats {
        val bands = HashSet<String>()
        val discs = HashSet<String>()
        var total = 0L
        for (t in rows) {
            if (t.artist.isNotEmpty()) bands.add(t.artist.lowercase(Locale.ROOT))
            if (t.album.isNotEmpty()) discs.add(t.artist.lowercase(Locale.ROOT) + "\u0001" + t.album.lowercase(Locale.ROOT))
            if (t.durationMs > 0) total += t.durationMs
        }
        return Stats(bands.size, discs.size, total)
    }

    /**
     * "Band - Year - Album", one line per disc; the band is not repeated: the next lines of the same band
     * are indented so that their dash lines up under the first one. Same output as the PC program.
     */
    fun buildBandList(tracks: List<Track>): String {
        val cmp = Comparator<String> { a, b -> Sorting.compareText(a, b) }
        val bands = TreeMap<String, TreeMap<String, MutableList<String>>>(cmp)
        for (t in tracks) {
            val band = if (t.artist.isEmpty()) "-" else t.artist
            val album = if (t.album.isEmpty()) "-" else t.album
            val albums = bands.getOrPut(band) { TreeMap(cmp) }
            val years = albums.getOrPut(album) { mutableListOf() }
            if (t.year.isNotEmpty()) years.add(t.year)
        }
        val sb = StringBuilder()
        for ((band, albums) in bands) {
            val discs = ArrayList<Pair<String, String>>()   // (year, album)
            for ((album, years) in albums) {
                var year = "-"
                var best = 0
                for (y in years) {
                    val c = years.count { it == y }
                    if (c > best) { best = c; year = y }
                }
                discs.add(Pair(year, album))
            }
            discs.sortWith(Comparator { x, y ->
                val xi = x.first.toIntOrNull()
                val yi = y.first.toIntOrNull()
                when {
                    (xi == null) != (yi == null) -> if (xi == null) 1 else -1    // albums without year go last
                    xi != null && yi != null && xi != yi -> xi.compareTo(yi)
                    else -> Sorting.compareText(x.second, y.second)
                }
            })
            val pad = " ".repeat(band.length + 1)
            for ((i, d) in discs.withIndex()) {
                val head = if (i == 0) "$band " else pad
                sb.append(head).append("- ").append(d.first).append(" - ").append(d.second).append("\r\n")
            }
        }
        return sb.toString()
    }
}
