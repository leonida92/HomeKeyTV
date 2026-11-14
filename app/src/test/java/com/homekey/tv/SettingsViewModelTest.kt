package com.homekey.tv

import android.app.Application
import com.homekey.tv.data.local.DockConfig
import com.homekey.tv.data.local.ServerConfig
import com.homekey.tv.data.models.AppUpdateState
import com.homekey.tv.data.models.ButtonRemapConfig
import com.homekey.tv.data.models.InstalledAppInfo
import com.homekey.tv.data.models.RemapAction
import com.homekey.tv.service.LocalAdbManager
import com.homekey.tv.util.InstalledAppsSnapshot
import com.homekey.tv.viewmodel.AdbSetupState
import com.homekey.tv.viewmodel.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher(TestCoroutineScheduler())
    private lateinit var fakePrefs: FakePreferencesRepository
    private lateinit var fakeHaRepo: FakeHomeAssistantRepository
    private lateinit var fakeCoordinator: FakeRemoteInputCoordinator
    private lateinit var fakeAdb: FakeAdbAccessibilityProvisioner
    private lateinit var fakeUpdateRepo: FakeAppUpdateRepository
    private lateinit var fakeInstaller: FakeAppInstaller
    private lateinit var fakeAppsProvider: FakeInstalledAppsProvider
    private lateinit var fakeQrProvider: FakeSetupQrProvider

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakePrefs = FakePreferencesRepository()
        fakeHaRepo = FakeHomeAssistantRepository()
        fakeCoordinator = FakeRemoteInputCoordinator()
        fakeAdb = FakeAdbAccessibilityProvisioner()
        fakeUpdateRepo = FakeAppUpdateRepository()
        fakeInstaller = FakeAppInstaller()
        fakeAppsProvider = FakeInstalledAppsProvider()
        fakeQrProvider = FakeSetupQrProvider()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): SettingsViewModel {
        return SettingsViewModel(
            application = Application(),
            prefs = fakePrefs,
            haRepo = fakeHaRepo,
            remoteInputCoordinator = fakeCoordinator,
            adbProvisioner = fakeAdb,
            appUpdateRepository = fakeUpdateRepo,
            appInstaller = fakeInstaller,
            installedAppsProvider = fakeAppsProvider,
            setupQrProvider = fakeQrProvider,
            ioDispatcher = testDispatcher
        )
    }

    @Test
    fun checkForUpdates_transitionsThroughCheckingToResult() = runTest(testDispatcher) {
        val available = AppUpdateState.UpdateAvailable(
            currentVersion = "1.6.6",
            latestVersion = "v2.0.0",
            releaseTitle = "v2.0.0",
            releaseNotes = "New dock",
            releaseUrl = "https://example.com/release",
            downloadUrl = "https://example.com/app.apk"
        )
        fakeUpdateRepo.nextCheckResult = available

        val viewModel = createViewModel()
        val states = mutableListOf<AppUpdateState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.updateState.toList(states)
        }

        viewModel.checkForUpdates()

        assertEquals(1, fakeUpdateRepo.checkCallCount)
        assertEquals(viewModel.appVersion, fakeUpdateRepo.lastCheckedVersion)
        assertTrue(states.contains(AppUpdateState.Checking))
        assertEquals(available, viewModel.updateState.value)

        viewModel.resetUpdateState()
        assertEquals(AppUpdateState.Idle, viewModel.updateState.value)
    }

    @Test
    fun downloadAndInstallUpdate_emitsProgressReadyToInstallAndInvokesInstaller() = runTest(testDispatcher) {
        val apkFile = File("build/test-update.apk")
        fakeUpdateRepo.downloadProgressSteps = listOf(20, 65, 100)
        fakeUpdateRepo.nextDownloadResult = Result.success(apkFile)

        val viewModel = createViewModel()
        val states = mutableListOf<AppUpdateState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.updateState.toList(states)
        }

        viewModel.downloadAndInstallUpdate("https://example.com/HomeKeyTV.apk")

        assertEquals("https://example.com/HomeKeyTV.apk", fakeUpdateRepo.lastDownloadUrl)
        assertTrue(states.contains(AppUpdateState.Downloading(0)))
        assertTrue(states.contains(AppUpdateState.Downloading(20)))
        assertTrue(states.contains(AppUpdateState.Downloading(65)))
        assertTrue(states.contains(AppUpdateState.Downloading(100)))
        assertEquals(AppUpdateState.ReadyToInstall(apkFile), viewModel.updateState.value)
        assertEquals(listOf(apkFile), fakeInstaller.installedApkFiles)
    }

    @Test
    fun downloadAndInstallUpdate_setsErrorStateOnFailureWithoutInvokingInstaller() = runTest(testDispatcher) {
        fakeUpdateRepo.nextDownloadResult = Result.failure(IOException("Connection reset"))

        val viewModel = createViewModel()
        viewModel.downloadAndInstallUpdate("https://example.com/broken.apk")

        val state = viewModel.updateState.value
        assertTrue(state is AppUpdateState.Error)
        assertEquals("Connection reset", (state as AppUpdateState.Error).message)
        assertTrue(fakeInstaller.installedApkFiles.isEmpty())
    }

    @Test
    fun loadInstalledApps_populatesAppsFromProvider() = runTest(testDispatcher) {
        val initialApps = listOf(
            InstalledAppInfo(packageName = "com.example.one", appName = "Alpha"),
            InstalledAppInfo(packageName = "com.example.two", appName = "Beta")
        )
        fakeAppsProvider.snapshot = InstalledAppsSnapshot(apps = initialApps)

        val viewModel = createViewModel()

        assertEquals(initialApps, viewModel.installedApps.value)
        assertEquals(1, fakeAppsProvider.loadCallCount)

        val updatedApps = listOf(InstalledAppInfo(packageName = "com.example.three", appName = "Gamma"))
        fakeAppsProvider.snapshot = InstalledAppsSnapshot(apps = updatedApps)
        viewModel.loadInstalledApps()

        assertEquals(updatedApps, viewModel.installedApps.value)
        assertEquals(2, fakeAppsProvider.loadCallCount)
    }

    @Test
    fun loadSetupQr_generatesUrlWhenIpAvailableAndSkipsWhenNull() = runTest(testDispatcher) {
        fakePrefs = FakePreferencesRepository(initialServerConfig = ServerConfig(haEnabled = false))
        fakeQrProvider.localIpAddress = null

        val viewModel = createViewModel()
        assertNull(viewModel.setupUrl.value)
        assertEquals(0, fakeQrProvider.ipCallCount)

        viewModel.loadSetupQr()
        assertNull(viewModel.setupUrl.value)
        assertEquals(0, fakeQrProvider.qrCallCount)

        fakeQrProvider.localIpAddress = "10.0.0.15"
        viewModel.loadSetupQr()
        assertEquals("http://10.0.0.15:8124", viewModel.setupUrl.value)
        assertEquals("http://10.0.0.15:8124", fakeQrProvider.lastGeneratedContent)
        assertEquals(400, fakeQrProvider.lastGeneratedSize)
    }

    @Test
    fun togglePinnedApp_addsAndRemovesAppsWithReindexing() = runTest(testDispatcher) {
        val viewModel = createViewModel()

        viewModel.togglePinnedApp("com.netflix.ninja", "Netflix")
        viewModel.togglePinnedApp("com.google.android.youtube.tv", "YouTube")

        assertEquals(2, viewModel.pinnedApps.value.size)
        assertEquals("com.netflix.ninja", viewModel.pinnedApps.value[0].packageName)
        assertEquals(0, viewModel.pinnedApps.value[0].order)
        assertEquals("com.google.android.youtube.tv", viewModel.pinnedApps.value[1].packageName)
        assertEquals(1, viewModel.pinnedApps.value[1].order)

        viewModel.togglePinnedApp("com.netflix.ninja", "Netflix")

        assertEquals(1, viewModel.pinnedApps.value.size)
        assertEquals("com.google.android.youtube.tv", viewModel.pinnedApps.value[0].packageName)
        assertEquals(0, viewModel.pinnedApps.value[0].order)
    }

    @Test
    fun saveOrUpdateAndRemoveButtonRemap_respectsKeyCodeAndAppScope() = runTest(testDispatcher) {
        val viewModel = createViewModel()

        val globalRule = ButtonRemapConfig(
            keyCode = 313,
            keyName = "Star",
            appScope = null,
            singlePressAction = RemapAction("OPEN_DOCK")
        )
        val scopedRule = ButtonRemapConfig(
            keyCode = 313,
            keyName = "Star",
            appScope = "com.netflix.ninja",
            singlePressAction = RemapAction("PASS_THROUGH")
        )
        val updatedGlobalRule = globalRule.copy(
            doublePressAction = RemapAction("SYSTEM_SETTINGS")
        )

        viewModel.saveOrUpdateButtonRemap(globalRule)
        viewModel.saveOrUpdateButtonRemap(scopedRule)
        assertEquals(2, viewModel.buttonRemaps.value.size)

        viewModel.saveOrUpdateButtonRemap(updatedGlobalRule)
        assertEquals(2, viewModel.buttonRemaps.value.size)
        assertEquals(
            "SYSTEM_SETTINGS",
            viewModel.buttonRemaps.value.first { it.appScope == null }.doublePressAction?.type
        )

        viewModel.removeButtonRemap(scopedRule)
        assertEquals(1, viewModel.buttonRemaps.value.size)
        assertNull(viewModel.buttonRemaps.value.first().appScope)

        viewModel.removeButtonRemap(313)
        assertTrue(viewModel.buttonRemaps.value.isEmpty())
    }

    @Test
    fun enableAccessibilityViaAdb_succeedsViaSecureSettingsWhenServiceBinds() = runTest(testDispatcher) {
        fakeAdb.secureSettingsResult = true
        fakeAdb.onSecureSettingsAttempt = {
            fakeCoordinator.setServiceRunning(true)
        }

        val viewModel = createViewModel()
        viewModel.enableAccessibilityViaAdb()

        assertEquals(1, fakeAdb.secureSettingsCallCount)
        assertEquals(0, fakeAdb.enableAdbCallCount)
        assertEquals(
            AdbSetupState.Success("Permissions active! Accessibility service is connected."),
            viewModel.adbSetupState.value
        )
    }

    @Test
    fun enableAccessibilityViaAdb_fallsBackToAdbAndReflectsServiceBinding() = runTest(testDispatcher) {
        fakeAdb.secureSettingsResult = false
        fakeAdb.adbResult = LocalAdbManager.AdbResult.Success
        fakeAdb.onAdbAttempt = {
            fakeCoordinator.setServiceRunning(true)
        }

        val viewModel = createViewModel()
        viewModel.enableAccessibilityViaAdb(port = 41235)

        assertEquals(1, fakeAdb.secureSettingsCallCount)
        assertEquals(1, fakeAdb.enableAdbCallCount)
        assertEquals(41235, fakeAdb.lastPort)
        assertEquals(
            AdbSetupState.Success("Permissions granted! Accessibility service is now active."),
            viewModel.adbSetupState.value
        )

        viewModel.resetAdbSetupState()
        assertEquals(AdbSetupState.Idle, viewModel.adbSetupState.value)
    }

    @Test
    fun enableAccessibilityViaAdb_setsErrorStateWhenAdbDisabled() = runTest(testDispatcher) {
        fakeAdb.secureSettingsResult = false
        fakeAdb.adbResult = LocalAdbManager.AdbResult.AdbDisabled("ADB debugging disabled")

        val viewModel = createViewModel()
        viewModel.enableAccessibilityViaAdb()

        assertEquals(AdbSetupState.Error("ADB debugging disabled"), viewModel.adbSetupState.value)
    }

    @Test
    fun configFacets_reflectUpdatedDockThemeAndRecentAppsPreferences() = runTest(testDispatcher) {
        val viewModel = createViewModel()

        viewModel.setDockLayout(DockConfig.LAYOUT_LEFT)
        viewModel.setPopupStyle(DockConfig.POPUP_STYLE_MINIMAL)
        viewModel.setCameraCompactSize(DockConfig.CAMERA_COMPACT_SIZE_LARGE)
        viewModel.setThemePreset("custom")
        viewModel.setCustomDomainColor("light", "#112233")
        viewModel.setRecentAppsEnabled(true)
        viewModel.setRecentAppsCount(4)

        assertEquals(DockConfig.LAYOUT_LEFT, viewModel.dockConfig.value.layout)
        assertEquals(DockConfig.POPUP_STYLE_MINIMAL, viewModel.dockConfig.value.popupStyle)
        assertEquals(DockConfig.CAMERA_COMPACT_SIZE_LARGE, viewModel.dockConfig.value.cameraCompactSize)
        assertEquals("custom", viewModel.themeConfig.value.preset)
        assertEquals("#112233", viewModel.themeConfig.value.customColors["light"])
        assertTrue(viewModel.recentAppsConfig.value.enabled)
        assertEquals(4, viewModel.recentAppsConfig.value.maxCount)

        viewModel.setRecentAppsEnabled(false)
        assertFalse(viewModel.recentAppsConfig.value.enabled)
    }
}
