package com.overdrive.app

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.text.InputType
import android.widget.EditText
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Toast
import kotlin.concurrent.thread
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.content.res.Configuration
import android.webkit.*

class MainActivity : Activity() {

    private var url: String? = null
    private var failCount = 0
    private var lastCheck = 0L
    private var updateDialogShowing = false
    private lateinit var web: WebView
    private val handler = Handler(Looper.getMainLooper())
    private var failed = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        url = Config.url(this)
        web = WebView(this).apply {
            setBackgroundColor(0xFF000000.toInt())
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                mediaPlaybackRequiresUserGesture = false
                allowFileAccess = false
                allowContentAccess = false
                javaScriptCanOpenWindowsAutomatically = false
                cacheMode = WebSettings.LOAD_DEFAULT
                loadWithOverviewMode = true
                useWideViewPort = true
                setSupportZoom(false)
                builtInZoomControls = false
            }
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(v: WebView, u: String?, f: Bitmap?) {
                    // the inline error page is a data: URL; don't let it cancel the pending retry
                    if (u?.startsWith("data:") != true) failed = false
                }
                override fun onPageFinished(v: WebView, u: String?) {
                    if (u?.startsWith("data:") != true && !failed) failCount = 0
                }
                override fun onReceivedError(v: WebView, r: WebResourceRequest, e: WebResourceError) {
                    // Only retry when the main page fails (e.g. Tailscale not up yet)
                    if (r.isForMainFrame) {
                        failed = true
                        // after a few failures, offer to fix the address (once per streak)
                        if (++failCount == 3) askAddress()
                        v.loadData(errorHtml(e.description?.toString() ?: "Connection failed"), "text/html", "utf-8")
                        handler.postDelayed({ if (failed) this@MainActivity.url?.let { v.loadUrl(it) } }, 5000)
                    }
                }
                // Only the configured Overdrive host may load in the app; anything else opens in the browser
                override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                    val home = this@MainActivity.url?.let { android.net.Uri.parse(it).host }
                    if (r.url.scheme == "data" || (home != null && r.url.host == home)) return false
                    try { startActivity(Intent(Intent.ACTION_VIEW, r.url)) } catch (_: Exception) {}
                    return true
                }
            }
            webChromeClient = WebChromeClient()
            // long-press on the error screen to change the address
            setOnLongClickListener { if (failed) { askAddress(); true } else false }
        }
        setContentView(web)
        if (savedInstanceState != null) web.restoreState(savedInstanceState) else {
            val u = url
            if (u == null) askAddress() else web.loadUrl(u)
        }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /** Launcher shortcut "Settings & updates" opens the address / update dialog. */
    private fun handleIntent(i: Intent?) {
        if (i?.action == "com.overdrive.app.SETTINGS") askAddress()
    }

    /** Prompts for the Overdrive address and stores it on the device only. */
    private fun askAddress() {
        if (isFinishing) return
        val input = EditText(this).apply {
            hint = "http://100.x.x.x:8080"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(url ?: "")
            setSingleLine()
        }
        val stdInput = EditText(this).apply {
            hint = "Standard price (pence per kWh)"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(Config.tariff(this@MainActivity).stdPence.toString().removeSuffix(".0"))
            setSingleLine()
        }
        val cheapInput = EditText(this).apply {
            hint = "Cheap-rate price (pence per kWh)"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(Config.tariff(this@MainActivity).cheapPence.toString().removeSuffix(".0"))
            setSingleLine()
        }
        val windowInput = EditText(this).apply {
            hint = "Cheap-rate window (HH:MM-HH:MM)"
            setText(Config.windowText(this@MainActivity))
            setSingleLine()
        }
        val tempInput = EditText(this).apply {
            hint = "Climate button temperature (°C)"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(Config.climateTemp(this@MainActivity).toString().removeSuffix(".0"))
            setSingleLine()
        }
        val minInput = EditText(this).apply {
            hint = "Climate run time (10, 15, 20, 25 or 30 min)"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(Config.climateMinutes(this@MainActivity).toString())
            setSingleLine()
        }
        val bgSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                arrayOf("Widget background: Transparent", "Widget background: Glass", "Widget background: Solid"))
            setSelection(Config.bg(this@MainActivity).coerceIn(0, 2))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, 0, pad, 0)
            addView(input)
            addView(stdInput)
            addView(cheapInput)
            addView(windowInput)
            addView(tempInput)
            addView(minInput)
            addView(bgSpinner)
        }
        val d = AlertDialog.Builder(this)
            .setTitle("Overdrive address")
            .setMessage("Your Overdrive address, electricity tariff (standard and cheap-rate prices and window) and climate button settings. All are stored only on this phone.")
            .setView(android.widget.ScrollView(this).apply { addView(box) })
            .setCancelable(url != null)
            .setPositiveButton("Save") { _, _ ->
                val saved = Config.setUrl(this, input.text.toString())
                if (saved == null) {
                    Toast.makeText(this, "That doesn't look like a valid address", Toast.LENGTH_LONG).show()
                    askAddress()
                } else {
                    Config.setBg(this, bgSpinner.selectedItemPosition)
                    if (!Config.setClimateTemp(this, tempInput.text.toString()))
                        Toast.makeText(this, "Climate temperature not saved - use 17 to 33", Toast.LENGTH_LONG).show()
                    if (!Config.setClimateMinutes(this, minInput.text.toString()))
                        Toast.makeText(this, "Run time not saved - use 10, 15, 20, 25 or 30", Toast.LENGTH_LONG).show()
                    SealDriveClimateWidget.redraw(this)
                    Config.setTariff(this, stdInput.text.toString(), cheapInput.text.toString(), windowInput.text.toString())
                        ?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
                    url = saved
                    failed = false
                    failCount = 0
                    web.loadUrl(saved)
                    if (SealDriveWidget.hasWidgets(this)) thread { SealDriveWidget.refreshNow(this) }
                }
            }
        if (url != null) d.setNegativeButton("Cancel", null)
        d.setNeutralButton("Check for updates") { _, _ -> checkForUpdate(manual = true) }
        d.show()
    }

    /** Looks for a newer release. [manual] = user asked, so report "up to date" and failures too. */
    private fun checkForUpdate(manual: Boolean = false) {
        lastCheck = System.currentTimeMillis()
        thread {
            val res = Updater.check()
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                val r = res.release
                when {
                    r == null -> if (manual) Toast.makeText(this, "Update check failed: ${res.error}", Toast.LENGTH_LONG).show()
                    r.code <= Updater.installedCode(this) ->
                        if (manual) Toast.makeText(this, "SealDrive is up to date (build ${Updater.installedCode(this)})", Toast.LENGTH_LONG).show()
                    else -> showUpdateDialog(r)
                }
            }
        }
    }

    private fun showUpdateDialog(r: Updater.Release) {
        if (updateDialogShowing) return
        updateDialogShowing = true
        AlertDialog.Builder(this)
            .setTitle("Update available")
            .setMessage("A new version of SealDrive (build ${r.code}) is ready to install.")
            .setPositiveButton("Update") { _, _ ->
                Toast.makeText(this, "Downloading update…", Toast.LENGTH_SHORT).show()
                thread {
                    val apk = Updater.download(this, r)
                    runOnUiThread {
                        if (apk == null) {
                            Toast.makeText(this, "Download failed - opening the download page", Toast.LENGTH_LONG).show()
                            openReleasePage()
                        } else if (!Updater.install(this, apk)) {
                            Toast.makeText(this, "Allow installs from SealDrive, then use Check for updates again", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            .setNeutralButton("Browser") { _, _ -> openReleasePage() }
            .setNegativeButton("Later", null)
            .setOnDismissListener { updateDialogShowing = false }
            .show()
    }

    private fun openReleasePage() {
        try { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(Updater.RELEASES))) } catch (_: Exception) {}
    }

    private fun errorHtml(msg: String): String {
        val href = url ?: ""
        return """
        <body style="background:#000;color:#ccc;font-family:sans-serif;display:flex;align-items:center;justify-content:center;height:100vh;margin:0;text-align:center">
        <div><h2>Can't reach Overdrive</h2><p>$msg</p><p>Check that Tailscale/Overdrive is running.<br>Retrying every 5 seconds…<br><small>Long-press this screen to change the address.</small></p><p><button onclick="location.href='$href'" style="font-size:18px;padding:12px 28px;border:0;border-radius:8px;background:#2BD4A7;color:#000">Reconnect</button></p></div></body>"""
    }

    private fun hideSystemUi() {
        // Phone: show the status bar in portrait, go fully immersive in landscape
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (!landscape) View.SYSTEM_UI_FLAG_VISIBLE else (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    override fun onConfigurationChanged(c: Configuration) { super.onConfigurationChanged(c); hideSystemUi() }

    override fun onResume() {
        super.onResume()
        web.onResume()
        if (failed) url?.let { web.loadUrl(it) }
        if (System.currentTimeMillis() - lastCheck > 6 * 3600_000L) checkForUpdate()
        // keep the widget fresh whenever the app is opened, and make sure its background refresh is scheduled
        if (SealDriveWidget.hasWidgets(this)) {
            SealDriveWidget.scheduleJob(this)
            thread { SealDriveWidget.refreshNow(this) }
        }
    }
    override fun onPause() { web.onPause(); CookieManager.getInstance().flush(); super.onPause() }
    override fun onSaveInstanceState(out: Bundle) { super.onSaveInstanceState(out); web.saveState(out) }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (web.canGoBack()) web.goBack() else super.onBackPressed() }

    override fun onDestroy() { handler.removeCallbacksAndMessages(null); web.destroy(); super.onDestroy() }
}
