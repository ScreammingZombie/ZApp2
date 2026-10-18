package com.pvm.zombiemusiclibrary

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import java.util.concurrent.Executors

// ---------------------------------------------------------------------------------------------
//  Helpers
// ---------------------------------------------------------------------------------------------

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
fun Context.dpf(v: Float): Float = v * resources.displayMetrics.density
fun Context.spf(v: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

/** Colors of the program: bright green on dark, a darker green on light (like the PC version). */
class Palette(val dark: Boolean) {
    val back = if (dark) Color.rgb(24, 24, 24) else Color.rgb(246, 246, 242)
    val panel = if (dark) Color.rgb(40, 40, 40) else Color.rgb(232, 232, 226)
    val listBack = if (dark) Color.rgb(18, 18, 18) else Color.WHITE
    val fore = if (dark) Color.rgb(236, 236, 236) else Color.rgb(20, 20, 20)
    val dim = if (dark) Color.rgb(160, 160, 160) else Color.rgb(100, 100, 100)
    val border = if (dark) Color.rgb(70, 70, 70) else Color.rgb(190, 190, 184)
    val accent = if (dark) Color.rgb(120, 248, 0) else Color.rgb(38, 120, 0)
    val accentShadow = Color.rgb(40, 136, 56)
    val buttonFill = if (dark) Color.rgb(58, 58, 58) else Color.rgb(224, 224, 218)
    val greenFill = if (dark) Color.rgb(40, 110, 50) else Color.rgb(38, 120, 0)

    companion object {
        fun from(ctx: Context): Palette {
            val night = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            return Palette(night)
        }
    }
}

object Assets {
    private var zombie: Typeface? = null
    private val bitmaps = HashMap<String, Bitmap?>()

    fun zombieFont(ctx: Context): Typeface {
        zombie?.let { return it }
        val t = try { Typeface.createFromAsset(ctx.assets, "DarkZombie.ttf") } catch (e: Exception) { Typeface.DEFAULT_BOLD }
        zombie = t
        return t
    }

    fun bitmap(ctx: Context, name: String): Bitmap? {
        if (bitmaps.containsKey(name)) return bitmaps[name]
        val b = try { ctx.assets.open(name).use { BitmapFactory.decodeStream(it) } } catch (e: Exception) { null }
        bitmaps[name] = b
        return b
    }
}

// ---------------------------------------------------------------------------------------------
//  Little vector icons (so the app needs no icon library)
// ---------------------------------------------------------------------------------------------

enum class Icon { NONE, PLAY, PAUSE, STOP, NEXT, PREV, NOTE, GEAR }

object Icons {
    /** Draws the icon centered at (cx, cy) inside a box of [size] pixels. */
    fun draw(c: Canvas, icon: Icon, cx: Float, cy: Float, size: Float, p: Paint) {
        val h = size / 2f
        when (icon) {
            Icon.PLAY -> {
                val path = Path()
                path.moveTo(cx - h * 0.6f, cy - h)
                path.lineTo(cx + h, cy)
                path.lineTo(cx - h * 0.6f, cy + h)
                path.close()
                c.drawPath(path, p)
            }
            Icon.PAUSE -> {
                val w = size * 0.28f
                c.drawRect(cx - h * 0.75f, cy - h, cx - h * 0.75f + w, cy + h, p)
                c.drawRect(cx + h * 0.75f - w, cy - h, cx + h * 0.75f, cy + h, p)
            }
            Icon.STOP -> c.drawRect(cx - h * 0.85f, cy - h * 0.85f, cx + h * 0.85f, cy + h * 0.85f, p)
            Icon.NEXT -> {
                val path = Path()
                path.moveTo(cx - h, cy - h)
                path.lineTo(cx + h * 0.55f, cy)
                path.lineTo(cx - h, cy + h)
                path.close()
                c.drawPath(path, p)
                c.drawRect(cx + h * 0.7f, cy - h, cx + h, cy + h, p)
            }
            Icon.PREV -> {
                val path = Path()
                path.moveTo(cx + h, cy - h)
                path.lineTo(cx - h * 0.55f, cy)
                path.lineTo(cx + h, cy + h)
                path.close()
                c.drawPath(path, p)
                c.drawRect(cx - h, cy - h, cx - h * 0.7f, cy + h, p)
            }
            Icon.NOTE -> {
                c.drawRect(cx + h * 0.15f, cy - h, cx + h * 0.15f + size * 0.14f, cy + h * 0.45f, p)
                c.drawOval(RectF(cx - h * 0.85f, cy + h * 0.1f, cx + h * 0.35f, cy + h * 0.9f), p)
                val flag = Path()
                flag.moveTo(cx + h * 0.15f, cy - h)
                flag.lineTo(cx + h * 0.95f, cy - h * 0.45f)
                flag.lineTo(cx + h * 0.95f, cy - h * 0.05f)
                flag.lineTo(cx + h * 0.15f, cy - h * 0.5f)
                flag.close()
                c.drawPath(flag, p)
            }
            Icon.GEAR -> {
                // teeth + body, with a hole in the middle
                val path = Path()
                path.fillType = Path.FillType.EVEN_ODD
                val outer = h
                val inner = h * 0.74f
                for (i in 0 until 8) {
                    val a = Math.toRadians((i * 45).toDouble())
                    fun pt(delta: Double, r: Float) = floatArrayOf(
                        (cx + r * Math.cos(a + Math.toRadians(delta))).toFloat(), (cy + r * Math.sin(a + Math.toRadians(delta))).toFloat())
                    val p1 = pt(-16.0, inner); val p2 = pt(-9.0, outer); val p3 = pt(9.0, outer); val p4 = pt(16.0, inner)
                    if (i == 0) path.moveTo(p1[0], p1[1]) else path.lineTo(p1[0], p1[1])
                    path.lineTo(p2[0], p2[1]); path.lineTo(p3[0], p3[1]); path.lineTo(p4[0], p4[1])
                }
                path.close()
                path.addCircle(cx, cy, h * 0.34f, Path.Direction.CW)
                c.drawPath(path, p)
            }
            Icon.NONE -> {}
        }
    }

    /** White silhouette used by the notification. */
    fun bitmap(icon: Icon, px: Int): Bitmap {
        val b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = Color.WHITE
        draw(Canvas(b), icon, px / 2f, px / 2f, px * 0.62f, p)
        return b
    }
}

// ---------------------------------------------------------------------------------------------
//  Rounded button (same look as the PC version)
// ---------------------------------------------------------------------------------------------

class ZButton(ctx: Context, private val pal: Palette) : View(ctx) {
    var text: String = ""
        set(v) { field = v; invalidate() }
    var icon: Icon = Icon.NONE
        set(v) { field = v; invalidate() }
    var accent: Boolean = false
        set(v) { field = v; invalidate() }
    var active: Boolean = false
        set(v) { field = v; invalidate() }
    var textSizeSp: Float = 14f
        set(v) { field = v; invalidate() }
    var bold: Boolean = false
        set(v) { field = v; invalidate() }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        isClickable = true
        isFocusable = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(resolveSize(context.dp(96), widthMeasureSpec), resolveSize(context.dp(44), heightMeasureSpec))
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val green = accent || active
        val fillColor: Int
        val strokeColor: Int
        val fg: Int
        if (!isEnabled) {
            fillColor = if (pal.dark) Color.rgb(44, 44, 44) else Color.rgb(236, 236, 232)
            strokeColor = if (pal.dark) Color.rgb(60, 60, 60) else Color.rgb(214, 214, 210)
            fg = pal.dim
        } else if (green) {
            fillColor = if (isPressed) darker(pal.greenFill) else pal.greenFill
            strokeColor = pal.accentShadow
            fg = Color.WHITE
        } else {
            fillColor = if (isPressed) (if (pal.dark) pal.greenFill else Color.rgb(200, 200, 194)) else pal.buttonFill
            strokeColor = pal.border
            fg = pal.fore
        }
        fill.color = fillColor
        stroke.color = strokeColor
        stroke.strokeWidth = context.dpf(1f)
        val r = minOf(height / 3f, context.dpf(14f))
        val rect = RectF(1f, 1f, width - 1f, height - 1f)
        canvas.drawRoundRect(rect, r, r, fill)
        canvas.drawRoundRect(rect, r, r, stroke)

        textPaint.color = fg
        textPaint.textSize = context.spf(textSizeSp)
        textPaint.typeface = if (bold || active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        iconPaint.color = fg
        val cx = width / 2f
        val hasText = text.isNotEmpty()
        if (icon != Icon.NONE && hasText) {
            val isize = height * 0.34f
            Icons.draw(canvas, icon, cx, height * 0.36f, isize, iconPaint)
            canvas.drawText(fit(text, width - context.dpf(8f)), cx, height * 0.82f - textPaint.descent() / 2, textPaint)
        } else if (icon != Icon.NONE) {
            Icons.draw(canvas, icon, cx, height / 2f, minOf(width, height) * 0.4f, iconPaint)
        } else if (hasText) {
            val y = height / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(fit(text, width - context.dpf(10f)), cx, y, textPaint)
        }
    }

    private fun fit(s: String, maxW: Float): String {
        if (textPaint.measureText(s) <= maxW) return s
        var n = s.length
        while (n > 1 && textPaint.measureText(s, 0, n) + textPaint.measureText("…") > maxW) n--
        return s.substring(0, n) + "…"
    }

    private fun darker(c: Int): Int = Color.rgb((Color.red(c) * 0.8f).toInt(), (Color.green(c) * 0.8f).toInt(), (Color.blue(c) * 0.8f).toInt())
}

// ---------------------------------------------------------------------------------------------
//  Progress bar with the zombie head as the thumb
// ---------------------------------------------------------------------------------------------

class SeekBarView(ctx: Context, private val pal: Palette) : View(ctx) {
    var onSeek: ((Float) -> Unit)? = null
    private var fractionValue = 0f
    private var dragging = false
    private val face: Bitmap? = Assets.bitmap(ctx, "thumb_zombie.png")
    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    fun setFraction(f: Float) {
        if (dragging) return
        fractionValue = f.coerceIn(0f, 1f)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(resolveSize(context.dp(200), widthMeasureSpec), resolveSize(context.dp(40), heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val th = minOf(height.toFloat(), context.dpf(34f))                    // thumb = a small square with the face
        val barH = context.dpf(8f)
        val left = th / 2f
        val usable = (width - th).coerceAtLeast(1f)
        val top = (height - barH) / 2f
        track.color = if (pal.dark) Color.rgb(62, 62, 62) else Color.rgb(205, 205, 200)
        canvas.drawRoundRect(RectF(left, top, left + usable, top + barH), barH / 2, barH / 2, track)
        track.color = pal.accent
        val w = usable * fractionValue
        if (w > 0f) canvas.drawRoundRect(RectF(left, top, left + w, top + barH), barH / 2, barH / 2, track)
        val x = w
        val y = (height - th) / 2f
        if (face != null) canvas.drawBitmap(face, null, RectF(x, y, x + th, y + th), bmpPaint)
        else { track.color = pal.accent; canvas.drawRect(x, y, x + th, y + th, track) }
    }

    private fun update(ev: MotionEvent) {
        val th = minOf(height.toFloat(), context.dpf(34f))
        fractionValue = ((ev.x - th / 2f) / (width - th).coerceAtLeast(1f)).coerceIn(0f, 1f)
        invalidate()
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { dragging = true; parent?.requestDisallowInterceptTouchEvent(true); update(ev) }
            MotionEvent.ACTION_MOVE -> if (dragging) update(ev)
            MotionEvent.ACTION_UP -> { if (dragging) { dragging = false; update(ev); onSeek?.invoke(fractionValue) } }
            MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return true
    }
}

// ---------------------------------------------------------------------------------------------
//  Banner: splash miniatures at both edges, title in the zombie font, "by P.V.M." under it
// ---------------------------------------------------------------------------------------------

class BannerView(ctx: Context, private val pal: Palette) : View(ctx) {
    private val splash: Bitmap? = Assets.bitmap(ctx, "splash.png")
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val byPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val line = Paint()
    private val title = "Zombie Music Library"
    private val by = "by P.V.M."

    init {
        titlePaint.typeface = Assets.zombieFont(ctx)
        shadowPaint.typeface = Assets.zombieFont(ctx)
        titlePaint.color = pal.accent
        shadowPaint.color = pal.accentShadow
        byPaint.color = pal.dim
        byPaint.textAlign = Paint.Align.CENTER
        line.color = pal.border
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(resolveSize(context.dp(300), widthMeasureSpec), context.dp(58))
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(pal.back)
        val side = height - context.dp(12)
        if (splash != null) {
            val y = (height - side) / 2f
            canvas.drawBitmap(splash, null, RectF(context.dpf(6f), y, context.dpf(6f) + side, y + side), bmpPaint)
            canvas.drawBitmap(splash, null, RectF(width - context.dpf(6f) - side, y, width - context.dpf(6f), y + side), bmpPaint)
        }
        // title: shrink until it fits between the two logos
        val avail = width - 2 * (side + context.dpf(12f))
        var size = context.spf(28f)
        titlePaint.textSize = size
        while (titlePaint.measureText(title) > avail && size > context.spf(12f)) {
            size -= context.spf(1f)
            titlePaint.textSize = size
        }
        shadowPaint.textSize = size
        byPaint.textSize = context.spf(9f)
        val bounds = Rect()
        titlePaint.getTextBounds(title, 0, title.length, bounds)           // the real ink of the letters (drips included)
        val byH = byPaint.descent() - byPaint.ascent()
        val gap = context.dpf(1f)
        val group = bounds.height() + gap + byH * 0.8f
        val top = (height - group) / 2f
        val baseline = top - bounds.top
        val x = (width - titlePaint.measureText(title)) / 2f
        if (pal.dark) canvas.drawText(title, x + context.dpf(1.5f), baseline + context.dpf(1.5f), shadowPaint)
        canvas.drawText(title, x, baseline, titlePaint)
        canvas.drawText(by, width / 2f, top + bounds.height() + gap - byPaint.ascent() * 0.85f, byPaint)
        canvas.drawRect(0f, height - 1f, width.toFloat(), height.toFloat(), line)
    }
}

// ---------------------------------------------------------------------------------------------
//  Thumbnails loaded in the background
// ---------------------------------------------------------------------------------------------

object ThumbLoader {
    private val cache = object : LruCache<String, Bitmap>(400) {}
    private val pool = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())

    fun load(view: ImageView, key: String, placeholder: Bitmap?, producer: () -> Bitmap?) {
        view.tag = key
        val hit = cache.get(key)
        if (hit != null) { view.setImageBitmap(hit); return }
        view.setImageBitmap(placeholder)
        pool.execute {
            val b = try { producer() } catch (e: Throwable) { null }
            if (b != null) cache.put(key, b)
            main.post { if (view.tag == key) view.setImageBitmap(b ?: placeholder) }
        }
    }

    fun decode(bytes: ByteArray, maxSide: Int): Bitmap? {
        val o = BitmapFactory.Options()
        o.inJustDecodeBounds = true
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        var sample = 1
        while (o.outWidth / sample > maxSide * 2 || o.outHeight / sample > maxSide * 2) sample *= 2
        val o2 = BitmapFactory.Options()
        o2.inSampleSize = sample
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o2)
    }
}
