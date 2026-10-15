package com.overdrive.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Self-updater: checks the latest GitHub Release and installs its APK. */
object Updater {
    private const val API = "https://api.github.com/repos/ali577cm/sealdrive/releases/latest"

    class Release(val code: Int, val apkUrl: String)

    /** Only ever download updates over https from GitHub's own hosts. */
    private fun trusted(u: String): Boolean {
        val uri = Uri.parse(u)
        val h = uri.host ?: return false
        return uri.scheme == "https" && (h == "github.com" || h.endsWith(".githubusercontent.com"))
    }

    const val RELEASES = "https://github.com/ali577cm/SealDrive/releases/latest"
    private const val APK_URL = "$RELEASES/download/BYDrive.apk"

    class Check(val release: Release?, val error: String?)

    /**
     * Finds the newest release. First tries the plain release page (its redirect names the tag; no API rate limit),
     * then falls back to the GitHub API. On failure, [Check.error] says why.
     */
    fun check(): Check {
        var err = "unknown error"
        try {
            val c = URL(RELEASES).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 6000
            c.readTimeout = 8000
            val code = c.responseCode
            val loc = c.getHeaderField("Location")
            c.disconnect()
            if (code in 300..399 && loc != null) {
                val n = loc.substringAfterLast("/").filter { it.isDigit() }.toIntOrNull()
                if (n != null) return Check(Release(n, APK_URL), null)
                err = "unexpected redirect"
            } else err = "release page HTTP $code"
        } catch (e: Exception) { err = "${e.javaClass.simpleName}: ${e.message ?: ""}" }

        try {
            val c = URL(API).openConnection() as HttpURLConnection
            c.connectTimeout = 6000
            c.readTimeout = 8000
            c.setRequestProperty("Accept", "application/vnd.github+json")
            if (c.responseCode == 200) {
                val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                val code = j.getString("tag_name").filter { it.isDigit() }.toIntOrNull()
                val assets = j.getJSONArray("assets")
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (code != null && a.getString("name").endsWith(".apk") && trusted(a.getString("browser_download_url")))
                        return Check(Release(code, a.getString("browser_download_url")), null)
                }
            } else err += "; API HTTP ${c.responseCode}"
        } catch (e: Exception) { err += "; API ${e.javaClass.simpleName}" }
        return Check(null, err)
    }

    @Suppress("DEPRECATION")
    fun installedCode(ctx: Context): Int {
        val i = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) i.longVersionCode.toInt() else i.versionCode
    }

    fun download(ctx: Context, r: Release): File? {
        try {
            val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
            val out = File(dir, "BYDrive.apk")
            val c = URL(r.apkUrl).openConnection() as HttpURLConnection
            c.connectTimeout = 10000
            c.readTimeout = 30000
            if (c.responseCode != 200) return null
            c.inputStream.use { i -> out.outputStream().use { o -> i.copyTo(o) } }
            return out
        } catch (_: Exception) { return null }
    }

    /** Opens the system installer. If "install unknown apps" isn't granted yet, opens that setting instead. */
    fun install(ctx: Context, apk: File): Boolean {
        if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return false
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apk)
        ctx.startActivity(Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }
}
