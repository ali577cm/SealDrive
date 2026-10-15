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
    ) {
        operator fun plus(o: Summary) = Summary(miles + o.miles, kwhUsed + o.kwhUsed, kwhCharged + o.kwhCharged,
            costPence + o.costPence, trips + o.trips, sessions + o.sessions)
    }

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

    /**
     * Totals for the half-open period [from, to) in local time, or null if the car could not be reached
     * (or a charge that began in the period is still running - try again later).
     * Trips count in the month they START. Charging sessions are split at the month boundaries: each month gets
     * only its share of the energy (spread evenly over the session), priced at the rates that applied then.
     */
    fun compute(ctx: Context, from: Long, to: Long, live: Boolean = false): Summary? {
        val (base, cookie) = CarStatus.auth(ctx) ?: return null
        var km = 0.0
        var kwh = 0.0
        var trips = 0
        var off = 0
        while (off < 50_000) {
            // Overdrive's range is inclusive at both ends, so stop 1 ms before the next month starts
            val arr = CarStatus.getJson(base, "/api/trips?from=$from&to=${to - 1}&limit=500&offset=$off", cookie)
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
        val counted = ArrayList<LongArray>()      // Overdrive sessions already counted (start, end)
        off = 0
        // also fetch sessions that began up to 3 days earlier, so one that crossed into this period is included
        val sessFrom = from - 3 * 86_400_000L
        while (off < 20_000) {
            val arr = CarStatus.getJson(base, "/api/charging?from=$sessFrom&to=${to - 1}&limit=200&offset=$off", cookie)
                ?.optJSONArray("sessions") ?: return null
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val start = o.optLong("startTime", 0L)
                val inProg = o.optBoolean("inProgress", false)
                if (inProg && !live) {
                    if (start in 1 until to) return null            // still charging from last month: wait and retry
                    continue
                }
                // month-to-date (live): a session still running counts up to now
                val end = if (inProg) System.currentTimeMillis() else o.optLong("endTime", 0L)
                val e = o.optDouble("energyAdded", Double.NaN)
                if (start <= 0 || end <= 0 || e.isNaN() || e <= 0) continue
                if (end > start) counted.add(longArrayOf(start, end))
                if (end > start) {
                    val os = maxOf(start, from)
                    val oe = minOf(end, to)
                    if (oe <= os) continue                            // no overlap with this month
                    val part = e * (oe - os).toDouble() / (end - start)
                    charged += part
                    cost += CarStatus.priceSession(os, oe, part, t)
                    sessions++
                } else if (start in from until to) {                  // zero-length session: count it where it began
                    charged += e
                    cost += CarStatus.priceSession(start, start, e, t)
                    sessions++
                }
            }
            if (arr.length() < 200) break
            off += 200
        }
        // charges added by hand from the charger app (energy at the meter, so no charger-loss gross-up);
        // skipped when Overdrive already recorded that charge
        val t0 = Config.Tariff(t.stdPence, t.cheapPence, t.startMin, t.endMin, 100.0)
        for (m in Config.manualSessions(ctx)) {
            val dur = m.end - m.start
            if (dur <= 0 || m.kwh <= 0) continue
            if (counted.any { r -> maxOf(0L, minOf(r[1], m.end) - maxOf(r[0], m.start)) > dur / 2 }) continue
            val os = maxOf(m.start, from)
            val oe = minOf(m.end, to)
            if (oe <= os) continue
            val part = m.kwh * (oe - os).toDouble() / dur
            charged += part
            cost += CarStatus.priceSession(os, oe, part, t0)
            sessions++
        }
        return Summary(km * 0.621371, kwh, charged, cost, trips, sessions)
    }

    /** One line of the Charges list. [kwh] is null when Overdrive recorded the session but no energy for it. */
    class ChargeRow(val id: Long, val start: Long, val end: Long, val inProgress: Boolean, val kwh: Double?,
                    val avgKw: Double?, val socStart: Double?, val socEnd: Double?, val costPence: Double?, val manual: Boolean)

    /** Sessions from the last [days] days: Overdrive's own records plus your manual additions, newest first. */
    fun chargeRows(ctx: Context, days: Int): List<ChargeRow>? {
        val (base, cookie) = CarStatus.auth(ctx) ?: return null
        val arr = CarStatus.getJson(base, "/api/charging?days=$days&limit=200&offset=0", cookie)
            ?.optJSONArray("sessions") ?: return null
        val t = Config.tariff(ctx)
        val rows = ArrayList<ChargeRow>()
        val counted = ArrayList<LongArray>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val start = o.optLong("startTime", 0L)
            val inProg = o.optBoolean("inProgress", false)
            val end = if (inProg) System.currentTimeMillis() else o.optLong("endTime", 0L)
            val e = o.optDouble("energyAdded", Double.NaN).takeIf { !it.isNaN() && it > 0 }
            val kw = o.optDouble("avgPower", Double.NaN).takeIf { !it.isNaN() && it > 0 }
            val s0 = o.optDouble("startSoc", Double.NaN).takeIf { !it.isNaN() }
            val s1 = o.optDouble("endSoc", Double.NaN).takeIf { !it.isNaN() }
            val cost = if (e != null && start > 0 && end > 0) CarStatus.priceSession(start, end, e, t) else null
            if (e != null && end > start) counted.add(longArrayOf(start, end))
            rows.add(ChargeRow(o.optLong("id", 0L), start, end, inProg, e, kw, s0, s1, cost, false))
        }
        val t0 = Config.Tariff(t.stdPence, t.cheapPence, t.startMin, t.endMin, 100.0)
        val since = System.currentTimeMillis() - days * 86_400_000L
        for (m in Config.manualSessions(ctx)) {
            if (m.end < since) continue
            rows.add(ChargeRow(m.id, m.start, m.end, false, m.kwh, null, null, null,
                CarStatus.priceSession(m.start, m.end, m.kwh, t0), true))
        }
        return rows.sortedByDescending { it.start }
    }

    private fun notify(ctx: Context, monthLabel: String, period: String, s: Summary) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Monthly summary", NotificationManager.IMPORTANCE_DEFAULT))
        val cost = "£%.2f".format(s.costPence / 100)
        val eff = if (s.kwhUsed > 0 && s.miles > 0) " (%.1f mi/kWh)".format(s.miles / s.kwhUsed) else ""
        val big = "$period\n" +
            "Charging cost: $cost (${"%.1f".format(s.kwhCharged)} kWh in ${s.sessions} sessions)\n" +
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
            .setVisibility(Notification.VISIBILITY_PRIVATE)       // amounts are hidden on the lock screen
            .setPublicVersion(Notification.Builder(ctx, CHANNEL).setSmallIcon(R.drawable.ic_bolt)
                .setContentTitle("BYDrive").setContentText("Monthly summary ready").build())
            .build()
        nm.notify(NOTIF_ID, n)
    }

    /**
     * Sends last month's summary if it is due. [test] forces a send now (for the launcher shortcut).
     * Returns false when it could not (yet) be done and should be retried.
     */
    fun checkAndSend(ctx: Context, test: Boolean = false): Boolean {
        if (!test && !Config.monthlyEnabled(ctx)) return true
        if (!test) {
            val now = Calendar.getInstance()
            if (now.get(Calendar.DAY_OF_MONTH) != 1) return true     // 1st of the month only
            if (now.get(Calendar.HOUR_OF_DAY) < 8) return true       // let overnight charging finish first
        }
        val prev = monthStart(-1)
        val key = monthKey(prev)
        if (!test && prefs(ctx).getString("sent", "") == key) return true      // already sent for that month
        if (Tailscale.isTailscaleAddress(Config.url(ctx)) && !Tailscale.isConnected() && !test) return false
        val summary = compute(ctx, prev, monthStart(0)) ?: return false
        val period = SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(prev)) + " - " +
            SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(monthStart(0) - 1))
        notify(ctx, SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Date(prev)) + " summary", period, summary)
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
