package com.homekey.tv

import com.homekey.tv.service.DefaultDockOverlayController
import org.junit.Assert.*
import org.junit.Test

class DockOverlayControllerTest {

    @Test
    fun defaultState_isNotShowing_andNotAttached() {
        val controller = DefaultDockOverlayController()
        assertFalse(controller.isShowing.value)
        assertFalse(controller.isServiceAttached)
    }

    @Test
    fun show_withoutAttachedService_returnsFalse() {
        val controller = DefaultDockOverlayController()
        val result = controller.show()
        assertFalse(result)
        assertFalse(controller.isShowing.value)
    }

    @Test
    fun handleBack_whenNotShowing_returnsFalse() {
        val controller = DefaultDockOverlayController()
        val handled = controller.handleBack()
        assertFalse(handled)
    }

    @Test
    fun teardown_resetsShowingState() {
        val controller = DefaultDockOverlayController()
        controller.teardown()
        assertFalse(controller.isShowing.value)
        assertFalse(controller.isServiceAttached)
    }

    @Test
    fun delegatesOperationsToEngine() {
        var engineShowCalled = false
        var engineHideCalled = false
        var engineToggleCalled = false
        var enginePrewarmCalled = false
        var engineTeardownCalled = false
        var engineHandleBackCalled = false

        val testEngine = object : com.homekey.tv.service.DockOverlayStateEngine {
            override val state = kotlinx.coroutines.flow.MutableStateFlow<com.homekey.tv.service.DockOverlayState>(com.homekey.tv.service.DockOverlayState.Hidden)
            override val isShowing = kotlinx.coroutines.flow.MutableStateFlow(false)
            override val isServiceAttached = true

            override fun attachService() {}
            override fun detachService() {}
            override fun show(): Boolean { engineShowCalled = true; return true }
            override fun hide() { engineHideCalled = true }
            override fun toggle() { engineToggleCalled = true }
            override fun prewarm() { enginePrewarmCalled = true }
            override fun teardown() { engineTeardownCalled = true }
            override fun handleBack(): Boolean { engineHandleBackCalled = true; return true }
        }

        val controller = DefaultDockOverlayController(
            engine = testEngine
        )

        assertTrue(controller.isServiceAttached)
        assertTrue(controller.show())
        assertTrue(engineShowCalled)

        controller.hide()
        assertTrue(engineHideCalled)

        controller.toggle()
        assertTrue(engineToggleCalled)

        controller.prewarm()
        assertTrue(enginePrewarmCalled)

        assertTrue(controller.handleBack())
        assertTrue(engineHandleBackCalled)

        controller.teardown()
        assertTrue(engineTeardownCalled)
    }
}
