package com.overdrive.app

import android.content.Context

/** The few live values the climate and pet-mode widgets need, read from Overdrive's vehicle state (GET only). */
object CarState {

    class Snap(
        val acOn: Boolean?,        // null = car did not report it
        val insideC: Double?,      // cabin temperature
        val locked: Int,           // 1 locked, 2 unlocked, other unknown
        val soc: Double?,          // battery %
        val cpdOn: Boolean?,       // Child Presence Detection
    )

    /** Returns null when the car/Overdrive cannot be reached or the login is missing. Blocking. */
    fun read(ctx: Context): Snap? {
        val (base, cookie) = CarStatus.auth(ctx) ?: return null
        val j = CarStatus.getJson(base, "/api/vehicle/state", cookie, 8000) ?: return null
        if (!j.optBoolean("success", false)) return null
        val climate = j.optJSONObject("climate")
        val setting = j.optJSONObject("setting")
        return Snap(
            acOn = climate?.let { if (it.has("acOn")) it.optBoolean("acOn") else null },
            insideC = climate?.optDouble("insideTempC", Double.NaN)?.takeIf { !it.isNaN() },
            locked = j.optJSONObject("doors")?.optInt("overall", -1) ?: -1,
            soc = j.optJSONObject("battery")?.optDouble("soc", Double.NaN)?.takeIf { !it.isNaN() },
            cpdOn = setting?.let { if (it.has("childPresenceDetection")) it.optBoolean("childPresenceDetection") else null },
        )
    }

    fun fmtTemp(t: Double?): String = if (t == null) "—" else "%.1f°".format(t).replace(".0°", "°")
}
