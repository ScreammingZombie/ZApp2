package com.pvm.zombiemusiclibrary

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Full screen "now playing": cover, data, progress bar with the zombie head and the four buttons. */
class NowPlayingView(ctx: Context, private val pal: Palette) : LinearLayout(ctx) {

    var onClose: (() -> Unit)? = null
    var onPrev: (() -> Unit)? = null
    var onPlayPause: (() -> Unit)? = null
    var onStop: (() -> Unit)? = null
    var onNext: (() -> Unit)? = null
    var onSeek: ((Float) -> Unit)? = null

    private val cover = ImageView(ctx)
    private val vYear = value()
    private val vArtist = value()
    private val vAlbum = value()
    private val vTitle = value()
    private val seek = SeekBarView(ctx, pal)
    private val elapsed = TextView(ctx)
    private val total = TextView(ctx)
    private val play = ZButton(ctx, pal)
    private val placeholder: Bitmap? = Assets.bitmap(ctx, "placeholder.png")
    private var shownId = Long.MIN_VALUE

    private fun value(): TextView {
        val t = TextView(context)
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        t.setTextColor(pal.fore)
        t.typeface = Typeface.DEFAULT_BOLD
        t.maxLines = 2
        t.ellipsize = TextUtils.TruncateAt.END
        return t
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(pal.back)
        isClickable = true                                     // do not let touches fall through to the lists underneath
        setPadding(context.dp(16), context.dp(8), context.dp(16), context.dp(12))

        val close = ZButton(context, pal)
        close.text = "▼  Volver a la lista"
        close.textSizeSp = 13f
        close.setOnClickListener { onClose?.invoke() }
        addView(close, LayoutParams(LayoutParams.WRAP_CONTENT, context.dp(38)).apply { gravity = Gravity.START })
        (close.layoutParams as LayoutParams).width = context.dp(180)

        cover.scaleType = ImageView.ScaleType.FIT_CENTER
        cover.setImageBitmap(placeholder)
        addView(cover, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply { setMargins(0, context.dp(12), 0, context.dp(12)) })

        addView(field("Año", vYear))
        addView(field("Artista", vArtist))
        addView(field("Disco", vAlbum))
        addView(field("Tema", vTitle))

        val seekRow = LinearLayout(context)
        seekRow.orientation = HORIZONTAL
        seekRow.gravity = Gravity.CENTER_VERTICAL
        elapsed.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); elapsed.setTextColor(pal.fore); elapsed.text = "00:00"
        total.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); total.setTextColor(pal.fore); total.text = "00:00"
        seek.onSeek = { onSeek?.invoke(it) }
        seekRow.addView(elapsed, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        seekRow.addView(seek, LayoutParams(0, context.dp(40), 1f).apply { setMargins(context.dp(8), 0, context.dp(8), 0) })
        seekRow.addView(total, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(seekRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(8) })

        val buttons = LinearLayout(context)
        buttons.orientation = HORIZONTAL
        fun btn(b: ZButton, icon: Icon, click: () -> Unit) {
            b.icon = icon; b.text = ""                       // symbols only, no words
            b.setOnClickListener { click() }
            buttons.addView(b, LayoutParams(0, context.dp(64), 1f).apply { setMargins(context.dp(3), 0, context.dp(3), 0) })
        }
        val prev = ZButton(context, pal)
        val stop = ZButton(context, pal)
        val next = ZButton(context, pal)
        btn(prev, Icon.PREV) { onPrev?.invoke() }
        play.accent = true
        btn(play, Icon.PAUSE) { onPlayPause?.invoke() }
        btn(stop, Icon.STOP) { onStop?.invoke() }
        btn(next, Icon.NEXT) { onNext?.invoke() }
        addView(buttons, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(14) })
    }

    private fun field(label: String, v: TextView): View {
        val row = LinearLayout(context)
        row.orientation = HORIZONTAL
        row.setPadding(0, context.dp(3), 0, context.dp(3))
        val l = TextView(context)
        l.text = label
        l.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        l.setTextColor(pal.dim)
        row.addView(l, LayoutParams(context.dp(64), LayoutParams.WRAP_CONTENT))
        row.addView(v, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    fun showTrack(t: Track?) {
        val id = t?.id ?: -1L
        vYear.text = if (t == null) "-" else Util.dash(t.year)
        vArtist.text = if (t == null) "-" else Util.dash(t.artist)
        vAlbum.text = if (t == null) "-" else Util.dash(t.album)
        vTitle.text = if (t == null) "-" else Util.dash(t.title)
        if (id == shownId) return
        shownId = id
        if (t == null) { cover.setImageBitmap(placeholder); return }
        ThumbLoader.load(cover, "big" + t.id, placeholder) {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(context, Repo.uriOf(t))
                r.embeddedPicture?.let { ThumbLoader.decode(it, 900) }
            } finally { r.release() }
        }
    }

    fun setPlaying(playing: Boolean) {
        play.icon = if (playing) Icon.PAUSE else Icon.PLAY
    }

    fun setProgress(pos: Int, dur: Int) {
        elapsed.text = Util.formatTime(pos.toLong())
        total.text = Util.formatTime(dur.toLong())
        seek.setFraction(if (dur > 0) pos.toFloat() / dur else 0f)
    }
}
