package com.miaoxiaohu.tvinstall

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.util.Collections

/** LAN address discovery - the TV has to tell the user which URL to open. */
object NetUtil {

    /** Addresses worth showing, best candidate first. */
    fun lanAddresses(): List<String> {
        val scored = ArrayList<Pair<Int, String>>()
        try {
            for (nif in Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp || nif.isLoopback || nif.isVirtual) continue
                for (addr in Collections.list(nif.inetAddresses)) {
                    if (addr is java.net.Inet4Address) {
                        val ip = addr.hostAddress ?: continue
                        if (isVirtual(nif.name, ip)) continue
                        scored.add(priority(nif.name) to ip)
                    }
                }
            }
        } catch (ignored: Exception) {
            // fall through to whatever we managed to collect
        }
        return scored
            .sortedBy { it.first }
            .map { it.second }
            .distinct()
    }

    fun primaryAddress(): String? = lanAddresses().firstOrNull()

    /**
     * Wi-Fi is what a TV normally uses, so prefer it; the interface name is the only
     * reliable hint we have without extra permissions.
     */
    private fun priority(interfaceName: String): Int = when {
        interfaceName.startsWith("wlan") -> 0
        interfaceName.startsWith("eth") -> 1
        interfaceName.startsWith("en") -> 2      // macOS-style names do not occur on Android, harmless
        else -> 5
    }

    /** Docker bridges, hotspot APs and VPN ranges all look like real LANs to the user. */
    private fun isVirtual(interfaceName: String, ip: String): Boolean {
        if (ip.startsWith("127.") || ip.startsWith("169.254.")) return true
        if (ip.startsWith("172.")) {
            val second = ip.substringAfter('.').substringBefore('.').toIntOrNull()
            if (second != null && second in 17..31) return true
        }
        return interfaceName.startsWith("tun") ||
            interfaceName.startsWith("ppp") ||
            interfaceName.startsWith("docker") ||
            interfaceName.startsWith("br-") ||
            interfaceName.startsWith("veth") ||
            interfaceName.startsWith("rmnet") ||
            interfaceName.startsWith("ccmni") ||
            interfaceName.startsWith("ifb")
    }

    fun deviceLabel(context: Context): String {
        Build.MODEL?.let { if (it.isNotBlank()) return it }
        return context.getString(R.string.app_name)
    }

    /** True when the device has any usable network, i.e. it is worth advertising a URL. */
    fun hasNetwork(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            } else {
                @Suppress("DEPRECATION")
                cm.activeNetworkInfo?.isConnected == true
            }
        } catch (ignored: Exception) {
            false
        }
    }

    fun isWifi(context: Context): Boolean = try {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        wm?.connectionInfo?.networkId != -1
    } catch (ignored: Exception) {
        false
    }

    /** Best-effort reachability probe so the TV UI can say "this URL actually answers". */
    fun canReach(host: String, port: Int, timeoutMs: Int = 800): Boolean = try {
        java.net.Socket().use { socket ->
            socket.connect(java.net.InetSocketAddress(host, port), timeoutMs)
            true
        }
    } catch (e: SocketTimeoutException) {
        false
    } catch (ignored: Exception) {
        false
    }
}