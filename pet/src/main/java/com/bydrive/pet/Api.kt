package com.bydrive.pet

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Talks to the Overdrive server running on this same head unit. Calls from 127.0.0.1 need no login (Overdrive trusts
 * its own device), so no password or token is stored here.
 */
object Api {
    class State(
        val acOn: Boolean?,
        val insideC: Double?,
        val locked: Int,          // 1 locked, 2 unlocked, other unknown
        val soc: Double?,
        val cpdOn: Boolean?,
        val doorsOpen: Int,
    )

    private fun conn(base: String, path: String, method: String): HttpURLConnection {
        val c = URL(base.trimEnd('/') + path).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false
        c.connectTimeout = 4000
        c.readTimeout = 8000
        c.requestMethod = method
        c.setRequestProperty("Accept", "application/json")
        return c
    }

    fun state(base: String): State? = try {
        val c = conn(base, "/api/vehicle/state", "GET")
        val body = if (c.responseCode == 200) c.inputStream.bufferedReader().use { it.readText() } else ""
        c.disconnect()
        val j = JSONObject(body)
        if (!j.optBoolean("success", false)) null else {
            val climate = j.optJSONObject("climate")
            val setting = j.optJSONObject("setting")
            State(
                acOn = climate?.let { if (it.has("acOn")) it.optBoolean("acOn") else null },
                insideC = climate?.optDouble("insideTempC", Double.NaN)?.takeIf { !it.isNaN() },
                locked = j.optJSONObject("doors")?.optInt("overall", -1) ?: -1,
                soc = j.optJSONObject("battery")?.optDouble("soc", Double.NaN)?.takeIf { !it.isNaN() },
                cpdOn = setting?.let { if (it.has("childPresenceDetection")) it.optBoolean("childPresenceDetection") else null },
                doorsOpen = j.optJSONObject("doorOpen")?.let { o ->
                    o.keys().asSequence().count { k -> k != "fuelCap" && k != "trunk" && o.optBoolean(k, false) }
                } ?: 0,
            )
        }
    } catch (_: Exception) { null }

    /** Returns null on success, or a short error message. */
    private fun post(base: String, path: String, body: JSONObject): String? = try {
        val c = conn(base, path, "POST")
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = c.responseCode
        val text = (if (code == 200) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        c.disconnect()
        if (code != 200) "HTTP $code" else {
            val j = JSONObject(text)
            if (j.optBoolean("success", false)) null else j.optString("error", "Failed").take(60)
        }
    } catch (e: Exception) { "No answer" }

    fun climateOn(base: String, tempC: Double, minutes: Int) =
        post(base, "/api/vehicle/climate", JSONObject().put("action", "power_on").put("temp", tempC).put("remoteDurationMinutes", minutes))

    fun climateOff(base: String) = post(base, "/api/vehicle/climate", JSONObject().put("action", "power_off"))

    /** Child Presence Detection: 1 = on, 2 = off. */
    fun cpd(base: String, value: Int) = post(base, "/api/vehicle/setting", JSONObject().put("target", "childPresenceDetection").put("value", value))
}
