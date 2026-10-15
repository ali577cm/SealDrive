package com.overdrive.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Monthly summary notification (charging cost, miles driven, kWh used for the previous calendar month).
 * A job runs every ~3 hours but only acts on the 1st of the month, when it sends last month's summary once. If the car
 * can't be reached (phone offline, Tailscale off, car asleep) it tries again at the next run that same day.
 * It never sends on any other day of the month.
 */
object MonthlyReport {
    private const val CHANNEL = "monthly"
    private const val NOTIF_ID = 4301
    private const val JOB_ID = 4202
    private fun prefs(c: Context) = c.getSharedPreferences("monthly", Context.MODE_PRIVATE)

    class Summary(
        val miles: Double, val kwhUsed: Double, val kwhCharged: Double,
        val costPence: Double, val trips: Int, val sessions: Int,
    )

    /** Local midnight at the start of the month [offsetMonths] away from the current one. */
    private fun monthStart(offsetMonths: Int): Long {
        val c = Calendar.getInstance()
        c.set(Calendar.DAY_OF_MONTH, 1)
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        c.add(Calendar.MONTH, offsetMonths)
        return c.timeInMillis
    }

    private fun monthKey(t: Long) = SimpleDateFormat("yyyy-MM", Locale.US).format(Date(t))

    /** Totals for [from, to) or null if the car could not be reached. */
    fun compute(ctx: Context, from: Long, to: Long): Summary? {
        val (base, cookie) = CarStatus.auth(ctx) ?: return null
        var km = 0.0
        var kwh = 0.0
        var trips = 0
        var off = 0
        while (off < 50_000) {
            val arr = CarStatus.getJson(base, "/api/trips?from=$from&to=$to&limit=500&offset=$off", cookie)
                ?.optJSONArray("trips") ?: return null
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val d = o.optDouble("distanceKm", 0.0)
                if (!d.isNaN() && d > 0) km += d
                kwh += CarStatus.tripEnergy(o)
                trips++
            }
            if (arr.length() < 500) break
            off += 500
        }
        val t = Config.tariff(ctx)
        var charged = 0.0
        var cost = 0.0
        var sessions = 0
        off = 0
        while (off < 20_000) {
            val arr = CarStatus.getJson(base, "/api/charging?from=$from&to=$to&limit=200&offset=$off", cookie)
                ?.optJSONArray("sessions") ?: return null
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optBoolean("inProgress", false)) continue
                val e = o.optDouble("energyAdded", Double.NaN)
                val start = o.optLong("startTime", 0L)
                val end = o.optLong("endTime", 0L)
                if (start > 0 && end > 0 && !e.isNaN() && e > 0) {
                    charged += e
                    cost += CarStatus.priceSession(start, end, e, t)
                    sessions++
                }
            }
            if (arr.length() < 200) break
            off += 200
        }
        return Summary(km * 0.621371, kwh, charged, cost, trips, sessions)
    }

    private fun notify(ctx: Context, monthLabel: String, s: Summary) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Monthly summary", NotificationManager.IMPORTANCE_DEFAULT))
        val cost = "£%.2f".format(s.costPence / 100)
        val eff = if (s.kwhUsed > 0 && s.miles > 0) " (%.1f mi/kWh)".format(s.miles / s.kwhUsed) else ""
        val big = "Charging cost: $cost (${"%.1f".format(s.kwhCharged)} kWh in ${s.sessions} sessions)\n" +
            "Driven: ${Math.round(s.miles)} mi in ${s.trips} trips\n" +
            "Energy used: ${"%.1f".format(s.kwhUsed)} kWh$eff"
        val open = PendingIntent.getActivity(ctx, 3, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_bolt)
            .setContentTitle("BYDrive - $monthLabel")
            .setContentText("$cost charging · ${Math.round(s.miles)} mi · ${"%.1f".format(s.kwhUsed)} kWh used")
            .setStyle(Notification.BigTextStyle().bigText(big))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIF_ID, n)
    }

    /**
     * Sends last month's summary if it is due. [test] forces a send now (for the launcher shortcut).
     * Returns false when it could not (yet) be done and should be retried.
     */
    fun checkAndSend(ctx: Context, test: Boolean = false): Boolean {
        if (!test && !Config.monthlyEnabled(ctx)) return true
        if (!test && Calendar.getInstance().get(Calendar.DAY_OF_MONTH) != 1) return true   // 1st of the month only
        val prev = monthStart(-1)
        val key = monthKey(prev)
        if (!test && prefs(ctx).getString("sent", "") == key) return true      // already sent for that month
        if (Tailscale.isTailscaleAddress(Config.url(ctx)) && !Tailscale.isConnected() && !test) return false
        val summary = compute(ctx, prev, monthStart(0)) ?: return false
        notify(ctx, SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Date(prev)) + " summary", summary)
        if (!test) prefs(ctx).edit().putString("sent", key).apply()
        return true
    }

    /** Schedules the ~3 hourly check (idempotent) - or cancels it when the feature is switched off. */
    fun schedule(ctx: Context) {
        val js = ctx.getSystemService(JobScheduler::class.java)
        if (!Config.monthlyEnabled(ctx)) { js.cancel(JOB_ID); return }
        if (js.getPendingJob(JOB_ID) != null) return
        js.schedule(JobInfo.Builder(JOB_ID, ComponentName(ctx, MonthlyJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(3 * 60 * 60 * 1000L)
            .setPersisted(true)
            .build())
    }
}
