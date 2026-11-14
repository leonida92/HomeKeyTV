package com.homekey.tv.data.api

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

private const val TAG = "MjpegCameraStreamClient"

/**
 * Presentation-ready state of a software-decoded camera feed (ADR 0007).
 */
sealed interface CameraFeedState {
    data object Connecting : CameraFeedState
    data class Streaming(
        val bitmap: Bitmap,
        val aspectRatio: Float,
        val isLive: Boolean
    ) : CameraFeedState

    data class Error(val message: String) : CameraFeedState
}

internal data class DecodedFrame<out T>(
    val image: T,
    val width: Int,
    val height: Int
) {
    val aspectRatio: Float
        get() = if (height > 0) width.toFloat() / height.toFloat() else 16f / 9f
}

internal sealed interface StreamPipelineState<out T> {
    data object Connecting : StreamPipelineState<Nothing>
    data class Streaming<out T>(
        val frame: T,
        val aspectRatio: Float,
        val isLive: Boolean
    ) : StreamPipelineState<T>

    data class Error(val message: String) : StreamPipelineState<Nothing>
}

internal sealed interface MjpegStreamOutcome {
    data object Completed : MjpegStreamOutcome
    data class HttpError(val code: Int) : MjpegStreamOutcome
    data class Failure(val message: String) : MjpegStreamOutcome
}

internal fun interface MjpegStreamTransport {
    suspend fun stream(
        streamUrl: String,
        accessToken: String,
        onStreamOpened: suspend (InputStream) -> Unit
    ): MjpegStreamOutcome
}

internal sealed interface SnapshotResult {
    data class Success(val bytes: ByteArray) : SnapshotResult
    data class HttpError(val code: Int) : SnapshotResult
    data class Failure(val message: String) : SnapshotResult
}

internal fun interface SnapshotFetcher {
    suspend fun fetchSnapshot(snapshotUrl: String, accessToken: String): SnapshotResult
}

private class OkHttpMjpegStreamTransport(
    private val client: OkHttpClient
) : MjpegStreamTransport {
    override suspend fun stream(
        streamUrl: String,
        accessToken: String,
        onStreamOpened: suspend (InputStream) -> Unit
    ): MjpegStreamOutcome = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(streamUrl)
            .header("Authorization", "Bearer $accessToken")
            .build()

        val call = client.newCall(request)
        // Register cancellation on the withContext Job so cancelling the collector aborts
        // blocking socket reads immediately instead of waiting for readTimeout.
        val cancelHandle = currentCoroutineContext()[Job]?.invokeOnCompletion {
            call.cancel()
        }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext MjpegStreamOutcome.HttpError(response.code)
                }
                val body = response.body
                    ?: return@withContext MjpegStreamOutcome.Failure("Empty MJPEG response body")
                body.byteStream().use { inputStream ->
                    onStreamOpened(inputStream)
                }
                MjpegStreamOutcome.Completed
            }
        } catch (ce: CancellationException) {
            call.cancel()
            throw ce
        } catch (e: Exception) {
            call.cancel()
            MjpegStreamOutcome.Failure(e.message ?: "MJPEG connection failed")
        } finally {
            cancelHandle?.dispose()
        }
    }
}

private class OkHttpSnapshotFetcher(
    private val client: OkHttpClient
) : SnapshotFetcher {
    override suspend fun fetchSnapshot(snapshotUrl: String, accessToken: String): SnapshotResult =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(snapshotUrl)
                .header("Authorization", "Bearer $accessToken")
                .build()

            val call = client.newCall(request)
            val cancelHandle = currentCoroutineContext()[Job]?.invokeOnCompletion {
                call.cancel()
            }
            try {
                call.execute().use { response ->
                    if (response.isSuccessful) {
                        val bytes = response.body?.bytes()
                        if (bytes != null && bytes.isNotEmpty()) {
                            SnapshotResult.Success(bytes)
                        } else {
                            SnapshotResult.Failure("Empty snapshot response")
                        }
                    } else {
                        SnapshotResult.HttpError(response.code)
                    }
                }
            } catch (ce: CancellationException) {
                call.cancel()
                throw ce
            } catch (e: Exception) {
                call.cancel()
                SnapshotResult.Failure(e.message ?: "Connection failed")
            } finally {
                cancelHandle?.dispose()
            }
        }
}

/**
 * Software-decoded HTTP MJPEG camera streaming client with automatic snapshot polling fallback
 * (ADR 0007) and dynamic active route synchronization across Dual-Endpoint Failover and
 * Opportunistic Failback transitions (ADR 0008).
 */
class MjpegCameraStreamClient internal constructor(
    private val streamTransport: MjpegStreamTransport,
    private val snapshotFetcher: SnapshotFetcher,
    private val snapshotPollIntervalMs: Long = DEFAULT_SNAPSHOT_POLL_INTERVAL_MS,
    private val decodeDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    constructor(
        httpClient: OkHttpClient = defaultCameraHttpClient
    ) : this(
        streamTransport = OkHttpMjpegStreamTransport(httpClient),
        snapshotFetcher = OkHttpSnapshotFetcher(httpClient)
    )

    /**
     * Streams downsampled RGB_565 camera frames for [entityId], automatically restarting via
     * `flatMapLatest` whenever [serverUrlFlow] switches routes or [reconnectTrigger] increments.
     */
    fun observeStream(
        entityId: String,
        serverUrlFlow: Flow<String>,
        accessToken: String,
        reconnectTrigger: Flow<Int> = flowOf(0),
        maxTargetWidthPx: Int = DEFAULT_MAX_TARGET_WIDTH_PX
    ): Flow<CameraFeedState> = observeStreamPipeline(
        entityId = entityId,
        serverUrlFlow = serverUrlFlow,
        accessToken = accessToken,
        reconnectTrigger = reconnectTrigger,
        decoder = { jpegBytes ->
            decodeSampledBitmap(jpegBytes, maxTargetWidthPx)?.let { bmp ->
                DecodedFrame(bmp, bmp.width, bmp.height)
            }
        }
    ).map { state ->
        when (state) {
            is StreamPipelineState.Connecting -> CameraFeedState.Connecting
            is StreamPipelineState.Streaming -> CameraFeedState.Streaming(
                bitmap = state.frame,
                aspectRatio = state.aspectRatio,
                isLive = state.isLive
            )

            is StreamPipelineState.Error -> CameraFeedState.Error(state.message)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    internal fun <T> observeStreamPipeline(
        entityId: String,
        serverUrlFlow: Flow<String>,
        accessToken: String,
        reconnectTrigger: Flow<Int> = flowOf(0),
        decoder: (ByteArray) -> DecodedFrame<T>?
    ): Flow<StreamPipelineState<T>> {
        val cleanUrls = serverUrlFlow
            .map { it.trim().removeSuffix("/") }
            .distinctUntilChanged()

        return combine(cleanUrls, reconnectTrigger) { baseUrl, trigger ->
            baseUrl to trigger
        }.flatMapLatest { (cleanBaseUrl, _) ->
            flow {
                if (cleanBaseUrl.isBlank()) {
                    emit(StreamPipelineState.Error("Server URL not configured"))
                    return@flow
                }

                emit(StreamPipelineState.Connecting)

                val streamUrl = "$cleanBaseUrl/api/camera_proxy_stream/$entityId"
                val snapshotUrl = "$cleanBaseUrl/api/camera_proxy/$entityId"
                var hasDecodedFrame = false

                val streamOutcome = try {
                    streamTransport.stream(streamUrl, accessToken) { inputStream ->
                        extractJpegFrames(inputStream) { jpegBytes ->
                            val decoded = decoder(jpegBytes)
                            if (decoded != null) {
                                hasDecodedFrame = true
                                emit(
                                    StreamPipelineState.Streaming(
                                        frame = decoded.image,
                                        aspectRatio = decoded.aspectRatio,
                                        isLive = true
                                    )
                                )
                            }
                        }
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    MjpegStreamOutcome.Failure(e.message ?: "MJPEG stream error")
                }

                when (streamOutcome) {
                    is MjpegStreamOutcome.HttpError ->
                        Log.w(TAG, "MJPEG stream HTTP ${streamOutcome.code} for $entityId")

                    is MjpegStreamOutcome.Failure ->
                        Log.w(TAG, "MJPEG stream ended or failed for $entityId: ${streamOutcome.message}")

                    is MjpegStreamOutcome.Completed -> Unit
                }

                // Fallback to periodic still snapshots when MJPEG is unsupported or closes.
                while (currentCoroutineContext().isActive) {
                    when (val snapResult = snapshotFetcher.fetchSnapshot(snapshotUrl, accessToken)) {
                        is SnapshotResult.Success -> {
                            val decoded = withContext(decodeDispatcher) { decoder(snapResult.bytes) }
                            if (decoded != null) {
                                hasDecodedFrame = true
                                emit(
                                    StreamPipelineState.Streaming(
                                        frame = decoded.image,
                                        aspectRatio = decoded.aspectRatio,
                                        isLive = false
                                    )
                                )
                            }
                        }

                        is SnapshotResult.HttpError -> {
                            emit(StreamPipelineState.Error("Camera offline (HTTP ${snapResult.code})"))
                        }

                        is SnapshotResult.Failure -> {
                            if (!hasDecodedFrame) {
                                emit(StreamPipelineState.Error(snapResult.message))
                            }
                        }
                    }
                    delay(snapshotPollIntervalMs)
                }
            }
        }
    }

    companion object {
        const val DEFAULT_MAX_TARGET_WIDTH_PX = 1920
        const val DEFAULT_SNAPSHOT_POLL_INTERVAL_MS = 1500L
        internal const val MAX_FRAME_SIZE = 4 * 1024 * 1024 // 4 MB

        private val defaultCameraHttpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build()
        }

        @Volatile
        private var instance: MjpegCameraStreamClient? = null

        fun getInstance(): MjpegCameraStreamClient {
            return instance ?: synchronized(this) {
                instance ?: MjpegCameraStreamClient().also { instance = it }
            }
        }

        fun initialize(client: MjpegCameraStreamClient) {
            instance = client
        }
    }
}

/**
 * Parses multipart MJPEG chunks from the HTTP input stream using block memory copies
 * rather than single-byte writes. Extracts individual JPEG byte arrays bounded by
 * `0xFF 0xD8` (SOI) and `0xFF 0xD9` (EOI) markers across arbitrary chunk boundaries.
 */
internal suspend fun extractJpegFrames(
    inputStream: InputStream,
    onFrameExtracted: suspend (ByteArray) -> Unit
) {
    val buffer = ByteArray(16384)
    var frameStream = ByteArrayOutputStream(65536)
    var inJpeg = false
    var trailing0xFF = false

    var bytesRead = 0
    while (currentCoroutineContext().isActive && inputStream.read(buffer).also { bytesRead = it } != -1) {
        var cursor = 0
        while (cursor < bytesRead) {
            if (!inJpeg) {
                if (trailing0xFF && (buffer[cursor].toInt() and 0xFF) == 0xD8) {
                    inJpeg = true
                    frameStream.reset()
                    frameStream.write(0xFF)
                    frameStream.write(0xD8)
                    cursor++
                    trailing0xFF = false
                    continue
                }
                trailing0xFF = false

                var soiIndex = -1
                for (i in cursor until bytesRead - 1) {
                    if ((buffer[i].toInt() and 0xFF) == 0xFF && (buffer[i + 1].toInt() and 0xFF) == 0xD8) {
                        soiIndex = i
                        break
                    }
                }

                if (soiIndex != -1) {
                    inJpeg = true
                    frameStream.reset()
                    cursor = soiIndex
                } else {
                    trailing0xFF = (buffer[bytesRead - 1].toInt() and 0xFF) == 0xFF
                    cursor = bytesRead
                }
            } else {
                if (trailing0xFF && (buffer[cursor].toInt() and 0xFF) == 0xD9) {
                    frameStream.write(0xD9)
                    inJpeg = false
                    trailing0xFF = false
                    cursor++
                    val jpegBytes = frameStream.toByteArray()
                    frameStream.reset()
                    onFrameExtracted(jpegBytes)
                    continue
                }
                trailing0xFF = false

                var eoiIndex = -1
                for (i in cursor until bytesRead - 1) {
                    if ((buffer[i].toInt() and 0xFF) == 0xFF && (buffer[i + 1].toInt() and 0xFF) == 0xD9) {
                        eoiIndex = i
                        break
                    }
                }

                if (eoiIndex != -1) {
                    val len = (eoiIndex + 2) - cursor
                    frameStream.write(buffer, cursor, len)
                    inJpeg = false
                    cursor = eoiIndex + 2
                    val jpegBytes = frameStream.toByteArray()
                    frameStream.reset()
                    onFrameExtracted(jpegBytes)
                } else {
                    val lastIsFF = (buffer[bytesRead - 1].toInt() and 0xFF) == 0xFF
                    val writeLen = if (lastIsFF) (bytesRead - 1) - cursor else bytesRead - cursor
                    if (writeLen > 0) {
                        frameStream.write(buffer, cursor, writeLen)
                    }
                    if (lastIsFF) {
                        frameStream.write(0xFF)
                        trailing0xFF = true
                    } else {
                        trailing0xFF = false
                    }
                    cursor = bytesRead

                    // Reallocate rather than reset so a corrupt stream cannot pin a multi-MB buffer.
                    if (frameStream.size() > MjpegCameraStreamClient.MAX_FRAME_SIZE) {
                        inJpeg = false
                        trailing0xFF = false
                        frameStream = ByteArrayOutputStream(65536)
                    }
                }
            }
        }
    }
}

/**
 * Parses a multipart MJPEG stream and decodes extracted frames into downsampled RGB_565 Bitmaps.
 */
internal suspend fun parseMjpegStream(
    inputStream: InputStream,
    maxTargetWidthPx: Int = MjpegCameraStreamClient.DEFAULT_MAX_TARGET_WIDTH_PX,
    onFrameDecoded: suspend (Bitmap) -> Unit
) {
    extractJpegFrames(inputStream) { jpegBytes ->
        val bmp = decodeSampledBitmap(jpegBytes, maxTargetWidthPx)
        if (bmp != null) {
            onFrameDecoded(bmp)
        }
    }
}

/**
 * Decodes JPEG bytes with adaptive downsampling bounded by [maxTargetWidthPx] using `RGB_565`
 * configuration, saving 50% RAM and avoiding CPU spikes on budget Android TV hardware (ADR 0007).
 */
internal fun decodeSampledBitmap(
    jpegBytes: ByteArray,
    maxTargetWidthPx: Int = MjpegCameraStreamClient.DEFAULT_MAX_TARGET_WIDTH_PX
): Bitmap? {
    return try {
        val boundsOptions = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, boundsOptions)
        val rawW = boundsOptions.outWidth
        val rawH = boundsOptions.outHeight
        if (rawW <= 0 || rawH <= 0) return null

        val maxW = maxTargetWidthPx.coerceAtLeast(1)
        val maxH = ((maxW * 9L) / 16L).toInt().coerceAtLeast(1)
        var sampleSize = 1
        while ((rawW / sampleSize) > maxW || (rawH / sampleSize) > maxH) {
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
