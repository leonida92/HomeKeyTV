package com.homekey.tv

import android.accessibilityservice.AccessibilityService
import android.app.Application
import com.homekey.tv.data.models.RemapAction
import com.homekey.tv.service.RemapActionDispatcher
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class RemapActionDispatcherTest {

    private lateinit var fakeDockController: FakeDockOverlayController
    private lateinit var fakeHaRepo: FakeHomeAssistantRepository
    private val executedGlobalActions = mutableListOf<Int>()
    private lateinit var dispatcher: RemapActionDispatcher

    @Before
    fun setUp() {
        executedGlobalActions.clear()
        fakeDockController = FakeDockOverlayController()
        fakeHaRepo = FakeHomeAssistantRepository()
        dispatcher = RemapActionDispatcher(
            haRepository = fakeHaRepo,
            dockController = fakeDockController,
            globalActionExecutor = { action ->
                executedGlobalActions.add(action)
                true
            }
        )
    }

    @Test
    fun execute_open_dock_toggles_dock_overlay() {
        val app = Application()
        dispatcher.execute(RemapAction(type = "OPEN_DOCK"), app)

        assertEquals(1, fakeDockController.toggleCount)
    }

    @Test
    fun execute_toggle_entity_calls_repository() {
        val app = Application()
        dispatcher.execute(RemapAction(type = "TOGGLE_ENTITY", target = "light.living_room"), app)

        assertTrue(fakeHaRepo.serviceCalls.contains("toggle:light.living_room"))
    }

    @Test
    fun execute_call_service_parses_domain_and_service() {
        val app = Application()
        dispatcher.execute(RemapAction(type = "CALL_SERVICE", target = "switch.turn_on"), app)

        assertTrue(fakeHaRepo.serviceCalls.contains("callService:switch:turn_on:null"))
    }

    @Test
    fun execute_select_source_calls_repository() {
        val app = Application()
        dispatcher.execute(RemapAction(type = "SELECT_SOURCE", target = "media_player.tv", extra = "HDMI 1"), app)

        assertTrue(fakeHaRepo.serviceCalls.contains("selectSource:media_player.tv:HDMI 1"))
    }

    @Test
    fun execute_system_sleep_invokes_global_action() {
        val app = Application()
        dispatcher.execute(RemapAction(type = "SYSTEM_SLEEP"), app)

        assertTrue(executedGlobalActions.contains(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN))
    }

    @Test
    fun execute_null_or_passthrough_does_not_call_service() {
        val app = Application()
        dispatcher.execute(null, app)
        dispatcher.execute(RemapAction(type = "PASS_THROUGH"), app)

        assertEquals(0, fakeDockController.toggleCount)
        assertTrue(fakeHaRepo.serviceCalls.isEmpty())
        assertTrue(executedGlobalActions.isEmpty())
    }
}
