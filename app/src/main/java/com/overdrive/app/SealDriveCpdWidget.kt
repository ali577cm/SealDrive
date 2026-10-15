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

/** 1x1 button: tap to turn the car's Child Presence Detection OFF (sends one command, then confirms the new state). */
class SealDriveCpdWidget : AppWidgetProvider() {

    companion object {
        private const val ACTION_CPD_OFF = "com.overdrive.app.CPD_OFF"
        private fun prefs(c: Context) = c.getSharedPreferences("cpd_widget", Context.MODE_PRIVATE)

        private fun render(ctx: Context, busy: Boolean): RemoteViews {
            val p = prefs(ctx)
            val (text, color) = when {
                busy -> "Sending…" to "#9FB3C8"
                p.getString("state", "") == "ok" -> (p.getString("status", "") ?: "") to "#2BD4A7"
                p.getString("state", "") == "fail" -> (p.getString("status", "") ?: "") to "#FF8A8A"
                else -> "Tap to turn off" to "#8FA6BD"
            }
            val tap = PendingIntent.getBroadcast(ctx, 5,
                Intent(ctx, SealDriveCpdWidget::class.java).setAction(ACTION_CPD_OFF),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            return RemoteViews(ctx.packageName, R.layout.widget_cpd).apply {
                setTextViewText(R.id.cpd_status, text)
                setTextColor(R.id.cpd_status, Color.parseColor(color))
                setInt(R.id.widget_root, "setBackgroundResource", when (Config.bg(ctx)) {
                    0 -> R.drawable.widget_bg_clear
                    1 -> R.drawable.widget_bg_glass
                    else -> R.drawable.widget_bg
                })
                setOnClickPendingIntent(R.id.widget_root, tap)
            }
        }

        private fun push(ctx: Context, busy: Boolean) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, SealDriveCpdWidget::class.java))
            if (ids.isNotEmpty()) mgr.updateAppWidget(ids, render(ctx, busy))
        }

        /** Redraws every CPD button (e.g. after the background setting changed). */
        fun redraw(ctx: Context) = push(ctx, false)
    }

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        mgr.updateAppWidget(ids, render(ctx, false))
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        if (intent.action != ACTION_CPD_OFF) return
        val p = prefs(ctx)
        val now = System.currentTimeMillis()
        if (now - p.getLong("busyAt", 0L) < 10_000L) return           // ignore taps while a command is in flight
        p.edit().putLong("busyAt", now).apply()
        val pending = goAsync()
        push(ctx, true)
        thread {
            try {
                val r = CarCommand.setChildPresenceDetection(ctx, 2)
                val stamp = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())
                p.edit()
                    .putString("state", if (r.ok) "ok" else "fail")
                    .putString("status", if (r.ok) "${r.message} ✓ $stamp" else r.message)
                    .putLong("busyAt", 0L)
                    .apply()
                push(ctx, false)
            } finally {
                pending.finish()
            }
        }
    }
}
