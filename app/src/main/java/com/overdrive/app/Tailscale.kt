package com.overdrive.app

import android.net.Uri
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

/** Detects whether Tailscale (needed to reach the car) is connected on this phone. */
object Tailscale {

    /** True when the address is inside Tailscale's range (100.64.0.0/10) or a *.ts.net name. */
    fun isTailscaleAddress(url: String?): Boolean {
        val host = try { Uri.parse(url ?: return false).host } catch (_: Exception) { null } ?: return false
        if (host.endsWith(".ts.net")) return true
        val p = host.split(".")
        if (p.size != 4) return false
        val a = p[0].toIntOrNull()
        val b = p[1].toIntOrNull()
        return a == 100 && b != null && b in 64..127
    }

    /** True when an up network interface carries a 100.64.0.0/10 address (Tailscale's tunnel). Fails open on errors. */
    fun isConnected(): Boolean = try {
        val ifaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList()
        ifaces.any { ni ->
            ni.isUp && !ni.isLoopback && ni.inetAddresses.toList().any { a ->
                val b = a.address
                a is Inet4Address && (b[0].toInt() and 0xFF) == 100 && (b[1].toInt() and 0xC0) == 0x40
            }
        }
    } catch (_: Exception) { true }

    /** Quick TCP probe of the configured address - a second opinion before telling the user Tailscale is off. */
    fun reachable(url: String): Boolean = try {
        val u = Uri.parse(url)
        val port = if (u.port > 0) u.port else if (u.scheme == "https") 443 else 80
        Socket().use { it.connect(InetSocketAddress(u.host, port), 2500); true }
    } catch (_: Exception) { false }
}
