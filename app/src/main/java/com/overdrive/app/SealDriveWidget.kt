package com.overdrive.app

import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
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
        private const val DIM = "#8FA6BD"
        private const val PLAIN = "#E6EEF7"

        private val VAL_IDS = intArrayOf(R.id.chip_lock_val, R.id.chip_win_val, R.id.chip_sen_val, R.id.chip_avg_val)
        private val ICON_IDS = intArrayOf(R.id.chip_lock_icon, R.id.chip_win_icon, R.id.chip_sen_icon, R.id.chip_avg_icon)

        private fun prefs(c: Context) = c.getSharedPreferences("widget", Context.MODE_PRIVATE)

        private class Chip(val text: String, val color: String)

        private fun save(c: Context, sub: String, soc: Int, range: String, unlocked: Boolean, chips: List<Chip>, cap: String = "Avg / day") {
            val e = prefs(c).edit().putString("sub", sub).putInt("soc", soc).putString("range", range)
                .putBoolean("unlocked", unlocked).putString("cap", cap)
            chips.forEachIndexed { i, ch -> e.putString("v$i", ch.text).putString("k$i", ch.color) }
            e.apply()
        }

        private fun render(ctx: Context, checking: Boolean): RemoteViews {
            val p = prefs(ctx)
            val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val refresh = PendingIntent.getBroadcast(ctx, 1,
                Intent(ctx, SealDriveWidget::class.java).setAction(ACTION_REFRESH),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val soc = p.getInt("soc", -1)
            return RemoteViews(ctx.packageName, R.layout.widget).apply {
                setTextViewText(R.id.widget_sub, if (checking) "Checking…" else p.getString("sub", "Tap ⟳ to check"))
                setTextViewText(R.id.widget_soc, if (soc >= 0) "$soc" else "--")
                setTextColor(R.id.widget_soc, Color.parseColor(if (soc in 0..14) BAD else if (soc in 15..29) WARN else "#FFFFFF"))
                setInt(R.id.widget_root, "setBackgroundResource", when (Config.bg(ctx)) {
                    0 -> R.drawable.widget_bg_clear
                    1 -> R.drawable.widget_bg_glass
                    else -> R.drawable.widget_bg
                })
                setTextViewText(R.id.widget_range, p.getString("range", ""))
                setTextViewText(R.id.chip_avg_cap, p.getString("cap", "Avg / day"))
                setProgressBar(R.id.widget_bar, 100, soc.coerceAtLeast(0), false)
                setImageViewResource(R.id.chip_lock_icon, if (p.getBoolean("unlocked", false)) R.drawable.ic_lock_open else R.drawable.ic_lock)
                for (i in VAL_IDS.indices) {
                    val color = Color.parseColor(p.getString("k$i", DIM))
                    setTextViewText(VAL_IDS[i], p.getString("v$i", "—"))
                    setTextColor(VAL_IDS[i], color)
                    setInt(ICON_IDS[i], "setColorFilter", color)
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

        private const val JOB_ID = 4201

        fun hasWidgets(ctx: Context): Boolean =
            AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, SealDriveWidget::class.java)).isNotEmpty()

        /** Fetches fresh data and redraws every widget. Blocking: call from a background thread. */
        fun refreshNow(ctx: Context) {
            store(ctx, CarStatus.fetch(ctx))
            prefs(ctx).edit().putLong("fetched", System.currentTimeMillis()).apply()
            push(ctx, false)
        }

        /** Schedules the ~15 minute background refresh (idempotent). */
        fun scheduleJob(ctx: Context) {
            val js = ctx.getSystemService(JobScheduler::class.java)
            if (js.getPendingJob(JOB_ID) != null) return
            js.schedule(JobInfo.Builder(JOB_ID, ComponentName(ctx, RefreshJobService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(15 * 60 * 1000L)
                .setPersisted(true)
                .build())
        }

        fun cancelJob(ctx: Context) = ctx.getSystemService(JobScheduler::class.java).cancel(JOB_ID)

        private fun time() = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())

        /** Pence -> "88p" or "£1.05". */
        private fun money(pence: Double) = if (pence >= 100) "£%.2f".format(pence / 100) else "${Math.round(pence)}p"

        private fun blank() = List(4) { Chip("—", DIM) }

        private fun store(ctx: Context, r: CarStatus.Result) {
            when (r) {
                is CarStatus.Result.NoAddress -> save(ctx, "Set address in app", -1, "", false, blank())
                is CarStatus.Result.SignIn -> save(ctx, "Sign in via app (${r.why})", -1, "", false, blank())
                is CarStatus.Result.Offline -> save(ctx, "Offline: ${r.why}", -1, "", false, blank())
                is CarStatus.Result.NoData -> save(ctx, "No data: ${r.why}", -1, "", false, blank())
                is CarStatus.Result.Ok -> {
                    val s = r.s
                    val lock = when {
                        s.doorsOpen > 0 -> Chip("${s.doorsOpen} open", WARN)
                        s.locked == 1 -> Chip("Locked", GOOD)
                        s.locked == 2 -> Chip("Unlocked", BAD)
                        else -> Chip("Unknown", DIM)
                    }
                    val win = s.windowsOpen?.let { if (it == 0) Chip("Closed", GOOD) else Chip("$it open", WARN) }
                        ?: Chip("Unknown", DIM)
                    val sentry = when {
                        s.sentryOn == null -> Chip("—", DIM)
                        s.sentryOn && s.sentryRecording -> Chip("Recording", GOOD)
                        s.sentryOn -> Chip("On", GOOD)
                        else -> Chip("Off", WARN)
                    }
                    val avg = s.avgKwhPerDay?.let { Chip("%.1f kWh".format(it), PLAIN) } ?: Chip("—", DIM)
                    val cap = s.avgKwhPerDay?.let { "Avg ${money(it * Config.pence(ctx))}/day" } ?: "Avg / day"
                    save(ctx, "Updated ${time()}", s.soc?.toInt() ?: -1,
                        s.rangeKm?.let { "${it.toInt()} km range" } ?: "",
                        s.locked == 2 && s.doorsOpen == 0, listOf(lock, win, sentry, avg), cap)
                }
            }
        }
    }

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        mgr.updateAppWidget(ids, render(ctx, false))
        scheduleJob(ctx)
    }

    override fun onEnabled(ctx: Context) = scheduleJob(ctx)

    override fun onDisabled(ctx: Context) = cancelJob(ctx)

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        when (intent.action) {
            ACTION_REFRESH -> {
                val pending = goAsync()
                push(ctx, true)
                thread {
                    try { refreshNow(ctx) } finally { pending.finish() }
                }
            }
            AppWidgetManager.ACTION_APPWIDGET_UPDATE, AppWidgetManager.ACTION_APPWIDGET_ENABLED -> {
                // widget just added / redrawn: fetch if the data is more than 2 minutes old
                if (System.currentTimeMillis() - prefs(ctx).getLong("fetched", 0L) > 2 * 60_000L) {
                    val pending = goAsync()
                    thread {
                        try { refreshNow(ctx) } finally { pending.finish() }
                    }
                }
            }
        }
    }
}
