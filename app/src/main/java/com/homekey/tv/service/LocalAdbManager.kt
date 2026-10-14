package com.homekey.tv.service

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.provider.Settings
import android.util.Log
import dadb.AdbKeyPair
import dadb.Dadb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.ConnectException
import java.net.SocketTimeoutException
import kotlin.coroutines.resume

object LocalAdbManager {
    private const val TAG = "LocalAdbManager"
    const val DEFAULT_PORT = 5555
    const val SERVICE_NAME = "com.homekey.tv/com.homekey.tv.service.RemoteButtonRemapService"
    const val PACKAGE_NAME = "com.homekey.tv"

    const val SERVICE_TYPE_TLS_CONNECT = "_adb-tls-connect._tcp"
    const val SERVICE_TYPE_TLS_PAIRING = "_adb-tls-pairing._tcp"
    const val SERVICE_TYPE_LEGACY = "_adb._tcp"

    sealed class AdbResult {
        object Success : AdbResult()
        data class AdbDisabled(val message: String) : AdbResult()
        data class AuthTimeout(val message: String) : AdbResult()
        data class Error(val message: String) : AdbResult()
    }

    data class DiscoveredAdbService(
        val name: String,
        val serviceType: String,
        val host: String,
        val port: Int
    )

    /**
     * Removes any existing entry containing [serviceToRemoveOrSubstring] from the colon-delimited string.
     */
    fun removeAccessibilityService(
        currentServices: String?,
        serviceToRemoveOrSubstring: String = "RemoteButtonRemapService"
    ): String {
        val trimmed = currentServices?.trim() ?: ""
        if (trimmed.isEmpty() || trimmed == "null") {
            return ""
        }
        return trimmed.split(":")
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.contains(serviceToRemoveOrSubstring) }
            .joinToString(":")
    }

    /**
     * Appends [newService] to the colon-delimited [currentServices] string without duplicates,
     * replacing any old or non-canonical formats of the same service.
     */
    fun mergeAccessibilityServices(currentServices: String?, newService: String): String {
        val withoutOld = removeAccessibilityService(currentServices)
        return if (withoutOld.isEmpty()) {
            newService
        } else {
            "$withoutOld:$newService"
        }
    }

    /**
     * Retrieves or generates the ADB RSA key pair in the app's internal files directory.
     */
    fun getOrCreateKeyPair(context: Context): AdbKeyPair {
        val privateKeyFile = File(context.filesDir, "adbkey")
        val publicKeyFile = File(context.filesDir, "adbkey.pub")

        if (!privateKeyFile.exists() || !publicKeyFile.exists()) {
            Log.d(TAG, "Generating new ADB RSA key pair in ${context.filesDir.absolutePath}")
            AdbKeyPair.generate(privateKeyFile, publicKeyFile)
        }

        return AdbKeyPair.read(privateKeyFile, publicKeyFile)
    }

    /**
     * Attempts to configure and enable [RemoteButtonRemapService] directly via [android.content.ContentResolver].
     * Succeeds only if WRITE_SECURE_SETTINGS has already been granted to this app.
     */
    fun tryEnableViaSecureSettings(context: Context): Boolean {
        return try {
            val resolver = context.contentResolver
            val current = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            if (current != null && current.contains("RemoteButtonRemapService")) {
                Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
                Log.d(TAG, "RemoteButtonRemapService already present in Secure Settings")
                return true
            }

            val withoutService = removeAccessibilityService(current)
            val merged = mergeAccessibilityServices(withoutService, SERVICE_NAME)

            Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, merged)
            Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            Log.d(TAG, "Successfully wrote accessibility settings directly via Secure Settings")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Direct Secure Settings write not possible or failed: ${e.message}")
            false
        }
    }

    /**
     * Discovers active dynamic ADB ports published via Network Service Discovery (mDNS).
     * Searches for Android 11+ TLS connect service (_adb-tls-connect._tcp) first,
     * falling back to legacy wireless service (_adb._tcp).
     */
    suspend fun discoverLocalAdbPort(context: Context, timeoutMs: Long = 3000): Int? {
        val tlsPort = discoverNsdServicePort(context, SERVICE_TYPE_TLS_CONNECT, timeoutMs)
        if (tlsPort != null) return tlsPort
        return discoverNsdServicePort(context, SERVICE_TYPE_LEGACY, timeoutMs / 2)
    }

    /**
     * Discovers active Wireless Debugging pairing ports published via mDNS (_adb-tls-pairing._tcp).
     */
    suspend fun discoverPairingPort(context: Context, timeoutMs: Long = 3000): Int? {
        return discoverNsdServicePort(context, SERVICE_TYPE_TLS_PAIRING, timeoutMs)
    }

    private suspend fun discoverNsdServicePort(
        context: Context,
        serviceType: String,
        timeoutMs: Long
    ): Int? {
        val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return null
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val multicastLock = try {
            wifiManager?.createMulticastLock("homekey_mdns")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire MulticastLock: ${e.message}")
            null
        }

        return try {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { continuation ->
                    var isCompleted = false
                    var isResolving = false
                    var discoveryStarted = false

                    var listenerRef: NsdManager.DiscoveryListener? = null
                    val listener = object : NsdManager.DiscoveryListener {
                        override fun onDiscoveryStarted(regType: String) {
                            discoveryStarted = true
                            Log.d(TAG, "NSD discovery started for $regType")
                        }

                        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                            Log.d(TAG, "NSD service found: ${serviceInfo.serviceName}, type: ${serviceInfo.serviceType}")
                            if (isCompleted || isResolving) return
                            isResolving = true

                            val resolveListener = object : NsdManager.ResolveListener {
                                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                                    Log.w(TAG, "NSD resolve failed for ${info.serviceName}: $errorCode")
                                    isResolving = false
                                }

                                override fun onServiceResolved(info: NsdServiceInfo) {
                                    Log.d(TAG, "NSD resolved service: ${info.serviceName} -> port ${info.port}")
                                    if (!isCompleted && continuation.isActive) {
                                        isCompleted = true
                                        try {
                                            listenerRef?.let { if (discoveryStarted) nsdManager.stopServiceDiscovery(it) }
                                        } catch (_: Exception) {}
                                        continuation.resume(info.port)
                                    }
                                }
                            }

                            try {
                                @Suppress("DEPRECATION")
                                nsdManager.resolveService(serviceInfo, resolveListener)
                            } catch (e: Exception) {
                                Log.w(TAG, "NSD resolveService exception: ${e.message}")
                                isResolving = false
                            }
                        }

                        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                            Log.d(TAG, "NSD service lost: ${serviceInfo.serviceName}")
                        }

                        override fun onDiscoveryStopped(serviceType: String) {
                            Log.d(TAG, "NSD discovery stopped for $serviceType")
                        }

                        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                            Log.e(TAG, "NSD start discovery failed: $errorCode")
                            if (!isCompleted && continuation.isActive) {
                                isCompleted = true
                                continuation.resume(null)
                            }
                        }

                        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                            Log.e(TAG, "NSD stop discovery failed: $errorCode")
                        }
                    }
                    listenerRef = listener

                    try {
                        nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
                    } catch (e: Exception) {
                        Log.w(TAG, "NSD discoverServices failed: ${e.message}")
                        if (!isCompleted && continuation.isActive) {
                            isCompleted = true
                            continuation.resume(null)
                        }
                    }

                    continuation.invokeOnCancellation {
                        if (discoveryStarted) {
                            try {
                                nsdManager.stopServiceDiscovery(listener)
                            } catch (_: Exception) {}
                        }
                    }
                }
            }
        } finally {
            try {
                if (multicastLock?.isHeld == true) {
                    multicastLock.release()
                }
            } catch (_: Exception) {}
        }
    }

    /**
     * Connects to local loopback ADB on [host]:[port], executes the necessary settings and
     * permission grants to activate [RemoteButtonRemapService], and closes the connection.
     * On connection refused at port 5555, automatically queries mDNS for dynamic Android 11+ ports.
     */
    fun enableAccessibilityService(
        context: Context,
        host: String = "127.0.0.1",
        port: Int = DEFAULT_PORT,
        onConnecting: () -> Unit = {},
        onStatusUpdate: (String) -> Unit = {}
    ): AdbResult {
        return try {
            val keyPair = getOrCreateKeyPair(context)
            onConnecting()

            var effectivePort = port

            val (dadb, connectedPort) = try {
                Log.d(TAG, "Connecting to ADB at $host:$effectivePort...")
                onStatusUpdate("Connecting to ADB at $host:$effectivePort...")
                Pair(
                    Dadb.create(
                        host = host,
                        port = effectivePort,
                        keyPair = keyPair,
                        connectTimeout = 5000,
                        socketTimeout = 45000
                    ),
                    effectivePort
                )
            } catch (e: ConnectException) {
                if (host == "127.0.0.1" && port == DEFAULT_PORT) {
                    Log.d(TAG, "Port 5555 refused. Checking for Android 11+ dynamic Wireless Debugging port...")
                    onStatusUpdate("Port 5555 closed. Searching for Wireless Debugging port...")
                    val discovered = runBlocking(Dispatchers.IO) {
                        discoverLocalAdbPort(context, 2500)
                    }
                    if (discovered != null && discovered != DEFAULT_PORT) {
                        Log.d(TAG, "Discovered active ADB port $discovered via mDNS. Connecting...")
                        onStatusUpdate("Found Wireless Debugging on port $discovered. Connecting...")
                        Pair(
                            Dadb.create(
                                host = host,
                                port = discovered,
                                keyPair = keyPair,
                                connectTimeout = 5000,
                                socketTimeout = 45000
                            ),
                            discovered
                        )
                    } else {
                        throw e
                    }
                } else {
                    throw e
                }
            }
            effectivePort = connectedPort
            dadb.use { client ->
                Log.d(TAG, "ADB connected successfully to $host:$effectivePort")
                onStatusUpdate("Connected! Configuring system permissions...")

                // 1. Grant permissions and appops FIRST.
                // On Android 13/14, ACCESS_RESTRICTED_SETTINGS must be allowed BEFORE enabling
                // the accessibility service in Secure Settings, otherwise AccessibilityManagerService
                // blocks and rejects the service.
                try {
                    client.shell("appops set $PACKAGE_NAME ACCESS_RESTRICTED_SETTINGS allow")
                    client.shell("pm grant $PACKAGE_NAME android.permission.WRITE_SECURE_SETTINGS")
                    client.shell("appops set $PACKAGE_NAME REQUEST_INSTALL_PACKAGES allow")
                    client.shell("appops set $PACKAGE_NAME SYSTEM_ALERT_WINDOW allow")
                } catch (pe: Exception) {
                    Log.w(TAG, "Permission/appops grant warning: ${pe.message}")
                }

                // 2. Read existing accessibility services
                val current = client.shell("settings get secure enabled_accessibility_services").output.trim()
                Log.d(TAG, "Current enabled_accessibility_services: $current")

                // 3. Write merged service string only if not already registered
                if (!current.contains("RemoteButtonRemapService")) {
                    val withoutService = removeAccessibilityService(current)
                    val merged = mergeAccessibilityServices(withoutService, SERVICE_NAME)
                    Log.d(TAG, "Writing enabled_accessibility_services: $merged")
                    client.shell("settings put secure enabled_accessibility_services $merged")
                }
                client.shell("settings put secure accessibility_enabled 1")

                Log.d(TAG, "ADB accessibility configuration complete")
                AdbResult.Success
            }
        } catch (e: ConnectException) {
            Log.e(TAG, "Connection refused connecting to $host:$port", e)
            AdbResult.AdbDisabled(
                "Cannot connect to $host:$port. On Android 11+, enable 'Wireless debugging' in TV Developer Options or enter the dynamic port."
            )
        } catch (e: SocketTimeoutException) {
            Log.e(TAG, "Connection timed out waiting for authorization", e)
            AdbResult.AuthTimeout(
                "Timed out waiting for authorization. Please accept the 'Allow USB debugging' prompt on your TV screen."
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to execute ADB configuration", e)
            AdbResult.Error(e.message ?: "Unknown ADB error occurred")
        }
    }
}
