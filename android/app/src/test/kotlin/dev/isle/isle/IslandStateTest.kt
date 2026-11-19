package dev.isle.isle

import org.junit.Assert.*
import org.junit.Test

class IslandStateTest {
    @Test fun suppressionOverridesManualAndEventExpansion() {
        val s = IslandState().open(true).suppress(true, true)
        assertTrue(s.suppressed)
        assertEquals(Presentation.IDLE, s.presentation)
        assertEquals(s, s.open(true))
        assertEquals(s, s.open(false))
    }
    @Test fun returningFromSuppressionDoesNotReplayAnExpandedEvent() {
        val s = IslandState().open(false).suppress(true, true).suppress(false, true)
        assertEquals(Presentation.COMPACT, s.presentation)
        assertFalse(s.pinnedByUser)
    }
    @Test fun timeoutReturnsToOngoingActivityOrIdle() {
        assertEquals(Presentation.COMPACT, IslandState().open(true).settle(true).presentation)
        assertEquals(Presentation.IDLE, IslandState().open(true).settle(false).presentation)
    }
    @Test fun eventCannotTakeOwnershipAwayFromManualInteraction() {
        assertTrue(IslandState().open(true).open(false).pinnedByUser)
    }
    @Test fun calibrationIsValidatedBeforeReachingWindowManager() {
        val s = IslandSettings.fromMap(mapOf("width" to -1, "height" to 500, "touchExtension" to 900, "timeout" to 0))
        assertEquals(16, s.width); assertEquals(80, s.height)
        assertEquals(80, s.touchExtension); assertEquals(3, s.timeout)
        assertFalse(s.notifications)
    }
    @Test fun defaultFitMatchesDeviceCalibration() {
        val s = IslandSettings.fromMap(emptyMap<String, Any>())
        assertEquals(110, s.width); assertEquals(34, s.height)
        assertEquals(30, s.touchExtension); assertEquals(0, s.offset)
    }
}
