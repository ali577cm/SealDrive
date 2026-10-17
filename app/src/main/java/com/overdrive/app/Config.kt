package com.overdrive.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** User-configured Overdrive address, stored only on the device (never in the repo). */
object Config {
    private fun prefs(c: Context) = c.getSharedPreferences("overdrive", Context.MODE_PRIVATE)

    /** Widget background: 0 = transparent (default), 1 = glass, 2 = solid. */
    fun bg(c: Context): Int = prefs(c).getInt("bg", 0)

    fun setBg(c: Context, v: Int) { prefs(c).edit().putInt("bg", v).apply() }

    /** Temperature (C) the 1x1 climate button sets. */
    fun climateTemp(c: Context): Float = prefs(c).getFloat("climate", 21f)

    fun setClimateTemp(c: Context, raw: String): Boolean {
        val v = raw.trim().replace(',', '.').toFloatOrNull() ?: return false
        if (v < 17f || v > 33f) return false
        prefs(c).edit().putFloat("climate", v).apply()
        return true
    }

    /** How long the climate runs after the button is tapped (Overdrive accepts 10, 15, 20, 25 or 30). */
    fun climateMinutes(c: Context): Int = prefs(c).getInt("climateMin", 20)

    fun setClimateMinutes(c: Context, raw: String): Boolean {
        val v = raw.trim().toIntOrNull() ?: return false
        if (v !in listOf(10, 15, 20, 25, 30)) return false
        prefs(c).edit().putInt("climateMin", v).apply()
        return true
    }

    // ---- pet mode (see PetSettingsActivity) ----

    /** Cabin temperature (C) pet mode holds. */
    fun petTemp(c: Context): Float = prefs(c).getFloat("petTemp", 20f)

    /** Pet mode stops and warns at or below this battery %. */
    fun petMinSoc(c: Context): Int = prefs(c).getInt("petMinSoc", 30)

    /** Show the Screen Deterrent picture on the car screen while pet mode runs. */
    fun petPicture(c: Context): Boolean = prefs(c).getBoolean("petPicture", true)

    /** Car screen brightness % pet mode sets; 0 = leave the brightness alone. */
    fun petBrightness(c: Context): Int = prefs(c).getInt("petBrightness", 100)

    /** Switch Child Presence Detection off while pet mode runs (restored afterwards). */
    fun petCpdOff(c: Context): Boolean = prefs(c).getBoolean("petCpdOff", true)

    /** Saves the pet mode settings; returns an error message, or null when everything was valid and saved. */
    fun setPet(c: Context, temp: String, minSoc: String, picture: Boolean, brightness: Int, cpdOff: Boolean): String? {
        val t = temp.trim().replace(',', '.').toFloatOrNull()
        if (t == null || t < 17f || t > 33f) return "Temperature must be 17 to 33"
        val s = minSoc.trim().toIntOrNull()
        if (s == null || s < 15 || s > 80) return "Stop battery must be 15 to 80"
        prefs(c).edit().putFloat("petTemp", t).putInt("petMinSoc", s).putBoolean("petPicture", picture)
            .putInt("petBrightness", brightness.coerceIn(0, 100)).putBoolean("petCpdOff", cpdOff).apply()
        return null
    }

    /** A charge Overdrive missed, entered by hand from the charger app (kWh = energy delivered, i.e. at the meter). */
    class Manual(val id: Long, val start: Long, val end: Long, val kwh: Double)

    fun manualSessions(c: Context): List<Manual> = try {
        val a = JSONArray(prefs(c).getString("manual", "[]"))
        (0 until a.length()).map { val o = a.getJSONObject(it); Manual(o.getLong("id"), o.getLong("s"), o.getLong("e"), o.getDouble("k")) }
    } catch (_: Exception) { emptyList() }

    private fun saveManual(c: Context, list: List<Manual>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("id", it.id).put("s", it.start).put("e", it.end).put("k", it.kwh)) }
        prefs(c).edit().putString("manual", a.toString()).apply()
    }

    fun addManual(c: Context, start: Long, end: Long, kwh: Double) =
        saveManual(c, manualSessions(c) + Manual(System.currentTimeMillis(), start, end, kwh))

    fun removeManual(c: Context, id: Long) = saveManual(c, manualSessions(c).filter { it.id != id })

    /** Monthly summary notification on the 1st (default on). */
    fun monthlyEnabled(c: Context): Boolean = prefs(c).getBoolean("monthly", true)

    fun setMonthlyEnabled(c: Context, on: Boolean) { prefs(c).edit().putBoolean("monthly", on).apply() }

    /** Time-of-day electricity tariff (pence per kWh): a cheap window and a standard rate the rest of the day. */
    class Tariff(val stdPence: Double, val cheapPence: Double, val startMin: Int, val endMin: Int, val effPercent: Double = 100.0) {
        fun signature() = "$stdPence|$cheapPence|$startMin|$endMin|$effPercent"
    }

    /** Defaults match Octopus Go: 9.5p 00:30-05:30, 36.67p otherwise. */
    fun tariff(c: Context): Tariff {
        val p = prefs(c)
        return Tariff(p.getFloat("tStd", 36.67f).toDouble(), p.getFloat("tCheap", 9.5f).toDouble(),
            p.getInt("tStart", 30), p.getInt("tEnd", 330), p.getFloat("tEff", 100f).toDouble())
    }

    fun windowText(c: Context): String {
        val t = tariff(c)
        return "%02d:%02d-%02d:%02d".format(t.startMin / 60, t.startMin % 60, t.endMin / 60, t.endMin % 60)
    }

    /** Saves the tariff; returns null when OK, otherwise a message saying what was wrong. */
    fun setTariff(c: Context, std: String, cheap: String, window: String, eff: String): String? {
        val s = std.trim().replace(',', '.').toFloatOrNull()
        val ch = cheap.trim().replace(',', '.').toFloatOrNull()
        if (s == null || s < 0f || s > 1000f) return "Standard price not saved (use pence per kWh, e.g. 36.67)"
        if (ch == null || ch < 0f || ch > 1000f) return "Cheap price not saved (use pence per kWh, e.g. 9.5)"
        val e = eff.trim().replace(',', '.').toFloatOrNull()
        if (e == null || e < 50f || e > 100f) return "Charger efficiency not saved (use 50 to 100; 100 = ignore losses)"
        val m = Regex("^(\\d{1,2}):(\\d{2})\\s*-\\s*(\\d{1,2}):(\\d{2})$").matchEntire(window.trim())
            ?: return "Cheap window not saved (use HH:MM-HH:MM, e.g. 00:30-05:30)"
        val (h1, m1, h2, m2) = m.destructured
        if (h1.toInt() > 23 || h2.toInt() > 23 || m1.toInt() > 59 || m2.toInt() > 59)
            return "Cheap window not saved (invalid time)"
        prefs(c).edit().putFloat("tStd", s).putFloat("tCheap", ch).putFloat("tEff", e)
            .putInt("tStart", h1.toInt() * 60 + m1.toInt()).putInt("tEnd", h2.toInt() * 60 + m2.toInt()).apply()
        return null
    }

    fun url(c: Context): String? = prefs(c).getString("url", null)

    /** Plain http is only acceptable for Tailscale (already encrypted), loopback and private home-network addresses. */
    private fun httpAllowed(u: String): Boolean {
        val host = try { android.net.Uri.parse(u).host } catch (_: Exception) { null } ?: return false
        if (Tailscale.isTailscaleAddress(u) || host == "localhost" || host.endsWith(".local")) return true
        val p = host.split(".")
        if (p.size != 4) return false
        val a = p[0].toIntOrNull()
        val b = p[1].toIntOrNull()
        return a == 10 || a == 127 || (a == 192 && b == 168) || (a == 172 && b != null && b in 16..31)
    }

    /** Normalises and saves the address; returns it, or null if it isn't a valid http(s) URL. */
    fun setUrl(c: Context, raw: String): String? {
        var u = raw.trim()
        if (u.isEmpty()) return null
        if (!u.contains("://")) u = "http://$u"
        if (!Regex("^https?://[^\\s'\"<>\\\\]+$").matches(u)) return null
        if (u.startsWith("http://") && !httpAllowed(u)) return null       // plain http only inside Tailscale / your own network
        prefs(c).edit().putString("url", u).apply()
        return u
    }
}
