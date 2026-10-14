package com.overdrive.app

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.text.InputType
import android.widget.EditText
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
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

class MainActivity : Activity() {

    private var url: String? = null
    private var failCount = 0
    private lateinit var web: WebView
    private lateinit var refresh: SwipeRefreshLayout
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
                    refresh.isRefreshing = false
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
        refresh = SwipeRefreshLayout(this).apply {
            addView(web)
            setOnRefreshListener { web.reload() }
            // only allow pull-down when the page is scrolled to the top
            setOnChildScrollUpCallback { _, _ -> web.scrollY > 0 }
        }
        setContentView(refresh)
        if (savedInstanceState != null) web.restoreState(savedInstanceState) else {
            val u = url
            if (u == null) askAddress() else web.loadUrl(u)
            checkForUpdate()
        }
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
        val d = AlertDialog.Builder(this)
            .setTitle("Overdrive address")
            .setMessage("Enter the address of your Overdrive page. It is stored only on this phone.")
            .setView(input)
            .setCancelable(url != null)
            .setPositiveButton("Save") { _, _ ->
                val saved = Config.setUrl(this, input.text.toString())
                if (saved == null) {
                    Toast.makeText(this, "That doesn't look like a valid address", Toast.LENGTH_LONG).show()
                    askAddress()
                } else {
                    url = saved
                    failed = false
                    failCount = 0
                    web.loadUrl(saved)
                }
            }
        if (url != null) d.setNegativeButton("Cancel", null)
        d.show()
    }

    private fun checkForUpdate() = thread {
        val r = Updater.fetchLatest() ?: return@thread
        if (r.code <= Updater.installedCode(this)) return@thread
        runOnUiThread {
            if (isFinishing) return@runOnUiThread
            AlertDialog.Builder(this)
                .setTitle("Update available")
                .setMessage("A new version of SealDrive is ready to install.")
                .setPositiveButton("Update") { _, _ ->
                    Toast.makeText(this, "Downloading update…", Toast.LENGTH_SHORT).show()
                    thread {
                        val apk = Updater.download(this, r)
                        runOnUiThread {
                            if (apk == null) Toast.makeText(this, "Download failed", Toast.LENGTH_LONG).show()
                            else if (!Updater.install(this, apk))
                                Toast.makeText(this, "Allow installs from this app, then tap Update again next launch", Toast.LENGTH_LONG).show()
                        }
                    }
                }
                .setNegativeButton("Later", null)
                .show()
        }
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

    override fun onResume() { super.onResume(); web.onResume(); if (failed) url?.let { web.loadUrl(it) } }
    override fun onPause() { web.onPause(); CookieManager.getInstance().flush(); super.onPause() }
    override fun onSaveInstanceState(out: Bundle) { super.onSaveInstanceState(out); web.saveState(out) }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (web.canGoBack()) web.goBack() else super.onBackPressed() }

    override fun onDestroy() { handler.removeCallbacksAndMessages(null); web.destroy(); super.onDestroy() }
}
