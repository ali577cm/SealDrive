package com.overdrive.app

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Running totals: this month so far, the last 12 months, and this/last year.
 * Each month is calculated exactly (1st 00:00 to the end of the last day) the same way as the monthly notification.
 * Finished months are cached on the phone; only the current month is re-read each time.
 */
class StatsActivity : Activity() {

    private class Month(val key: String, val from: Long, val to: Long, val current: Boolean, val year: Int, val month: Int)

    private val results = LinkedHashMap<String, MonthlyReport.Summary>()
    private var tab = 0
    private var status = ""
    @Volatile private var loading = false
    private lateinit var content: LinearLayout
    private lateinit var tabViews: List<TextView>
    private lateinit var months: List<Month>

    private val bg = Color.parseColor("#0A1626")
    private val card = Color.parseColor("#12263D")
    private val accent = Color.parseColor("#2BD4A7")
    private val dim = Color.parseColor("#8FA6BD")

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        months = buildMonths()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg) }

        // title bar
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(4))
        }
        bar.addView(text("←", 24f, Color.WHITE, true).apply {
            setPadding(dp(12), dp(4), dp(12), dp(4))
            setOnClickListener { finish() }
            contentDescription = "Back"
        })
        bar.addView(text("Usage & costs", 20f, Color.WHITE, true), LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(text("⟳", 24f, accent, true).apply {
            setPadding(dp(12), dp(4), dp(12), dp(4))
            setOnClickListener { load(force = true) }
            contentDescription = "Refresh"
        })
        root.addView(bar)

        // tabs
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), dp(4), dp(12), dp(8)) }
        tabViews = listOf("This month", "Months", "Year").mapIndexed { i, label ->
            text(label, 14f, dim, true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(10), dp(8), dp(10))
                setOnClickListener { tab = i; render() }
            }.also { tabs.addView(it, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(3); marginEnd = dp(3) }) }
        }
        root.addView(tabs)

        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(4), dp(16), dp(24)) }
        root.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        render()
        load(force = false)
    }

    // ---------------- data ----------------

    private fun buildMonths(): List<Month> {
        val now = System.currentTimeMillis()
        return (0 until 24).map { i ->
            val c = Calendar.getInstance()
            c.set(Calendar.DAY_OF_MONTH, 1)
            c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
            c.add(Calendar.MONTH, -i)
            val from = c.timeInMillis
            val y = c.get(Calendar.YEAR)
            val m = c.get(Calendar.MONTH) + 1
            c.add(Calendar.MONTH, 1)
            Month("%04d-%02d".format(y, m), from, if (i == 0) now else c.timeInMillis, i == 0, y, m)
        }
    }

    private fun cache() = getSharedPreferences("stats_cache", MODE_PRIVATE)

    private fun cached(m: Month): MonthlyReport.Summary? {
        val sig = Config.tariff(this).signature()
        val raw = cache().getString("m_${m.key}", null) ?: return null
        return try {
            val (s, json) = raw.split("#", limit = 2)
            if (s != sig) return null
            val o = JSONObject(json)
            MonthlyReport.Summary(o.getDouble("mi"), o.getDouble("kwh"), o.getDouble("chg"), o.getDouble("cost"), o.getInt("trips"), o.getInt("sess"))
        } catch (_: Exception) { null }
    }

    private fun store(m: Month, s: MonthlyReport.Summary) {
        val o = JSONObject().put("mi", s.miles).put("kwh", s.kwhUsed).put("chg", s.kwhCharged)
            .put("cost", s.costPence).put("trips", s.trips).put("sess", s.sessions)
        cache().edit().putString("m_${m.key}", Config.tariff(this).signature() + "#" + o.toString()).apply()
    }

    private fun load(force: Boolean) {
        if (loading) return
        if (CarStatus.auth(this) == null) { status = "Open the main screen and sign in to Overdrive first."; render(); return }
        loading = true
        status = "Loading…"
        render()
        thread {
            var failed = false
            for (m in months) {
                val s = (if (!m.current && !force) cached(m) else null)
                    ?: MonthlyReport.compute(this, m.from, m.to, live = m.current)
                if (s == null) { failed = true; break }
                if (!m.current) store(m, s)
                results[m.key] = s
                runOnUiThread { render() }
            }
            loading = false
            status = if (failed) "Couldn't reach the car. Check Tailscale is connected, then tap ⟳." else ""
            runOnUiThread { render() }
        }
    }

    // ---------------- drawing ----------------

    private fun text(t: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t
        textSize = sp
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun cardView(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = GradientDrawable().apply { setColor(card); cornerRadius = dp(20).toFloat() }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
    }

    private fun money(p: Double) = "£%.2f".format(p / 100)
    private fun mi(v: Double) = "%,d mi".format(Math.round(v))
    private fun kwh(v: Double) = "%.1f kWh".format(v)

    private fun line(parent: LinearLayout, label: String, value: String) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(3), 0, dp(3)) }
        row.addView(text(label, 14f, dim), LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(text(value, 14f, Color.WHITE, true))
        parent.addView(row)
    }

    private fun total(parent: LinearLayout, title: String, sub: String, s: MonthlyReport.Summary?) {
        parent.addView(text(title, 13f, dim))
        parent.addView(text(if (s != null) money(s.costPence) else "—", 38f, Color.WHITE, true))
        parent.addView(text("charging cost" + if (sub.isNotEmpty()) " · $sub" else "", 12f, dim).apply { setPadding(0, 0, 0, dp(8)) })
        if (s != null) {
            line(parent, "Driven", mi(s.miles) + "  (${s.trips} trips)")
            line(parent, "Energy used", kwh(s.kwhUsed))
            line(parent, "Energy charged", kwh(s.kwhCharged) + "  (${s.sessions} sessions)")
        }
    }

    private fun render() {
        tabViews.forEachIndexed { i, t ->
            t.setTextColor(if (i == tab) Color.BLACK else dim)
            t.background = GradientDrawable().apply {
                setColor(if (i == tab) accent else card); cornerRadius = dp(14).toFloat()
            }
        }
        content.removeAllViews()
        if (status.isNotEmpty()) content.addView(text(status, 13f, if (status == "Loading…") dim else Color.parseColor("#FFC857")).apply { setPadding(0, 0, 0, dp(10)) })
        when (tab) {
            0 -> renderMonth()
            1 -> renderMonths()
            else -> renderYear()
        }
        content.addView(text("Costs use your tariff and charger-efficiency settings; they are estimates, not a bill.", 11f, dim).apply { setPadding(0, dp(8), 0, 0) })
    }

    private fun renderMonth() {
        val m = months[0]
        val s = results[m.key]
        val c = cardView()
        val label = SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Date(m.from))
        val today = SimpleDateFormat("d MMM", Locale.getDefault()).format(Date())
        total(c, label, "1 – $today", s)
        content.addView(c)
        if (s != null) {
            val cal = Calendar.getInstance()
            val day = cal.get(Calendar.DAY_OF_MONTH)
            val inMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
            val c2 = cardView()
            line(c2, "Average per day", money(s.costPence / day) + " · " + mi(s.miles / day))
            line(c2, "On track for the month", money(s.costPence / day * inMonth))
            content.addView(c2)
        }
    }

    private fun renderMonths() {
        for (m in months.take(12)) {
            val s = results[m.key]
            val c = cardView()
            val label = SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Date(m.from)) + if (m.current) " (so far)" else ""
            val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            head.addView(text(label, 15f, Color.WHITE, true), LinearLayout.LayoutParams(0, -2, 1f))
            head.addView(text(if (s != null) money(s.costPence) else "…", 17f, accent, true))
            c.addView(head)
            if (s != null) c.addView(text("${mi(s.miles)} · ${kwh(s.kwhUsed)} used · ${kwh(s.kwhCharged)} charged", 12f, dim).apply { setPadding(0, dp(2), 0, 0) })
            content.addView(c)
        }
    }

    private fun renderYear() {
        val thisYear = months[0].year
        for (y in listOf(thisYear, thisYear - 1)) {
            val ms = months.filter { it.year == y }
            val loaded = ms.mapNotNull { results[it.key] }
            val c = cardView()
            if (loaded.isEmpty()) {
                c.addView(text(if (y == thisYear) "$y (to date)" else "$y", 13f, dim))
                c.addView(text("—", 38f, Color.WHITE, true))
            } else {
                val sum = loaded.reduce { a, b -> a + b }
                val partial = ms.size < 12 || loaded.size < ms.size
                total(c, if (y == thisYear) "$y (to date)" else "$y", "${loaded.size} months of data" + if (partial && y != thisYear) " (may be incomplete)" else "", sum)
                if (loaded.size > 1) line(c, "Average per month", money(sum.costPence / loaded.size))
            }
            content.addView(c)
        }
    }
}
