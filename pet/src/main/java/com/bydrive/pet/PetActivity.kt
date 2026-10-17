package com.bydrive.pet

import android.app.Activity
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Pet mode on the car's own screen. While active it shows Henry's sign full screen and keeps the AC at the chosen
 * temperature (re-sending Overdrive's climate command), until the car is unlocked, the battery reaches the limit, or
 * you hold the small lock at the bottom for two seconds. Touching anything else does nothing.
 */
class PetActivity : Activity() {

    private val h = Handler(Looper.getMainLooper())
    private val busy = AtomicBoolean(false)
    private var wake: PowerManager.WakeLock? = null

    @Volatile private var active = false
    private var sawLocked = false
    private var lastSend = 0L
    private var lastAcSend = 0L
    private var fails = 0
    private var cpdBefore = -1
    private var last: Api.State? = null
    private var note = ""
    private var result = ""          // shown on the idle screen after pet mode ends
    private var resultWarn = false

    // live views of the active screen
    private var tvTemp: TextView? = null
    private var tvPill: TextView? = null
    private var tvStatus: TextView? = null

    private lateinit var root: FrameLayout

    private fun prefs() = getSharedPreferences("pet", MODE_PRIVATE)
    private fun temp() = prefs().getFloat("temp", 20f)
    private fun minSoc() = prefs().getInt("minSoc", 30)
    private fun base() = prefs().getString("base", "http://127.0.0.1:8080") ?: "http://127.0.0.1:8080"
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        root = FrameLayout(this)
        setContentView(root)
        render()
    }

    override fun onConfigurationChanged(c: android.content.res.Configuration) { super.onConfigurationChanged(c); render() }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) @Suppress("DEPRECATION") {
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (!active) super.onBackPressed() }       // a paw on the back button must not end pet mode

    override fun onDestroy() {
        h.removeCallbacksAndMessages(null)
        active = false
        wake?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    // ---------------- screens ----------------

    private fun render() {
        root.removeAllViews()
        tvTemp = null; tvPill = null; tvStatus = null
        root.addView(if (active) activeView() else idleView(), FrameLayout.LayoutParams(-1, -1))
    }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color); if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun gradient(a: Int, b: Int) = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(a, b))

    private fun idleView(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(40), dp(32), dp(40), dp(32))
        }
        col.addView(text("Pet mode", 40f, Color.WHITE, true))
        col.addView(text("Keeps the car cool for Henry and shows his sign on this screen.", 18f, Color.parseColor("#9FB3C8")).apply { setPadding(0, dp(6), 0, dp(20)) })
        if (result.isNotEmpty())
            col.addView(text(result, 20f, if (resultWarn) Color.parseColor("#FF8A8A") else Color.parseColor("#2BD4A7"), true).apply { setPadding(0, 0, 0, dp(16)) })

        fun field(label: String, value: String, decimal: Boolean?): EditText {
            col.addView(text(label, 14f, Color.parseColor("#9FB3C8")))
            return EditText(this).apply {
                setText(value); setSingleLine(); setTextColor(Color.WHITE); textSize = 20f
                inputType = if (decimal == null) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                            else InputType.TYPE_CLASS_NUMBER or (if (decimal) InputType.TYPE_NUMBER_FLAG_DECIMAL else 0)
            }.also { col.addView(it, LinearLayout.LayoutParams(dp(360), -2).apply { bottomMargin = dp(8) }) }
        }
        val t = field("Temperature (17 to 33 C)", temp().let { if (it % 1f == 0f) it.toInt().toString() else it.toString() }, true)
        val s = field("Stop and warn at battery % (15 to 80)", minSoc().toString(), false)
        val a = field("Overdrive address on this car", base(), null)
        val msg = text("", 15f, Color.parseColor("#FF8A8A"))
        col.addView(msg)

        col.addView(Button(this).apply {
            text = "START PET MODE"; textSize = 22f
            setOnClickListener {
                val tv = t.text.toString().trim().replace(',', '.').toFloatOrNull()
                val sv = s.text.toString().trim().toIntOrNull()
                val av = a.text.toString().trim()
                when {
                    tv == null || tv < 17f || tv > 33f -> msg.text = "Temperature must be 17 to 33"
                    sv == null || sv < 15 || sv > 80 -> msg.text = "Battery limit must be 15 to 80"
                    !(av.startsWith("http://127.0.0.1") || av.startsWith("http://localhost")) -> msg.text = "Address must start with http://127.0.0.1"
                    else -> { prefs().edit().putFloat("temp", tv).putInt("minSoc", sv).putString("base", av).apply(); start() }
                }
            }
        }, LinearLayout.LayoutParams(dp(360), dp(72)).apply { topMargin = dp(8) })

        val upd = text("", 14f, Color.parseColor("#9FB3C8")).apply { setPadding(0, dp(12), 0, 0) }
        col.addView(Button(this).apply {
            text = "Check for updates"
            setOnClickListener { checkUpdate(upd) }
        }, LinearLayout.LayoutParams(dp(360), -2).apply { topMargin = dp(12) })
        col.addView(upd)
        col.addView(text("Version ${Updater.installedCode(this)}", 12f, Color.parseColor("#5C7089")).apply { setPadding(0, dp(8), 0, 0) })

        return ScrollView(this).apply {
            background = gradient(Color.parseColor("#0A1626"), Color.parseColor("#102A43"))
            addView(col, FrameLayout.LayoutParams(-1, -2))
        }
    }

    private fun activeView(): View {
        val land = resources.displayMetrics.widthPixels >= resources.displayMetrics.heightPixels
        val box = FrameLayout(this).apply { background = gradient(Color.parseColor("#1E7BFF"), Color.parseColor("#1FD3C3")) }

        val row = LinearLayout(this).apply {
            orientation = if (land) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(40), dp(24), dp(40), dp(24))
        }
        val size = (minOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels) * (if (land) 0.62 else 0.5)).toInt()
        val ring = FrameLayout(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        ring.addView(ImageView(this).apply {
            setImageResource(R.drawable.henry)
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) { o.setOval(0, 0, v.width, v.height) }
            }
        }, FrameLayout.LayoutParams(-1, -1))
        row.addView(ring, LinearLayout.LayoutParams(size, size).apply { if (land) marginEnd = dp(40) else bottomMargin = dp(20) })

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = if (land) Gravity.START else Gravity.CENTER_HORIZONTAL }
        val big = (resources.displayMetrics.heightPixels / resources.displayMetrics.density * (if (land) 0.13 else 0.07)).toFloat()
        col.addView(text("Henry is", big, Color.WHITE, true))
        col.addView(text("chilling!", big, Color.parseColor("#FFE873"), true))
        col.addView(text("My owner will be back soon.", big * 0.36f, Color.WHITE, true).apply { setPadding(0, dp(14), 0, 0) })
        col.addView(text("The air con is on. Don't worry!", big * 0.3f, Color.parseColor("#E2F4FF")))
        tvTemp = text(tempText(), big * 1.7f, Color.WHITE, true).apply { setPadding(0, dp(8), 0, 0) }
        col.addView(tvTemp)
        tvPill = text(pillText(), big * 0.3f, Color.parseColor("#04302A"), true).apply {
            setPadding(dp(18), dp(6), dp(18), dp(6))
            background = GradientDrawable().apply { cornerRadius = dp(30).toFloat(); setColor(Color.parseColor("#2BD4A7")) }
        }
        col.addView(tvPill)
        row.addView(col, LinearLayout.LayoutParams(-2, -2))
        box.addView(row, FrameLayout.LayoutParams(-1, -1))

        tvStatus = text(statusText(), 13f, Color.parseColor("#E2F4FF")).apply { setPadding(dp(16), dp(8), dp(16), dp(8)) }
        box.addView(tvStatus, FrameLayout.LayoutParams(-2, -2).apply { gravity = Gravity.BOTTOM or Gravity.START })

        // hold for two seconds to stop; ordinary taps do nothing
        val lock = text("HOLD TO STOP", 12f, Color.WHITE, true).apply {
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(Color.parseColor("#66000000")) }
            setOnTouchListener { _, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { h.postDelayed(stopRun, 2000); text = "KEEP HOLDING…" }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { h.removeCallbacks(stopRun); text = "HOLD TO STOP" }
                }
                true
            }
        }
        box.addView(lock, FrameLayout.LayoutParams(-2, -2).apply { gravity = Gravity.BOTTOM or Gravity.END; setMargins(dp(16), dp(16), dp(16), dp(16)) })
        return box
    }

    private fun tempText(): String = last?.insideC?.let { "%.1f°C".format(it).replace(".0°", "°") } ?: "%d°C".format(temp().toInt())
    private fun pillText(): String = when (last?.acOn) { true -> "AC ON"; false -> "AC STARTING…"; null -> "AC ON" }
    private fun statusText(): String {
        val s = last
        val bat = s?.soc?.let { "battery ${it.toInt()}%" } ?: "battery ?"
        return (if (note.isNotEmpty()) "$note  ·  " else "") + bat
    }

    private fun updateUi() {
        tvTemp?.text = tempText(); tvPill?.text = pillText(); tvStatus?.text = statusText()
    }

    // ---------------- pet mode ----------------

    private val stopRun = Runnable { finish("Pet mode stopped", stopAc = false, warn = false) }

    private val loop = object : Runnable {
        override fun run() {
            if (!active) return
            thread { tick() }
            h.postDelayed(this, 5000)
        }
    }

    private fun start() {
        active = true; sawLocked = false; lastSend = 0L; lastAcSend = 0L; fails = 0; cpdBefore = -1; last = null
        note = "starting"; result = ""; resultWarn = false
        wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bydrivepet:run").apply { acquire(12 * 3600_000L) }
        render()
        h.post(loop)
    }

    private fun tick() {
        if (!busy.compareAndSet(false, true)) return
        try {
            if (!active) return
            val b = base()
            val s = Api.state(b)
            if (s == null) {
                fails++
                note = "can't reach Overdrive ($fails)"
                runOnUiThread { updateUi() }
                return
            }
            fails = 0; last = s; note = ""
            if (s.locked == 1) sawLocked = true
            if (sawLocked && (s.locked == 2 || s.doorsOpen > 0)) { finish("Welcome back - pet mode ended", stopAc = false, warn = false); return }
            if (s.soc != null && s.soc <= minSoc()) { finish("Stopped: battery ${s.soc.toInt()}% - AC switched off", stopAc = true, warn = true); return }

            if (s.cpdOn == true) { if (cpdBefore == -1) cpdBefore = 1; Api.cpd(b, 2) }
            else if (s.cpdOn == false && cpdBefore == -1) cpdBefore = 0

            val now = System.currentTimeMillis()
            val due = now - lastSend >= 15 * 60_000L
            val needed = s.acOn != true && now - lastAcSend >= 60_000L
            if (due || needed) {
                lastAcSend = now
                val err = Api.climateOn(b, temp().toDouble(), 30)
                if (err == null) lastSend = now else note = "AC command failed: $err"
            }
            runOnUiThread { updateUi() }
        } finally {
            busy.set(false)
        }
    }

    private fun finish(reason: String, stopAc: Boolean, warn: Boolean) {
        if (!active) return
        active = false
        h.removeCallbacks(loop)
        thread {
            val b = base()
            if (stopAc) Api.climateOff(b)
            if (cpdBefore == 1) Api.cpd(b, 1)           // put Child Presence Detection back
            runOnUiThread {
                wake?.let { if (it.isHeld) it.release() }
                result = reason; resultWarn = warn
                render()
            }
        }
    }

    // ---------------- updates ----------------

    private fun checkUpdate(out: TextView) {
        out.text = "Checking…"
        thread {
            val c = Updater.check()
            val code = c.code
            if (code == null) { runOnUiThread { out.text = "Couldn't check: ${c.error}" }; return@thread }
            if (code <= Updater.installedCode(this)) { runOnUiThread { out.text = "Up to date (${Updater.installedCode(this)})" }; return@thread }
            runOnUiThread { out.text = "Downloading update $code…" }
            val apk = Updater.download(this)
            runOnUiThread {
                when {
                    apk == null -> out.text = "Download failed. Open ${Updater.APK_URL} in the car browser instead."
                    !Updater.verify(this, apk) -> out.text = "Update rejected: not signed with the same key"
                    !Updater.install(this, apk) -> out.text = "Allow installs from this app, then check again"
                    else -> out.text = "Installer opened"
                }
            }
        }
    }
}
