package com.homekey.tv.service

import com.homekey.tv.data.models.ButtonRemapConfig
import com.homekey.tv.data.models.RemapAction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class DefaultRemoteInputEngine(
    private val scheduler: TimeScheduler,
    private val keyNameResolver: (Int) -> String = { "KEY_$it" },
    private val onVolumeAdjust: (VolumeDirection) -> Unit = {},
    private val onAction: (RemapAction) -> Unit
) : RemoteInputEngine {

    private class KeyState {
        var lastDownTime: Long = 0L
        var isWaitingForDouble: Boolean = false
        var isDoublePressTriggered: Boolean = false
        var isLongPressTriggered: Boolean = false
    }

    private val keyStates = mutableMapOf<Int, KeyState>()
    private var globalRemaps = mutableMapOf<Int, ButtonRemapConfig>()
    private var scopedRemaps = mutableMapOf<String, MutableMap<Int, ButtonRemapConfig>>()

    private var currentPackage: String? = null
    private var isDockShowing: Boolean = false
    private var isSettingsForeground: Boolean = false
    private var isLearnMode: Boolean = false
    private var isVolumeFixEnabled: Boolean = false

    private val _learnedKey = MutableStateFlow<Pair<Int, String>?>(null)
    override val learnedKey: StateFlow<Pair<Int, String>?> = _learnedKey.asStateFlow()

    // Tracks a key captured on ACTION_DOWN in Learn Mode so its trailing ACTION_UP is consumed
    // even if the UI immediately disables Learn Mode before the user releases the button.
    private var pendingLearnedKeyUp: Int? = null

    private var volumeRepeatToken: Any? = null
    private var volumeRepeatStartTime: Long = 0L

    override fun updateRemaps(remaps: List<ButtonRemapConfig>) {
        val newGlobal = mutableMapOf<Int, ButtonRemapConfig>()
        val newScoped = mutableMapOf<String, MutableMap<Int, ButtonRemapConfig>>()

        fun targetMapFor(config: ButtonRemapConfig): MutableMap<Int, ButtonRemapConfig> {
            val scope = config.appScope?.trim()?.ifEmpty { null }
            return if (scope != null) {
                newScoped.getOrPut(scope) { mutableMapOf() }
            } else {
                newGlobal
            }
        }

        // Populate explicit bindings first so they always take precedence over inferred OEM aliases.
        for (config in remaps) {
            targetMapFor(config)[config.keyCode] = config
        }
        for (config in remaps) {
            val map = targetMapFor(config)
            for (alias in RemoteInputEngine.aliasesFor(config.keyCode)) {
                if (!map.containsKey(alias)) {
                    map[alias] = config
                }
            }
        }
        this.globalRemaps = newGlobal
        this.scopedRemaps = newScoped
    }

    override fun setForegroundPackage(packageName: String?) {
        this.currentPackage = packageName
    }

    override fun setDockShowing(isShowing: Boolean) {
        this.isDockShowing = isShowing
    }

    override fun setSettingsForeground(isForeground: Boolean) {
        this.isSettingsForeground = isForeground
    }

    override fun setLearnMode(isLearnMode: Boolean) {
        this.isLearnMode = isLearnMode
        if (isLearnMode) {
            pendingLearnedKeyUp = null
            _learnedKey.update { null }
        }
    }

    override fun setVolumeFixEnabled(enabled: Boolean) {
        this.isVolumeFixEnabled = enabled
        if (!enabled) {
            cancelVolumeRepeat()
        }
    }

    override fun clearLearnedKey() {
        _learnedKey.update { null }
    }

    private fun cancelVolumeRepeat() {
        volumeRepeatToken?.let { scheduler.cancel(it) }
        volumeRepeatToken = null
    }

    private fun findRemapConfig(keyCode: Int): ButtonRemapConfig? {
        val pkg = currentPackage
        if (pkg != null) {
            val scoped = scopedRemaps[pkg]?.get(keyCode)
            if (scoped != null) return scoped
        }
        return globalRemaps[keyCode]
    }

    private fun handleUnmappedVolumeFix(keyCode: Int, action: Int, repeatCount: Int): InputDecision {
        if (keyCode == RemoteInputEngine.KEYCODE_VOLUME_UP || keyCode == RemoteInputEngine.KEYCODE_VOLUME_DOWN) {
            val direction = if (keyCode == RemoteInputEngine.KEYCODE_VOLUME_UP) VolumeDirection.UP else VolumeDirection.DOWN
            if (action == RemoteInputEngine.ACTION_DOWN) {
                if (repeatCount > 0) {
                    cancelVolumeRepeat()
                    return InputDecision.PassThrough
                }
                cancelVolumeRepeat()
                val token = "volume_repeat_$keyCode"
                volumeRepeatToken = token
                volumeRepeatStartTime = scheduler.currentTimeMs()

                fun scheduleNextRepeat() {
                    scheduler.schedule(RemoteInputEngine.VOLUME_REPEAT_INTERVAL_MS, token) {
                        if (scheduler.currentTimeMs() - volumeRepeatStartTime <= RemoteInputEngine.VOLUME_MAX_DURATION_MS) {
                            onVolumeAdjust(direction)
                            scheduleNextRepeat()
                        }
                    }
                }
                scheduler.schedule(RemoteInputEngine.VOLUME_REPEAT_INITIAL_DELAY_MS, token) {
                    onVolumeAdjust(direction)
                    scheduleNextRepeat()
                }
                return InputDecision.PassThrough
            } else if (action == RemoteInputEngine.ACTION_UP) {
                cancelVolumeRepeat()
                return InputDecision.PassThrough
            }
        }
        return InputDecision.PassThrough
    }

    override fun onKeyEvent(keyCode: Int, action: Int, repeatCount: Int): InputDecision {
        // Consume trailing repeat/release events of a key just captured in Learn Mode
        // even after UI stops Learn Mode upon ACTION_DOWN.
        if (!isLearnMode && pendingLearnedKeyUp == keyCode) {
            if (action == RemoteInputEngine.ACTION_DOWN && repeatCount == 0) {
                pendingLearnedKeyUp = null
            } else {
                if (action == RemoteInputEngine.ACTION_UP) {
                    pendingLearnedKeyUp = null
                }
                return InputDecision.Consume
            }
        }

        if (isLearnMode) {
            if (keyCode in NAVIGATION_AND_SYSTEM_KEYS) {
                return InputDecision.PassThrough
            }
            if (action == RemoteInputEngine.ACTION_DOWN && repeatCount == 0) {
                pendingLearnedKeyUp = keyCode
                val name = keyNameResolver(keyCode)
                _learnedKey.update { Pair(keyCode, name) }
            }
            return InputDecision.Consume
        }

        // Volume Buttons Fix
        val isVolumeKey = keyCode in VOLUME_KEYS
        if (isVolumeKey) {
            val mapped = findRemapConfig(keyCode)
            if (mapped == null || mapped.isPassThroughOnly) {
                return if (isVolumeFixEnabled) {
                    handleUnmappedVolumeFix(keyCode, action, repeatCount)
                } else {
                    InputDecision.PassThrough
                }
            }
        }

        // Fast-path bypass when dock is closed and settings is not foreground
        if (!isDockShowing && !isSettingsForeground && keyCode in NAVIGATION_AND_SYSTEM_KEYS) {
            return InputDecision.PassThrough
        }

        // Active Dock Navigation
        if (isDockShowing) {
            val mappedConfig = findRemapConfig(keyCode)
            val isDockToggleKey = mappedConfig?.isDockToggle == true

            if (!isDockToggleKey) {
                if (keyCode == RemoteInputEngine.KEYCODE_BACK || keyCode == RemoteInputEngine.KEYCODE_ESCAPE) {
                    return if (action == RemoteInputEngine.ACTION_UP) InputDecision.HandleBack else InputDecision.Consume
                }
                if (keyCode == RemoteInputEngine.KEYCODE_HOME) {
                    return InputDecision.DismissDock
                }
                if (keyCode in DOCK_NAV_KEYS) {
                    return InputDecision.PassThrough
                }
            }
        }

        // Check if key is mapped
        val config = findRemapConfig(keyCode)
        if (config == null || config.isPassThroughOnly) {
            if (isSettingsForeground) {
                return if (keyCode in NAVIGATION_AND_SYSTEM_KEYS) InputDecision.PassThrough else InputDecision.Consume
            }
            return InputDecision.PassThrough
        }

        // In Settings, navigation keys still control the UI unless explicitly overridden;
        // mapped non-navigation shortcut keys execute normally so users can test them right away.
        if (isSettingsForeground && keyCode in NAVIGATION_AND_SYSTEM_KEYS) {
            return InputDecision.PassThrough
        }

        val isSingleActionOnly = config.singlePressAction != null &&
                config.doublePressAction == null &&
                config.longPressAction == null

        val state = keyStates.getOrPut(config.keyCode) { KeyState() }
        val now = scheduler.currentTimeMs()

        if (action == RemoteInputEngine.ACTION_DOWN) {
            if (repeatCount == 0) {
                val debounceLimit = if (isSingleActionOnly) RemoteInputEngine.SINGLE_PRESS_DEBOUNCE_MS else RemoteInputEngine.MULTI_PRESS_DEBOUNCE_MS
                val timeSinceLastDown = now - state.lastDownTime
                if (timeSinceLastDown < debounceLimit) {
                    return InputDecision.Consume
                }
                state.lastDownTime = now

                if (isSingleActionOnly) {
                    state.isLongPressTriggered = false
                    state.isDoublePressTriggered = false
                    return InputDecision.Execute(config.singlePressAction!!)
                }

                val doubleToken = "double_${config.keyCode}"
                val longToken = "long_${config.keyCode}"

                if (state.isWaitingForDouble) {
                    scheduler.cancel(doubleToken)
                    state.isWaitingForDouble = false
                    state.isDoublePressTriggered = true
                    return InputDecision.Execute(config.doublePressAction!!)
                }

                state.isLongPressTriggered = false
                state.isDoublePressTriggered = false
                val longAction = config.longPressAction
                if (longAction != null) {
                    scheduler.schedule(RemoteInputEngine.LONG_PRESS_TIMEOUT_MS, longToken) {
                        state.isLongPressTriggered = true
                        onAction(longAction)
                    }
                }
                return InputDecision.Consume
            }
            return InputDecision.Consume
        } else if (action == RemoteInputEngine.ACTION_UP) {
            val longToken = "long_${config.keyCode}"
            val doubleToken = "double_${config.keyCode}"
            scheduler.cancel(longToken)

            if (state.isLongPressTriggered) {
                state.isLongPressTriggered = false
                return InputDecision.Consume
            }

            if (state.isDoublePressTriggered) {
                state.isDoublePressTriggered = false
                return InputDecision.Consume
            }

            if (isSingleActionOnly) {
                return InputDecision.Consume
            }

            if (config.doublePressAction != null) {
                state.isWaitingForDouble = true
                val singleAction = config.singlePressAction
                scheduler.schedule(RemoteInputEngine.DOUBLE_PRESS_TIMEOUT_MS, doubleToken) {
                    state.isWaitingForDouble = false
                    if (singleAction != null) {
                        onAction(singleAction)
                    }
                }
                return InputDecision.Consume
            } else {
                val singleAction = config.singlePressAction
                if (singleAction != null) {
                    return InputDecision.Execute(singleAction)
                }
            }
        }

        return InputDecision.Consume
    }

    companion object {
        private val VOLUME_KEYS = setOf(
            RemoteInputEngine.KEYCODE_VOLUME_UP,
            RemoteInputEngine.KEYCODE_VOLUME_DOWN,
            RemoteInputEngine.KEYCODE_VOLUME_MUTE,
            RemoteInputEngine.KEYCODE_MUTE
        )

        private val NAVIGATION_AND_SYSTEM_KEYS = setOf(
            RemoteInputEngine.KEYCODE_DPAD_UP,
            RemoteInputEngine.KEYCODE_DPAD_DOWN,
            RemoteInputEngine.KEYCODE_DPAD_LEFT,
            RemoteInputEngine.KEYCODE_DPAD_RIGHT,
            RemoteInputEngine.KEYCODE_DPAD_CENTER,
            RemoteInputEngine.KEYCODE_ENTER,
            RemoteInputEngine.KEYCODE_NUMPAD_ENTER,
            RemoteInputEngine.KEYCODE_BACK,
            RemoteInputEngine.KEYCODE_ESCAPE,
            RemoteInputEngine.KEYCODE_HOME,
            RemoteInputEngine.KEYCODE_APP_SWITCH,
            RemoteInputEngine.KEYCODE_POWER,
            RemoteInputEngine.KEYCODE_SLEEP
        )

        private val DOCK_NAV_KEYS = setOf(
            RemoteInputEngine.KEYCODE_DPAD_UP,
            RemoteInputEngine.KEYCODE_DPAD_DOWN,
            RemoteInputEngine.KEYCODE_DPAD_LEFT,
            RemoteInputEngine.KEYCODE_DPAD_RIGHT,
            RemoteInputEngine.KEYCODE_DPAD_CENTER,
            RemoteInputEngine.KEYCODE_ENTER,
            RemoteInputEngine.KEYCODE_NUMPAD_ENTER
        )
    }
}
