package com.homekey.tv

import android.graphics.Bitmap
import com.homekey.tv.util.InstalledAppsProvider
import com.homekey.tv.util.InstalledAppsSnapshot
import com.homekey.tv.util.SetupQrProvider

class FakeInstalledAppsProvider(
    var snapshot: InstalledAppsSnapshot = InstalledAppsSnapshot()
) : InstalledAppsProvider {

    var loadCallCount = 0
        private set

    override suspend fun loadInstalledApps(): InstalledAppsSnapshot {
        loadCallCount++
        return snapshot
    }
}

class FakeSetupQrProvider(
    var localIpAddress: String? = "192.168.1.50",
    var qrBitmap: Bitmap? = null
) : SetupQrProvider {

    var ipCallCount = 0
        private set
    var qrCallCount = 0
        private set
    var lastGeneratedContent: String? = null
        private set
    var lastGeneratedSize: Int? = null
        private set

    override suspend fun getLocalIpAddress(): String? {
        ipCallCount++
        return localIpAddress
    }

    override suspend fun generateQrCode(content: String, size: Int): Bitmap? {
        qrCallCount++
        lastGeneratedContent = content
        lastGeneratedSize = size
        return qrBitmap
    }
}
