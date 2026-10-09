package com.homekey.tv.util

import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

object NetworkUtils {
    fun getLocalIpAddress(): String? {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
                .filter { !it.isLoopback && it.isUp }
                .sortedWith(compareByDescending { intf ->
                    val name = intf.name.lowercase()
                    when {
                        name.startsWith("eth") -> 3
                        name.startsWith("wlan") -> 2
                        !name.startsWith("dummy") && !name.startsWith("tun") && !name.startsWith("p2p") -> 1
                        else -> 0
                    }
                })
            for (intf in interfaces) {
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }
}
