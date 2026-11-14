package com.homekey.tv

import android.app.Application
import com.homekey.tv.data.local.DockConfig
import com.homekey.tv.data.models.ConnectionStatus
import com.homekey.tv.data.models.HAEntityState
import com.homekey.tv.data.models.PinnedAppConfig
import com.homekey.tv.data.models.PinnedEntityConfig
import com.homekey.tv.viewmodel.PanelViewModel
import com.homekey.tv.viewmodel.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
class ViewModelHATest {

    private val testDispatcher = UnconfinedTestDispatcher(TestCoroutineScheduler())
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
    fun panelViewModel_delegates_extended_domain_actions_to_repository() = runTest(testDispatcher) {
        val app = Application()
        val viewModel = PanelViewModel(app, fakePrefs, fakeHaRepo)

        viewModel.openCover("cover.blinds")
        assertTrue(fakeHaRepo.serviceCalls.contains("openCover:cover.blinds"))

        viewModel.closeCover("cover.blinds")
        assertTrue(fakeHaRepo.serviceCalls.contains("closeCover:cover.blinds"))

        viewModel.stopCover("cover.blinds")
        assertTrue(fakeHaRepo.serviceCalls.contains("stopCover:cover.blinds"))

        viewModel.setCoverPosition("cover.blinds", 50)
        assertTrue(fakeHaRepo.serviceCalls.contains("setCoverPosition:cover.blinds:50"))

        viewModel.setCoverTiltPosition("cover.blinds", 30)
        assertTrue(fakeHaRepo.serviceCalls.contains("setCoverTiltPosition:cover.blinds:30"))

        viewModel.setFanPercentage("fan.bedroom", 80)
        assertTrue(fakeHaRepo.serviceCalls.contains("setFanPercentage:fan.bedroom:80"))

        viewModel.setFanOscillating("fan.bedroom", true)
        assertTrue(fakeHaRepo.serviceCalls.contains("setFanOscillating:fan.bedroom:true"))

        viewModel.lock("lock.front_door")
        assertTrue(fakeHaRepo.serviceCalls.contains("lock:lock.front_door"))

        viewModel.unlock("lock.front_door")
        assertTrue(fakeHaRepo.serviceCalls.contains("unlock:lock.front_door"))
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

        val viewModel = SettingsViewModel(
            application = app,
            prefs = fakePrefs,
            haRepo = fakeHaRepo,
            installedAppsProvider = FakeInstalledAppsProvider(),
            setupQrProvider = FakeSetupQrProvider(),
            ioDispatcher = testDispatcher
        )

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

    @Test
    fun panelViewModel_displayEntities_populates_resolvedHumidity_and_filters_when_ha_disabled() = runTest(testDispatcher) {
        val app = Application()
        val climate = HAEntityState(entityId = "climate.living_room", state = "heat")
        val humiditySensor = HAEntityState(entityId = "sensor.living_room_humidity", state = "54.5")
        fakeHaRepo.setEntities(
            mapOf(
                climate.entityId to climate,
                humiditySensor.entityId to humiditySensor
            )
        )
        fakePrefs.savePinnedEntities(
            listOf(PinnedEntityConfig(entityId = "climate.living_room", order = 0))
        )
        fakePrefs.savePinnedApps(
            listOf(PinnedAppConfig(packageName = "com.netflix.ninja", appName = "Netflix", order = 0))
        )

        val viewModel = PanelViewModel(app, fakePrefs, fakeHaRepo)

        val items = viewModel.displayEntities.first { it.size == 2 }
        assertEquals("climate.living_room", items[0].id)
        assertEquals(54.5f, items[0].resolvedHumidity ?: 0f, 0.01f)
        assertEquals("app:com.netflix.ninja", items[1].id)
        assertTrue(items[1].isApp)
        assertNull(items[1].resolvedHumidity)

        viewModel.openEntityDialog(climate)
        val dialogHumidity = viewModel.activeDialogHumidity.first { it != null }
        assertEquals(54.5f, dialogHumidity ?: 0f, 0.01f)

        fakePrefs.updateServerConfig { it.copy(haEnabled = false) }
        val appOnlyItems = viewModel.displayEntities.first { it.size == 1 }
        assertEquals("app:com.netflix.ninja", appOnlyItems[0].id)
    }

    @Test
    fun panelViewModel_launchApp_handles_null_packageManager_and_records_recent_app() = runTest(testDispatcher) {
        val app = Application()
        fakePrefs.updateRecentAppsConfig { it.copy(enabled = true, maxCount = 3) }
        val viewModel = PanelViewModel(app, fakePrefs, fakeHaRepo)

        // Application().packageManager is null in JVM tests; launchApp must not throw NPE
        viewModel.launchApp("com.netflix.ninja")

        assertEquals(listOf("com.netflix.ninja"), viewModel.recentAppsConfig.value.recentPackages)
    }

    @Test
    fun panelViewModel_exposes_cohesive_preference_facets() = runTest(testDispatcher) {
        val app = Application()
        val viewModel = PanelViewModel(app, fakePrefs, fakeHaRepo)

        fakePrefs.updateDockConfig {
            it.copy(layout = DockConfig.LAYOUT_LEFT, popupStyle = DockConfig.POPUP_STYLE_MINIMAL)
        }
        fakePrefs.updateThemeConfig { it.copy(preset = "cyberpunk") }
        fakePrefs.updateRecentAppsConfig { it.copy(enabled = true, maxCount = 5) }

        assertEquals(DockConfig.LAYOUT_LEFT, viewModel.dockConfig.value.layout)
        assertEquals(DockConfig.POPUP_STYLE_MINIMAL, viewModel.dockConfig.value.popupStyle)
        assertEquals("cyberpunk", viewModel.themeConfig.value.preset)
        assertTrue(viewModel.recentAppsConfig.value.enabled)
        assertEquals(5, viewModel.recentAppsConfig.value.maxCount)
    }
}
