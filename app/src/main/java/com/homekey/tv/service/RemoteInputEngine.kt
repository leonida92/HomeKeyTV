package com.homekey.tv.service

import com.homekey.tv.data.models.ButtonRemapConfig
import com.homekey.tv.data.models.RemapAction
import kotlinx.coroutines.flow.StateFlow

enum class VolumeDirection {
    UP,
    DOWN
}

sealed interface InputDecision {
    object PassThrough : InputDecision
    object Consume : InputDecision
    data class Execute(val action: RemapAction) : InputDecision
    object HandleBack : InputDecision
    object DismissDock : InputDecision
}

interface RemoteInputEngine {
    fun onKeyEvent(keyCode: Int, action: Int, repeatCount: Int): InputDecision
    fun updateRemaps(remaps: List<ButtonRemapConfig>)
    fun setForegroundPackage(packageName: String?)
    fun setDockShowing(isShowing: Boolean)
    fun setSettingsForeground(isForeground: Boolean)
    fun setLearnMode(isLearnMode: Boolean)
    fun setVolumeFixEnabled(enabled: Boolean)
    val learnedKey: StateFlow<Pair<Int, String>?>
    fun clearLearnedKey()

    companion object {
        const val ACTION_DOWN = 0
        const val ACTION_UP = 1

        const val KEYCODE_HOME = 3
        const val KEYCODE_BACK = 4
        const val KEYCODE_DPAD_UP = 19
        const val KEYCODE_DPAD_DOWN = 20
        const val KEYCODE_DPAD_LEFT = 21
        const val KEYCODE_DPAD_RIGHT = 22
        const val KEYCODE_DPAD_CENTER = 23
        const val KEYCODE_VOLUME_UP = 24
        const val KEYCODE_VOLUME_DOWN = 25
        const val KEYCODE_POWER = 26
        const val KEYCODE_ENTER = 66
        const val KEYCODE_MUTE = 91
        const val KEYCODE_ESCAPE = 111
        const val KEYCODE_NUMPAD_ENTER = 160
        const val KEYCODE_VOLUME_MUTE = 164
        const val KEYCODE_APP_SWITCH = 187
        const val KEYCODE_SLEEP = 223
        const val KEYCODE_PAIRING = 225
        const val KEYCODE_STAR_ALIAS = 313

        const val SINGLE_PRESS_DEBOUNCE_MS = 250L
        const val MULTI_PRESS_DEBOUNCE_MS = 80L
        const val DOUBLE_PRESS_TIMEOUT_MS = 280L
        const val LONG_PRESS_TIMEOUT_MS = 450L
        const val VOLUME_REPEAT_INITIAL_DELAY_MS = 300L
        const val VOLUME_REPEAT_INTERVAL_MS = 90L
        const val VOLUME_MAX_DURATION_MS = 8000L
    }
}
