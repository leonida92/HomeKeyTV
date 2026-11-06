package com.homekey.tv.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.homekey.tv.data.api.HomeAssistantRepository
import com.homekey.tv.data.local.PreferencesRepository
import com.homekey.tv.data.models.RemapAction

class RemapActionDispatcher(
    private val prefs: PreferencesRepository,
    private val haRepository: HomeAssistantRepository,
    private val dockController: DockOverlayController,
    private val globalActionExecutor: ((Int) -> Boolean)? = null
) {
    private val tag = "RemapActionDispatcher"

    fun execute(action: RemapAction?, context: Context) {
        if (action == null) return
        Log.d(tag, "Executing Remap Action: ${action.type} -> ${action.target}")

        when (action.type) {
            "PASS_THROUGH" -> {
                Log.d(tag, "Pass-through action: native event dispatch preserved")
            }

            "OPEN_DOCK" -> {
                dockController.toggle()
            }

            "TOGGLE_ENTITY" -> {
                action.target?.let { entityId ->
                    haRepository.toggleEntity(entityId)
                }
            }

            "CALL_SERVICE" -> {
                action.target?.let { serviceKey ->
                    val domain = serviceKey.substringBefore(".", "homeassistant")
                    val service = serviceKey.substringAfter(".", serviceKey)
                    haRepository.callService(domain, service)
                }
            }

            "LAUNCH_APP" -> {
                action.target?.let { pkgName ->
                    val launchIntent = context.packageManager?.getLaunchIntentForPackage(pkgName)
                    if (launchIntent != null) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(launchIntent)
                    }
                }
            }

            "SYSTEM_SLEEP" -> {
                val lockAction = AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
                val powerAction = AccessibilityService.GLOBAL_ACTION_POWER_DIALOG

                val lockHandled = globalActionExecutor?.invoke(lockAction)
                val locked = if (lockHandled == true) {
                    true
                } else if (context is AccessibilityService && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    context.performGlobalAction(lockAction)
                } else {
                    false
                }

                if (!locked) {
                    val powerHandled = globalActionExecutor?.invoke(powerAction)
                    if (powerHandled != true && context is AccessibilityService) {
                        context.performGlobalAction(powerAction)
                    }
                }
            }

            "SYSTEM_SETTINGS" -> {
                try {
                    val intent = Intent(android.provider.Settings.ACTION_SETTINGS).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                } catch (e: Exception) {
                    Log.e(tag, "Failed to launch settings", e)
                }
            }

            "SELECT_SOURCE" -> {
                val entityId = action.target
                val source = action.extra
                if (entityId != null && source != null) {
                    haRepository.selectSource(entityId, source)
                }
            }

            "SEND_BROADCAST" -> {
                action.target?.let { intentAction ->
                    try {
                        val intent = Intent(intentAction).applyTarget(action.extra)
                        context.sendBroadcast(intent)
                        Log.d(tag, "Sent broadcast intent: $intentAction (target=${action.extra})")
                    } catch (e: Exception) {
                        Log.e(tag, "Failed to send broadcast intent: $intentAction", e)
                    }
                }
            }

            "START_SERVICE" -> {
                action.target?.let { intentAction ->
                    try {
                        val intent = Intent(intentAction).applyTarget(action.extra)
                        try {
                            context.startService(intent)
                            Log.d(tag, "Started service intent: $intentAction (target=${action.extra})")
                        } catch (ise: IllegalStateException) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                context.startForegroundService(intent)
                            } else {
                                context.startService(intent)
                            }
                            Log.d(tag, "Started foreground service intent: $intentAction (target=${action.extra})")
                        }
                    } catch (e: Exception) {
                        Log.e(tag, "Failed to start service intent: $intentAction", e)
                    }
                }
            }
        }
    }

    private fun Intent.applyTarget(extra: String?): Intent {
        val cleanExtra = extra?.ifBlank { null } ?: return this
        if (cleanExtra.contains("/")) {
            val pkg = cleanExtra.substringBefore("/")
            val cls = cleanExtra.substringAfter("/").let { if (it.startsWith(".")) "$pkg$it" else it }
            component = android.content.ComponentName(pkg, cls)
        } else {
            `package` = cleanExtra
        }
        return this
    }
}
