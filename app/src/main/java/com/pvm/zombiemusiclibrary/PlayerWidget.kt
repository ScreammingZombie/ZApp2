package com.pvm.zombiemusiclibrary

import android.app.Activity
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.os.SystemClock
import android.widget.RemoteViews

/** Home-screen widget: cover (or the splash), "Banda - año - disco" on top, the song name and the four buttons at the bottom. */
class PlayerWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val pending = goAsync()
        Thread {
            try { update(context, null) } catch (e: Exception) { } finally { pending.finish() }
        }.start()
    }

    companion object {
        private fun service(ctx: Context, action: String, code: Int): PendingIntent =
            PendingIntent.getService(ctx, code, Intent(ctx, PlayerService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        private fun forWidget(b: Bitmap): Bitmap {
            val max = 640
            val r = minOf(1f, max.toFloat() / maxOf(b.width, b.height))
            val w = maxOf(1, (b.width * r).toInt())
            val h = maxOf(1, (b.height * r).toInt())
            val scaled = if (r < 1f) Bitmap.createScaledBitmap(b, w, h, true) else b
            return scaled.copy(Bitmap.Config.RGB_565, false) ?: scaled
        }

        private fun embeddedCover(ctx: Context, t: Track): Bitmap? {
            if (t.id < 0) return null
            return try {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(ctx, Repo.uriOf(t))
                    r.embeddedPicture?.let { ThumbLoader.decode(it, 640) }
                } finally { r.release() }
            } catch (e: Throwable) { null }
        }

        /** Redraws every widget with the current song (or the last one heard). */
        fun update(ctx: Context, svc: PlayerService?) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, PlayerWidget::class.java))
            if (ids.isEmpty()) return
            Repo.init(ctx)
            val live = svc?.current
            val t: Track? = live ?: Repo.lastTrack()
            val playing = svc?.playing == true

            var cover: Bitmap? = if (live != null) svc?.coverBitmap else if (t != null) embeddedCover(ctx, t) else null
            if (cover == null) cover = Assets.bitmap(ctx, "splash.png")           // no cover in the MP3: show the splash instead

            val rv = RemoteViews(ctx.packageName, R.layout.widget_player)
            if (cover != null) rv.setImageViewBitmap(R.id.w_cover, forWidget(cover))
            rv.setTextViewText(R.id.w_top, if (t != null) Util.dash(t.artist) + " - " + Util.dash(t.year) + " - " + Util.dash(t.album) else "Zombie Music Library")
            rv.setTextViewText(R.id.w_title, if (t != null) Util.dash(t.title) else "Tocá play para empezar")
            rv.setImageViewResource(R.id.w_play, if (playing) R.drawable.ic_w_pause else R.drawable.ic_w_play)
            rv.setOnClickPendingIntent(R.id.w_prev, service(ctx, PlayerService.ACT_PREV, 11))
            rv.setOnClickPendingIntent(R.id.w_play, service(ctx, PlayerService.ACT_PLAY_PAUSE, 12))
            rv.setOnClickPendingIntent(R.id.w_stop, service(ctx, PlayerService.ACT_STOP, 13))
            rv.setOnClickPendingIntent(R.id.w_next, service(ctx, PlayerService.ACT_NEXT, 14))
            val tap = PendingIntent.getActivity(ctx, 20, Intent(ctx, WidgetTapActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            rv.setOnClickPendingIntent(R.id.w_root, tap)
            mgr.updateAppWidget(ids, rv)
        }
    }
}

/**
 * Invisible screen behind the widget cover: the first tap does nothing, a second tap right after
 * opens the app straight on the song that is playing.
 */
class WidgetTapActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("zml", MODE_PRIVATE)
        val now = SystemClock.elapsedRealtime()
        val last = prefs.getLong("lastCoverTap", 0L)
        if (last != 0L && now - last < 800) {
            prefs.edit().putLong("lastCoverTap", 0L).apply()
            val i = Intent(this, MainActivity::class.java)
            i.putExtra("show_playing", true)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            startActivity(i)
        } else {
            prefs.edit().putLong("lastCoverTap", now).apply()
        }
        finish()
    }
}
