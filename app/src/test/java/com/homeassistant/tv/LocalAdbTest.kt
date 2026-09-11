package com.homeassistant.tv

import com.homeassistant.tv.service.LocalAdbManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAdbTest {

    private val targetService = "com.homeassistant.tv/.service.RemoteButtonRemapService"

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
    fun testMergeAccessibilityServices_handlesColonsWithSpaces() {
        val existing = "com.example.service1/.Service1 : $targetService"
        assertEquals(existing.trim(), LocalAdbManager.mergeAccessibilityServices(existing, targetService))
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
