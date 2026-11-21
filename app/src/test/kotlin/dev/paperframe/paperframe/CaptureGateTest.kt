package dev.paperframe.paperframe

import org.junit.Assert.*
import org.junit.Test

class CaptureGateTest {
    private val page = listOf(.1f, .1f, .9f, .1f, .9f, .9f, .1f, .9f)
    @Test fun rejectsCrossedConcaveTinyAndNonFiniteQuads() {
        assertTrue(validQuad(page))
        assertFalse(validQuad(listOf(.1f,.1f,.9f,.9f,.9f,.1f,.1f,.9f)))
        assertFalse(validQuad(listOf(.1f,.1f,.9f,.1f,.3f,.2f,.1f,.9f)))
        assertFalse(validQuad(List(8) { .2f }))
        assertFalse(validQuad(page.toMutableList().apply { this[0] = Float.NaN }))
    }
    @Test fun needsTwelveHundredMillisecondsOfSharpStableFrames() {
        val gate = CaptureGate()
        for (time in 1000L..2100L step 100) assertFalse(gate.update(page,.7,100.0,time))
        assertTrue(gate.update(page,.7,100.0,2200))
        assertEquals(1f,gate.progress,0f)
    }
    @Test fun blurStreamGapsAndMissingPagesResetCapture() {
        val gate = CaptureGate()
        for (time in 1000L..2000L step 100) gate.update(page,.7,100.0,time)
        assertFalse(gate.update(page,.7,10.0,2100))
        assertEquals(0f,gate.progress,0f)
        gate.update(page,.7,100.0,2200)
        assertFalse(gate.update(page,.7,100.0,3200))
        assertEquals(0f,gate.progress,0f)
        assertFalse(gate.update(null,0.0,100.0,3300))
    }
    @Test fun slowDriftNeverBuildsAnAutoCapture() {
        val gate = CaptureGate()
        for (i in 0..40) {
            val moved = page.mapIndexed { index,v -> if(index % 2 == 0) v + i * .002f else v }
            assertFalse(gate.update(moved,.7,100.0,1000L+i*100))
        }
    }
    @Test fun lowConfidenceAndBackwardTimeCannotTrigger() {
        val gate = CaptureGate()
        assertFalse(gate.update(page,.1,100.0,1000))
        gate.update(page,.7,100.0,2000)
        assertFalse(gate.update(page,.7,100.0,1000))
        assertEquals(0f,gate.progress,0f)
    }
}
