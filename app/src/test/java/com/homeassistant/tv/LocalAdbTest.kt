package com.homeassistant.tv

import com.homeassistant.tv.service.LocalAdbManager
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
        val existing = "com.google.android.marvin.talkback/.TalkBackService:com.homeassistant.tv/.service.RemoteButtonRemapService"
        val expected = "com.google.android.marvin.talkback/.TalkBackService:$targetService"
        assertEquals(expected, LocalAdbManager.mergeAccessibilityServices(existing, targetService))
    }

    @Test
    fun testRemoveAccessibilityService() {
        val sole = targetService
        assertEquals("", LocalAdbManager.removeAccessibilityService(sole))

        val nonCanonical = "com.homeassistant.tv/.service.RemoteButtonRemapService"
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
    }
}
