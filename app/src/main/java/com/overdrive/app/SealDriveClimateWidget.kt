package com.overdrive.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.widget.RemoteViews
import java.text.DateFormat
import java.util.Date
import kotlin.concurrent.thread

/** 1x1 button: tap to set the car's cabin temperature (default 21 C; change it in Settings & updates). */
class SealDriveClimateWidget : AppWidgetProvider() {

    companion object {
        private const val ACTION_SET = "com.overdrive.app.CLIMATE_SET"
        private fun prefs(c: Context) = c.getSharedPreferences("climate_widget", Context.MODE_PRIVATE)

        private fun fmt(t: Float) = if (t % 1f == 0f) "${t.toInt()}°" else "$t°"

        private fun render(ctx: Context, busy: Boolean): RemoteViews {
            val p = prefs(ctx)
            val (text, color) = when {
                busy -> "Sending…" to "#9FB3C8"
                p.getString("state", "") == "ok" -> (p.getString("status", "") ?: "") to "#2BD4A7"
                p.getString("state", "") == "fail" -> (p.getString("status", "") ?: "") to "#FF8A8A"
                else -> "Tap to set" to "#8FA6BD"
            }
            val tap = PendingIntent.getBroadcast(ctx, 2,
                Intent(ctx, SealDriveClimateWidget::class.java).setAction(ACTION_SET),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            return RemoteViews(ctx.packageName, R.layout.widget_climate).apply {
                setTextViewText(R.id.climate_val, fmt(Config.climateTemp(ctx)))
                setTextViewText(R.id.climate_status, text)
                setTextColor(R.id.climate_status, Color.parseColor(color))
                setInt(R.id.widget_root, "setBackgroundResource", when (Config.bg(ctx)) {
                    0 -> R.drawable.widget_bg_clear
                    1 -> R.drawable.widget_bg_glass
                    else -> R.drawable.widget_bg
                })
                setOnClickPendingIntent(R.id.widget_root, tap)
            }
        }

        /** Redraws every climate button (e.g. after the temperature or background setting changed). */
        fun redraw(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, SealDriveClimateWidget::class.java))
            if (ids.isNotEmpty()) mgr.updateAppWidget(ids, render(ctx, false))
        }

        private fun redrawBusy(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, SealDriveClimateWidget::class.java))
            if (ids.isNotEmpty()) mgr.updateAppWidget(ids, render(ctx, true))
        }
    }

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        mgr.updateAppWidget(ids, render(ctx, false))
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        if (intent.action != ACTION_SET) return
        val p = prefs(ctx)
        val now = System.currentTimeMillis()
        if (now - p.getLong("busyAt", 0L) < 10_000L) return           // ignore taps while a command is in flight
        p.edit().putLong("busyAt", now).apply()
        val pending = goAsync()
        redrawBusy(ctx)
        thread {
            try {
                val temp = Config.climateTemp(ctx)
                val r = CarCommand.setClimateTemp(ctx, temp.toDouble())
                val stamp = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())
                p.edit()
                    .putString("state", if (r.ok) "ok" else "fail")
                    .putString("status", if (r.ok) "Set ✓ $stamp" else r.message)
                    .putLong("busyAt", 0L)
                    .apply()
                redraw(ctx)
            } finally {
                pending.finish()
            }
        }
    }
}
