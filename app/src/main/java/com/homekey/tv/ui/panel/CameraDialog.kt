package com.homekey.tv.ui.panel

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.homekey.tv.data.local.PreferencesManager
import com.homekey.tv.data.models.DomainColorPalette
import com.homekey.tv.data.models.HAEntityState
import com.homekey.tv.data.models.LocalThemePalette
import com.homekey.tv.ui.theme.*
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.math.abs

private const val TAG = "CameraDialog"

private enum class StreamStatus {
    CONNECTING,
    LIVE,
    SNAPSHOT,
    ERROR
}

@Composable
fun CameraDialog(
    entity: HAEntityState,
    serverUrl: String,
    accessToken: String,
    isCompact: Boolean = false,
    compactSize: String = PreferencesManager.CAMERA_COMPACT_SIZE_MEDIUM,
    onDismiss: () -> Unit
) {
    val palette = LocalThemePalette.current
    val cameraColor = palette.getColorForDomain("camera")
    val offColor = palette.getOffBackgroundColor()
    val isOffDark = DomainColorPalette.isColorDark(offColor)
    val textColor = if (isOffDark) Color.White else Color(0xFF0F172A)
    var streamStatus by remember { mutableStateOf(StreamStatus.CONNECTING) }
    var currentBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var detectedAspectRatio by remember { mutableFloatStateOf(16f / 9f) }
    var reconnectTrigger by remember { mutableIntStateOf(0) }

    val closeFocusRequester = remember { FocusRequester() }
    val reconnectFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        try {
            closeFocusRequester.requestFocus()
        } catch (_: Exception) {}
    }

    BackHandler(onBack = onDismiss)

    val cleanBaseUrl = remember(serverUrl) { serverUrl.trim().removeSuffix("/") }
    val streamUrl = remember(cleanBaseUrl, entity.entityId) { "$cleanBaseUrl/api/camera_proxy_stream/${entity.entityId}" }
    val snapshotUrl = remember(cleanBaseUrl, entity.entityId) { "$cleanBaseUrl/api/camera_proxy/${entity.entityId}" }

    val client = remember {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    // Live MJPEG streaming with automatic still snapshot fallback
    LaunchedEffect(entity.entityId, reconnectTrigger, streamUrl, snapshotUrl) {
        streamStatus = StreamStatus.CONNECTING
        errorMessage = null

        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(streamUrl)
                    .header("Authorization", "Bearer $accessToken")
                    .build()

                val call = client.newCall(request)
                try {
                    val response = call.execute()
                    if (response.isSuccessful) {
                        val body = response.body
                        if (body != null) {
                            val inputStream = body.byteStream()
                            withContext(Dispatchers.Main) {
                                streamStatus = StreamStatus.LIVE
                            }
                            parseMjpegStream(
                                inputStream = inputStream,
                                onFrameDecoded = { bitmap ->
                                    val ratio = bitmap.width.toFloat() / bitmap.height.toFloat()
                                    if (abs(ratio - detectedAspectRatio) > 0.05f) {
                                        detectedAspectRatio = ratio
                                    }
                                    currentBitmap = bitmap
                                    if (streamStatus != StreamStatus.LIVE) {
                                        streamStatus = StreamStatus.LIVE
                                    }
                                }
                            )
                        }
                    }
                } catch (ce: CancellationException) {
                    call.cancel()
                    throw ce
                } catch (e: Exception) {
                    call.cancel()
                    Log.w(TAG, "MJPEG stream ended or failed: ${e.message}")
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                Log.w(TAG, "MJPEG connection error: ${e.message}")
            }

            // If MJPEG wasn't supported or terminated, smoothly fallback to snapshot polling
            if (isActive) {
                withContext(Dispatchers.Main) {
                    streamStatus = StreamStatus.SNAPSHOT
                }
                while (isActive) {
                    val snapRequest = Request.Builder()
                        .url(snapshotUrl)
                        .header("Authorization", "Bearer $accessToken")
                        .build()

                    val snapCall = client.newCall(snapRequest)
                    try {
                        val response = snapCall.execute()
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
                                        streamStatus = StreamStatus.SNAPSHOT
                                    }
                                }
                            }
                        } else {
                            withContext(Dispatchers.Main) {
                                streamStatus = StreamStatus.ERROR
                                errorMessage = "Camera offline (HTTP ${response.code})"
                            }
                        }
                    } catch (ce: CancellationException) {
                        snapCall.cancel()
                        throw ce
                    } catch (e: Exception) {
                        snapCall.cancel()
                        withContext(Dispatchers.Main) {
                            if (currentBitmap == null) {
                                streamStatus = StreamStatus.ERROR
                                errorMessage = e.message ?: "Connection failed"
                            }
                        }
                    }
                    delay(1500L)
                }
            }
        }
    }

    // Pulse animation for LIVE indicator
    val infiniteTransition = rememberInfiniteTransition(label = "live_indicator")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(750, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )

    if (isCompact) {
        val (baseVideoHeight, maxVideoWidth) = when (compactSize) {
            PreferencesManager.CAMERA_COMPACT_SIZE_SMALL -> Pair(180.dp, 380.dp)
            PreferencesManager.CAMERA_COMPACT_SIZE_LARGE -> Pair(290.dp, 680.dp)
            else -> Pair(230.dp, 520.dp)
        }

        // Active aspect ratio derived from the live decoded bitmap or fallback to detectedAspectRatio
        val activeAspect = currentBitmap?.let { bmp ->
            if (bmp.height > 0) bmp.width.toFloat() / bmp.height.toFloat() else null
        } ?: detectedAspectRatio
        val safeAspect = activeAspect.coerceIn(0.45f, 2.5f)

        // Calculate video viewport dimensions matching the exact aspect ratio
        val rawWidth = baseVideoHeight * safeAspect
        val (videoWidth, videoHeight) = if (rawWidth > maxVideoWidth) {
            Pair(maxVideoWidth, maxVideoWidth / safeAspect)
        } else {
            Pair(rawWidth, baseVideoHeight)
        }

        val minCardWidth = 260.dp
        val cardWidth = if (videoWidth + 24.dp > minCardWidth) videoWidth + 24.dp else minCardWidth
        val cardHeight = videoHeight + 64.dp

        val animatedCardWidth by animateDpAsState(
            targetValue = cardWidth,
            animationSpec = spring(dampingRatio = 0.8f, stiffness = 350f),
            label = "camera_card_w"
        )
        val animatedCardHeight by animateDpAsState(
            targetValue = cardHeight,
            animationSpec = spring(dampingRatio = 0.8f, stiffness = 350f),
            label = "camera_card_h"
        )
        val animatedVideoWidth by animateDpAsState(
            targetValue = videoWidth,
            animationSpec = spring(dampingRatio = 0.8f, stiffness = 350f),
            label = "camera_video_w"
        )
        val animatedVideoHeight by animateDpAsState(
            targetValue = videoHeight,
            animationSpec = spring(dampingRatio = 0.8f, stiffness = 350f),
            label = "camera_video_h"
        )

        // Compact floating video card directly adjacent to the dock
        Column(
            modifier = Modifier
                .width(animatedCardWidth)
                .height(animatedCardHeight)
                .clip(RoundedCornerShape(18.dp))
                .background(offColor)
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Header: Camera Info + Status Pill + Close
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(cameraColor.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Videocam,
                            contentDescription = null,
                            tint = cameraColor,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Text(
                        text = entity.friendlyName,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = textColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Status Pill
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                when (streamStatus) {
                                    StreamStatus.LIVE -> Color(0x334CAF50)
                                    StreamStatus.SNAPSHOT -> Color(0x33FFB74D)
                                    StreamStatus.CONNECTING -> Color(0x3342A5F5)
                                    StreamStatus.ERROR -> Color(0x33EF5350)
                                }
                            )
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(
                                    when (streamStatus) {
                                        StreamStatus.LIVE -> Color(0xFF4CAF50).copy(alpha = pulseAlpha)
                                        StreamStatus.SNAPSHOT -> Color(0xFFFFB74D)
                                        StreamStatus.CONNECTING -> Color(0xFF42A5F5)
                                        StreamStatus.ERROR -> Color(0xFFEF5350)
                                    }
                                )
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = when (streamStatus) {
                                StreamStatus.LIVE -> "LIVE"
                                StreamStatus.SNAPSHOT -> "SNAPSHOT"
                                StreamStatus.CONNECTING -> "CONNECTING..."
                                StreamStatus.ERROR -> "OFFLINE"
                            },
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = when (streamStatus) {
                                StreamStatus.LIVE -> Color(0xFF4CAF50)
                                StreamStatus.SNAPSHOT -> Color(0xFFFFB74D)
                                StreamStatus.CONNECTING -> Color(0xFF42A5F5)
                                StreamStatus.ERROR -> Color(0xFFEF5350)
                            }
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    FocusableIconButton(
                        icon = Icons.Default.Close,
                        description = "Close",
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(28.dp)
                            .focusRequester(closeFocusRequester)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Main Viewport matching exact video aspect ratio
            Box(
                modifier = Modifier
                    .width(animatedVideoWidth)
                    .height(animatedVideoHeight)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                if (currentBitmap != null) {
                    Image(
                        bitmap = currentBitmap!!.asImageBitmap(),
                        contentDescription = entity.friendlyName,
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                if (streamStatus == StreamStatus.CONNECTING && currentBitmap == null) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(
                            color = cameraColor,
                            modifier = Modifier.size(32.dp),
                            strokeWidth = 2.5.dp
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Connecting to camera...",
                            fontSize = 11.sp,
                            color = TV_Text_Secondary
                        )
                    }
                } else if (streamStatus == StreamStatus.ERROR && currentBitmap == null) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(8.dp)
                    ) {
                        Text(
                            text = errorMessage ?: "Camera offline",
                            fontSize = 11.sp,
                            color = Color(0xFFEF5350)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        FocusableButton(
                            text = "Retry",
                            icon = Icons.Default.Refresh,
                            onClick = { reconnectTrigger++ },
                            modifier = Modifier.focusRequester(reconnectFocusRequester)
                        )
                    }
                }
            }
        }
    } else {
        // Floating centered modal dialog container
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.70f)),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .widthIn(min = 480.dp, max = 880.dp)
                    .heightIn(min = 320.dp, max = 580.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(offColor)
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Header: Camera Info + Status Badge
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f, fill = false)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(cameraColor.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Videocam,
                                contentDescription = null,
                                tint = cameraColor,
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column {
                            Text(
                                text = entity.friendlyName,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                color = textColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = entity.entityId,
                                fontSize = 11.sp,
                                color = TV_Text_Secondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    // Status Pill
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(
                                when (streamStatus) {
                                    StreamStatus.LIVE -> Color(0x334CAF50)
                                    StreamStatus.SNAPSHOT -> Color(0x33FFB74D)
                                    StreamStatus.CONNECTING -> Color(0x3342A5F5)
                                    StreamStatus.ERROR -> Color(0x33EF5350)
                                }
                            )
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(
                                    when (streamStatus) {
                                        StreamStatus.LIVE -> Color(0xFF4CAF50).copy(alpha = pulseAlpha)
                                        StreamStatus.SNAPSHOT -> Color(0xFFFFB74D)
                                        StreamStatus.CONNECTING -> Color(0xFF42A5F5)
                                        StreamStatus.ERROR -> Color(0xFFEF5350)
                                    }
                                )
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = when (streamStatus) {
                                StreamStatus.LIVE -> "LIVE"
                                StreamStatus.SNAPSHOT -> "SNAPSHOT"
                                StreamStatus.CONNECTING -> "CONNECTING..."
                                StreamStatus.ERROR -> "OFFLINE"
                            },
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = when (streamStatus) {
                                StreamStatus.LIVE -> Color(0xFF4CAF50)
                                StreamStatus.SNAPSHOT -> Color(0xFFFFB74D)
                                StreamStatus.CONNECTING -> Color(0xFF42A5F5)
                                StreamStatus.ERROR -> Color(0xFFEF5350)
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Main Video Viewport (auto-adapts aspect ratio dynamically)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    if (currentBitmap != null) {
                        Image(
                            bitmap = currentBitmap!!.asImageBitmap(),
                            contentDescription = entity.friendlyName,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .aspectRatio(detectedAspectRatio, matchHeightConstraintsFirst = true)
                        )
                    }

                    if (streamStatus == StreamStatus.CONNECTING && currentBitmap == null) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(
                                color = cameraColor,
                                modifier = Modifier.size(42.dp),
                                strokeWidth = 3.dp
                            )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Connecting to camera stream...",
                            fontSize = 13.sp,
                            color = TV_Text_Secondary
                        )
                    }
                } else if (streamStatus == StreamStatus.ERROR && currentBitmap == null) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = Color(0xFFEF5350),
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = errorMessage ?: "Unable to display camera stream",
                            fontSize = 13.sp,
                            color = Color(0xFFEF5350),
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Action Footer
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (streamStatus == StreamStatus.ERROR) {
                    FocusableButton(
                        text = "Reconnect",
                        icon = Icons.Default.Refresh,
                        onClick = {
                            reconnectTrigger++
                        },
                        modifier = Modifier
                            .focusRequester(reconnectFocusRequester)
                            .padding(end = 12.dp)
                    )
                }

                FocusableButton(
                    text = "Close",
                    icon = Icons.Default.Close,
                    onClick = onDismiss,
                    modifier = Modifier.focusRequester(closeFocusRequester)
                )
            }
        }
    }
}
}

/**
 * Parses multipart MJPEG chunks from the HTTP input stream, extracting individual JPEG images
 * bounded by 0xFF 0xD8 (SOI) and 0xFF 0xD9 (EOI) markers.
 */
private suspend fun parseMjpegStream(
    inputStream: InputStream,
    onFrameDecoded: suspend (Bitmap) -> Unit
) {
    val buffer = ByteArray(16384)
    val frameStream = ByteArrayOutputStream(65536)
    var inJpeg = false
    var prevByte = 0

    var bytesRead = 0
    while (currentCoroutineContext().isActive && inputStream.read(buffer).also { bytesRead = it } != -1) {
        for (i in 0 until bytesRead) {
            val b = buffer[i].toInt() and 0xFF
            if (!inJpeg) {
                if (prevByte == 0xFF && b == 0xD8) {
                    inJpeg = true
                    frameStream.reset()
                    frameStream.write(0xFF)
                    frameStream.write(0xD8)
                }
            } else {
                frameStream.write(b)
                if (prevByte == 0xFF && b == 0xD9) {
                    inJpeg = false
                    val jpegBytes = frameStream.toByteArray()
                    frameStream.reset()
                    val bmp = decodeSampledBitmap(jpegBytes)
                    if (bmp != null) {
                        onFrameDecoded(bmp)
                    }
                }
            }
            prevByte = b
        }
    }
}

/**
 * Decodes JPEG bytes with adaptive downsampling to 1080p maximum using RGB_565 configuration,
 * saving 50% RAM and avoiding CPU spikes on budget Android TV hardware.
 */
private fun decodeSampledBitmap(jpegBytes: ByteArray): Bitmap? {
    return try {
        val boundsOptions = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, boundsOptions)
        val rawW = boundsOptions.outWidth
        val rawH = boundsOptions.outHeight
        if (rawW <= 0 || rawH <= 0) return null

        var sampleSize = 1
        while ((rawW / sampleSize) > 1920 || (rawH / sampleSize) > 1080) {
            sampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, decodeOptions)
    } catch (e: Exception) {
        Log.e(TAG, "Error decoding frame bitmap", e)
        null
    }
}
