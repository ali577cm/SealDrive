package com.overdrive.app

import android.content.Context

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

    /** Monthly summary notification on the 1st (default on). */
    fun monthlyEnabled(c: Context): Boolean = prefs(c).getBoolean("monthly", true)

    fun setMonthlyEnabled(c: Context, on: Boolean) { prefs(c).edit().putBoolean("monthly", on).apply() }

    /** Time-of-day electricity tariff (pence per kWh): a cheap window and a standard rate the rest of the day. */
    class Tariff(val stdPence: Double, val cheapPence: Double, val startMin: Int, val endMin: Int) {
        fun signature() = "$stdPence|$cheapPence|$startMin|$endMin"
    }

    /** Defaults match Octopus Go: 9.5p 00:30-05:30, 36.67p otherwise. */
    fun tariff(c: Context): Tariff {
        val p = prefs(c)
        return Tariff(p.getFloat("tStd", 36.67f).toDouble(), p.getFloat("tCheap", 9.5f).toDouble(),
            p.getInt("tStart", 30), p.getInt("tEnd", 330))
    }

    fun windowText(c: Context): String {
        val t = tariff(c)
        return "%02d:%02d-%02d:%02d".format(t.startMin / 60, t.startMin % 60, t.endMin / 60, t.endMin % 60)
    }

    /** Saves the tariff; returns null when OK, otherwise a message saying what was wrong. */
    fun setTariff(c: Context, std: String, cheap: String, window: String): String? {
        val s = std.trim().replace(',', '.').toFloatOrNull()
        val ch = cheap.trim().replace(',', '.').toFloatOrNull()
        if (s == null || s < 0f || s > 1000f) return "Standard price not saved (use pence per kWh, e.g. 36.67)"
        if (ch == null || ch < 0f || ch > 1000f) return "Cheap price not saved (use pence per kWh, e.g. 9.5)"
        val m = Regex("^(\\d{1,2}):(\\d{2})\\s*-\\s*(\\d{1,2}):(\\d{2})$").matchEntire(window.trim())
            ?: return "Cheap window not saved (use HH:MM-HH:MM, e.g. 00:30-05:30)"
        val (h1, m1, h2, m2) = m.destructured
        if (h1.toInt() > 23 || h2.toInt() > 23 || m1.toInt() > 59 || m2.toInt() > 59)
            return "Cheap window not saved (invalid time)"
        prefs(c).edit().putFloat("tStd", s).putFloat("tCheap", ch)
            .putInt("tStart", h1.toInt() * 60 + m1.toInt()).putInt("tEnd", h2.toInt() * 60 + m2.toInt()).apply()
        return null
    }

    fun url(c: Context): String? = prefs(c).getString("url", null)

    /** Normalises and saves the address; returns it, or null if it isn't a valid http(s) URL. */
    fun setUrl(c: Context, raw: String): String? {
        var u = raw.trim()
        if (u.isEmpty()) return null
        if (!u.contains("://")) u = "http://$u"
        if (!Regex("^https?://[^\\s'\"<>\\\\]+$").matches(u)) return null
        prefs(c).edit().putString("url", u).apply()
        return u
    }
}
