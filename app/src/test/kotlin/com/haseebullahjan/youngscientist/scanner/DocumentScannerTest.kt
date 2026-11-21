package com.haseebullahjan.youngscientist.scanner

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class DocumentScannerTest {
    private val page=DocumentDetection(true,DocumentQuad.inset,.9,.7,175.0,180.0,"detected")
    @Test fun stableGeometryNeedsElapsedTimeAndEnoughFrames() {
        val gate=DocumentCaptureGate()
        for(i in 0..5)assertEquals(DocumentCaptureGate.Hint.HOLD,gate.observe(page,1000+i*100L))
        assertEquals(DocumentCaptureGate.Hint.HOLD,gate.observe(page,1600))
        assertEquals(DocumentCaptureGate.Hint.CAPTURE,gate.observe(page,1700))
    }
    @Test fun motionRestartsTheStabilityInterval() {
        val gate=DocumentCaptureGate()
        repeat(7) { gate.observe(page,1000+it*100L) }
        val moved=page.copy(corners=page.corners.mapIndexed { i,v -> if(i%2==0)v-.03 else v })
        assertEquals(DocumentCaptureGate.Hint.HOLD,gate.observe(moved,1700))
        assertEquals(DocumentCaptureGate.Hint.HOLD,gate.observe(moved,1800))
    }
    @Test fun poorVisibilityNeverTriggersAutoCapture() {
        for((bad,hint) in listOf(page.copy(found=false) to DocumentCaptureGate.Hint.FRAME,
            page.copy(area=.2) to DocumentCaptureGate.Hint.CLOSER,
            page.copy(brightness=20.0) to DocumentCaptureGate.Hint.LIGHT,
            page.copy(sharpness=10.0) to DocumentCaptureGate.Hint.SHARPEN)) {
            val gate=DocumentCaptureGate();repeat(20) { assertEquals(hint,gate.observe(bad,1000+it*100L)) }
        }
    }
    @Test fun capturedPageMustLeaveBeforeAnotherAutoCapture() {
        val gate=DocumentCaptureGate();gate.captured()
        repeat(20) { assertEquals(DocumentCaptureGate.Hint.REMOVE,gate.observe(page,1000+it*100L)) }
        assertEquals(DocumentCaptureGate.Hint.REMOVE,gate.observe(null,3100))
        assertEquals(DocumentCaptureGate.Hint.FRAME,gate.observe(null,3600));assertFalse(gate.awaitingRemoval)
    }
    @Test fun interruptedFramesCannotReuseOldStability() {
        val gate=DocumentCaptureGate();repeat(7) { gate.observe(page,1000+it*100L) }
        assertEquals(DocumentCaptureGate.Hint.HOLD,gate.observe(page,3000))
        gate.captured();gate.failed();assertFalse(gate.awaitingRemoval)
    }
    @Test fun gradualDriftMustNotLookStableJustBecauseAdjacentFramesAreClose() {
        val gate=DocumentCaptureGate()
        repeat(20) { frame ->
            val moved=page.copy(corners=page.corners.mapIndexed { i,v -> if(i%2==0)v-frame*.0018 else v })
            assertNotEquals(DocumentCaptureGate.Hint.CAPTURE,gate.observe(moved,1000+frame*100L))
        }
    }
    @Test fun removalRequiresContinuousFreshFramesAndWorksFromZeroTime() {
        val gate=DocumentCaptureGate();gate.captured()
        assertEquals(DocumentCaptureGate.Hint.REMOVE,gate.observe(null,0))
        assertEquals(DocumentCaptureGate.Hint.REMOVE,gate.observe(null,300))
        assertEquals(DocumentCaptureGate.Hint.FRAME,gate.observe(null,450))
        gate.captured()
        assertEquals(DocumentCaptureGate.Hint.REMOVE,gate.observe(null,1000))
        assertEquals(DocumentCaptureGate.Hint.REMOVE,gate.observe(null,3000))
        assertTrue(gate.awaitingRemoval)
    }
    @Test fun cornersRejectBowTiesReversedOrderAndNonfiniteValues() {
        assertTrue(DocumentQuad.valid(DocumentQuad.inset))
        assertFalse(DocumentQuad.valid(listOf(0.0,0.0,1.0,1.0,1.0,0.0,0.0,1.0)))
        assertFalse(DocumentQuad.valid(listOf(0.0,0.0,0.0,1.0,1.0,1.0,1.0,0.0)))
        assertFalse(DocumentQuad.valid(DocumentQuad.inset.toMutableList().also { it[0]=Double.NaN }))
        assertFalse(DocumentQuad.valid(List(8){.5}))
    }
    @Test fun paddedPixelStridesCropOffsetsAndBufferPositionsAreRespected() {
        val stride=205;val prefix=13;val storage=ByteArray(prefix+stride*100)
        for(y in 0 until 100)for(x in 0 until 96)storage[prefix+y*stride+x*2]=((x+y*3)%256).toByte()
        val buffer=ByteBuffer.wrap(storage).apply { position(prefix) }
        val frame=DocumentFramePacker.pack(buffer,stride,2,5,7,64,64)
        assertEquals(prefix,buffer.position());assertEquals(64,frame.width);assertEquals(64,frame.height)
        assertEquals(((5+7*3)%256).toByte(),frame.bytes.first())
        assertEquals(((68+70*3)%256).toByte(),frame.bytes.last())
    }
    @Test fun truncatedCameraPlaneIsRejectedBeforeNativeAccess() {
        assertThrows(IllegalArgumentException::class.java) { DocumentFramePacker.pack(ByteBuffer.wrap(ByteArray(50)),64,1,0,0,64,64) }
    }
    @Test fun narrowOrOverlappingCameraRowsAreRejectedInsteadOfDistortingTheFrame() {
        assertThrows(IllegalArgumentException::class.java) { DocumentFramePacker.pack(ByteBuffer.allocate(64*4096),64,1,0,0,64,4096) }
        assertThrows(IllegalArgumentException::class.java) { DocumentFramePacker.pack(ByteBuffer.allocate(64*64),32,1,0,0,64,64) }
    }
}
