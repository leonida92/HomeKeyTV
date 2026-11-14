package com.homekey.tv.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.homekey.tv.HATVApplication
import com.homekey.tv.service.AdbAccessibilityProvisioner
import com.homekey.tv.service.RemoteInputCoordinator
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
                val prefs = HATVApplication.preferencesRepository
                val hasRemaps = prefs.buttonRemaps.value.isNotEmpty()
                if (!hasRemaps) {
                    Log.d(TAG, "No button remaps configured, skipping accessibility restoration")
                    return@launch
                }

                val coordinator = RemoteInputCoordinator.getInstance()
                val adbProvisioner = AdbAccessibilityProvisioner.getInstance()

                // 1. Give Android OS time to naturally re-bind the service after package update
                delay(1500)
                if (coordinator.isServiceRunning.value) {
                    Log.d(TAG, "Accessibility service already running after package replace")
                    return@launch
                }

                Log.d(TAG, "Package updated. Checking RemoteButtonRemapService accessibility...")

                // 2. Try direct Secure Settings write first if permission is already granted
                adbProvisioner.tryEnableViaSecureSettings(context)
                delay(800)

                if (coordinator.isServiceRunning.value) {
                    Log.d(TAG, "Accessibility service restored successfully via Secure Settings")
                    return@launch
                }

                // 3. Fallback to silent local ADB loopback if Secure Settings alone did not bind the service
                Log.d(TAG, "Attempting silent ADB restoration...")
                val result = adbProvisioner.enableAccessibilityService(context)
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
