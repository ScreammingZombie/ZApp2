package com.pvm.zombiemusiclibrary

import java.text.CollationKey
import java.text.Collator
import java.util.Locale

/** One row of the tree: a group (band, year, disc) or a song. */
class TreeNode(val key: String, val level: Int, val label: String) {
    var sub: String = ""
    var track: Track? = null
    var parent: TreeNode? = null
    val children = ArrayList<TreeNode>()
    var isBand = false
    var isDisc = false
    var year = ""
    var band = ""
    var leafIndex = -1
    var tracks = 0
    var discs = 0
    var bands = 0
    var durationMs = 0L
    val isLeaf: Boolean get() = track != null
}

class TreeResult(val roots: List<TreeNode>, val leaves: List<Track>, val leafNodes: Map<Track, TreeNode>)

/**
 * Builds the tree for the chosen sort:
 *  Artista -> band > disc > song     Año -> year > band > disc > song
 *  Disco   -> disc > song            Tema -> plain list of songs
 */
object Tree {
    private val collator: Collator = Collator.getInstance(Locale.forLanguageTag("es")).apply { strength = Collator.PRIMARY }

    private class Keys {
        private val m = HashMap<String, CollationKey>()
        fun of(s: String): CollationKey = m.getOrPut(s) { collator.getCollationKey(s) }

        /** Empty values always go last, in both directions. */
        fun cmp(a: String, b: String, asc: Boolean): Int {
            if (a.isEmpty() && b.isEmpty()) return 0
            if (a.isEmpty()) return 1
            if (b.isEmpty()) return -1
            val r = of(a).compareTo(of(b))
            return if (asc) r else -r
        }
    }

    private class Ctx(val keys: Keys) {
        val leaves = ArrayList<Track>()
        val leafNodes = HashMap<Track, TreeNode>()
    }

    private fun yearCmp(a: String, b: String, asc: Boolean): Int {
        val x = a.toIntOrNull()
        val y = b.toIntOrNull()
        if (x == null && y == null) return 0
        if (x == null) return 1
        if (y == null) return -1
        val r = x.compareTo(y)
        return if (asc) r else -r
    }

    private fun group(tracks: List<Track>, keyOf: (Track) -> String): LinkedHashMap<String, MutableList<Track>> {
        val m = LinkedHashMap<String, MutableList<Track>>()
        for (t in tracks) m.getOrPut(keyOf(t)) { ArrayList() }.add(t)
        return m
    }

    private fun mostCommonYear(ts: List<Track>): String {
        val counts = HashMap<String, Int>()
        for (t in ts) if (t.year.isNotEmpty()) counts[t.year] = (counts[t.year] ?: 0) + 1
        if (counts.isEmpty()) return ""
        return counts.entries.sortedWith(compareBy({ -it.value }, { it.key })).first().key
    }

    private fun link(parent: TreeNode?, child: TreeNode, roots: MutableList<TreeNode>) {
        if (parent == null) roots.add(child) else { child.parent = parent; parent.children.add(child) }
    }

    private fun lower(s: String) = s.trim().lowercase(Locale.ROOT)

    private fun addLeaves(parent: TreeNode?, tracks: List<Track>, level: Int, c: Ctx, roots: MutableList<TreeNode>, asc: Boolean = true, byTitle: Boolean = false) {
        val cmp = Comparator<Track> { a, b ->
            var r: Int
            if (byTitle) {
                r = c.keys.cmp(a.title, b.title, asc)
                if (r == 0) r = c.keys.cmp(a.artist, b.artist, true)
            } else {
                val na = if (a.trackNo > 0) a.trackNo else Int.MAX_VALUE
                val nb = if (b.trackNo > 0) b.trackNo else Int.MAX_VALUE
                r = na.compareTo(nb)
                if (r == 0) r = c.keys.cmp(a.title, b.title, true)
            }
            if (r == 0) r = a.path.compareTo(b.path)
            r
        }
        for (t in tracks.sortedWith(cmp)) {
            val n = TreeNode("t:" + t.id + "|" + t.path, level, t.title.ifEmpty { "-" })
            n.track = t
            n.leafIndex = c.leaves.size
            c.leaves.add(t)
            c.leafNodes[t] = n
            link(parent, n, roots)
        }
    }

    private fun addDiscs(parent: TreeNode, tracks: List<Track>, level: Int, prefix: String, c: Ctx, roots: MutableList<TreeNode>) {
        val byAlbum = group(tracks) { lower(it.album) }
        val discs = byAlbum.entries.map { Triple(it.key, it.value, mostCommonYear(it.value)) }.sortedWith(Comparator { a, b ->
            var r = yearCmp(a.third, b.third, true)
            if (r == 0) r = c.keys.cmp(a.second[0].album.trim(), b.second[0].album.trim(), true)
            r
        })
        for ((k, ts, y) in discs) {
            val d = TreeNode("$prefix|d:$k", level, ts[0].album.trim().ifEmpty { "-" })
            d.isDisc = true
            d.year = y
            d.band = ts[0].artist.trim()
            link(parent, d, roots)
            addLeaves(d, ts, level + 1, c, roots)
        }
    }

    private fun addBands(parent: TreeNode?, tracks: List<Track>, level: Int, prefix: String, asc: Boolean, c: Ctx, roots: MutableList<TreeNode>) {
        val byBand = group(tracks) { lower(it.artist) }
        val bands = byBand.entries.sortedWith(Comparator { a, b -> c.keys.cmp(a.value[0].artist.trim(), b.value[0].artist.trim(), asc) })
        for (e in bands) {
            val b = TreeNode("$prefix|b:" + e.key, level, e.value[0].artist.trim().ifEmpty { "-" })
            b.isBand = true
            link(parent, b, roots)
            addDiscs(b, e.value, level + 1, "$prefix|b:" + e.key, c, roots)
        }
    }

    fun build(tracks: List<Track>, col: SortColumn, asc: Boolean): TreeResult {
        val c = Ctx(Keys())
        val roots = ArrayList<TreeNode>()
        when (col) {
            SortColumn.ARTIST -> addBands(null, tracks, 0, "A", asc, c, roots)
            SortColumn.YEAR -> {
                val byYear = group(tracks) { it.year.trim() }
                val years = byYear.entries.sortedWith(Comparator { a, b -> yearCmp(a.key, b.key, asc) })
                for (e in years) {
                    val y = TreeNode("Y|y:" + e.key, 0, e.key.ifEmpty { "-" })
                    y.year = e.key
                    roots.add(y)
                    addBands(y, e.value, 1, "Y|y:" + e.key, true, c, roots)
                }
            }
            SortColumn.ALBUM -> {
                val byDisc = group(tracks) { lower(it.artist) + "\u0001" + lower(it.album) }
                val discs = byDisc.entries.sortedWith(Comparator { a, b ->
                    var r = c.keys.cmp(a.value[0].album.trim(), b.value[0].album.trim(), asc)
                    if (r == 0) r = c.keys.cmp(a.value[0].artist.trim(), b.value[0].artist.trim(), true)
                    r
                })
                for (e in discs) {
                    val d = TreeNode("D|d:" + e.key, 0, e.value[0].album.trim().ifEmpty { "-" })
                    d.isDisc = true
                    d.year = mostCommonYear(e.value)
                    d.band = e.value[0].artist.trim()
                    roots.add(d)
                    addLeaves(d, e.value, 1, c, roots)
                }
            }
            SortColumn.TITLE -> addLeaves(null, tracks, 0, c, roots, asc, true)
        }
        for (r in roots) { aggregate(r); describe(r, col) }
        return TreeResult(roots, c.leaves, c.leafNodes)
    }

    private fun aggregate(n: TreeNode) {
        val t = n.track
        if (t != null) { n.tracks = 1; n.durationMs = maxOf(0L, t.durationMs); return }
        var tracks = 0
        var dur = 0L
        var discs = if (n.isDisc) 1 else 0
        var bands = if (n.isBand) 1 else 0
        for (c in n.children) {
            aggregate(c)
            tracks += c.tracks
            dur += c.durationMs
            if (!n.isDisc) discs += c.discs
            if (!n.isBand) bands += c.bands
        }
        n.tracks = tracks; n.durationMs = dur; n.discs = discs; n.bands = bands
    }

    private fun plural(n: Int, one: String, many: String) = "$n " + (if (n == 1) one else many)

    private fun describe(n: TreeNode, col: SortColumn) {
        val t = n.track
        if (t != null) {
            n.sub = if (col == SortColumn.TITLE) Util.dash(t.artist) + "  ·  " + Util.dash(t.album) else ""
            return
        }
        val time = Util.formatTotal(n.durationMs)
        n.sub = when {
            n.isBand -> plural(n.discs, "disco", "discos") + "  ·  " + plural(n.tracks, "tema", "temas") + "  ·  " + time
            n.isDisc && col == SortColumn.ARTIST -> Util.dash(n.year) + "  ·  " + plural(n.tracks, "tema", "temas") + "  ·  " + time
            n.isDisc && col == SortColumn.YEAR -> plural(n.tracks, "tema", "temas") + "  ·  " + time
            n.isDisc -> Util.dash(n.band) + "  ·  " + Util.dash(n.year) + "  ·  " + plural(n.tracks, "tema", "temas")
            else -> plural(n.bands, "banda", "bandas") + "  ·  " + plural(n.discs, "disco", "discos") + "  ·  " + plural(n.tracks, "tema", "temas")
        }
        for (c in n.children) describe(c, col)
    }

    /** The rows that are visible: a group shows its children only when it is open (or while searching). */
    fun flatten(roots: List<TreeNode>, expanded: Set<String>, expandAll: Boolean): List<TreeNode> {
        val out = ArrayList<TreeNode>()
        fun walk(n: TreeNode) {
            out.add(n)
            if (!n.isLeaf && (expandAll || expanded.contains(n.key))) for (c in n.children) walk(c)
        }
        for (r in roots) walk(r)
        return out
    }

    fun ancestorKeys(n: TreeNode): List<String> {
        val keys = ArrayList<String>()
        var p = n.parent
        while (p != null) { keys.add(p.key); p = p.parent }
        return keys
    }
}
