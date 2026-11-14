package com.homekey.tv

import android.content.Context
import com.homekey.tv.service.AdbAccessibilityProvisioner
import com.homekey.tv.service.LocalAdbManager

class FakeAdbAccessibilityProvisioner : AdbAccessibilityProvisioner {
    var secureSettingsResult: Boolean = false
    var adbResult: LocalAdbManager.AdbResult = LocalAdbManager.AdbResult.Success
    var onSecureSettingsAttempt: (() -> Unit)? = null
    var onAdbAttempt: (() -> Unit)? = null

    var secureSettingsCallCount = 0
        private set
    var enableAdbCallCount = 0
        private set
    var lastHost: String? = null
        private set
    var lastPort: Int? = null
        private set
    val capturedStatuses = mutableListOf<String>()

    override fun tryEnableViaSecureSettings(context: Context): Boolean {
        secureSettingsCallCount++
        onSecureSettingsAttempt?.invoke()
        return secureSettingsResult
    }

    override suspend fun enableAccessibilityService(
        context: Context,
        host: String,
        port: Int,
        onConnecting: () -> Unit,
        onStatusUpdate: (String) -> Unit
    ): LocalAdbManager.AdbResult {
        enableAdbCallCount++
        lastHost = host
        lastPort = port
        onConnecting()
        val status = "Connecting to $host:$port..."
        capturedStatuses.add(status)
        onStatusUpdate(status)
        onAdbAttempt?.invoke()
        return adbResult
    }
}
