package com.homekey.tv.ui.panel

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.homekey.tv.data.models.CameraPipAlert
import kotlinx.coroutines.*
import okhttp3.Request
import java.util.concurrent.CancellationException
import kotlin.math.abs

private const val TAG = "CameraPipCard"

@Composable
fun CameraPipCard(
    alert: CameraPipAlert,
    serverUrl: String,
    accessToken: String,
    modifier: Modifier = Modifier,
    onDismiss: () -> Unit = {}
) {
    var currentBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var detectedAspectRatio by remember { mutableFloatStateOf(16f / 9f) }
    var isLive by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var secondsRemaining by remember { mutableIntStateOf(alert.durationSeconds) }

    DisposableEffect(alert.entityId) {
        onDispose {
            currentBitmap = null
        }
    }

    // Countdown timer for user feedback
    LaunchedEffect(alert.durationSeconds) {
        secondsRemaining = alert.durationSeconds
        while (secondsRemaining > 0) {
            delay(1000L)
            secondsRemaining--
        }
        onDismiss()
    }

    val cleanBaseUrl = remember(serverUrl) { serverUrl.trim().removeSuffix("/") }
    val streamUrl = remember(cleanBaseUrl, alert.entityId) { "$cleanBaseUrl/api/camera_proxy_stream/${alert.entityId}" }
    val snapshotUrl = remember(cleanBaseUrl, alert.entityId) { "$cleanBaseUrl/api/camera_proxy/${alert.entityId}" }

    // MJPEG software-decoded stream with snapshot polling fallback
    LaunchedEffect(alert.entityId, streamUrl, snapshotUrl) {
        isLive = false
        errorMessage = null

        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(streamUrl)
                    .header("Authorization", "Bearer $accessToken")
                    .build()

                val call = cameraHttpClient.newCall(request)
                val cancelHandle = currentCoroutineContext()[Job]?.invokeOnCompletion {
                    call.cancel()
                }
                try {
                    call.execute().use { response ->
                        if (response.isSuccessful) {
                            val body = response.body
                            if (body != null) {
                                body.byteStream().use { inputStream ->
                                    withContext(Dispatchers.Main) {
                                        isLive = true
                                    }
                                    parseMjpegStream(
                                        inputStream = inputStream,
                                        onFrameDecoded = { bitmap ->
                                            val ratio = bitmap.width.toFloat() / bitmap.height.toFloat()
                                            if (abs(ratio - detectedAspectRatio) > 0.05f) {
                                                detectedAspectRatio = ratio
                                            }
                                            currentBitmap = bitmap
                                            if (!isLive) isLive = true
                                        }
                                    )
                                }
                            }
                        }
                    }
                } catch (ce: CancellationException) {
                    call.cancel()
                    throw ce
                } catch (e: Exception) {
                    call.cancel()
                    Log.w(TAG, "MJPEG stream ended: ${e.message}")
                } finally {
                    cancelHandle?.dispose()
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                Log.w(TAG, "MJPEG connection error: ${e.message}")
            }

            // Fallback to snapshot polling if stream ended or was unavailable
            if (isActive) {
                while (isActive) {
                    val snapRequest = Request.Builder()
                        .url(snapshotUrl)
                        .header("Authorization", "Bearer $accessToken")
                        .build()

                    val snapCall = cameraHttpClient.newCall(snapRequest)
                    val snapCancelHandle = currentCoroutineContext()[Job]?.invokeOnCompletion {
                        snapCall.cancel()
                    }
                    try {
                        snapCall.execute().use { response ->
                            if (response.isSuccessful) {
                                val bytes = response.body?.bytes()
                                if (bytes != null && bytes.isNotEmpty()) {
                                    val bmp = decodeSampledBitmap(bytes)
                                    if (bmp != null) {
                                        withContext(Dispatchers.Main) {
                                            val ratio = bmp.width.toFloat() / bmp.height.toFloat()
                                            if (abs(ratio - detectedAspectRatio) > 0.05f) {
                                                detectedAspectRatio = ratio
                                            }
                                            currentBitmap = bmp
                                            isLive = true
                                        }
                                    }
                                }
                            } else {
                                withContext(Dispatchers.Main) {
                                    if (currentBitmap == null) {
                                        errorMessage = "Camera offline"
                                    }
                                }
                            }
                        }
                    } catch (ce: CancellationException) {
                        snapCall.cancel()
                        throw ce
                    } catch (e: Exception) {
                        snapCall.cancel()
                        withContext(Dispatchers.Main) {
                            if (currentBitmap == null) {
                                errorMessage = e.message ?: "Connection error"
                            }
                        }
                    } finally {
                        snapCancelHandle?.dispose()
                    }
                    delay(1500L)
                }
            }
        }
    }

    // Pulse animation for alert badge
    val infiniteTransition = rememberInfiniteTransition(label = "pip_pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(650, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF0F172A))
            .border(2.dp, Color(0xFFEF4444), RoundedCornerShape(16.dp))
    ) {
        val bitmap = currentBitmap
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = alert.title ?: "Camera Alert",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else if (errorMessage != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF1E293B)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFEF4444), modifier = Modifier.size(24.dp))
                    Text(text = errorMessage ?: "Camera offline", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF1E293B)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(color = Color(0xFF38BDF8), modifier = Modifier.size(28.dp), strokeWidth = 2.5.dp)
                    Text(text = "Connecting...", color = Color(0xFF94A3B8), fontSize = 11.sp)
                }
            }
        }

        // Top Gradient Scrim & Notification Header
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        colors = listOf(Color(0xEE000000), Color(0x88000000), Color.Transparent)
                    )
                )
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFEF4444).copy(alpha = pulseAlpha))
                    )
                    Column {
                        Text(
                            text = alert.title?.ifBlank { null } ?: alert.entityId.substringAfter("."),
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (!alert.message.isNullOrBlank()) {
                            Text(
                                text = alert.message,
                                color = Color(0xFFCBD5E1),
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0x66000000))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "${secondsRemaining}s",
                        color = Color(0xFF38BDF8),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
