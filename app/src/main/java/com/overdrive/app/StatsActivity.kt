package com.overdrive.app

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.app.AlertDialog
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
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
    private var rows: List<MonthlyReport.ChargeRow>? = null
    private var rowsFailed = false
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
            setOnClickListener { load(force = true); loadCharges() }
            contentDescription = "Refresh"
        })
        root.addView(bar)

        // tabs
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), dp(4), dp(12), dp(8)) }
        tabViews = listOf("This month", "Months", "Year", "Charges").mapIndexed { i, label ->
            text(label, 13f, dim, true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(4), dp(10), dp(4), dp(10))
                setOnClickListener { tab = i; if (i == 3 && rows == null) loadCharges(); render() }
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

    private fun loadCharges() {
        thread {
            val r = MonthlyReport.chargeRows(this, 45)
            rows = r
            rowsFailed = r == null
            runOnUiThread { render() }
        }
    }

    /** Charges you add by hand change the totals, so forget the saved month results and recalculate. */
    private fun manualChanged() {
        cache().edit().clear().apply()
        results.clear()
        loadCharges()
        load(force = true)
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
            2 -> renderYear()
            else -> renderCharges()
        }
        content.addView(text("Costs use your tariff and charger-efficiency settings; they are estimates, not a bill.", 11f, dim).apply { setPadding(0, dp(8), 0, 0) })
    }

    private fun renderCharges() {
        content.addView(text("＋  Add a charge Overdrive missed", 14f, Color.BLACK, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = GradientDrawable().apply { setColor(accent); cornerRadius = dp(14).toFloat() }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
            setOnClickListener { showAddDialog() }
        })
        content.addView(text("This is every charge Overdrive recorded in the last 45 days. If one is missing or has no energy, compare with your charger app and add it by hand (kWh as shown by the charger). Tap a charge you added to remove it.", 12f, dim).apply { setPadding(0, 0, 0, dp(10)) })
        val list = rows
        if (list == null) {
            content.addView(text(if (rowsFailed) "Couldn't reach the car. Check Tailscale, then tap ⟳." else "Loading…", 13f, dim))
            return
        }
        if (list.isEmpty()) content.addView(text("No charges recorded in the last 45 days.", 13f, dim))
        val day = SimpleDateFormat("EEE d MMM", Locale.getDefault())
        val hm = SimpleDateFormat("HH:mm", Locale.getDefault())
        for (r in list) {
            val c = cardView()
            val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            head.addView(text(day.format(Date(r.start)) + if (r.manual) "  (added by you)" else if (r.inProgress) "  (charging now)" else "", 14f, Color.WHITE, true), LinearLayout.LayoutParams(0, -2, 1f))
            head.addView(text(r.costPence?.let { money(it) } ?: "—", 16f, accent, true))
            c.addView(head)
            val mins = Math.max(0L, (r.end - r.start) / 60_000L)
            c.addView(text("${hm.format(Date(r.start))} – ${if (r.inProgress) "now" else hm.format(Date(r.end))} · ${mins / 60}h ${mins % 60}m", 12f, dim).apply { setPadding(0, dp(2), 0, 0) })
            val detail = StringBuilder()
            if (r.kwh != null) detail.append(kwh(r.kwh)) else detail.append("no energy recorded")
            r.avgKw?.let { detail.append(" · %.1f kW avg".format(it)) }
            if (r.socStart != null && r.socEnd != null) detail.append(" · ${Math.round(r.socStart)}→${Math.round(r.socEnd)}%")
            c.addView(text(detail.toString(), 12f, if (r.kwh == null) Color.parseColor("#FFC857") else dim).apply { setPadding(0, dp(2), 0, 0) })
            if (r.manual) c.setOnClickListener {
                AlertDialog.Builder(this).setTitle("Remove this charge?")
                    .setMessage("${day.format(Date(r.start))} ${hm.format(Date(r.start))}–${hm.format(Date(r.end))}, ${kwh(r.kwh ?: 0.0)}")
                    .setPositiveButton("Remove") { _, _ -> Config.removeManual(this, r.id); manualChanged() }
                    .setNegativeButton("Keep", null).show()
            }
            content.addView(c)
        }
    }

    private fun showAddDialog() {
        val dateF = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        fun field(hint: String, value: String, number: Boolean = false) = EditText(this).apply {
            this.hint = hint
            setText(value)
            setSingleLine()
            if (number) inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val d = field("Date it started (YYYY-MM-DD)", dateF.format(Date()))
        val a = field("Start time (HH:MM)", "")
        val b = field("End time (HH:MM)", "")
        val k = field("Energy delivered (kWh)", "", number = true)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), 0, dp(20), 0)
            listOf(d, a, b, k).forEach { addView(it) }
        }
        AlertDialog.Builder(this).setTitle("Add a charge")
            .setMessage("Use the figures from your charger app. If it ends after midnight just enter the end time; the date is when it started.")
            .setView(box)
            .setPositiveButton("Add") { _, _ ->
                try {
                    val f = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                    val start = f.parse(d.text.toString().trim() + " " + a.text.toString().trim())!!.time
                    var end = f.parse(d.text.toString().trim() + " " + b.text.toString().trim())!!.time
                    if (end <= start) end += 86_400_000L                       // finished after midnight
                    val e = k.text.toString().trim().replace(',', '.').toDouble()
                    if (e <= 0 || e > 300 || end - start > 48 * 3_600_000L || start > System.currentTimeMillis()) throw IllegalArgumentException()
                    Config.addManual(this, start, end, e)
                    manualChanged()
                } catch (_: Exception) {
                    Toast.makeText(this, "Couldn't add that - check the date, times and kWh", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
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
