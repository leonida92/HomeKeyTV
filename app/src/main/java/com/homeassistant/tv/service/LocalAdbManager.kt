package com.homeassistant.tv.service

import android.content.Context
import android.util.Log
import dadb.AdbKeyPair
import dadb.Dadb
import java.io.File
import java.net.ConnectException
import java.net.SocketTimeoutException

object LocalAdbManager {
    private const val TAG = "LocalAdbManager"
    const val DEFAULT_PORT = 5555
    const val SERVICE_NAME = "com.homeassistant.tv/.service.RemoteButtonRemapService"
    const val PACKAGE_NAME = "com.homeassistant.tv"

    sealed class AdbResult {
        object Success : AdbResult()
        data class AdbDisabled(val message: String) : AdbResult()
        data class AuthTimeout(val message: String) : AdbResult()
        data class Error(val message: String) : AdbResult()
    }

    /**
     * Appends [newService] to the colon-delimited [currentServices] string without duplicates.
     */
    fun mergeAccessibilityServices(currentServices: String?, newService: String): String {
        val trimmed = currentServices?.trim() ?: ""
        if (trimmed.isEmpty() || trimmed == "null") {
            return newService
        }
        val services = trimmed.split(":").map { it.trim() }.filter { it.isNotBlank() }
        if (services.contains(newService)) {
            return trimmed
        }
        return "$trimmed:$newService"
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
     * Connects to local loopback ADB on [host]:[port], executes the necessary settings and
     * permission grants to activate [RemoteButtonRemapService], and closes the connection.
     */
    fun enableAccessibilityService(
        context: Context,
        host: String = "127.0.0.1",
        port: Int = DEFAULT_PORT,
        onConnecting: () -> Unit = {}
    ): AdbResult {
        return try {
            val keyPair = getOrCreateKeyPair(context)
            onConnecting()

            Log.d(TAG, "Connecting to ADB at $host:$port...")
            // socketTimeout = 45000 ms gives user time to click 'Always allow' on TV screen if prompted
            Dadb.create(
                host = host,
                port = port,
                keyPair = keyPair,
                connectTimeout = 5000,
                socketTimeout = 45000
            ).use { dadb ->
                Log.d(TAG, "ADB connected successfully to $host:$port")

                // 1. Read existing accessibility services
                val current = dadb.shell("settings get secure enabled_accessibility_services").output.trim()
                Log.d(TAG, "Current enabled_accessibility_services: $current")

                // 2. Merge our service into the colon-separated list
                val merged = mergeAccessibilityServices(current, SERVICE_NAME)
                Log.d(TAG, "Writing enabled_accessibility_services: $merged")
                dadb.shell("settings put secure enabled_accessibility_services $merged")

                // 3. Turn on master accessibility switch
                dadb.shell("settings put secure accessibility_enabled 1")

                // 4. Grant overlay, secure settings, and package installation permissions
                try {
                    dadb.shell("pm grant $PACKAGE_NAME android.permission.SYSTEM_ALERT_WINDOW")
                    dadb.shell("pm grant $PACKAGE_NAME android.permission.WRITE_SECURE_SETTINGS")
                    dadb.shell("appops set $PACKAGE_NAME REQUEST_INSTALL_PACKAGES allow")
                } catch (pe: Exception) {
                    Log.w(TAG, "Optional permission grant warning: ${pe.message}")
                }

                Log.d(TAG, "ADB accessibility configuration complete")
                AdbResult.Success
            }
        } catch (e: ConnectException) {
            Log.e(TAG, "Connection refused connecting to $host:$port", e)
            AdbResult.AdbDisabled(
                "Cannot connect to $host:$port. Please verify that 'ADB Debugging' is enabled in TV Settings > Developer Options."
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
