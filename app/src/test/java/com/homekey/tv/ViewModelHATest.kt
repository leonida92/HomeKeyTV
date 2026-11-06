package com.homekey.tv

import android.app.Application
import com.homekey.tv.data.models.ConnectionStatus
import com.homekey.tv.data.models.HAEntityState
import com.homekey.tv.viewmodel.PanelViewModel
import com.homekey.tv.viewmodel.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ViewModelHATest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var fakePrefs: FakePreferencesRepository
    private lateinit var fakeHaRepo: FakeHomeAssistantRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakePrefs = FakePreferencesRepository()
        fakeHaRepo = FakeHomeAssistantRepository()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun panelViewModel_receives_entities_and_connection_from_haRepository() = runTest(testDispatcher) {
        val app = Application()
        fakeHaRepo.setEntities(mapOf(
            "light.living_room" to HAEntityState(entityId = "light.living_room", state = "on")
        ))
        fakeHaRepo.setConnectionStatus(ConnectionStatus.AUTHENTICATED)

        val viewModel = PanelViewModel(app, fakePrefs, fakeHaRepo)

        assertEquals(1, viewModel.allEntities.value.size)
        assertEquals("on", viewModel.allEntities.value["light.living_room"]?.state)
        assertEquals(ConnectionStatus.AUTHENTICATED, viewModel.connectionStatus.value)

        viewModel.toggleEntity("light.living_room")
        assertTrue(fakeHaRepo.serviceCalls.contains("toggle:light.living_room"))
    }

    @Test
    fun panelViewModel_handleBack_respects_ordered_precedence() = runTest(testDispatcher) {
        val app = Application()
        val viewModel = PanelViewModel(app, fakePrefs, fakeHaRepo)

        // Neither active -> returns false
        assertFalse(viewModel.handleBack())

        // In reorder mode -> exits reorder mode and returns true
        viewModel.toggleReorderMode()
        assertTrue(viewModel.isReorderMode.value)
        assertTrue(viewModel.handleBack())
        assertFalse(viewModel.isReorderMode.value)

        // Dialog active -> closes dialog and returns true
        val entity = HAEntityState(entityId = "light.lamp", state = "on")
        viewModel.openEntityDialog(entity)
        assertNotNull(viewModel.activeDialogEntity.value)
        assertTrue(viewModel.handleBack())
        assertNull(viewModel.activeDialogEntity.value)

        // Both dialog and reorder mode active -> closes dialog FIRST
        viewModel.toggleReorderMode()
        viewModel.openEntityDialog(entity)
        assertTrue(viewModel.isReorderMode.value)
        assertNotNull(viewModel.activeDialogEntity.value)

        assertTrue(viewModel.handleBack())
        assertNull(viewModel.activeDialogEntity.value)
        assertTrue(viewModel.isReorderMode.value) // reorder mode stayed until second back

        assertTrue(viewModel.handleBack())
        assertFalse(viewModel.isReorderMode.value)
        assertFalse(viewModel.handleBack())
    }

    @Test
    fun settingsViewModel_receives_entities_and_connection_from_haRepository() = runTest(testDispatcher) {
        val app = Application()
        fakeHaRepo.setEntities(mapOf(
            "light.kitchen" to HAEntityState(entityId = "light.kitchen", state = "on")
        ))
        fakeHaRepo.setConnectionStatus(ConnectionStatus.AUTHENTICATED)

        val viewModel = SettingsViewModel(app, fakePrefs, fakeHaRepo)

        val countJob = launch { viewModel.entityCount.collect {} }
        assertEquals(1, viewModel.entityCount.value)
        countJob.cancel()

        val sorted = viewModel.sortedEntities.first { it.isNotEmpty() }
        assertEquals(1, sorted.size)
        assertEquals("light.kitchen", sorted[0].entityId)
        assertEquals(ConnectionStatus.AUTHENTICATED, viewModel.connectionStatus.value)
    }

    @Test
    fun viewModels_have_single_arg_application_constructor_for_androidViewModelFactory() {
        val panelCtor = PanelViewModel::class.java.getConstructor(Application::class.java)
        assertNotNull(panelCtor)

        val settingsCtor = SettingsViewModel::class.java.getConstructor(Application::class.java)
        assertNotNull(settingsCtor)
    }
}
