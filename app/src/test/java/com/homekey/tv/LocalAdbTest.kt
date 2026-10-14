package com.homekey.tv

import com.homekey.tv.service.LocalAdbManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAdbTest {

    private val targetService = LocalAdbManager.SERVICE_NAME

    @Test
    fun testMergeAccessibilityServices_nullOrEmpty() {
        assertEquals(targetService, LocalAdbManager.mergeAccessibilityServices(null, targetService))
        assertEquals(targetService, LocalAdbManager.mergeAccessibilityServices("", targetService))
        assertEquals(targetService, LocalAdbManager.mergeAccessibilityServices("   ", targetService))
        assertEquals(targetService, LocalAdbManager.mergeAccessibilityServices("null", targetService))
    }

    @Test
    fun testMergeAccessibilityServices_appendsToExisting() {
        val existing = "com.google.android.marvin.talkback/.TalkBackService"
        val expected = "$existing:$targetService"
        assertEquals(expected, LocalAdbManager.mergeAccessibilityServices(existing, targetService))
    }

    @Test
    fun testMergeAccessibilityServices_preservesMultipleExisting() {
        val existing = "com.example.service1/.Service1:com.example.service2/.Service2"
        val expected = "$existing:$targetService"
        assertEquals(expected, LocalAdbManager.mergeAccessibilityServices(existing, targetService))
    }

    @Test
    fun testMergeAccessibilityServices_idempotentWhenAlreadyPresent() {
        val existing = "com.example.service1/.Service1:$targetService"
        assertEquals(existing, LocalAdbManager.mergeAccessibilityServices(existing, targetService))
    }

    @Test
    fun testMergeAccessibilityServices_replacesNonCanonicalVariant() {
        val existing = "com.google.android.marvin.talkback/.TalkBackService:com.homekey.tv/.service.RemoteButtonRemapService"
        val expected = "com.google.android.marvin.talkback/.TalkBackService:$targetService"
        assertEquals(expected, LocalAdbManager.mergeAccessibilityServices(existing, targetService))
    }

    @Test
    fun testRemoveAccessibilityService() {
        val sole = targetService
        assertEquals("", LocalAdbManager.removeAccessibilityService(sole))

        val nonCanonical = "com.homekey.tv/.service.RemoteButtonRemapService"
        assertEquals("", LocalAdbManager.removeAccessibilityService(nonCanonical))

        val multi = "com.google.android.marvin.talkback/.TalkBackService:$targetService:com.other.service/.Other"
        val expectedMulti = "com.google.android.marvin.talkback/.TalkBackService:com.other.service/.Other"
        assertEquals(expectedMulti, LocalAdbManager.removeAccessibilityService(multi))

        assertEquals("", LocalAdbManager.removeAccessibilityService(null))
        assertEquals("", LocalAdbManager.removeAccessibilityService(""))
    }

    @Test
    fun testAdbResultHierarchy() {
        val success: LocalAdbManager.AdbResult = LocalAdbManager.AdbResult.Success
        assertTrue(success is LocalAdbManager.AdbResult.Success)

        val disabled: LocalAdbManager.AdbResult = LocalAdbManager.AdbResult.AdbDisabled("Connection refused")
        assertTrue(disabled is LocalAdbManager.AdbResult.AdbDisabled)
        assertEquals("Connection refused", (disabled as LocalAdbManager.AdbResult.AdbDisabled).message)

        val timeout: LocalAdbManager.AdbResult = LocalAdbManager.AdbResult.AuthTimeout("Timed out waiting for prompt")
        assertTrue(timeout is LocalAdbManager.AdbResult.AuthTimeout)
        assertEquals("Timed out waiting for prompt", (timeout as LocalAdbManager.AdbResult.AuthTimeout).message)

        val error: LocalAdbManager.AdbResult = LocalAdbManager.AdbResult.Error("Unexpected failure")
        assertTrue(error is LocalAdbManager.AdbResult.Error)
        assertEquals("Unexpected failure", (error as LocalAdbManager.AdbResult.Error).message)
    }

    @Test
    fun testNsdServiceConstants() {
        assertEquals("_adb-tls-connect._tcp", LocalAdbManager.SERVICE_TYPE_TLS_CONNECT)
        assertEquals("_adb-tls-pairing._tcp", LocalAdbManager.SERVICE_TYPE_TLS_PAIRING)
        assertEquals("_adb._tcp", LocalAdbManager.SERVICE_TYPE_LEGACY)
        assertEquals(5555, LocalAdbManager.DEFAULT_PORT)

        val service = LocalAdbManager.DiscoveredAdbService(
            name = "adb-device",
            serviceType = LocalAdbManager.SERVICE_TYPE_TLS_CONNECT,
            host = "127.0.0.1",
            port = 41235
        )
        assertEquals("adb-device", service.name)
        assertEquals("_adb-tls-connect._tcp", service.serviceType)
        assertEquals("127.0.0.1", service.host)
        assertEquals(41235, service.port)
    }
}
