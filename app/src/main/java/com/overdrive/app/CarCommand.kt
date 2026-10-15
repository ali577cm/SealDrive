package com.overdrive.app

import android.content.Context
import android.webkit.CookieManager
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Commands sent to the car through Overdrive. Unlike [CarStatus] these CHANGE something, so they are only ever
 * called from an explicit tap, never from the background refresh. Uses the login the app's WebView already holds.
 */
object CarCommand {

    class Outcome(val ok: Boolean, val message: String)

    /**
     * Sets Child Presence Detection: value 1 = on, 2 = off, 3 = delay (Overdrive's own values).
     * After the car accepts it, the state is read back so the widget can say whether it really changed.
     */
    fun setChildPresenceDetection(ctx: Context, value: Int): Outcome {
        val base = Config.url(ctx) ?: return Outcome(false, "Set address in app")
        val cookie = CookieManager.getInstance().getCookie(base)
        if (cookie.isNullOrEmpty()) return Outcome(false, "Sign in via app")
        return try {
            val c = URL(base.trimEnd('/') + "/api/vehicle/setting").openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 4000
            c.readTimeout = 10000
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Cookie", cookie)
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Accept", "application/json")
            val body = JSONObject().put("target", "childPresenceDetection").put("value", value).toString()
            c.outputStream.use { it.write(body.toByteArray()) }
            val code = c.responseCode
            val text = (if (code == 200) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            c.disconnect()
            if (code != 200) return Outcome(false, if (code == 401 || code == 403 || code in 300..399) "Sign in via app" else "HTTP $code")
            val j = try { JSONObject(text) } catch (_: Exception) { return Outcome(false, "Sign in via app") }
            if (!j.optBoolean("success", false))
                return Outcome(false, j.optString("error", j.optString("reason", "Failed")).ifEmpty { "Failed" }.take(40))
            // read it back so we only claim success if the car really changed
            Thread.sleep(1200)
            val on = CarStatus.getJson(base, "/api/vehicle/state", cookie, 8000)?.optJSONObject("setting")
                ?.let { if (it.has("childPresenceDetection")) it.optBoolean("childPresenceDetection") else null }
            when {
                value == 2 && on == false -> Outcome(true, "Off")
                value == 2 && on == true -> Outcome(false, "Still on")
                else -> Outcome(true, "Sent")          // state not reported - the car accepted the command
            }
        } catch (e: IOException) {
            Outcome(false, "Offline")
        }
    }

    /** Turns the climate ON at [tempC] for [minutes] (Overdrive allows 10, 15, 20, 25 or 30). */
    fun startClimate(ctx: Context, tempC: Double, minutes: Int): Outcome {
        val base = Config.url(ctx) ?: return Outcome(false, "Set address in app")
        val cookie = CookieManager.getInstance().getCookie(base)
        if (cookie.isNullOrEmpty()) return Outcome(false, "Sign in via app")
        return try {
            val c = URL(base.trimEnd('/') + "/api/vehicle/climate").openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 4000
            c.readTimeout = 10000
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Cookie", cookie)
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Accept", "application/json")
            val body = JSONObject().put("action", "power_on").put("temp", tempC).put("remoteDurationMinutes", minutes).toString()
            c.outputStream.use { it.write(body.toByteArray()) }
            val code = c.responseCode
            val text = (if (code == 200) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            c.disconnect()
            if (code != 200) return Outcome(false, if (code == 401 || code == 403 || code in 300..399) "Sign in via app" else "HTTP $code")
            val j = try { JSONObject(text) } catch (_: Exception) { return Outcome(false, "Sign in via app") }
            if (j.optBoolean("success", false)) Outcome(true, "On")
            else Outcome(false, j.optString("error", j.optString("reason", "Failed")).ifEmpty { "Failed" }.take(40))
        } catch (e: IOException) {
            Outcome(false, "Offline")
        }
    }
}
