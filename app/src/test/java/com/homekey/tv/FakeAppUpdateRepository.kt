package com.homekey.tv

import com.homekey.tv.data.api.AppInstaller
import com.homekey.tv.data.api.AppUpdateRepository
import com.homekey.tv.data.models.AppUpdateState
import kotlinx.coroutines.yield
import java.io.File

class FakeAppUpdateRepository : AppUpdateRepository {
    var nextCheckResult: AppUpdateState = AppUpdateState.UpToDate("1.0.0")
    var downloadProgressSteps: List<Int> = listOf(25, 75, 100)
    var nextDownloadResult: Result<File> = Result.success(File("fake_update.apk"))

    var checkCallCount = 0
        private set
    var lastCheckedVersion: String? = null
        private set
    var downloadCallCount = 0
        private set
    var lastDownloadUrl: String? = null
        private set

    override suspend fun checkForUpdates(currentVersion: String): AppUpdateState {
        yield()
        checkCallCount++
        lastCheckedVersion = currentVersion
        return nextCheckResult
    }

    override suspend fun downloadApk(downloadUrl: String, onProgress: (Int) -> Unit): Result<File> {
        yield()
        downloadCallCount++
        lastDownloadUrl = downloadUrl
        downloadProgressSteps.forEach { progress ->
            onProgress(progress)
            yield()
        }
        return nextDownloadResult
    }
}

class FakeAppInstaller : AppInstaller {
    val installedApkFiles = mutableListOf<File>()
    val openedUrls = mutableListOf<String>()

    override fun promptInstallApk(apkFile: File) {
        installedApkFiles.add(apkFile)
    }

    override fun openBrowserUrl(url: String) {
        openedUrls.add(url)
    }
}
