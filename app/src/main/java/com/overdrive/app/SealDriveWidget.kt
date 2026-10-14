package com.overdrive.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.widget.RemoteViews
import java.net.HttpURLConnection
import java.net.URL
import java.text.DateFormat
import java.util.Date
import kotlin.concurrent.thread

/**
 * 4x2 home-screen widget: tap to open SealDrive; tap the refresh icon to check the car once.
 * No background polling and no notifications - it only checks when you ask.
 */
class SealDriveWidget : AppWidgetProvider() {

    companion object {
        private const val ACTION_REFRESH = "com.overdrive.app.WIDGET_REFRESH"
        private const val PREFS = "widget"

        private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        private fun reachable(target: String): Boolean = try {
            val c = URL(target).openConnection() as HttpURLConnection
            c.connectTimeout = 3000
            c.readTimeout = 3000
            val ok = c.responseCode > 0
            c.disconnect()
            ok
        } catch (_: Exception) { false }

        private fun views(ctx: Context, checking: Boolean): RemoteViews {
            val p = prefs(ctx)
            val (text, color) = when {
                checking -> "Checking…" to "#9FB3C8"
                p.getString("state", "") == "up" -> "Car online" to "#2BD4A7"
                p.getString("state", "") == "down" -> "Car offline" to "#FF8A8A"
                p.getString("state", "") == "none" -> "Set address in app" to "#FFC857"
                else -> "Tap ⟳ to check" to "#9FB3C8"
            }
            val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val refresh = PendingIntent.getBroadcast(ctx, 1,
                Intent(ctx, SealDriveWidget::class.java).setAction(ACTION_REFRESH),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            return RemoteViews(ctx.packageName, R.layout.widget).apply {
                setTextViewText(R.id.widget_status, text)
                setTextColor(R.id.widget_status, Color.parseColor(color))
                val t = p.getLong("time", 0L)
                setTextViewText(R.id.widget_time,
                    if (t == 0L || checking) "" else "Checked " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(t)))
                setOnClickPendingIntent(R.id.widget_root, open)
                setOnClickPendingIntent(R.id.widget_refresh, refresh)
            }
        }

        private fun push(ctx: Context, checking: Boolean) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, SealDriveWidget::class.java))
            if (ids.isNotEmpty()) mgr.updateAppWidget(ids, views(ctx, checking))
        }
    }

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        mgr.updateAppWidget(ids, views(ctx, false))
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        if (intent.action != ACTION_REFRESH) return
        val pending = goAsync()
        push(ctx, true)
        thread {
            try {
                val target = Config.url(ctx)
                val state = if (target == null) "none" else if (reachable(target)) "up" else "down"
                prefs(ctx).edit().putString("state", state).putLong("time", System.currentTimeMillis()).apply()
                push(ctx, false)
            } finally {
                pending.finish()
            }
        }
    }
}
