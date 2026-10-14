package com.overdrive.app

import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Read-only status fetch from the Overdrive server. Reuses the login cookie the app's WebView already holds,
 * so no password or device token is ever stored. Only GET requests are made, and only to the configured address.
 */
object CarStatus {

    class Snapshot(
        val soc: Double?, val rangeKm: Double?,
        val locked: Int,            // 1 locked, 2 unlocked, other unknown
        val doorsOpen: Int,
        val windowsOpen: Int?,      // null = unknown
        val sentryOn: Boolean?, val sentryRecording: Boolean,
        val avgKwhPerDay: Double?,
    )

    sealed class Result {
        class Ok(val s: Snapshot) : Result()
        object NoAddress : Result()
        class SignIn(val why: String) : Result()
        class Offline(val why: String) : Result()
        class NoData(val why: String) : Result()
    }

    private class Resp(val code: Int, val json: JSONObject?, val err: String? = null)

    private fun get(base: String, path: String, cookie: String): Resp? = try {
        val c = URL(base.trimEnd('/') + path).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false          // a redirect means "go to login"
        c.connectTimeout = 3000
        c.readTimeout = 4000
        c.requestMethod = "GET"
        c.setRequestProperty("Cookie", cookie)
        c.setRequestProperty("Accept", "application/json")
        val code = c.responseCode
        val body = if (code == 200) c.inputStream.bufferedReader().use { it.readText() } else ""
        c.disconnect()
        Resp(code, if (body.trimStart().startsWith("{")) try { JSONObject(body) } catch (_: Exception) { null } else null)
    } catch (e: IOException) { Resp(-1, null, e.javaClass.simpleName) }

    private fun dayStart(t: Long): Long {
        val c = java.util.Calendar.getInstance()
        c.timeInMillis = t
        c.set(java.util.Calendar.HOUR_OF_DAY, 0)
        c.set(java.util.Calendar.MINUTE, 0)
        c.set(java.util.Calendar.SECOND, 0)
        c.set(java.util.Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    fun fetch(ctx: Context): Result {
        val base = Config.url(ctx) ?: return Result.NoAddress
        val cookie = CookieManager.getInstance().getCookie(base)
        if (cookie.isNullOrEmpty()) return Result.SignIn("no login in app")

        // sentry + trips in parallel with the main state call
        var sentryResp: Resp? = null
        var tripsResp: Resp? = null
        val t1 = thread { sentryResp = get(base, "/api/surveillance/status", cookie) }
        val t2 = thread { tripsResp = get(base, "/api/trips?days=7&limit=500", cookie) }
        val state = get(base, "/api/vehicle/state", cookie)
        t1.join(); t2.join()

        if (state == null || state.code == -1) return Result.Offline(state?.err ?: "no response")
        val j = state.json ?: return Result.SignIn(if (state.code == 200) "got a web page" else "HTTP ${state.code}")
        if (!j.optBoolean("success", false)) return Result.NoData(j.optString("error", "car data unavailable").take(40))

        val battery = j.optJSONObject("battery")
        val soc = battery?.optDouble("soc", Double.NaN)?.takeIf { !it.isNaN() }
        val range = battery?.optDouble("rangeKm", Double.NaN)?.takeIf { !it.isNaN() && it > 0 }

        val locked = j.optJSONObject("doors")?.optInt("overall", -1) ?: -1

        var doorsOpen = 0
        j.optJSONObject("doorOpen")?.let { o -> o.keys().forEach { k -> if (k != "fuelCap" && o.optBoolean(k, false)) doorsOpen++ } }

        var winOpen = 0
        var winKnown = false
        val wins = j.optJSONObject("windows")
        val winFlags = j.optJSONObject("windowOpen")
        for (k in listOf("lf", "rf", "lr", "rr")) {
            val pct = wins?.optInt(k, -1) ?: -1
            val flag = winFlags?.let { if (it.has(k)) it.optBoolean(k) else null }
            if (pct >= 0 || flag != null) winKnown = true
            if (pct > 0 || flag == true) winOpen++
        }

        val st = sentryResp?.json?.optJSONObject("status")
        val sentryOn = st?.let { if (it.has("enabled")) it.optBoolean("enabled") else null }
        val sentryRec = st?.optBoolean("recording", false) ?: false

        val trips = tripsResp?.json?.optJSONArray("trips")
        val avg = trips?.let { arr ->
            val now = System.currentTimeMillis()
            var sum = 0.0
            var earliest = now
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                // measured energy; when the car can't measure it Overdrive reports 0 and keeps energyPerKm (kWh/km) instead
                var e = o.optDouble("energyUsedKwh", 0.0)
                if (e.isNaN() || e <= 0) {
                    val perKm = o.optDouble("energyPerKm", 0.0)
                    val km = o.optDouble("distanceKm", 0.0)
                    e = if (!perKm.isNaN() && !km.isNaN() && perKm > 0 && km > 0) perKm * km else 0.0
                }
                sum += e
                val start = o.optLong("startTime", 0L)
                if (start > 0 && start < earliest) earliest = start
            }
            // average over the days since the first recorded trip (today counts as a day), at most 7
            val days = Math.round((dayStart(now) - dayStart(earliest)) / 86_400_000.0).toInt().plus(1).coerceIn(1, 7)
            sum / days
        }

        return Result.Ok(Snapshot(soc, range, locked, doorsOpen, if (winKnown) winOpen else null, sentryOn, sentryRec, avg))
    }
}
