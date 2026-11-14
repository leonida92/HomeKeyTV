package com.homekey.tv.util

import android.annotation.SuppressLint
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import com.homekey.tv.data.models.InstalledAppInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Snapshot of launchable installed applications and their decoded launcher icons.
 */
data class InstalledAppsSnapshot(
    val apps: List<InstalledAppInfo> = emptyList(),
    val icons: Map<String, Bitmap> = emptyMap()
)

/**
 * Provides launchable applications installed on the device.
 */
interface InstalledAppsProvider {
    suspend fun loadInstalledApps(): InstalledAppsSnapshot
}

/**
 * Android [PackageManager]-backed implementation of [InstalledAppsProvider].
 */
class AndroidInstalledAppsProvider(
    private val application: Application
) : InstalledAppsProvider {

    @SuppressLint("QueryPermissionsNeeded")
    override suspend fun loadInstalledApps(): InstalledAppsSnapshot = withContext(Dispatchers.IO) {
        val pm = application.packageManager ?: return@withContext InstalledAppsSnapshot()
        val myPkg = application.packageName ?: ""
        val apps = mutableListOf<InstalledAppInfo>()
        val seenPackages = mutableSetOf<String>()

        fun appendResolveInfos(resolveInfos: List<ResolveInfo>) {
            for (info in resolveInfos) {
                val pkg = info.activityInfo.packageName
                if (pkg != myPkg && seenPackages.add(pkg)) {
                    apps.add(
                        InstalledAppInfo(
                            packageName = pkg,
                            appName = info.loadLabel(pm).toString(),
                            icon = info.loadIcon(pm)
                        )
                    )
                }
            }
        }

        val leanbackIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        }
        appendResolveInfos(pm.queryIntentActivities(leanbackIntent, 0))

        val launcherIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        appendResolveInfos(pm.queryIntentActivities(launcherIntent, 0))

        val installedList = pm.getInstalledApplications(0)
        for (appInfo in installedList) {
            val pkg = appInfo.packageName
            if (pkg != myPkg && !seenPackages.contains(pkg)) {
                val launchIntent = pm.getLaunchIntentForPackage(pkg)
                if (launchIntent != null) {
                    seenPackages.add(pkg)
                    apps.add(
                        InstalledAppInfo(
                            packageName = pkg,
                            appName = appInfo.loadLabel(pm).toString(),
                            icon = appInfo.loadIcon(pm)
                        )
                    )
                }
            }
        }

        val sorted = apps.sortedBy { it.appName.lowercase() }
        val icons = HashMap<String, Bitmap>()
        for (installedApp in sorted) {
            installedApp.icon?.let { drawable ->
                drawableToBitmapSafe(drawable)?.let { icons[installedApp.packageName] = it }
            }
        }
        InstalledAppsSnapshot(apps = sorted, icons = icons)
    }
}

/**
 * Safely converts an Android [Drawable] to a [Bitmap], returning null if rendering fails.
 */
fun drawableToBitmapSafe(drawable: Drawable): Bitmap? {
    return try {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            drawable.bitmap
        } else {
            val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 96
            val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 96
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            bitmap
        }
    } catch (_: Exception) {
        null
    }
}

/**
 * Provides local network setup URL resolution and QR code bitmap generation.
 */
interface SetupQrProvider {
    suspend fun getLocalIpAddress(): String?
    suspend fun generateQrCode(content: String, size: Int = 400): Bitmap?
}

/**
 * Default production [SetupQrProvider] delegating to [NetworkUtils] and [QRCodeGenerator].
 */
object DefaultSetupQrProvider : SetupQrProvider {
    override suspend fun getLocalIpAddress(): String? = withContext(Dispatchers.IO) {
        NetworkUtils.getLocalIpAddress()
    }

    override suspend fun generateQrCode(content: String, size: Int): Bitmap? = withContext(Dispatchers.Default) {
        QRCodeGenerator.generateQRCode(content, size)
    }
}
