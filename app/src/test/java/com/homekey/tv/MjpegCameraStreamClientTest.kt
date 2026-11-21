package com.homekey.tv

import com.homekey.tv.data.api.DecodedFrame
import com.homekey.tv.data.api.MjpegCameraStreamClient
import com.homekey.tv.data.api.MjpegStreamOutcome
import com.homekey.tv.data.api.MjpegStreamTransport
import com.homekey.tv.data.api.SnapshotFetcher
import com.homekey.tv.data.api.SnapshotResult
import com.homekey.tv.data.api.StreamPipelineState
import com.homekey.tv.data.api.extractJpegFrames
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

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
            onStreamOpened(ByteArrayInputStream(frame1))
            kotlinx.coroutines.yield()
            onStreamOpened(ByteArrayInputStream(frame2))
            kotlinx.coroutines.yield()
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
            ioDispatcher = dispatcher
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
            ioDispatcher = dispatcher
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
            ioDispatcher = dispatcher
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
            ioDispatcher = dispatcher
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

    @Test
    fun observeStreamPipeline_executesTransportAndDecodeOnIoDispatcherWithoutFlowInvariantViolation() {
        val uiExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "camera-ui-collector") }
        val ioExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "camera-io-producer") }
        val uiDispatcher = uiExecutor.asCoroutineDispatcher()
        val ioDispatcher = ioExecutor.asCoroutineDispatcher()

        try {
            val liveFrame = fakeJpeg(0x11)
            val snapFrame = fakeJpeg(0x22)
            val liveDelivered = CompletableDeferred<Unit>()
            val transportThread = AtomicReference<String>()
            val decodeThread = AtomicReference<String>()
            val collectorThread = AtomicReference<String>()

            val transport = MjpegStreamTransport { _, _, onStreamOpened ->
                transportThread.set(Thread.currentThread().name)
                onStreamOpened(ByteArrayInputStream(liveFrame))
                liveDelivered.await()
                MjpegStreamOutcome.Completed
            }
            val snapshotFetcher = SnapshotFetcher { _, _ ->
                SnapshotResult.Success(snapFrame)
            }

            val client = MjpegCameraStreamClient(
                streamTransport = transport,
                snapshotFetcher = snapshotFetcher,
                snapshotPollIntervalMs = 50L,
                ioDispatcher = ioDispatcher
            )

            val received = mutableListOf<StreamPipelineState.Streaming<FakeImage>>()
            runBlocking(uiDispatcher) {
                withTimeout(5000L) {
                    val job = launch {
                        client.observeStreamPipeline(
                            entityId = "camera.porch",
                            serverUrlFlow = MutableStateFlow("http://192.168.1.50:8123"),
                            accessToken = "token"
                        ) { bytes ->
                            decodeThread.set(Thread.currentThread().name)
                            DecodedFrame(FakeImage("img_${bytes[4]}", bytes), 1280, 720)
                        }.collect { state ->
                            collectorThread.set(Thread.currentThread().name)
                            if (state is StreamPipelineState.Streaming) {
                                received.add(state)
                                if (state.isLive) {
                                    liveDelivered.complete(Unit)
                                }
                            }
                        }
                    }

                    while (received.none { !it.isLive }) {
                        kotlinx.coroutines.delay(10L)
                    }
                    job.cancel()
                }
            }

            assertTrue(
                "Expected transport on IO thread, got ${transportThread.get()}",
                transportThread.get().startsWith("camera-io-producer")
            )
            assertTrue(
                "Expected decode on IO thread, got ${decodeThread.get()}",
                decodeThread.get().startsWith("camera-io-producer")
            )
            assertTrue(
                "Expected collector on UI thread, got ${collectorThread.get()}",
                collectorThread.get().startsWith("camera-ui-collector")
            )
            assertNotEquals(transportThread.get(), collectorThread.get())
            assertTrue(received.any { it.isLive && it.frame.bytes.contentEquals(liveFrame) })
            assertTrue(received.any { !it.isLive && it.frame.bytes.contentEquals(snapFrame) })
        } finally {
            uiDispatcher.close()
            ioDispatcher.close()
            uiExecutor.shutdownNow()
            ioExecutor.shutdownNow()
        }
    }

    @Test
    fun observeStreamPipeline_conflatesBackloggedFramesWhenCollectorIsSlow() {
        val uiExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "camera-conflate-ui") }
        val ioExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "camera-conflate-io") }
        val uiDispatcher = uiExecutor.asCoroutineDispatcher()
        val ioDispatcher = ioExecutor.asCoroutineDispatcher()

        try {
            val frames = (1..5).map { fakeJpeg(it) }
            val firstFrameCollected = CompletableDeferred<Unit>()
            val burstEmitted = CompletableDeferred<Unit>()

            val gatedStream = object : InputStream() {
                private var index = 0
                override fun read(): Int = -1
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (index == 1) {
                        runBlocking { firstFrameCollected.await() }
                    }
                    if (index >= frames.size) {
                        burstEmitted.complete(Unit)
                        return -1
                    }
                    val payload = frames[index++]
                    System.arraycopy(payload, 0, b, off, payload.size)
                    return payload.size
                }
            }

            val transport = MjpegStreamTransport { _, _, onStreamOpened ->
                onStreamOpened(gatedStream)
                awaitCancellation()
            }

            val client = MjpegCameraStreamClient(
                streamTransport = transport,
                snapshotFetcher = { _, _ -> SnapshotResult.Failure("unused") },
                ioDispatcher = ioDispatcher
            )

            val receivedIds = mutableListOf<String>()
            runBlocking(uiDispatcher) {
                withTimeout(5000L) {
                    val job = launch {
                        client.observeStreamPipeline(
                            entityId = "camera.driveway",
                            serverUrlFlow = MutableStateFlow("http://192.168.1.50:8123"),
                            accessToken = "token"
                        ) { bytes ->
                            DecodedFrame(FakeImage("f${bytes[4]}", bytes), 1280, 720)
                        }.collect { state ->
                            if (state is StreamPipelineState.Streaming) {
                                receivedIds.add(state.frame.id)
                                if (state.frame.id == "f1") {
                                    firstFrameCollected.complete(Unit)
                                    burstEmitted.await()
                                }
                            }
                        }
                    }

                    while (!receivedIds.contains("f5")) {
                        kotlinx.coroutines.delay(10L)
                    }
                    job.cancel()
                }
            }

            assertEquals("f1", receivedIds.first())
            assertEquals("f5", receivedIds.last())
            assertTrue(
                "Expected conflation to drop intermediate frames, but got $receivedIds",
                receivedIds.size < frames.size
            )
        } finally {
            uiDispatcher.close()
            ioDispatcher.close()
            uiExecutor.shutdownNow()
            ioExecutor.shutdownNow()
        }
    }

    @Test
    fun observeStreamPipeline_realOkHttpTransportStreamsMjpegAndFallsBackToSnapshotsOverLoopback() {
        val uiExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "fake-android-ui") }
        val uiDispatcher = uiExecutor.asCoroutineDispatcher()
        val serverExecutor = Executors.newCachedThreadPool()
        val serverSocket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))

        val liveFrame = fakeJpeg(0x31)
        val snapFrame = fakeJpeg(0x42)
        val streamAuthHeader = AtomicReference<String?>()
        val snapshotAuthHeader = AtomicReference<String?>()
        val liveReceivedLatch = CountDownLatch(1)

        serverExecutor.submit {
            while (!serverSocket.isClosed) {
                val socket = try {
                    serverSocket.accept()
                } catch (_: Exception) {
                    break
                }
                serverExecutor.submit {
                    socket.use { clientSocket ->
                        val reader = BufferedReader(InputStreamReader(clientSocket.getInputStream()))
                        val requestLine = reader.readLine() ?: return@use
                        val path = requestLine.split(" ").getOrNull(1) ?: ""
                        var authHeader: String? = null
                        while (true) {
                            val headerLine = reader.readLine() ?: break
                            if (headerLine.isEmpty()) break
                            if (headerLine.startsWith("Authorization:", ignoreCase = true)) {
                                authHeader = headerLine.substringAfter(":").trim()
                            }
                        }

                        val out = clientSocket.getOutputStream()
                        when (path) {
                            "/api/camera_proxy_stream/camera.front_door" -> {
                                streamAuthHeader.set(authHeader)
                                val headers = "HTTP/1.1 200 OK\r\n" +
                                    "Content-Type: multipart/x-mixed-replace; boundary=frame\r\n" +
                                    "Connection: close\r\n\r\n"
                                out.write(headers.toByteArray())
                                out.write("--frame\r\nContent-Type: image/jpeg\r\n\r\n".toByteArray())
                                out.write(liveFrame)
                                out.write("\r\n".toByteArray())
                                out.flush()
                                liveReceivedLatch.await(3, TimeUnit.SECONDS)
                                out.write("--frame--\r\n".toByteArray())
                                out.flush()
                            }

                            "/api/camera_proxy/camera.front_door" -> {
                                snapshotAuthHeader.set(authHeader)
                                val headers = "HTTP/1.1 200 OK\r\n" +
                                    "Content-Type: image/jpeg\r\n" +
                                    "Content-Length: ${snapFrame.size}\r\n" +
                                    "Connection: close\r\n\r\n"
                                out.write(headers.toByteArray())
                                out.write(snapFrame)
                                out.flush()
                            }
                        }
                    }
                }
            }
        }

        val httpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .build()

        try {
            val client = MjpegCameraStreamClient(httpClient = httpClient)
            val baseUrl = "http://127.0.0.1:${serverSocket.localPort}"
            val received = mutableListOf<StreamPipelineState.Streaming<FakeImage>>()
            val collectorThread = AtomicReference<String>()

            runBlocking(uiDispatcher) {
                withTimeout(5000L) {
                    client.observeStreamPipeline(
                        entityId = "camera.front_door",
                        serverUrlFlow = MutableStateFlow(baseUrl),
                        accessToken = "test-token"
                    ) { bytes ->
                        DecodedFrame(FakeImage("ok_${bytes[4]}", bytes), 640, 480)
                    }.takeWhile { state ->
                        collectorThread.set(Thread.currentThread().name)
                        if (state is StreamPipelineState.Streaming) {
                            received.add(state)
                            if (state.isLive) {
                                liveReceivedLatch.countDown()
                                true
                            } else {
                                false
                            }
                        } else {
                            true
                        }
                    }.collect { }
                }
            }

            assertEquals("Bearer test-token", streamAuthHeader.get())
            assertEquals("Bearer test-token", snapshotAuthHeader.get())
            assertTrue(
                "Expected collector on fake-android-ui thread, got ${collectorThread.get()}",
                collectorThread.get().startsWith("fake-android-ui")
            )
            assertTrue(received.any { it.isLive && it.frame.bytes.contentEquals(liveFrame) })
            assertTrue(received.any { !it.isLive && it.frame.bytes.contentEquals(snapFrame) })
        } finally {
            serverSocket.close()
            httpClient.dispatcher.executorService.shutdown()
            httpClient.connectionPool.evictAll()
            uiDispatcher.close()
            uiExecutor.shutdownNow()
            serverExecutor.shutdownNow()
        }
    }
}
