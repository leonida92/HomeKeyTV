package com.homekey.tv.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.homekey.tv.data.models.AppUpdateState
import com.homekey.tv.ui.panel.FocusableButton
import com.homekey.tv.ui.theme.HA_Blue
import com.homekey.tv.ui.theme.HA_Green_On
import com.homekey.tv.ui.theme.HA_Red_Off
import com.homekey.tv.ui.theme.HA_Yellow_On
import com.homekey.tv.ui.theme.TV_Text_Secondary
import java.io.File

@Composable
fun UpdatesView(
    appVersion: String,
    updateState: AppUpdateState,
    actionButtonFocusRequester: FocusRequester = remember { FocusRequester() },
    onCheckForUpdates: () -> Unit,
    onDownloadAndInstall: (String) -> Unit,
    onInstallApk: (File) -> Unit,
    onOpenReleaseUrl: (String) -> Unit,
    onDismiss: () -> Unit,
    onUp: (() -> Unit)? = null
) {
    val upModifier = if (onUp != null) {
        Modifier.onPreviewKeyEvent { keyEvent ->
            if (keyEvent.type == KeyEventType.KeyDown &&
                keyEvent.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP
            ) {
                onUp()
                true
            } else {
                false
            }
        }
    } else {
        Modifier
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "App Updates",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = "Installed Version: v$appVersion",
                    fontSize = 14.sp,
                    color = TV_Text_Secondary
                )
            }

            Text(
                text = "Repository: leonida92/HomeKeyTV",
                fontSize = 12.sp,
                color = HA_Blue,
                fontWeight = FontWeight.SemiBold
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF1E293B))
                .border(1.dp, Color(0x33475569), RoundedCornerShape(16.dp))
                .padding(24.dp)
        ) {
            when (updateState) {
                is AppUpdateState.Idle -> {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(
                            text = "Check directly with GitHub for new versions and improvements.",
                            fontSize = 14.sp,
                            color = Color.White
                        )
                        FocusableButton(
                            text = "Check for Updates",
                            modifier = Modifier.focusRequester(actionButtonFocusRequester).then(upModifier),
                            onClick = onCheckForUpdates
                        )
                    }
                }

                is AppUpdateState.Checking -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier
                            .focusRequester(actionButtonFocusRequester)
                            .then(upModifier)
                            .focusable()
                    ) {
                        CircularProgressIndicator(
                            color = HA_Blue,
                            modifier = Modifier.size(28.dp),
                            strokeWidth = 3.dp
                        )
                        Column {
                            Text(
                                text = "Checking for updates...",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = "Connecting to GitHub API (leonida92/HomeKeyTV)...",
                                fontSize = 13.sp,
                                color = TV_Text_Secondary
                            )
                        }
                    }
                }

                is AppUpdateState.UpToDate -> {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = HA_Green_On,
                                modifier = Modifier.size(28.dp)
                            )
                            Column {
                                Text(
                                    text = "HomeKey TV is up to date",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text(
                                    text = "You are running the latest version (v${updateState.version}).",
                                    fontSize = 13.sp,
                                    color = TV_Text_Secondary
                                )
                            }
                        }

                        FocusableButton(
                            text = "Check Again",
                            modifier = Modifier.focusRequester(actionButtonFocusRequester).then(upModifier),
                            onClick = onCheckForUpdates
                        )
                    }
                }

                is AppUpdateState.UpdateAvailable -> {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.SystemUpdate,
                                contentDescription = null,
                                tint = HA_Yellow_On,
                                modifier = Modifier.size(28.dp)
                            )
                            Column {
                                Text(
                                    text = "Update Available: ${updateState.latestVersion}",
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text(
                                    text = updateState.releaseTitle,
                                    fontSize = 13.sp,
                                    color = HA_Yellow_On
                                )
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (!updateState.downloadUrl.isNullOrBlank()) {
                                FocusableButton(
                                    text = "Download & Install",
                                    modifier = Modifier.focusRequester(actionButtonFocusRequester).then(upModifier),
                                    onClick = { onDownloadAndInstall(updateState.downloadUrl) }
                                )
                            }
                            FocusableButton(
                                text = "View Release Page",
                                modifier = (if (updateState.downloadUrl.isNullOrBlank()) Modifier.focusRequester(actionButtonFocusRequester) else Modifier).then(upModifier),
                                onClick = { onOpenReleaseUrl(updateState.releaseUrl) }
                            )
                            FocusableButton(
                                text = "Dismiss",
                                modifier = upModifier,
                                onClick = onDismiss
                            )
                        }

                        if (updateState.releaseNotes.isNotBlank()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 140.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF0F172A))
                                    .verticalScroll(rememberScrollState())
                                    .padding(12.dp)
                            ) {
                                Text(
                                    text = updateState.releaseNotes,
                                    fontSize = 13.sp,
                                    color = Color(0xFFCBD5E1)
                                )
                            }
                        }
                    }
                }

                is AppUpdateState.Downloading -> {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier
                            .focusRequester(actionButtonFocusRequester)
                            .then(upModifier)
                            .focusable()
                    ) {
                        Text(
                            text = "Downloading Update... ${updateState.progressPercent}%",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        LinearProgressIndicator(
                            progress = { updateState.progressPercent / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = HA_Blue,
                            trackColor = Color(0xFF0F172A)
                        )
                    }
                }

                is AppUpdateState.ReadyToInstall -> {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = HA_Green_On,
                                modifier = Modifier.size(28.dp)
                            )
                            Column {
                                Text(
                                    text = "Download Complete",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text(
                                    text = "Package ready. Click below to launch installer.",
                                    fontSize = 13.sp,
                                    color = TV_Text_Secondary
                                )
                            }
                        }

                        FocusableButton(
                            text = "Install Update",
                            modifier = Modifier.focusRequester(actionButtonFocusRequester).then(upModifier),
                            onClick = { onInstallApk(updateState.apkFile) }
                        )
                    }
                }

                is AppUpdateState.Error -> {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Error,
                                contentDescription = null,
                                tint = HA_Red_Off,
                                modifier = Modifier.size(28.dp)
                            )
                            Column {
                                Text(
                                    text = "Update Check Failed",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text(
                                    text = updateState.message,
                                    fontSize = 13.sp,
                                    color = HA_Red_Off
                                )
                            }
                        }

                        FocusableButton(
                            text = "Retry",
                            modifier = Modifier.focusRequester(actionButtonFocusRequester).then(upModifier),
                            onClick = onCheckForUpdates
                        )
                    }
                }
            }
        }
    }
}
