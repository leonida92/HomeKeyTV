package com.homekey.tv.service

import android.content.Context
import android.provider.Settings
import android.util.Log
import dadb.AdbKeyPair
import dadb.Dadb
import java.io.File
import java.net.ConnectException
import java.net.SocketTimeoutException

object LocalAdbManager {
    private const val TAG = "LocalAdbManager"
    const val DEFAULT_PORT = 5555
    const val SERVICE_NAME = "com.homekey.tv/com.homekey.tv.service.RemoteButtonRemapService"
    const val PACKAGE_NAME = "com.homekey.tv"

    sealed class AdbResult {
        object Success : AdbResult()
        data class AdbDisabled(val message: String) : AdbResult()
        data class AuthTimeout(val message: String) : AdbResult()
        data class Error(val message: String) : AdbResult()
    }

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
            val withoutService = removeAccessibilityService(current)
            val merged = mergeAccessibilityServices(withoutService, SERVICE_NAME)

            if (current != null && current.contains("RemoteButtonRemapService")) {
                Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, withoutService)
                try {
                    Thread.sleep(100)
                } catch (_: InterruptedException) {}
            }
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

                // 1. Grant permissions and appops FIRST.
                // On Android 13/14, ACCESS_RESTRICTED_SETTINGS must be allowed BEFORE enabling
                // the accessibility service in Secure Settings, otherwise AccessibilityManagerService
                // blocks and rejects the service.
                try {
                    dadb.shell("appops set $PACKAGE_NAME ACCESS_RESTRICTED_SETTINGS allow")
                    dadb.shell("pm grant $PACKAGE_NAME android.permission.WRITE_SECURE_SETTINGS")
                    dadb.shell("appops set $PACKAGE_NAME REQUEST_INSTALL_PACKAGES allow")
                    dadb.shell("appops set $PACKAGE_NAME SYSTEM_ALERT_WINDOW allow")
                } catch (pe: Exception) {
                    Log.w(TAG, "Permission/appops grant warning: ${pe.message}")
                }

                // 2. Read existing accessibility services
                val current = dadb.shell("settings get secure enabled_accessibility_services").output.trim()
                Log.d(TAG, "Current enabled_accessibility_services: $current")

                val withoutService = removeAccessibilityService(current)
                val merged = mergeAccessibilityServices(withoutService, SERVICE_NAME)

                // 3. If our service was already present, force Android's SettingsProvider ContentObserver
                // to trigger by clearing or setting without our service first.
                if (current.contains("RemoteButtonRemapService")) {
                    if (withoutService.isEmpty()) {
                        dadb.shell("settings delete secure enabled_accessibility_services")
                    } else {
                        dadb.shell("settings put secure enabled_accessibility_services $withoutService")
                    }
                    try {
                        Thread.sleep(150)
                    } catch (_: InterruptedException) {}
                }

                // 4. Write the canonical merged service string and enable accessibility master switch
                Log.d(TAG, "Writing enabled_accessibility_services: $merged")
                dadb.shell("settings put secure enabled_accessibility_services $merged")
                dadb.shell("settings put secure accessibility_enabled 1")

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
