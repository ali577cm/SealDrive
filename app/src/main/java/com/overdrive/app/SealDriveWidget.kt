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
open class SealDriveWidget : AppWidgetProvider() {

    /** True for the compact 4x1 strip. */
    protected open val compact: Boolean = false

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

        private fun save(c: Context, sub: String, soc: Int, range: String, unlocked: Boolean, chips: List<Chip>, cap: String = "Avg / day", capS: String = "no charge", rangeS: String = "", stripEnergy: String = "—") {
            val e = prefs(c).edit().putString("sub", sub).putInt("soc", soc).putString("range", range)
                .putBoolean("unlocked", unlocked).putString("cap", cap).putString("capS", capS).putString("rangeS", rangeS).putString("vS3", stripEnergy)
            chips.forEachIndexed { i, ch -> e.putString("v$i", ch.text).putString("k$i", ch.color) }
            e.apply()
        }

        private fun render(ctx: Context, checking: Boolean, compact: Boolean): RemoteViews {
            val p = prefs(ctx)
            val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val refresh = PendingIntent.getBroadcast(ctx, 1,
                Intent(ctx, SealDriveWidget::class.java).setAction(ACTION_REFRESH),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val soc = p.getInt("soc", -1)
            return RemoteViews(ctx.packageName, if (compact) R.layout.widget_small else R.layout.widget).apply {
                if (!compact) setTextViewText(R.id.widget_sub, if (checking) "Checking…" else p.getString("sub", "Tap ⟳ to check"))
                setTextViewText(R.id.widget_soc, if (soc >= 0) "$soc" else "--")
                setTextColor(R.id.widget_soc, Color.parseColor(if (soc in 0..14) BAD else if (soc in 15..29) WARN else "#FFFFFF"))
                setInt(R.id.widget_root, "setBackgroundResource", when (Config.bg(ctx)) {
                    0 -> R.drawable.widget_bg_clear
                    1 -> R.drawable.widget_bg_glass
                    else -> R.drawable.widget_bg
                })
                setTextViewText(R.id.widget_range, if (compact) p.getString("rangeS", "") else p.getString("range", ""))
                setTextViewText(R.id.chip_avg_cap, if (compact) p.getString("capS", "no charge") else p.getString("cap", "Avg / day"))
                if (!compact) {
                    setProgressBar(R.id.widget_bar, 100, soc.coerceAtLeast(0), false)
                }
                setImageViewResource(R.id.chip_lock_icon, if (p.getBoolean("unlocked", false)) R.drawable.ic_lock_open else R.drawable.ic_lock)
                for (i in VAL_IDS.indices) {
                    val color = Color.parseColor(p.getString("k$i", DIM))
                    setTextViewText(VAL_IDS[i], if (compact && i == 3) p.getString("vS3", "—") else p.getString("v$i", "—"))
                    setTextColor(VAL_IDS[i], if (compact && i == 3) Color.parseColor(PLAIN) else color)
                    setInt(ICON_IDS[i], "setColorFilter", color)
                }
                setOnClickPendingIntent(R.id.widget_root, open)
                setOnClickPendingIntent(R.id.widget_refresh, refresh)
            }
        }

        private fun push(ctx: Context, checking: Boolean) {
            val mgr = AppWidgetManager.getInstance(ctx)
            for ((cls, small) in listOf(SealDriveWidget::class.java to false, SealDriveWidgetSmall::class.java to true)) {
                val ids = mgr.getAppWidgetIds(ComponentName(ctx, cls))
                if (ids.isNotEmpty()) mgr.updateAppWidget(ids, render(ctx, checking, small))
            }
        }

        private const val JOB_ID = 4201

        /** True when any BYDrive widget (including the climate and pet buttons) is on the home screen. */
        fun hasWidgets(ctx: Context): Boolean {
            val mgr = AppWidgetManager.getInstance(ctx)
            return hasMainWidgets(ctx) ||
                mgr.getAppWidgetIds(ComponentName(ctx, SealDriveClimateWidget::class.java)).isNotEmpty()
        }

        fun hasMainWidgets(ctx: Context): Boolean {
            val mgr = AppWidgetManager.getInstance(ctx)
            return mgr.getAppWidgetIds(ComponentName(ctx, SealDriveWidget::class.java)).isNotEmpty() ||
                mgr.getAppWidgetIds(ComponentName(ctx, SealDriveWidgetSmall::class.java)).isNotEmpty()
        }

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

        /** Minutes -> "1h 20m" / "45m". */
        private fun eta(min: Int?): String? = min?.let { if (it >= 60) "${it / 60}h ${it % 60}m" else "${it}m" }

        /** Minutes -> "1h20m" / "45m" (no space, for the narrow strip). */
        private fun etaTight(min: Int?): String? = min?.let { if (it >= 60) "${it / 60}h${it % 60}m" else "${it}m" }

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
                    // both widgets: kWh used today, and the cost of the previous charge (low/high-rate minutes)
                    val avg = s.todayKwh?.let { Chip("%.1f kWh".format(it), PLAIN) } ?: Chip("—", DIM)
                    val cap = s.lastChargeCostPence?.let { "Charge ${money(it)}" } ?: "no charge"
                    // 4x1 strip: kWh used today, and the cost of the previous charge (low/high-rate minutes)
                    val capS = s.lastChargeCostPence?.let { "Charge ${money(it)}" } ?: "no charge"
                    val stripEnergy = s.todayKwh?.let { "%.1f kWh".format(it) } ?: "—"
                    val miles = s.rangeKm?.let { "${Math.round(it * 0.621371)} mi" }
                    val charge = if (s.charging) "⚡ " + (eta(s.chargeEtaMin) ?: "Charging") else null
                    val rangeFull = if (charge != null) listOfNotNull(miles, charge).joinToString(" · ") else (miles?.let { "$it range" } ?: "")
                    // session cost = energy added so far x your price per kWh
                    val sessCost = s.sessionCostPence?.let { money(it) }
                    val sub = if (s.charging && sessCost != null) "Session $sessCost · ${"%.1f".format(s.chargeSessionKwh)} kWh"
                        else "Updated ${time()}"
                    val stripCharge = if (s.charging) "⚡ " + (etaTight(s.chargeEtaMin) ?: "Charging") + (sessCost?.let { " · $it" } ?: "") else null
                    save(ctx, sub, s.soc?.toInt() ?: -1, rangeFull,
                        s.locked == 2 && s.doorsOpen == 0, listOf(lock, win, sentry, avg), cap, capS, stripCharge ?: (miles ?: ""), stripEnergy)
                }
            }
        }
    }

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        mgr.updateAppWidget(ids, render(ctx, false, compact))
        scheduleJob(ctx)
    }

    override fun onEnabled(ctx: Context) = scheduleJob(ctx)

    override fun onDisabled(ctx: Context) {
        if (!hasWidgets(ctx)) cancelJob(ctx)   // keep refreshing while the other widget size is still on the home screen
    }

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
