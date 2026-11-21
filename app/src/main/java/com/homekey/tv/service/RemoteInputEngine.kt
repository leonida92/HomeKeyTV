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
        const val KEYCODE_YOUTUBE_PRESET = 190
        const val KEYCODE_NETFLIX_PRESET = 191
        const val KEYCODE_SLEEP = 223
        const val KEYCODE_PAIRING = 225
        const val KEYCODE_PRIME_VIDEO_PRESET = 229
        const val KEYCODE_STAR_ALIAS = 313

        // TCL Google TV OEM key codes from framework KeyEvent & TclShortCutKeyManager
        const val KEYCODE_TCL_EPG = 4001
        const val KEYCODE_TCL_FAVORITE = 4002
        const val KEYCODE_TCL_FORCE_AMAZON = 4061
        const val KEYCODE_TCL_NETFLIX = 4062
        const val KEYCODE_TCL_YOUTUBE = 4065
        const val KEYCODE_TCL_FORCE_PRIMETIME = 4069
        const val KEYCODE_TCL_GLOBO_PLAY = 4097
        const val KEYCODE_TCL_PRIME_VIDEO = 4099
        const val KEYCODE_TCL_DISNEY = 4141
        const val KEYCODE_TCL_CUSTOMIZE_1 = 4149
        const val KEYCODE_TCL_CUSTOMIZE_2 = 4150
        const val KEYCODE_TCL_CUSTOMIZE_3 = 4151
        const val KEYCODE_TCL_TVPLUS = 4158
        const val KEYCODE_TCL_APPLETV = 4160
        const val KEYCODE_TCL_CHANNELPLUS = 4184

        /**
         * Bidirectional alias groups for remote buttons whose key codes vary across
         * Android TV presets, Fire TV, and TCL OEM keymaps.
         */
        val KEY_ALIAS_GROUPS: List<Set<Int>> = listOf(
            setOf(
                KEYCODE_STAR_ALIAS,
                KEYCODE_PAIRING,
                KEYCODE_TCL_CUSTOMIZE_1,
                KEYCODE_TCL_CUSTOMIZE_2,
                KEYCODE_TCL_CUSTOMIZE_3
            ),
            setOf(KEYCODE_NETFLIX_PRESET, KEYCODE_TCL_NETFLIX),
            setOf(KEYCODE_YOUTUBE_PRESET, KEYCODE_TCL_YOUTUBE, KEYCODE_TCL_GLOBO_PLAY),
            setOf(
                KEYCODE_PRIME_VIDEO_PRESET,
                KEYCODE_TCL_PRIME_VIDEO,
                KEYCODE_TCL_FORCE_AMAZON,
                KEYCODE_TCL_FORCE_PRIMETIME
            ),
            setOf(KEYCODE_VOLUME_MUTE, KEYCODE_MUTE)
        )

        private val FRIENDLY_KEY_NAMES: Map<Int, String> = mapOf(
            KEYCODE_NETFLIX_PRESET to "Netflix Button",
            KEYCODE_TCL_NETFLIX to "Netflix Button",
            KEYCODE_YOUTUBE_PRESET to "YouTube Button",
            KEYCODE_TCL_YOUTUBE to "YouTube Button",
            KEYCODE_TCL_GLOBO_PLAY to "YouTube Button",
            KEYCODE_PRIME_VIDEO_PRESET to "Prime Video Button",
            KEYCODE_TCL_PRIME_VIDEO to "Prime Video Button",
            KEYCODE_TCL_FORCE_AMAZON to "Prime Video Button",
            KEYCODE_TCL_FORCE_PRIMETIME to "Prime Video Button",
            KEYCODE_STAR_ALIAS to "Custom Star Button",
            KEYCODE_PAIRING to "Custom Star Button",
            KEYCODE_TCL_EPG to "Live TV / EPG Button",
            KEYCODE_TCL_FAVORITE to "Favorite Button",
            KEYCODE_TCL_CUSTOMIZE_1 to "Custom Star Button",
            KEYCODE_TCL_CUSTOMIZE_2 to "Custom Star Button",
            KEYCODE_TCL_CUSTOMIZE_3 to "Custom Star Button",
            KEYCODE_TCL_DISNEY to "Disney+ Button",
            KEYCODE_TCL_APPLETV to "Apple TV Button",
            KEYCODE_TCL_TVPLUS to "TCL TV+ Button",
            KEYCODE_TCL_CHANNELPLUS to "TCL Channel Button"
        )

        /**
         * Returns all sibling key codes that alias the given [keyCode], excluding [keyCode] itself.
         */
        fun aliasesFor(keyCode: Int): Set<Int> {
            val group = KEY_ALIAS_GROUPS.firstOrNull { keyCode in it } ?: return emptySet()
            return group - keyCode
        }

        /**
         * Returns a human-readable button label for known preset and TCL OEM key codes, or null if unknown.
         */
        fun friendlyKeyName(keyCode: Int): String? = FRIENDLY_KEY_NAMES[keyCode]

        const val SINGLE_PRESS_DEBOUNCE_MS = 250L
        const val MULTI_PRESS_DEBOUNCE_MS = 80L
        const val DOUBLE_PRESS_TIMEOUT_MS = 280L
        const val LONG_PRESS_TIMEOUT_MS = 450L
        const val VOLUME_REPEAT_INITIAL_DELAY_MS = 300L
        const val VOLUME_REPEAT_INTERVAL_MS = 90L
        const val VOLUME_MAX_DURATION_MS = 8000L
    }
}
