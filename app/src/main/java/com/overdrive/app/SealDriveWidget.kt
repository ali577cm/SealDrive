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

/**
 * 4x2 home-screen widget showing charge, lock, windows, sentry and average daily kWh.
 * Updates only when you tap the refresh icon - no background polling, no notifications, read-only requests.
 */
class SealDriveWidget : AppWidgetProvider() {

    companion object {
        private const val ACTION_REFRESH = "com.overdrive.app.WIDGET_REFRESH"
        private const val GOOD = "#2BD4A7"
        private const val WARN = "#FFC857"
        private const val BAD = "#FF8A8A"
        private const val DIM = "#9FB3C8"

        private val TEXT_IDS = intArrayOf(R.id.widget_charge, R.id.widget_lock, R.id.widget_windows, R.id.widget_sentry, R.id.widget_avg)

        private fun prefs(c: Context) = c.getSharedPreferences("widget", Context.MODE_PRIVATE)

        private fun save(c: Context, sub: String, lines: List<Pair<String, String>>) {
            val e = prefs(c).edit().putString("sub", sub)
            lines.forEachIndexed { i, (t, k) -> e.putString("t$i", t).putString("k$i", k) }
            e.apply()
        }

        private fun render(ctx: Context, checking: Boolean): RemoteViews {
            val p = prefs(ctx)
            val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val refresh = PendingIntent.getBroadcast(ctx, 1,
                Intent(ctx, SealDriveWidget::class.java).setAction(ACTION_REFRESH),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            return RemoteViews(ctx.packageName, R.layout.widget).apply {
                setTextViewText(R.id.widget_sub, if (checking) "Checking…" else p.getString("sub", "Tap ⟳ to check"))
                TEXT_IDS.forEachIndexed { i, id ->
                    setTextViewText(id, p.getString("t$i", "—"))
                    setTextColor(id, Color.parseColor(p.getString("k$i", DIM)))
                }
                setOnClickPendingIntent(R.id.widget_root, open)
                setOnClickPendingIntent(R.id.widget_refresh, refresh)
            }
        }

        private fun push(ctx: Context, checking: Boolean) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, SealDriveWidget::class.java))
            if (ids.isNotEmpty()) mgr.updateAppWidget(ids, render(ctx, checking))
        }

        private fun time() = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())

        private fun blank() = List(5) { "—" to DIM }

        private fun store(ctx: Context, r: CarStatus.Result) {
            when (r) {
                is CarStatus.Result.NoAddress -> save(ctx, "Set address in app", blank())
                is CarStatus.Result.SignIn -> save(ctx, "Open app to sign in", blank())
                is CarStatus.Result.Offline -> save(ctx, "Car offline · ${time()}", blank())
                is CarStatus.Result.NoData -> save(ctx, "No car data · ${time()}", blank())
                is CarStatus.Result.Ok -> {
                    val s = r.s
                    val charge = s.soc?.let {
                        val txt = "🔋 ${it.toInt()}%" + (s.rangeKm?.let { km -> " · ${km.toInt()} km" } ?: "")
                        txt to (if (it >= 30) GOOD else if (it >= 15) WARN else BAD)
                    } ?: ("🔋 —" to DIM)
                    val lock = when {
                        s.doorsOpen > 0 -> "🚪 ${s.doorsOpen} door${if (s.doorsOpen > 1) "s" else ""} open" to WARN
                        s.locked == 1 -> "🔒 Locked" to GOOD
                        s.locked == 2 -> "🔓 Unlocked" to BAD
                        else -> "🔒 Unknown" to DIM
                    }
                    val win = s.windowsOpen?.let {
                        if (it == 0) "🪟 Closed" to GOOD else "🪟 $it open" to WARN
                    } ?: ("🪟 Unknown" to DIM)
                    val sentry = when {
                        s.sentryOn == null -> "🛡 Sentry —" to DIM
                        s.sentryOn && s.sentryRecording -> "🛡 Sentry rec" to WARN
                        s.sentryOn -> "🛡 Sentry on" to GOOD
                        else -> "🛡 Sentry off" to WARN
                    }
                    val avg = s.avgKwhPerDay?.let { "⚡ %.1f kWh/day".format(it) to DIM } ?: ("⚡ —" to DIM)
                    save(ctx, "Updated ${time()}", listOf(charge, lock, win, sentry, avg))
                }
            }
        }
    }

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        mgr.updateAppWidget(ids, render(ctx, false))
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        if (intent.action != ACTION_REFRESH) return
        val pending = goAsync()
        push(ctx, true)
        thread {
            try {
                store(ctx, CarStatus.fetch(ctx))
                push(ctx, false)
            } finally {
                pending.finish()
            }
        }
    }
}
