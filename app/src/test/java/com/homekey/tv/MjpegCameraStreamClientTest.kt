package com.homekey.tv

import com.homekey.tv.data.api.DecodedFrame
import com.homekey.tv.data.api.MjpegCameraStreamClient
import com.homekey.tv.data.api.MjpegStreamOutcome
import com.homekey.tv.data.api.MjpegStreamTransport
import com.homekey.tv.data.api.SnapshotFetcher
import com.homekey.tv.data.api.SnapshotResult
import com.homekey.tv.data.api.StreamPipelineState
import com.homekey.tv.data.api.extractJpegFrames
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

@OptIn(ExperimentalCoroutinesApi::class)
class MjpegCameraStreamClientTest {

    private data class FakeImage(
        val id: String,
        val bytes: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is FakeImage) return false
            return id == other.id && bytes.contentEquals(other.bytes)
        }

        override fun hashCode(): Int = 31 * id.hashCode() + bytes.contentHashCode()
    }

    private class ChunkedInputStream(
        private val chunks: List<ByteArray>
    ) : InputStream() {
        private var chunkIndex = 0
        private var offset = 0

        override fun read(): Int {
            val single = ByteArray(1)
            val count = read(single, 0, 1)
            return if (count == -1) -1 else single[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (chunkIndex >= chunks.size) return -1
            val current = chunks[chunkIndex]
            val remaining = current.size - offset
            val toCopy = minOf(len, remaining)
            System.arraycopy(current, offset, b, off, toCopy)
            offset += toCopy
            if (offset >= current.size) {
                chunkIndex++
                offset = 0
            }
            return toCopy
        }
    }

    private fun bytes(vararg values: Int): ByteArray =
        ByteArray(values.size) { idx -> values[idx].toByte() }

    private fun fakeJpeg(tag: Int): ByteArray =
        bytes(0xFF, 0xD8, 0xFF, 0xE0, tag, 0x11, 0x22, 0xFF, 0xD9)

    @Test
    fun extractJpegFrames_parsesMultipleFramesSplitAcrossChunkBoundaries() = runTest {
        val frame1 = bytes(0xFF, 0xD8, 0x11, 0x22, 0xFF, 0xE1, 0x33, 0xFF, 0xD9)
        val frame2 = bytes(0xFF, 0xD8, 0x44, 0xFF, 0xFF, 0xD9)

        val boundaryPrefix = "--frame\r\nContent-Type: image/jpeg\r\n\r\n".toByteArray()
        val boundaryMiddle = "\r\n--frame\r\nContent-Type: image/jpeg\r\n\r\n".toByteArray()

        // Split SOI (0xFF | 0xD8), interior 0xFF markers, and EOI (0xFF | 0xD9) across separate read() calls
        val chunks = listOf(
            boundaryPrefix + bytes(0xFF),
            bytes(0xD8, 0x11),
            bytes(0x22, 0xFF),
            bytes(0xE1, 0x33, 0xFF),
            bytes(0xD9) + boundaryMiddle + bytes(0xFF),
            bytes(0xD8, 0x44, 0xFF),
            bytes(0xFF),
            bytes(0xD9) + "\r\n--frame--\r\n".toByteArray()
        )

        val extracted = mutableListOf<ByteArray>()
        extractJpegFrames(ChunkedInputStream(chunks)) { jpeg ->
            extracted.add(jpeg)
        }

        assertEquals(2, extracted.size)
        assertArrayEquals(frame1, extracted[0])
        assertArrayEquals(frame2, extracted[1])
    }

    @Test
    fun extractJpegFrames_handlesTrailing0xFFNotFollowedBySOIOrEOI() = runTest {
        val frame = bytes(0xFF, 0xD8, 0x01, 0xFF, 0x02, 0xFF, 0xD9)
        val chunks = listOf(
            bytes(0xAA, 0xFF),
            bytes(0x00, 0xFF, 0xD8, 0x01, 0xFF),
            bytes(0x02, 0xFF, 0xD9, 0xFF),
            bytes(0x10)
        )

        val extracted = mutableListOf<ByteArray>()
        extractJpegFrames(ChunkedInputStream(chunks)) { jpeg ->
            extracted.add(jpeg)
        }

        assertEquals(1, extracted.size)
        assertArrayEquals(frame, extracted[0])
    }

    @Test
    fun observeStreamPipeline_fallsBackToSnapshotPollingWhenMjpegStreamEnds() = runTest {
        val frame1 = fakeJpeg(0x01)
        val frame2 = fakeJpeg(0x02)
        val snap1 = fakeJpeg(0xA1)
        val snap2 = fakeJpeg(0xA2)

        val mjpegUrls = mutableListOf<String>()
        val snapshotUrls = mutableListOf<String>()
        var snapshotCallCount = 0

        val transport = MjpegStreamTransport { streamUrl, accessToken, onStreamOpened ->
            mjpegUrls.add("$streamUrl|$accessToken")
            onStreamOpened(ByteArrayInputStream(frame1 + frame2))
            MjpegStreamOutcome.Completed
        }
        val snapshotFetcher = SnapshotFetcher { snapshotUrl, accessToken ->
            snapshotUrls.add("$snapshotUrl|$accessToken")
            snapshotCallCount++
            when (snapshotCallCount) {
                1 -> SnapshotResult.Success(snap1)
                else -> SnapshotResult.Success(snap2)
            }
        }

        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val client = MjpegCameraStreamClient(
            streamTransport = transport,
            snapshotFetcher = snapshotFetcher,
            snapshotPollIntervalMs = 1500L,
            decodeDispatcher = dispatcher
        )

        val states = mutableListOf<StreamPipelineState<FakeImage>>()
        backgroundScope.launch(dispatcher) {
            client.observeStreamPipeline(
                entityId = "camera.front_door",
                serverUrlFlow = MutableStateFlow("http://192.168.1.50:8123/"),
                accessToken = "secret_token"
            ) { bytes ->
                DecodedFrame(FakeImage("f${bytes[4]}", bytes), 1280, 720)
            }.collect { states.add(it) }
        }

        runCurrent()

        assertEquals(listOf("http://192.168.1.50:8123/api/camera_proxy_stream/camera.front_door|secret_token"), mjpegUrls)
        assertEquals(listOf("http://192.168.1.50:8123/api/camera_proxy/camera.front_door|secret_token"), snapshotUrls)
        assertEquals(4, states.size)
        assertEquals(StreamPipelineState.Connecting, states[0])
        assertEquals(StreamPipelineState.Streaming(FakeImage("f1", frame1), 16f / 9f, isLive = true), states[1])
        assertEquals(StreamPipelineState.Streaming(FakeImage("f2", frame2), 16f / 9f, isLive = true), states[2])
        assertEquals(StreamPipelineState.Streaming(FakeImage("f-95", snap1), 16f / 9f, isLive = false), states[3])

        // Advance past poll interval to verify continued snapshot polling fallback
        advanceTimeBy(1500L)
        runCurrent()

        assertEquals(2, snapshotUrls.size)
        assertEquals(5, states.size)
        val last = states[4] as StreamPipelineState.Streaming
        assertFalse(last.isLive)
        assertArrayEquals(snap2, last.frame.bytes)
    }

    @Test
    fun observeStreamPipeline_fallsBackToSnapshotPollingWhenMjpegFailsAndSurfacesHttpErrors() = runTest {
        val snap = fakeJpeg(0x55)
        var snapshotAttempts = 0

        val transport = MjpegStreamTransport { _, _, _ ->
            MjpegStreamOutcome.HttpError(502)
        }
        val snapshotFetcher = SnapshotFetcher { _, _ ->
            snapshotAttempts++
            if (snapshotAttempts == 1) {
                SnapshotResult.HttpError(404)
            } else {
                SnapshotResult.Success(snap)
            }
        }

        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val client = MjpegCameraStreamClient(
            streamTransport = transport,
            snapshotFetcher = snapshotFetcher,
            snapshotPollIntervalMs = 1500L,
            decodeDispatcher = dispatcher
        )

        val states = mutableListOf<StreamPipelineState<FakeImage>>()
        backgroundScope.launch(dispatcher) {
            client.observeStreamPipeline(
                entityId = "camera.garage",
                serverUrlFlow = MutableStateFlow("http://192.168.1.50:8123"),
                accessToken = "token"
            ) { bytes ->
                DecodedFrame(FakeImage("snap", bytes), 640, 480)
            }.collect { states.add(it) }
        }

        runCurrent()
        assertEquals(2, states.size)
        assertEquals(StreamPipelineState.Connecting, states[0])
        assertEquals(StreamPipelineState.Error("Camera offline (HTTP 404)"), states[1])

        // Recovers on next snapshot poll
        advanceTimeBy(1500L)
        runCurrent()
        assertEquals(3, states.size)
        assertEquals(
            StreamPipelineState.Streaming(FakeImage("snap", snap), 640f / 480f, isLive = false),
            states[2]
        )
    }

    @Test
    fun observeStreamPipeline_switchesStreamUrlOnFailoverAndFailback() = runTest {
        val lanFrame1 = fakeJpeg(0x10)
        val cloudFrame = fakeJpeg(0x20)
        val lanFrame2 = fakeJpeg(0x30)

        val openedUrls = mutableListOf<String>()
        val cancelledUrls = mutableListOf<String>()
        var lanOpenCount = 0

        val transport = MjpegStreamTransport { streamUrl, _, onStreamOpened ->
            openedUrls.add(streamUrl)
            val payload = when {
                streamUrl.startsWith("http://192.168.1.50:8123") -> {
                    lanOpenCount++
                    if (lanOpenCount == 1) lanFrame1 else lanFrame2
                }
                else -> cloudFrame
            }
            onStreamOpened(ByteArrayInputStream(payload))
            try {
                awaitCancellation()
            } finally {
                cancelledUrls.add(streamUrl)
            }
        }

        val snapshotFetcher = SnapshotFetcher { _, _ ->
            SnapshotResult.Failure("Should not poll snapshot while MJPEG is active")
        }

        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val client = MjpegCameraStreamClient(
            streamTransport = transport,
            snapshotFetcher = snapshotFetcher,
            decodeDispatcher = dispatcher
        )

        val activeServerUrl = MutableStateFlow("http://192.168.1.50:8123")
        val states = mutableListOf<StreamPipelineState<FakeImage>>()

        backgroundScope.launch(dispatcher) {
            client.observeStreamPipeline(
                entityId = "camera.driveway",
                serverUrlFlow = activeServerUrl,
                accessToken = "token"
            ) { bytes ->
                DecodedFrame(FakeImage("img_${bytes[4]}", bytes), 1920, 1080)
            }.collect { states.add(it) }
        }

        runCurrent()
        assertEquals(
            listOf("http://192.168.1.50:8123/api/camera_proxy_stream/camera.driveway"),
            openedUrls
        )
        assertEquals(2, states.size)
        assertEquals(StreamPipelineState.Connecting, states[0])
        assertEquals(StreamPipelineState.Streaming(FakeImage("img_16", lanFrame1), 16f / 9f, isLive = true), states[1])

        // Simulate Dual-Endpoint Failover to remote URL
        activeServerUrl.value = "https://remote.ui.nabu.casa"
        runCurrent()

        assertEquals(
            listOf(
                "http://192.168.1.50:8123/api/camera_proxy_stream/camera.driveway",
                "https://remote.ui.nabu.casa/api/camera_proxy_stream/camera.driveway"
            ),
            openedUrls
        )
        assertTrue(cancelledUrls.contains("http://192.168.1.50:8123/api/camera_proxy_stream/camera.driveway"))
        assertEquals(4, states.size)
        assertEquals(StreamPipelineState.Connecting, states[2])
        assertEquals(StreamPipelineState.Streaming(FakeImage("img_32", cloudFrame), 16f / 9f, isLive = true), states[3])

        // Simulate Opportunistic Failback to local LAN URL
        activeServerUrl.value = "http://192.168.1.50:8123/"
        runCurrent()

        assertEquals(3, openedUrls.size)
        assertTrue(cancelledUrls.contains("https://remote.ui.nabu.casa/api/camera_proxy_stream/camera.driveway"))
        assertEquals(6, states.size)
        assertEquals(StreamPipelineState.Connecting, states[4])
        assertEquals(StreamPipelineState.Streaming(FakeImage("img_48", lanFrame2), 16f / 9f, isLive = true), states[5])
    }

    @Test
    fun observeStreamPipeline_restartsOnReconnectTriggerAndHandlesBlankUrl() = runTest {
        val frame = fakeJpeg(0x77)
        var openCount = 0

        val transport = MjpegStreamTransport { _, _, onStreamOpened ->
            openCount++
            onStreamOpened(ByteArrayInputStream(frame))
            awaitCancellation()
        }

        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val client = MjpegCameraStreamClient(
            streamTransport = transport,
            snapshotFetcher = { _, _ -> SnapshotResult.Failure("unused") },
            decodeDispatcher = dispatcher
        )

        val serverUrl = MutableStateFlow("")
        val reconnectTrigger = MutableStateFlow(0)
        val states = mutableListOf<StreamPipelineState<FakeImage>>()

        backgroundScope.launch(dispatcher) {
            client.observeStreamPipeline(
                entityId = "camera.patio",
                serverUrlFlow = serverUrl,
                accessToken = "token",
                reconnectTrigger = reconnectTrigger
            ) { bytes ->
                DecodedFrame(FakeImage("patio", bytes), 1280, 720)
            }.collect { states.add(it) }
        }

        runCurrent()
        assertEquals(listOf(StreamPipelineState.Error("Server URL not configured")), states)
        assertEquals(0, openCount)

        serverUrl.value = "http://192.168.1.50:8123"
        runCurrent()
        assertEquals(1, openCount)

        reconnectTrigger.value = 1
        runCurrent()
        assertEquals(2, openCount)
    }
}
