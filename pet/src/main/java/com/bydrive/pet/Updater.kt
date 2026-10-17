package com.bydrive.pet

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Self-updater: checks the newest GitHub release and installs BYDrivePet.apk from it (same signing key required). */
object Updater {
    const val RELEASES = "https://github.com/ali577cm/SealDrive/releases/latest"
    const val APK_URL = "$RELEASES/download/BYDrivePet.apk"

    class Check(val code: Int?, val error: String?)

    /** The release page redirects to .../tag/vNNN; the number is the build code. */
    fun check(): Check = try {
        val c = URL(RELEASES).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false
        c.connectTimeout = 6000
        c.readTimeout = 8000
        val code = c.responseCode
        val loc = c.getHeaderField("Location")
        c.disconnect()
        val n = if (code in 300..399 && loc != null) loc.substringAfterLast("/").filter { it.isDigit() }.toIntOrNull() else null
        if (n != null) Check(n, null) else Check(null, "release page HTTP $code")
    } catch (e: Exception) { Check(null, e.javaClass.simpleName) }

    @Suppress("DEPRECATION")
    fun installedCode(ctx: Context): Int {
        val i = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) i.longVersionCode.toInt() else i.versionCode
    }

    fun download(ctx: Context): File? = try {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        val out = File(dir, "BYDrivePet.apk")
        val c = URL(APK_URL).openConnection() as HttpURLConnection
        c.connectTimeout = 10000
        c.readTimeout = 30000
        if (c.responseCode != 200) null else {
            var total = 0L
            c.inputStream.use { i ->
                out.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = i.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > 100L * 1024 * 1024) { out.delete(); return null }
                        o.write(buf, 0, n)
                    }
                }
            }
            if (total < 50_000L) { out.delete(); null } else out
        }
    } catch (_: Exception) { null }

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** The download must be signed with the same key as the installed app, otherwise it is discarded. */
    @Suppress("DEPRECATION")
    fun verify(ctx: Context, apk: File): Boolean {
        if (Build.VERSION.SDK_INT < 28) return true
        return try {
            val pm = ctx.packageManager
            val mine = pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo ?: return false
            val theirs = pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES)?.signingInfo ?: return false
            fun certs(si: android.content.pm.SigningInfo) =
                (if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory).map { sha256(it.toByteArray()) }.toSet()
            certs(mine).intersect(certs(theirs)).isNotEmpty()
        } catch (_: Exception) { false }
    }

    /** Opens the system installer; if installs from this app are not allowed yet, opens that setting instead. */
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
