package com.overdrive.app

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Plain-text log of what the car and BYDrive are doing, kept on the phone only (see CarLogActivity). */
object CarLog {
    private const val FILE = "carlog.txt"
    private const val MAX_BYTES = 1_500_000L
    private val fmt = SimpleDateFormat("dd/MM HH:mm:ss", Locale.UK)

    fun on(c: Context): Boolean = c.getSharedPreferences("overdrive", Context.MODE_PRIVATE).getBoolean("carlog", false)

    fun setOn(c: Context, v: Boolean) {
        c.getSharedPreferences("overdrive", Context.MODE_PRIVATE).edit().putBoolean("carlog", v).apply()
    }

    private fun file(c: Context) = File(c.filesDir, FILE)

    /** Adds one line when logging is switched on. */
    @Synchronized
    fun append(c: Context, line: String) {
        if (!on(c)) return
        try {
            val f = file(c)
            if (f.length() > MAX_BYTES) {                       // keep the newest half
                val keep = f.readText().takeLast((MAX_BYTES / 2).toInt())
                f.writeText(keep.substringAfter('\n'))
            }
            f.appendText("${fmt.format(Date())}  $line\n")
        } catch (_: Exception) {}
    }

    /** The newest part of the log, newest line last. */
    @Synchronized
    fun tail(c: Context, maxChars: Int = 40_000): String = try {
        val f = file(c)
        if (f.exists()) f.readText().takeLast(maxChars).let { if (it.length == maxChars) it.substringAfter('\n') else it } else ""
    } catch (_: Exception) { "" }

    @Synchronized
    fun clear(c: Context) { try { file(c).delete() } catch (_: Exception) {} }
}
