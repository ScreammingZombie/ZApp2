package com.pvm.zombiemusiclibrary

import java.io.ByteArrayOutputStream

/** Same file format as the PC program (.zml, version 3; versions 1 and 2 can be read too). */
class Volume(var serial: String, var label: String, var letter: String)

class ZmlData {
    val roots = ArrayList<String>()
    val covers = LinkedHashMap<String, ByteArray>()
    val tracks = ArrayList<Track>()
    val playlists = ArrayList<Pair<String, List<String>>>()
    val volumes = ArrayList<Volume>()
}

object Zml {
    private const val MAGIC = "ZML1"

    // ---------------- writing (little endian, like .NET's BinaryWriter) ----------------

    private class Writer {
        val out = ByteArrayOutputStream()

        fun int(v: Int) {
            out.write(v and 0xFF); out.write((v shr 8) and 0xFF); out.write((v shr 16) and 0xFF); out.write((v shr 24) and 0xFF)
        }

        fun long(v: Long) {
            for (i in 0 until 8) out.write(((v shr (8 * i)) and 0xFF).toInt())
        }

        fun bool(v: Boolean) = out.write(if (v) 1 else 0)

        fun string(s: String) {
            val b = s.toByteArray(Charsets.UTF_8)
            var n = b.size
            while (n >= 0x80) { out.write((n and 0x7F) or 0x80); n = n ushr 7 }
            out.write(n)
            out.write(b)
        }
    }

    fun write(tracks: List<Track>, covers: Map<String, ByteArray> = emptyMap()): ByteArray {
        val w = Writer()
        w.out.write(MAGIC.toByteArray(Charsets.US_ASCII))
        w.int(3)
        w.int(0)                                   // roots: none (the paths belong to the phone)
        val used = tracks.map { it.coverId }.filter { it.isNotEmpty() && covers.containsKey(it) }.toSet()
        w.int(used.size)
        for (id in used) {
            val b = covers.getValue(id)
            w.string(id); w.int(b.size); w.out.write(b)
        }
        w.int(tracks.size)
        for (t in tracks) {
            w.string(t.path); w.string(t.artist); w.string(t.album); w.string(t.title); w.string(t.year)
            w.int(t.trackNo); w.string(t.coverId); w.long(t.size); w.long(0L); w.bool(true)
            w.long(if (t.durationMs > 0) t.durationMs else 0L)
        }
        w.int(0)                                   // playlists
        w.int(0)                                   // volumes
        return w.out.toByteArray()
    }

    // ---------------- reading ----------------

    private class Reader(val b: ByteArray) {
        var p = 0

        private fun need(n: Int) { if (p + n > b.size) throw IllegalStateException("Archivo incompleto") }

        fun byte(): Int { need(1); return b[p++].toInt() and 0xFF }

        fun int(): Int {
            need(4)
            val v = (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8) or
                ((b[p + 2].toInt() and 0xFF) shl 16) or ((b[p + 3].toInt() and 0xFF) shl 24)
            p += 4
            return v
        }

        fun long(): Long {
            need(8)
            var v = 0L
            for (i in 0 until 8) v = v or ((b[p + i].toLong() and 0xFF) shl (8 * i))
            p += 8
            return v
        }

        fun bool(): Boolean = byte() != 0

        fun bytes(n: Int): ByteArray {
            if (n < 0) throw IllegalStateException("Archivo dañado")
            need(n)
            val r = b.copyOfRange(p, p + n)
            p += n
            return r
        }

        fun string(): String {
            var n = 0
            var shift = 0
            while (true) {
                val x = byte()
                n = n or ((x and 0x7F) shl shift)
                if (x and 0x80 == 0) break
                shift += 7
                if (shift > 28) throw IllegalStateException("Archivo dañado")
            }
            return String(bytes(n), Charsets.UTF_8)
        }
    }

    fun read(bytes: ByteArray): ZmlData {
        val r = Reader(bytes)
        val magic = String(r.bytes(4), Charsets.US_ASCII)
        if (magic != MAGIC) throw IllegalStateException("Ese archivo no es una biblioteca de Zombie Music Library.")
        val ver = r.int()
        if (ver < 1 || ver > 3) throw IllegalStateException("Versión de biblioteca no soportada (creada con una versión más nueva).")
        val d = ZmlData()
        repeat(r.int()) { d.roots.add(r.string()) }
        repeat(r.int()) {
            val id = r.string()
            d.covers[id] = r.bytes(r.int())
        }
        repeat(r.int()) {
            val t = Track()
            t.path = r.string(); t.artist = r.string(); t.album = r.string(); t.title = r.string(); t.year = r.string()
            t.trackNo = r.int(); t.coverId = r.string(); t.size = r.long()
            r.long()                                // modified date (PC only)
            val inLibrary = r.bool()
            if (ver >= 2) t.durationMs = maxOf(0L, r.long())
            t.buildKey()
            if (inLibrary) d.tracks.add(t)
        }
        repeat(r.int()) {
            val name = r.string()
            val items = ArrayList<String>()
            repeat(r.int()) { items.add(r.string()) }
            d.playlists.add(Pair(name, items))
        }
        if (ver >= 3) repeat(r.int()) { d.volumes.add(Volume(r.string(), r.string(), r.string())) }
        return d
    }
}
