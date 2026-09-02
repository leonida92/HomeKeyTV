package com.homeassistant.tv.data.api

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.homeassistant.tv.data.models.AppUpdateState
import com.homeassistant.tv.data.models.GitHubRelease
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

class UpdateManager(private val context: Context) {

    private val tag = "UpdateManager"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    companion object {
        const val GITHUB_OWNER = "leonida92"
        const val GITHUB_REPO = "HomeKeyTV"
        const val GITHUB_API_URL = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"

        fun isNewerVersion(latestTag: String, currentVersion: String): Boolean {
            val cleanLatest = latestTag.trim().removePrefix("v").substringBefore("-")
            val cleanCurrent = currentVersion.trim().removePrefix("v").substringBefore("-")

            val latestParts = cleanLatest.split(".").mapNotNull { it.toIntOrNull() }
            val currentParts = cleanCurrent.split(".").mapNotNull { it.toIntOrNull() }

            val maxLen = maxOf(latestParts.size, currentParts.size)
            for (i in 0 until maxLen) {
                val l = latestParts.getOrElse(i) { 0 }
                val c = currentParts.getOrElse(i) { 0 }
                if (l > c) return true
                if (l < c) return false
            }
            return false
        }
    }

    suspend fun checkForUpdates(currentVersion: String): AppUpdateState = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(GITHUB_API_URL)
                .header("User-Agent", "HomeKeyTV-App")
                .header("Accept", "application/vnd.github.v3+json")
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                if (response.code == 404) {
                    return@withContext AppUpdateState.UpToDate(currentVersion)
                }
                return@withContext AppUpdateState.Error("GitHub returned HTTP ${response.code}")
            }

            val body = response.body?.string() ?: return@withContext AppUpdateState.Error("Empty response from GitHub")
            val release = json.decodeFromString<GitHubRelease>(body)

            val latestTag = release.tagName.ifBlank { release.name }
            if (isNewerVersion(latestTag, currentVersion)) {
                // Find apk asset if available
                val apkAsset = release.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
                AppUpdateState.UpdateAvailable(
                    currentVersion = currentVersion,
                    latestVersion = latestTag,
                    releaseTitle = release.name.ifBlank { latestTag },
                    releaseNotes = release.body?.trim() ?: "New version available on GitHub.",
                    releaseUrl = release.htmlUrl,
                    downloadUrl = apkAsset?.downloadUrl
                )
            } else {
                AppUpdateState.UpToDate(currentVersion)
            }
        } catch (e: Exception) {
            Log.e(tag, "Error checking for updates", e)
            AppUpdateState.Error(e.message ?: "Failed to check for updates")
        }
    }

    suspend fun downloadApk(downloadUrl: String, onProgress: (Int) -> Unit): Result<File> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(downloadUrl)
                .header("User-Agent", "HomeKeyTV-App")
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(IOException("Download failed with HTTP ${response.code}"))
            }

            val body = response.body ?: return@withContext Result.failure(IOException("Empty body from server"))
            val contentLength = body.contentLength()

            val updateDir = File(context.cacheDir, "updates")
            if (!updateDir.exists()) updateDir.mkdirs()
            val apkFile = File(updateDir, "HomeKeyTV_update.apk")

            body.byteStream().use { input ->
                FileOutputStream(apkFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalBytesRead = 0L
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalBytesRead += bytesRead
                        if (contentLength > 0) {
                            val percent = ((totalBytesRead * 100) / contentLength).toInt()
                            onProgress(percent)
                        }
                    }
                }
            }

            Result.success(apkFile)
        } catch (e: Exception) {
            Log.e(tag, "Error downloading update APK", e)
            Result.failure(e)
        }
    }

    fun promptInstallApk(apkFile: File) {
        try {
            val authority = "${context.packageName}.fileprovider"
            val uri: Uri = FileProvider.getUriForFile(context, authority, apkFile)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(tag, "Error launching package installer for APK", e)
        }
    }

    fun openBrowserUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(tag, "Error opening browser URL", e)
        }
    }
}
