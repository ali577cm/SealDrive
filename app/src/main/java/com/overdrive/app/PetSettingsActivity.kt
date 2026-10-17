package com.overdrive.app

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

/** Pet mode settings: temperature, stop battery level, the picture on the car screen, brightness and Child Presence Detection. */
class PetSettingsActivity : Activity() {

    private val bg = Color.parseColor("#0A1626")
    private val card = Color.parseColor("#12263D")
    private val accent = Color.parseColor("#2BD4A7")
    private val dim = Color.parseColor("#8FA6BD")

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun field(hint: String, value: String, decimal: Boolean) = EditText(this).apply {
        this.hint = hint
        setText(value)
        setSingleLine()
        setTextColor(Color.WHITE)
        setHintTextColor(dim)
        inputType = InputType.TYPE_CLASS_NUMBER or (if (decimal) InputType.TYPE_NUMBER_FLAG_DECIMAL else 0)
    }

    private fun section(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(card) }
        views.forEach { addView(it) }
    }.also { it.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg) }

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
        bar.addView(text("Pet mode settings", 20f, Color.WHITE, true), LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(bar)

        val temp = field("Temperature (17 to 33)", Config.petTemp(this).let { if (it % 1f == 0f) it.toInt().toString() else it.toString() }, true)
        val soc = field("Stop at battery % (15 to 80)", Config.petMinSoc(this).toString(), false)
        val picture = CheckBox(this).apply {
            text = "Show the picture on the car screen"
            setTextColor(Color.WHITE)
            isChecked = Config.petPicture(this@PetSettingsActivity)
        }
        val level = Config.petBrightness(this)
        val brightLabel = text("", 14f, Color.WHITE)
        fun label(v: Int) { brightLabel.text = if (v == 0) "Car screen brightness: leave alone" else "Car screen brightness: $v%" }
        label(level)
        val bright = SeekBar(this).apply {
            max = 100
            progress = level
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) = label(p)
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }
        val cpd = CheckBox(this).apply {
            text = "Turn Child Presence Detection off (put back afterwards)"
            setTextColor(Color.WHITE)
            isChecked = Config.petCpdOff(this@PetSettingsActivity)
        }

        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(24)) }
        content.addView(section(text("Air conditioning", 15f, accent, true), temp,
            text("Pet mode re-sends the car's remote climate every few minutes to keep this temperature.", 12f, dim)))
        content.addView(section(text("Safety stop", 15f, accent, true), soc,
            text("At or below this battery level pet mode switches the AC off and warns you. Lower values cool for longer but leave less charge to drive home.", 12f, dim)))
        content.addView(section(text("Car screen", 15f, accent, true), picture, brightLabel, bright,
            text("Shows the picture from Overdrive's Screen Deterrent settings while pet mode runs. Brightness is not put back afterwards.", 12f, dim)))
        content.addView(section(text("Child Presence Detection", 15f, accent, true), cpd,
            text("It would sound the alarm and call you with the pet inside.", 12f, dim)))

        content.addView(Button(this).apply {
            text = "Save"
            setOnClickListener {
                val err = Config.setPet(this@PetSettingsActivity, temp.text.toString(), soc.text.toString(),
                    picture.isChecked, bright.progress, cpd.isChecked)
                if (err != null) Toast.makeText(this@PetSettingsActivity, err, Toast.LENGTH_LONG).show()
                else { Toast.makeText(this@PetSettingsActivity, "Saved - applies on the next check", Toast.LENGTH_SHORT).show(); finish() }
            }
        })
        content.addView(Button(this).apply {
            text = if (PetMode.isActive(this@PetSettingsActivity)) "Turn pet mode off" else "Start pet mode now"
            setOnClickListener {
                if (PetMode.isActive(this@PetSettingsActivity)) PetMode.stop(this@PetSettingsActivity) else PetMode.start(this@PetSettingsActivity)
                finish()
            }
        })
        root.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }
}
