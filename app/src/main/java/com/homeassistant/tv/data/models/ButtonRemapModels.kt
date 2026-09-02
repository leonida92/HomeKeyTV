package com.homeassistant.tv.data.models

import android.graphics.drawable.Drawable
import kotlinx.serialization.Serializable

@Serializable
data class RemapAction(
    val type: String, // "OPEN_DOCK", "TOGGLE_ENTITY", "CALL_SERVICE", "LAUNCH_APP", "SYSTEM_SLEEP", "SYSTEM_SETTINGS"
    val target: String? = null, // entityId, service (e.g. script.goodnight), or packageName (e.g. com.limelight)
    val label: String? = null
)

@Serializable
data class ButtonRemapConfig(
    val keyCode: Int,
    val keyName: String,
    val singlePressAction: RemapAction? = null,
    val doublePressAction: RemapAction? = null,
    val longPressAction: RemapAction? = null
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
