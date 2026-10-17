package com.overdrive.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Shows the car log, with start/stop, share and clear. */
class CarLogActivity : Activity() {

    private lateinit var body: TextView
    private lateinit var scroll: ScrollView
    private lateinit var toggle: Button
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { refresh(); handler.postDelayed(this, 4000) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#0A1626")) }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(4)) }
        bar.addView(TextView(this).apply {
            text = "←"; textSize = 24f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(12), dp(4), dp(12), dp(4)); setOnClickListener { finish() }; contentDescription = "Back"
        })
        bar.addView(TextView(this).apply { text = "Car log"; textSize = 20f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD) },
            LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(bar)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), 0, dp(12), dp(4)) }
        toggle = Button(this).apply {
            setOnClickListener {
                if (CarLog.on(this@CarLogActivity)) CarLogService.stop(this@CarLogActivity) else CarLogService.start(this@CarLogActivity)
                refresh()
            }
        }
        row.addView(toggle, LinearLayout.LayoutParams(0, -2, 1.4f))
        row.addView(Button(this).apply {
            text = "Share"
            setOnClickListener {
                val t = CarLog.tail(this@CarLogActivity, 60_000)
                if (t.isEmpty()) Toast.makeText(context, "Nothing logged yet", Toast.LENGTH_SHORT).show()
                else startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, t), "Share car log"))
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(Button(this).apply {
            text = "Clear"
            setOnClickListener { CarLog.clear(this@CarLogActivity); refresh() }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(row)

        body = TextView(this).apply {
            typeface = Typeface.MONOSPACE; textSize = 11f; setTextColor(Color.parseColor("#CFE3F5")); setTextIsSelectable(true)
            setPadding(dp(12), dp(8), dp(12), dp(24))
        }
        scroll = ScrollView(this).apply { addView(body) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { setMargins(dp(8), dp(4), dp(8), dp(8)) })
        scroll.background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(Color.parseColor("#0F2036")) }
        setContentView(root)
    }

    private fun refresh() {
        toggle.text = if (CarLog.on(this)) "Stop logging" else "Start logging"
        val t = CarLog.tail(this)
        body.text = if (t.isEmpty()) "Nothing logged yet.\n\nTap Start logging, then do what you want to watch (tap the widget, start pet mode, lock or unlock the car). It logs every change the car reports, plus every command BYDrive sends and what came back." else t
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    override fun onResume() { super.onResume(); handler.post(tick) }
    override fun onPause() { handler.removeCallbacks(tick); super.onPause() }
}
