package com.homekey.tv.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.homekey.tv.data.local.PreferencesManager
import com.homekey.tv.service.LocalAdbManager
import com.homekey.tv.service.RemoteButtonRemapService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class PackageReplacedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val prefs = PreferencesManager.getInstance(context)
                val hasRemaps = prefs.buttonRemaps.value.isNotEmpty()
                if (!hasRemaps) {
                    Log.d(TAG, "No button remaps configured, skipping accessibility restoration")
                    return@launch
                }

                Log.d(TAG, "Package updated. Restoring RemoteButtonRemapService accessibility...")

                // 1. Try direct Secure Settings write first if permission is already granted
                LocalAdbManager.tryEnableViaSecureSettings(context)
                delay(800)

                if (RemoteButtonRemapService.isServiceRunning.value) {
                    Log.d(TAG, "Accessibility service restored successfully via Secure Settings")
                    return@launch
                }

                // 2. Fallback to silent local ADB loopback if Secure Settings alone did not bind the service
                Log.d(TAG, "Attempting silent ADB restoration...")
                val result = LocalAdbManager.enableAccessibilityService(context)
                Log.d(TAG, "ADB restoration completed with result: $result")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restore accessibility service on package replace", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "PackageReplacedReceiver"
    }
}
