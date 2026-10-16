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
 * 1x1 climate toggle: shows the live cabin temperature and whether the AC is on or off; tap to switch it.
 * Turning on uses the temperature and run time from Settings & updates (default 21 C for 20 min).
 */
class SealDriveClimateWidget : AppWidgetProvider() {

    companion object {
        private const val ACTION_SET = "com.overdrive.app.CLIMATE_SET"
        private fun prefs(c: Context) = c.getSharedPreferences("climate_widget", Context.MODE_PRIVATE)

        private fun fmt(t: Float) = if (t % 1f == 0f) "${t.toInt()}°" else "$t°"

        private fun render(ctx: Context, busy: Boolean): RemoteViews {
            val p = prefs(ctx)
            val ac = p.getString("ac", "")            // "on" / "off" / "" (unknown)
            val (text, color) = when {
                busy -> "Sending…" to "#9FB3C8"
                p.getString("state", "") == "fail" -> (p.getString("status", "") ?: "") to "#FF8A8A"
                ac == "on" -> "AC ON · ${fmt(Config.climateTemp(ctx))}" to "#2BD4A7"
                ac == "off" -> "AC OFF" to "#8FA6BD"
                else -> "Tap to switch" to "#8FA6BD"
            }
            val tap = PendingIntent.getBroadcast(ctx, 2,
                Intent(ctx, SealDriveClimateWidget::class.java).setAction(ACTION_SET),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            return RemoteViews(ctx.packageName, R.layout.widget_climate).apply {
                setTextViewText(R.id.climate_val, p.getString("inside", null) ?: fmt(Config.climateTemp(ctx)))
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

        private fun push(ctx: Context, busy: Boolean) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, SealDriveClimateWidget::class.java))
            if (ids.isNotEmpty()) mgr.updateAppWidget(ids, render(ctx, busy))
        }

        /** Redraws every climate button (e.g. after the temperature or background setting changed). */
        fun redraw(ctx: Context) = push(ctx, false)

        /**
         * Overdrive reports the AC as off while the car itself is switched off, even when a remote climate run is
         * going, so a recent "on" command from this widget counts as on until its run time is up.
         */
        private fun effectiveAc(ctx: Context, reported: Boolean?): Boolean? {
            val p = prefs(ctx)
            val recentOn = p.getBoolean("cmdOn", false) &&
                System.currentTimeMillis() - p.getLong("cmdAt", 0L) < Config.climateMinutes(ctx) * 60_000L
            return if (reported == true || recentOn) true else reported
        }

        private fun store(ctx: Context, s: CarState.Snap?) {
            if (s == null) return
            prefs(ctx).edit()
                .putString("ac", when (effectiveAc(ctx, s.acOn)) { true -> "on"; false -> "off"; null -> "" })
                .putString("inside", s.insideC?.let { CarState.fmtTemp(it) })
                .putString("state", "")
                .apply()
        }

        /** Reads the live cabin temperature and AC state and redraws. Blocking: call from a background thread. */
        fun refresh(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            if (mgr.getAppWidgetIds(ComponentName(ctx, SealDriveClimateWidget::class.java)).isEmpty()) return
            store(ctx, CarState.read(ctx))
            push(ctx, false)
        }
    }

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        mgr.updateAppWidget(ids, render(ctx, false))
        SealDriveWidget.scheduleJob(ctx)
    }

    override fun onEnabled(ctx: Context) = SealDriveWidget.scheduleJob(ctx)

    override fun onDisabled(ctx: Context) {
        if (!SealDriveWidget.hasWidgets(ctx)) SealDriveWidget.cancelJob(ctx)
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        when (intent.action) {
            AppWidgetManager.ACTION_APPWIDGET_UPDATE, AppWidgetManager.ACTION_APPWIDGET_ENABLED -> {
                val pending = goAsync()
                thread { try { refresh(ctx) } finally { pending.finish() } }       // fill in the live temperature
            }
            ACTION_SET -> toggle(ctx)
        }
    }

    private fun toggle(ctx: Context) {
        val p = prefs(ctx)
        val now = System.currentTimeMillis()
        if (now - p.getLong("busyAt", 0L) < 10_000L) return           // ignore taps while a command is in flight
        p.edit().putLong("busyAt", now).apply()
        val pending = goAsync()
        push(ctx, true)
        thread {
            try {
                val before = CarState.read(ctx)
                val turnOn = effectiveAc(ctx, before?.acOn) != true     // on -> off; off or unknown -> on
                val r = if (turnOn) CarCommand.startClimate(ctx, Config.climateTemp(ctx).toDouble(), Config.climateMinutes(ctx))
                        else CarCommand.stopClimate(ctx)
                if (!r.ok) {
                    p.edit().putString("state", "fail").putString("status", r.message).putLong("busyAt", 0L).apply()
                } else {
                    // the car accepted it; remember that, then refresh the cabin temperature
                    p.edit().putBoolean("cmdOn", turnOn).putLong("cmdAt", System.currentTimeMillis())
                        .putLong("busyAt", 0L).putString("ac", if (turnOn) "on" else "off").putString("state", "").apply()
                    Thread.sleep(2000)
                    CarState.read(ctx)?.let { s ->
                        p.edit().putString("inside", s.insideC?.let { CarState.fmtTemp(it) }).apply()
                        if (!turnOn && s.acOn == true) p.edit().putString("ac", "on").apply()   // still running: say so
                    }
                }
                p.edit().putLong("busyAt", 0L).apply()
                push(ctx, false)
            } finally {
                pending.finish()
            }
        }
    }
}
