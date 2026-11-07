package com.homekey.tv.service

import com.homekey.tv.data.models.ButtonRemapConfig
import com.homekey.tv.data.models.RemapAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RemoteInputEngineTest {

    private lateinit var scheduler: VirtualTimeScheduler
    private lateinit var dispatchedActions: MutableList<RemapAction>
    private lateinit var volumeAdjustments: MutableList<VolumeDirection>
    private lateinit var engine: RemoteInputEngine

    @Before
    fun setup() {
        scheduler = VirtualTimeScheduler(currentTime = 1000L)
        dispatchedActions = mutableListOf()
        volumeAdjustments = mutableListOf()
        engine = DefaultRemoteInputEngine(
            scheduler = scheduler,
            onVolumeAdjust = { volumeAdjustments.add(it) },
            onAction = { dispatchedActions.add(it) }
        )
    }

    @Test
    fun fast_path_bypass_passes_navigation_keys_when_dock_and_settings_hidden() {
        val navKeys = listOf(
            RemoteInputEngine.KEYCODE_DPAD_UP,
            RemoteInputEngine.KEYCODE_DPAD_DOWN,
            RemoteInputEngine.KEYCODE_DPAD_LEFT,
            RemoteInputEngine.KEYCODE_DPAD_RIGHT,
            RemoteInputEngine.KEYCODE_DPAD_CENTER,
            RemoteInputEngine.KEYCODE_BACK,
            RemoteInputEngine.KEYCODE_HOME,
            RemoteInputEngine.KEYCODE_ENTER
        )

        for (keyCode in navKeys) {
            val decision = engine.onKeyEvent(
                keyCode = keyCode,
                action = RemoteInputEngine.ACTION_DOWN,
                repeatCount = 0
            )
            assertEquals("Fast path bypass for $keyCode", InputDecision.PassThrough, decision)
        }
    }

    @Test
    fun single_action_only_key_triggers_immediate_execute_on_down() {
        val remap = ButtonRemapConfig(
            keyCode = 191, // Custom Button
            keyName = "Custom 1",
            singlePressAction = RemapAction("OPEN_DOCK")
        )
        engine.updateRemaps(listOf(remap))

        // ACTION_DOWN
        val downDecision = engine.onKeyEvent(
            keyCode = 191,
            action = RemoteInputEngine.ACTION_DOWN,
            repeatCount = 0
        )
        assertEquals(InputDecision.Execute(RemapAction("OPEN_DOCK")), downDecision)

        // ACTION_UP
        val upDecision = engine.onKeyEvent(
            keyCode = 191,
            action = RemoteInputEngine.ACTION_UP,
            repeatCount = 0
        )
        assertEquals(InputDecision.Consume, upDecision)
    }

    @Test
    fun single_press_debounce_rejects_rapid_contact_bounce_within_250ms() {
        val remap = ButtonRemapConfig(
            keyCode = 191,
            keyName = "Custom 1",
            singlePressAction = RemapAction("OPEN_DOCK")
        )
        engine.updateRemaps(listOf(remap))

        // First click
        val firstDown = engine.onKeyEvent(
            keyCode = 191,
            action = RemoteInputEngine.ACTION_DOWN,
            repeatCount = 0
        )
        assertEquals(InputDecision.Execute(RemapAction("OPEN_DOCK")), firstDown)

        // Rapid bounce down at +80ms (< 250ms)
        scheduler.advanceTimeBy(80)
        val bounceDown = engine.onKeyEvent(
            keyCode = 191,
            action = RemoteInputEngine.ACTION_DOWN,
            repeatCount = 0
        )
        // Consumes bounce without triggering second action
        assertEquals(InputDecision.Consume, bounceDown)
    }

    @Test
    fun double_press_triggers_double_action_when_second_press_arrives_within_280ms() {
        val remap = ButtonRemapConfig(
            keyCode = 191,
            keyName = "Custom 1",
            singlePressAction = RemapAction("TOGGLE_ENTITY", "light.kitchen"),
            doublePressAction = RemapAction("OPEN_DOCK")
        )
        engine.updateRemaps(listOf(remap))

        // Press 1 DOWN
        val down1 = engine.onKeyEvent(191, RemoteInputEngine.ACTION_DOWN, 0)
        assertEquals(InputDecision.Consume, down1)

        // Press 1 UP at +60ms
        scheduler.advanceTimeBy(60)
        val up1 = engine.onKeyEvent(191, RemoteInputEngine.ACTION_UP, 0)
        assertEquals(InputDecision.Consume, up1)

        // Press 2 DOWN at +120ms (< 280ms)
        scheduler.advanceTimeBy(60)
        val down2 = engine.onKeyEvent(191, RemoteInputEngine.ACTION_DOWN, 0)
        assertEquals(InputDecision.Execute(RemapAction("OPEN_DOCK")), down2)

        // Advance past window to verify single action was cancelled
        scheduler.advanceTimeBy(500)
        assertTrue(dispatchedActions.isEmpty())
    }

    @Test
    fun double_press_does_not_fire_single_press_on_release() {
        val remap = ButtonRemapConfig(
            keyCode = 191,
            keyName = "Custom 1",
            singlePressAction = RemapAction("TOGGLE_ENTITY", "light.kitchen"),
            doublePressAction = RemapAction("OPEN_DOCK")
        )
        engine.updateRemaps(listOf(remap))

        // Press 1 DOWN & UP
        engine.onKeyEvent(191, RemoteInputEngine.ACTION_DOWN, 0)
        scheduler.advanceTimeBy(50)
        engine.onKeyEvent(191, RemoteInputEngine.ACTION_UP, 0)

        // Press 2 DOWN (fires double action immediately)
        scheduler.advanceTimeBy(50)
        val down2 = engine.onKeyEvent(191, RemoteInputEngine.ACTION_DOWN, 0)
        assertEquals(InputDecision.Execute(RemapAction("OPEN_DOCK")), down2)

        // Press 2 UP
        scheduler.advanceTimeBy(50)
        val up2 = engine.onKeyEvent(191, RemoteInputEngine.ACTION_UP, 0)
        assertEquals(InputDecision.Consume, up2)

        // Advance 500ms: ensure single press was NOT dispatched
        scheduler.advanceTimeBy(500)
        assertTrue(dispatchedActions.isEmpty())
    }

    @Test
    fun single_press_dispatches_via_callback_when_double_press_window_expires() {
        val remap = ButtonRemapConfig(
            keyCode = 191,
            keyName = "Custom 1",
            singlePressAction = RemapAction("TOGGLE_ENTITY", "light.kitchen"),
            doublePressAction = RemapAction("OPEN_DOCK")
        )
        engine.updateRemaps(listOf(remap))

        // Press 1 DOWN & UP
        engine.onKeyEvent(191, RemoteInputEngine.ACTION_DOWN, 0)
        scheduler.advanceTimeBy(50)
        engine.onKeyEvent(191, RemoteInputEngine.ACTION_UP, 0)

        assertEquals(0, dispatchedActions.size)

        // Advance time past 280ms double-click timeout
        scheduler.advanceTimeBy(300)

        assertEquals(1, dispatchedActions.size)
        assertEquals(RemapAction("TOGGLE_ENTITY", "light.kitchen"), dispatchedActions[0])
    }

    @Test
    fun long_press_triggers_long_action_when_held_for_450ms() {
        val remap = ButtonRemapConfig(
            keyCode = 191,
            keyName = "Custom 1",
            singlePressAction = RemapAction("OPEN_DOCK"),
            longPressAction = RemapAction("LAUNCH_APP", "com.netflix.ninja")
        )
        engine.updateRemaps(listOf(remap))

        // DOWN
        val down = engine.onKeyEvent(191, RemoteInputEngine.ACTION_DOWN, 0)
        assertEquals(InputDecision.Consume, down)

        // Advance 450ms
        scheduler.advanceTimeBy(460)

        assertEquals(1, dispatchedActions.size)
        assertEquals(RemapAction("LAUNCH_APP", "com.netflix.ninja"), dispatchedActions[0])

        // UP after long press release
        val up = engine.onKeyEvent(191, RemoteInputEngine.ACTION_UP, 0)
        assertEquals(InputDecision.Consume, up)

        // Ensure single action didn't also fire
        scheduler.advanceTimeBy(500)
        assertEquals(1, dispatchedActions.size)
    }

    @Test
    fun star_button_313_aliases_to_keycode_pairing_225() {
        val remap = ButtonRemapConfig(
            keyCode = RemoteInputEngine.KEYCODE_PAIRING, // 225
            keyName = "Star Button",
            singlePressAction = RemapAction("OPEN_DOCK")
        )
        engine.updateRemaps(listOf(remap))

        // Key code 313 should alias to 225 and match the remap
        val decision = engine.onKeyEvent(
            keyCode = RemoteInputEngine.KEYCODE_STAR_ALIAS, // 313
            action = RemoteInputEngine.ACTION_DOWN,
            repeatCount = 0
        )
        assertEquals(InputDecision.Execute(RemapAction("OPEN_DOCK")), decision)
    }

    @Test
    fun star_button_aliasing_works_when_remap_saved_with_313() {
        val remap = ButtonRemapConfig(
            keyCode = RemoteInputEngine.KEYCODE_STAR_ALIAS, // 313
            keyName = "Star Button",
            singlePressAction = RemapAction("OPEN_DOCK")
        )
        engine.updateRemaps(listOf(remap))

        // Key code 225 should alias to 313 and match the remap
        val decision = engine.onKeyEvent(
            keyCode = RemoteInputEngine.KEYCODE_PAIRING, // 225
            action = RemoteInputEngine.ACTION_DOWN,
            repeatCount = 0
        )
        assertEquals(InputDecision.Execute(RemapAction("OPEN_DOCK")), decision)
    }

    @Test
    fun app_scoped_remap_takes_precedence_over_global_remap() {
        val globalRemap = ButtonRemapConfig(
            keyCode = 191,
            keyName = "Netflix Button",
            appScope = null,
            singlePressAction = RemapAction("OPEN_DOCK")
        )
        val youtubeRemap = ButtonRemapConfig(
            keyCode = 191,
            keyName = "Netflix Button",
            appScope = "com.google.android.youtube.tv",
            singlePressAction = RemapAction("TOGGLE_ENTITY", "light.movie_mode")
        )
        engine.updateRemaps(listOf(globalRemap, youtubeRemap))

        // In YouTube app: triggers YouTube-scoped remap
        engine.setForegroundPackage("com.google.android.youtube.tv")
        val ytDecision = engine.onKeyEvent(191, RemoteInputEngine.ACTION_DOWN, 0)
        assertEquals(InputDecision.Execute(RemapAction("TOGGLE_ENTITY", "light.movie_mode")), ytDecision)

        // In other app: falls back to global remap
        scheduler.advanceTimeBy(500)
        engine.setForegroundPackage("com.netflix.ninja")
        val globalDecision = engine.onKeyEvent(191, RemoteInputEngine.ACTION_DOWN, 0)
        assertEquals(InputDecision.Execute(RemapAction("OPEN_DOCK")), globalDecision)
    }

    @Test
    fun learn_mode_captures_key_and_consumes_event() {
        engine.setLearnMode(true)

        val decision = engine.onKeyEvent(200, RemoteInputEngine.ACTION_DOWN, 0)
        assertEquals(InputDecision.Consume, decision)
        assertEquals(200, engine.learnedKey.value?.first)

        engine.clearLearnedKey()
        assertEquals(null, engine.learnedKey.value)
    }

    @Test
    fun custom_mapped_volume_key_triggers_action_and_bypasses_fast_path() {
        val remap = ButtonRemapConfig(
            keyCode = RemoteInputEngine.KEYCODE_VOLUME_DOWN,
            keyName = "Volume Down",
            singlePressAction = RemapAction("OPEN_DOCK")
        )
        engine.updateRemaps(listOf(remap))

        // When dock is closed, volume key with remap must NOT be passed through by fast-path
        val decision = engine.onKeyEvent(RemoteInputEngine.KEYCODE_VOLUME_DOWN, RemoteInputEngine.ACTION_DOWN, 0)
        assertEquals(InputDecision.Execute(RemapAction("OPEN_DOCK")), decision)
    }

    @Test
    fun dock_showing_intercepts_back_and_passes_dpad() {
        engine.setDockShowing(true)

        // Back key down
        val backDown = engine.onKeyEvent(RemoteInputEngine.KEYCODE_BACK, RemoteInputEngine.ACTION_DOWN, 0)
        assertEquals(InputDecision.Consume, backDown)

        // Back key up
        val backUp = engine.onKeyEvent(RemoteInputEngine.KEYCODE_BACK, RemoteInputEngine.ACTION_UP, 0)
        assertEquals(InputDecision.HandleBack, backUp)

        // Home key dismisses dock
        val homeDecision = engine.onKeyEvent(RemoteInputEngine.KEYCODE_HOME, RemoteInputEngine.ACTION_DOWN, 0)
        assertEquals(InputDecision.DismissDock, homeDecision)

        // D-pad center down passes through to overlay
        val dpadDecision = engine.onKeyEvent(RemoteInputEngine.KEYCODE_DPAD_CENTER, RemoteInputEngine.ACTION_DOWN, 0)
        assertEquals(InputDecision.PassThrough, dpadDecision)
    }

    @Test
    fun volume_fix_generates_synthetic_repeats_when_unmapped() {
        engine.setVolumeFixEnabled(true)

        // Unmapped Volume Up initial DOWN returns PassThrough for native 0ms latency
        val downDecision = engine.onKeyEvent(RemoteInputEngine.KEYCODE_VOLUME_UP, RemoteInputEngine.ACTION_DOWN, 0)
        assertEquals(InputDecision.PassThrough, downDecision)
        assertEquals(0, volumeAdjustments.size)

        // Advance 300ms initial delay: first synthetic repeat should fire
        scheduler.advanceTimeBy(300)
        assertEquals(1, volumeAdjustments.size)
        assertEquals(VolumeDirection.UP, volumeAdjustments[0])

        // Advance 90ms: second synthetic repeat
        scheduler.advanceTimeBy(90)
        assertEquals(2, volumeAdjustments.size)

        // Release key (ACTION_UP): should cancel further repeats
        val upDecision = engine.onKeyEvent(RemoteInputEngine.KEYCODE_VOLUME_UP, RemoteInputEngine.ACTION_UP, 0)
        assertEquals(InputDecision.PassThrough, upDecision)

        // Advance another 200ms: no more volume adjustments
        scheduler.advanceTimeBy(200)
        assertEquals(2, volumeAdjustments.size)
    }
}
