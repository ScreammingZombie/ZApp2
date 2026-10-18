package com.pvm.zombiemusiclibrary

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.Size
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.inputmethod.EditorInfo
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.util.concurrent.Executors

/**
 * Sort buttons, search box, totals and the tree list.
 * Tap a band / year / disc to open or close it; tap a song to play it. Used for the library and for the PC catalog.
 */
class TreeListView(ctx: Context, private val pal: Palette, private val isCatalog: Boolean) : LinearLayout(ctx) {

    /** queue = every song of the list in tree order, index = the tapped one */
    var onTrackClick: ((List<Track>, Int) -> Unit)? = null
    var onTrackLongClick: ((Track) -> Unit)? = null

    private val search = EditText(ctx)
    private val stats = TextView(ctx)
    private val list = ListView(ctx)
    private val message = TextView(ctx)
    private val sortButtons = ArrayList<ZButton>()
    private val adapter = RowAdapter()
    private val handler = Handler(Looper.getMainLooper())
    private val pool = Executors.newSingleThreadExecutor()
    private val doRebuild = Runnable { rebuild() }
    private val placeholder: Bitmap? = Assets.bitmap(ctx, "placeholder.png")
    private val labels = listOf("Artista", "Año", "Disco", "Tema")

    @Volatile private var gen = 0
    private var builtSort = -1
    private var source: List<Track> = emptyList()
    private var result: TreeResult? = null
    private var visible: List<TreeNode> = emptyList()
    private val expanded = HashSet<String>()
    private var searching = false
    private var emptyMessage: String? = null
    private var busyMessage: String? = null
    private var pendingReveal: Track? = null

    init {
        orientation = VERTICAL
        setPadding(context.dp(10), context.dp(6), context.dp(10), 0)

        // sort buttons first (all the buttons together), then the search box
        val sortRow = LinearLayout(context)
        sortRow.orientation = HORIZONTAL
        for ((i, name) in labels.withIndex()) {
            val b = ZButton(context, pal)
            b.text = name
            b.textSizeSp = 12f
            b.setOnClickListener { onSort(SortColumn.values()[i]) }
            val lp = LayoutParams(0, context.dp(38), 1f)
            lp.setMargins(if (i == 0) 0 else context.dp(4), 0, 0, 0)
            sortRow.addView(b, lp)
            sortButtons.add(b)
        }
        addView(sortRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        search.hint = "Buscar por artista, disco o tema…"
        search.setSingleLine(true)
        search.imeOptions = EditorInfo.IME_ACTION_SEARCH
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        search.setTextColor(pal.fore)
        search.setHintTextColor(pal.dim)
        search.setPadding(context.dp(14), context.dp(9), context.dp(14), context.dp(9))
        search.background = GradientDrawable().apply {
            setColor(pal.listBack)
            setStroke(context.dp(1), pal.border)
            cornerRadius = context.dpf(14f)
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { handler.removeCallbacks(doRebuild); handler.postDelayed(doRebuild, 250) }
        })
        addView(search, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(8) })

        stats.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        stats.setTextColor(pal.dim)
        stats.setPadding(context.dp(2), context.dp(8), context.dp(2), context.dp(6))
        addView(stats, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        val frame = FrameLayout(context)
        list.divider = null
        list.dividerHeight = 0
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ -> onRowClick(pos) }
        list.setOnItemLongClickListener { _, _, pos, _ ->
            val n = visible.getOrNull(pos)
            val t = n?.track
            if (t != null) { onTrackLongClick?.invoke(t); true } else false
        }
        frame.addView(list, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        message.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        message.setTextColor(pal.dim)
        message.gravity = Gravity.CENTER
        message.setPadding(context.dp(24), context.dp(24), context.dp(24), context.dp(24))
        message.visibility = View.GONE
        frame.addView(message, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        addView(frame, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        updateSortButtons()
    }

    // ------------------------------------------------------------------ sort / search / build

    private fun onSort(col: SortColumn) {
        if (Repo.sortColumn == col) Repo.sortAsc = !Repo.sortAsc
        else { Repo.sortColumn = col; Repo.sortAsc = true }
        updateSortButtons()
        rebuild()
    }

    private fun updateSortButtons() {
        for ((i, b) in sortButtons.withIndex()) {
            val on = SortColumn.values()[i] == Repo.sortColumn
            b.active = on
            b.text = labels[i] + if (on) (if (Repo.sortAsc) " ▲" else " ▼") else ""
        }
    }

    fun setTracks(t: List<Track>) {
        source = t
        rebuild()
    }

    fun syncSort() {
        updateSortButtons()
        val sig = Repo.sortColumn.ordinal * 2 + (if (Repo.sortAsc) 1 else 0)
        if (sig != builtSort) rebuild()
    }

    fun setEmptyMessage(msg: String?) { emptyMessage = msg; showMessage() }

    /** Text shown over the list while something is being done in the background (null = nothing). */
    fun setBusy(msg: String?) { busyMessage = msg; showMessage() }

    private fun showMessage() {
        val busy = busyMessage
        val msg = emptyMessage
        val rows = visible.size
        when {
            busy != null -> { message.text = busy; message.visibility = View.VISIBLE }
            rows == 0 && source.isEmpty() && msg != null -> { message.text = msg; message.visibility = View.VISIBLE }
            rows == 0 && source.isNotEmpty() -> { message.text = "Ningún tema coincide con la búsqueda."; message.visibility = View.VISIBLE }
            else -> message.visibility = View.GONE
        }
    }

    /** Filtering, sorting and grouping happen in the background so the screen never freezes. */
    private fun rebuild() {
        val my = ++gen
        val q = search.text.toString()
        val col = Repo.sortColumn
        val asc = Repo.sortAsc
        builtSort = col.ordinal * 2 + (if (asc) 1 else 0)
        val src = source
        stats.text = "Cargando…"
        pool.execute {
            if (my != gen) return@execute
            val filtered = Lists.filter(src, q)
            val res = Tree.build(filtered, col, asc)
            val st = Lists.stats(filtered)
            handler.post { if (my == gen) applyResult(res, st, q.isNotBlank()) }
        }
    }

    private fun applyResult(res: TreeResult, st: Stats, isSearch: Boolean) {
        result = res
        searching = isSearch
        visible = Tree.flatten(res.roots, expanded, searching)
        adapter.notifyDataSetChanged()
        stats.text = "Bandas: ${st.bands}    Discos: ${st.discs}    Temas: ${res.leaves.size}    Tiempo Total: ${Util.formatTotal(st.totalMs)}"
        showMessage()
        pendingReveal?.let { val t = it; pendingReveal = null; revealTrack(t) }
    }

    // ------------------------------------------------------------------ taps

    private fun onRowClick(pos: Int) {
        val n = visible.getOrNull(pos) ?: return
        val res = result ?: return
        if (n.isLeaf) {
            onTrackClick?.invoke(res.leaves, n.leafIndex)
            return
        }
        if (searching) return                                    // while searching everything stays open
        if (!expanded.remove(n.key)) expanded.add(n.key)
        visible = Tree.flatten(res.roots, expanded, false)
        adapter.notifyDataSetChanged()
    }

    /** Opens the band / disc of this song and scrolls to it (used when coming from the widget). */
    fun revealTrack(t: Track) {
        val res = result
        if (res == null) { pendingReveal = t; return }
        val node = res.leafNodes[t] ?: res.leafNodes.entries.firstOrNull { it.key.path == t.path }?.value ?: return
        expanded.addAll(Tree.ancestorKeys(node))
        visible = Tree.flatten(res.roots, expanded, searching)
        adapter.notifyDataSetChanged()
        val pos = visible.indexOf(node)
        if (pos >= 0) list.post { list.setSelectionFromTop(pos, context.dp(120)) }
    }

    // ------------------------------------------------------------------ rows

    private class Holder(val indent: View, val arrow: TextView, val thumb: ImageView, val title: TextView, val sub: TextView, val dur: TextView, val root: LinearLayout)

    private inner class RowAdapter : BaseAdapter() {
        override fun getCount(): Int = visible.size
        override fun getItem(position: Int): Any = visible[position]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getViewTypeCount(): Int = 2
        override fun getItemViewType(position: Int): Int = if (visible[position].isLeaf) 1 else 0

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val n = visible[position]
            val v: View
            val h: Holder
            if (convertView == null) {
                val row = LinearLayout(context)
                row.orientation = HORIZONTAL
                row.gravity = Gravity.CENTER_VERTICAL
                val indent = View(context)
                row.addView(indent, LayoutParams(0, 1))
                val arrow = TextView(context)
                arrow.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                arrow.setTextColor(pal.accent)
                arrow.gravity = Gravity.CENTER
                row.addView(arrow, LayoutParams(context.dp(26), LayoutParams.WRAP_CONTENT))
                val thumb = ImageView(context)
                thumb.scaleType = ImageView.ScaleType.CENTER_CROP
                thumb.clipToOutline = true
                thumb.outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) { outline.setRoundRect(0, 0, view.width, view.height, context.dpf(8f)) }
                }
                row.addView(thumb, LayoutParams(context.dp(46), context.dp(46)).apply { rightMargin = context.dp(10) })
                val col = LinearLayout(context)
                col.orientation = VERTICAL
                val title = TextView(context)
                title.maxLines = 1; title.ellipsize = TextUtils.TruncateAt.END
                val sub = TextView(context)
                sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); sub.setTextColor(pal.dim)
                sub.maxLines = 1; sub.ellipsize = TextUtils.TruncateAt.END
                col.addView(title); col.addView(sub)
                row.addView(col, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
                val dur = TextView(context)
                dur.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); dur.setTextColor(pal.dim)
                dur.setPadding(context.dp(8), 0, context.dp(2), 0)
                row.addView(dur, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
                h = Holder(indent, arrow, thumb, title, sub, dur, row)
                row.tag = h
                v = row
            } else {
                v = convertView
                h = v.tag as Holder
            }

            h.indent.layoutParams = (h.indent.layoutParams as LayoutParams).apply { width = context.dp(14 * n.level) }
            h.root.setPadding(context.dp(6), context.dp(if (n.isLeaf) 7 else 9), context.dp(6), context.dp(if (n.isLeaf) 7 else 9))
            if (n.isLeaf) {
                val t = n.track!!
                h.root.setBackgroundColor(0)
                h.arrow.text = ""
                h.thumb.visibility = View.GONE
                h.title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                h.title.setTextColor(pal.fore)
                h.title.typeface = Typeface.DEFAULT
                h.title.text = (if (t.trackNo > 0) String.format("%02d   ", t.trackNo) else "") + Util.dash(t.title)
                h.sub.text = n.sub
                h.sub.visibility = if (n.sub.isEmpty()) View.GONE else View.VISIBLE
                h.dur.text = if (t.durationMs > 0) Util.formatTime(t.durationMs) else ""
                if (n.level == 0) {                                       // flat list (sorted by song): show the cover too
                    h.thumb.visibility = View.VISIBLE
                    loadThumb(h, t)
                }
            } else {
                h.root.setBackgroundColor(if (n.level == 0) pal.panel else 0)
                val open = searching || expanded.contains(n.key)
                h.arrow.text = if (open) "▾" else "▸"
                h.title.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (n.level == 0) 16f else 15f)
                h.title.setTextColor(pal.fore)
                h.title.typeface = Typeface.DEFAULT_BOLD
                h.title.text = n.label
                h.sub.text = n.sub
                h.sub.visibility = View.VISIBLE
                h.dur.text = ""
                if (n.isDisc && n.children.isNotEmpty()) {
                    h.thumb.visibility = View.VISIBLE
                    loadThumb(h, n.children[0].track!!)
                } else h.thumb.visibility = View.GONE
            }
            return v
        }

        private fun loadThumb(h: Holder, t: Track) {
            if (!isCatalog) {
                if (t.id < 0) { h.thumb.tag = null; h.thumb.setImageBitmap(placeholder); return }
                ThumbLoader.load(h.thumb, "m" + t.id, placeholder) {
                    context.contentResolver.loadThumbnail(Repo.uriOf(t), Size(128, 128), null)
                }
            } else if (t.coverId.isNotEmpty()) {
                ThumbLoader.load(h.thumb, "c" + t.coverId, placeholder) {
                    Repo.catalogCover(t)?.let { ThumbLoader.decode(it, 128) }
                }
            } else {
                h.thumb.tag = null
                h.thumb.setImageBitmap(placeholder)
            }
        }
    }
}
