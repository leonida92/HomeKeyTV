package com.homekey.tv

import android.app.Application
import com.homekey.tv.service.DefaultRemoteInputCoordinator
import com.homekey.tv.service.DefaultRemoteInputEngine
import com.homekey.tv.service.InputDecision
import com.homekey.tv.service.RemoteInputEngine
import com.homekey.tv.service.VirtualTimeScheduler
import com.homekey.tv.viewmodel.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteInputCoordinatorTest {

    private val testDispatcher = UnconfinedTestDispatcher(TestCoroutineScheduler())

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun defaultState_hasExpectedInitialValues() {
        val coordinator = DefaultRemoteInputCoordinator()
        assertFalse(coordinator.isServiceRunning.value)
        assertFalse(coordinator.isSettingsForeground.value)
        assertFalse(coordinator.isLearnModeActive.value)
        assertNull(coordinator.learnedKey.value)
        assertNull(coordinator.currentForegroundPackage.value)
    }

    @Test
    fun mutators_updateStateFlows() {
        val coordinator = DefaultRemoteInputCoordinator()

        coordinator.setServiceRunning(true)
        assertTrue(coordinator.isServiceRunning.value)

        coordinator.setSettingsForeground(true)
        assertTrue(coordinator.isSettingsForeground.value)

        coordinator.setForegroundPackage("com.netflix.ninja")
        assertEquals("com.netflix.ninja", coordinator.currentForegroundPackage.value)

        coordinator.updateLearnedKey(Pair(225, "PAIRING"))
        assertEquals(Pair(225, "PAIRING"), coordinator.learnedKey.value)

        coordinator.setServiceRunning(false)
        coordinator.setSettingsForeground(false)
        coordinator.setForegroundPackage(null)
        assertFalse(coordinator.isServiceRunning.value)
        assertFalse(coordinator.isSettingsForeground.value)
        assertNull(coordinator.currentForegroundPackage.value)
    }

    @Test
    fun setLearnMode_true_clearsStaleLearnedKey_andAttachedEngine() {
        val scheduler = VirtualTimeScheduler()
        val engine = DefaultRemoteInputEngine(
            scheduler = scheduler,
            onAction = {}
        )
        val coordinator = DefaultRemoteInputCoordinator()
        coordinator.attachInputEngine(engine)

        engine.setLearnMode(true)
        val decision = engine.onKeyEvent(42, RemoteInputEngine.ACTION_DOWN, 0)
        assertEquals(InputDecision.Consume, decision)
        coordinator.updateLearnedKey(engine.learnedKey.value)
        assertEquals(Pair(42, "KEY_42"), coordinator.learnedKey.value)

        coordinator.setLearnMode(true)
        assertTrue(coordinator.isLearnModeActive.value)
        assertNull(coordinator.learnedKey.value)
        assertNull(engine.learnedKey.value)
    }

    @Test
    fun clearLearnedKey_clearsBothCoordinatorAndAttachedEngine_preventingConflation() {
        val scheduler = VirtualTimeScheduler()
        val engine = DefaultRemoteInputEngine(
            scheduler = scheduler,
            onAction = {}
        )
        val coordinator = DefaultRemoteInputCoordinator()
        coordinator.attachInputEngine(engine)

        coordinator.setLearnMode(true)
        engine.setLearnMode(true)

        // First press of key 42
        engine.onKeyEvent(42, RemoteInputEngine.ACTION_DOWN, 0)
        coordinator.updateLearnedKey(engine.learnedKey.value)
        assertEquals(Pair(42, "KEY_42"), coordinator.learnedKey.value)
        assertEquals(Pair(42, "KEY_42"), engine.learnedKey.value)

        // Clear via coordinator: must reset both coordinator and engine
        coordinator.clearLearnedKey()
        assertNull(coordinator.learnedKey.value)
        assertNull(engine.learnedKey.value)

        // Second press of the exact same key 42 must re-emit cleanly
        engine.onKeyEvent(42, RemoteInputEngine.ACTION_DOWN, 0)
        coordinator.updateLearnedKey(engine.learnedKey.value)
        assertEquals(Pair(42, "KEY_42"), coordinator.learnedKey.value)
        assertEquals(Pair(42, "KEY_42"), engine.learnedKey.value)
    }

    @Test
    fun detachInputEngine_stopsPropagatingClearToDetachedEngine() {
        val scheduler = VirtualTimeScheduler()
        val engine = DefaultRemoteInputEngine(
            scheduler = scheduler,
            onAction = {}
        )
        val coordinator = DefaultRemoteInputCoordinator()
        coordinator.attachInputEngine(engine)
        engine.setLearnMode(true)
        engine.onKeyEvent(42, RemoteInputEngine.ACTION_DOWN, 0)
        coordinator.updateLearnedKey(engine.learnedKey.value)

        coordinator.detachInputEngine()
        coordinator.clearLearnedKey()

        assertNull(coordinator.learnedKey.value)
        assertEquals(Pair(42, "KEY_42"), engine.learnedKey.value)
    }

    @Test
    fun settingsViewModel_delegatesLearnModeAndServiceStateToCoordinator() = runTest(testDispatcher) {
        val app = Application()
        val fakePrefs = FakePreferencesRepository()
        val fakeHaRepo = FakeHomeAssistantRepository()
        val fakeCoordinator = FakeRemoteInputCoordinator()
        val fakeAdb = FakeAdbAccessibilityProvisioner()

        val viewModel = SettingsViewModel(
            application = app,
            prefs = fakePrefs,
            haRepo = fakeHaRepo,
            remoteInputCoordinator = fakeCoordinator,
            adbProvisioner = fakeAdb,
            installedAppsProvider = FakeInstalledAppsProvider(),
            setupQrProvider = FakeSetupQrProvider(),
            ioDispatcher = testDispatcher
        )

        assertFalse(viewModel.isAccessibilityEnabled.value)
        fakeCoordinator.setServiceRunning(true)
        assertTrue(viewModel.isAccessibilityEnabled.value)

        viewModel.setLearnMode(true)
        assertTrue(fakeCoordinator.isLearnModeActive.value)

        fakeCoordinator.updateLearnedKey(Pair(99, "KEY_99"))
        assertEquals(Pair(99, "KEY_99"), viewModel.learnedKey.value)

        viewModel.clearLearnedKey()
        assertNull(viewModel.learnedKey.value)
        assertEquals(1, fakeCoordinator.clearLearnedKeyCount)
    }
}
