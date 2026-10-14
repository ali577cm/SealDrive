package com.overdrive.app

import android.content.Context

/** User-configured Overdrive address, stored only on the device (never in the repo). */
object Config {
    private fun prefs(c: Context) = c.getSharedPreferences("overdrive", Context.MODE_PRIVATE)

    /** Electricity price in pence per kWh (default 9p). */
    fun pence(c: Context): Float = prefs(c).getFloat("pence", 9f)

    /** Saves the price; returns false if it isn't a sensible number. */
    fun setPence(c: Context, raw: String): Boolean {
        val v = raw.trim().replace(',', '.').toFloatOrNull() ?: return false
        if (v < 0f || v > 1000f) return false
        prefs(c).edit().putFloat("pence", v).apply()
        return true
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
