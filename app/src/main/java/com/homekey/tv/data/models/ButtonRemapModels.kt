package com.homekey.tv.data.models

import android.graphics.drawable.Drawable
import kotlinx.serialization.Serializable

@Serializable
data class RemapAction(
    val type: String, // "OPEN_DOCK", "TOGGLE_ENTITY", "CALL_SERVICE", "LAUNCH_APP", "SYSTEM_SLEEP", "SYSTEM_SETTINGS", "SELECT_SOURCE", "SEND_BROADCAST", "START_SERVICE", "PASS_THROUGH", "NONE"
    val target: String? = null, // entityId, service (e.g. script.goodnight), packageName (e.g. com.limelight), or intent action (e.g. com.wled.tv.ACTION_TOGGLE_OVERLAY)
    val label: String? = null,
    val extra: String? = null // target input source (e.g. "TV", "HDMI 1") or target package name for explicit intent (e.g. "com.wled.tv")
)

@Serializable
data class ButtonRemapConfig(
    val keyCode: Int,
    val keyName: String,
    val singlePressAction: RemapAction? = null,
    val doublePressAction: RemapAction? = null,
    val longPressAction: RemapAction? = null,
    val appScope: String? = null // null = global (all apps), or specific packageName e.g. "com.limelight"
)

@Serializable
data class PinnedAppConfig(
    val packageName: String,
    val appName: String,
    val order: Int = 0
)

data class InstalledAppInfo(
    val packageName: String,
    val appName: String,
    val icon: Drawable? = null
)
