package com.homekey.tv.ui.panel

import android.graphics.Bitmap
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import com.homekey.tv.data.api.CameraFeedState
import com.homekey.tv.data.models.CameraPipAlert
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow

@Composable
fun CameraPipCard(
    alert: CameraPipAlert,
    feedFlow: Flow<CameraFeedState>,
    modifier: Modifier = Modifier,
    onDismiss: () -> Unit = {}
) {
    var currentBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var secondsRemaining by remember { mutableIntStateOf(alert.durationSeconds) }

    DisposableEffect(alert.entityId) {
        onDispose {
            currentBitmap = null
        }
    }

    // Countdown timer for user feedback (dismissal owned by CameraPipOverlayController)
    LaunchedEffect(alert.durationSeconds, alert.timestamp) {
        secondsRemaining = alert.durationSeconds
        while (secondsRemaining > 0) {
            delay(1000L)
            secondsRemaining--
        }
    }

    // Collect pre-wired software-decoded stream with snapshot polling fallback synced to active route
    LaunchedEffect(alert.entityId, feedFlow) {
        currentBitmap = null
        errorMessage = null
        feedFlow.collect { feedState ->
            when (feedState) {
                is CameraFeedState.Connecting -> {
                    errorMessage = null
                }
                is CameraFeedState.Streaming -> {
                    currentBitmap = feedState.bitmap
                    errorMessage = null
                }
                is CameraFeedState.Error -> {
                    if (currentBitmap == null) {
                        errorMessage = feedState.message
                    }
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
                        .clickable { onDismiss() }
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
