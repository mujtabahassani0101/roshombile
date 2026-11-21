package com.haseebullahjan.roshd.ui.components

import com.haseebullahjan.roshd.ui.camera.AutoCaptureGate
import com.haseebullahjan.roshd.ui.camera.PageFingerprint
import org.junit.Assert.*
import org.junit.Test

class AutoCaptureGateTest {
    @Test fun cameraStillCapturesANewPageWith350msAnalysisWhileOmrIsBusy() {
        val gate=AutoCaptureGate()
        for(i in 0..2)assertEquals(AutoCaptureGate.Hint.HOLD_STILL,gate.update(frame(1000L+i*350)).hint)
        assertEquals(AutoCaptureGate.Hint.CAPTURE,gate.update(frame(2050)).hint)
        gate.captureStarted()
        for(i in 0..3)assertEquals(AutoCaptureGate.Hint.NEXT_PAGE,gate.update(frame(2500L+i*350)).hint)
        for(i in 0..2)assertEquals(AutoCaptureGate.Hint.HOLD_STILL,gate.update(frame(4500L+i*350,signature=fingerprint(9))).hint)
        assertEquals(AutoCaptureGate.Hint.CAPTURE,gate.update(frame(5550,signature=fingerprint(9))).hint)
    }
    @Test fun deletingTheCapturedPageAllowsScanningTheSamePaperAgain() {
        val gate=AutoCaptureGate();stable(gate);gate.captureStarted()
        assertEquals(AutoCaptureGate.Hint.NEXT_PAGE,stable(gate,2200).hint)
        gate.forgetCapturedPage()
        assertEquals(AutoCaptureGate.Hint.CAPTURE,stable(gate,4000).hint)
    }
    private val corners=listOf(.10f,.08f,.90f,.08f,.90f,.92f,.10f,.92f)
    private fun fingerprint(seed:Int=1,exposure:Int=0)=PageFingerprint(ByteArray(PageFingerprint.SIZE) { i ->
        (65+(i*37+seed*23+(i/17)*seed*7)%100+exposure).toByte()
    })
    private fun frame(time:Long,points:List<Float>?=corners,sharpness:Double=140.0,confidence:Double=.8,age:Long=100,signature:PageFingerprint?=fingerprint())=
        AutoCaptureGate.Observation(time,points,sharpness,confidence,age,signature)
    private fun stable(gate:AutoCaptureGate,from:Long=1000,signature:PageFingerprint=fingerprint()):AutoCaptureGate.Decision {
        var result=gate.update(frame(from,signature=signature))
        for(i in 1..3)result=gate.update(frame(from+i*200,signature=signature))
        return result
    }
    @Test fun verifiedPrintedSheetToleratesSmallHandJitterAndCapturesIn320ms() {
        val gate=AutoCaptureGate()
        for((i,shift) in listOf(0f,.006f,-.005f,.011f,.003f).withIndex()) {
            val points=corners.mapIndexed { j,v -> v+if(j%2==0)shift else -shift*.3f }
            val decision=gate.update(frame(1000L+i*80,points=points).copy(verifiedAnchors=true))
            assertEquals(if(i==4)AutoCaptureGate.Hint.CAPTURE else AutoCaptureGate.Hint.HOLD_STILL,decision.hint)
        }
    }
    @Test fun verifiedAnchorsAllowAnOffcenterFullyVisibleSheet() {
        val points=listOf(.035f,.04f,.775f,.04f,.775f,.91f,.035f,.91f)
        val gate=AutoCaptureGate()
        var decision=gate.update(frame(1000,points=points).copy(verifiedAnchors=true))
        for(i in 1..4)decision=gate.update(frame(1000L+i*80,points=points).copy(verifiedAnchors=true))
        assertEquals(AutoCaptureGate.Hint.CAPTURE,decision.hint)
    }
    @Test fun verifiedCaptureStillRejectsBlurMissingCornersClippingAndMovement() {
        val invalid=listOf(frame(1320,sharpness=5.0),frame(1320,points=null),
            frame(1320,points=corners.toMutableList().apply { set(0,.001f) }),
            frame(1320,points=corners.mapIndexed { i,v -> v+if(i%2==0).03f else 0f }))
        for(bad in invalid) {
            val gate=AutoCaptureGate()
            for(i in 0..3)gate.update(frame(1000L+i*80).copy(verifiedAnchors=true))
            assertNotEquals(AutoCaptureGate.Hint.CAPTURE,gate.update(bad.copy(verifiedAnchors=true)).hint)
        }
    }
    @Test fun GenericAndVerifiedCornersCannotShareAStabilityWindow() {
        val gate=AutoCaptureGate()
        for(i in 0..3)gate.update(frame(1000L+i*200))
        assertEquals(0f,gate.update(frame(1640).copy(verifiedAnchors=true)).progress,0f)
        assertEquals(AutoCaptureGate.Hint.HOLD_STILL,gate.update(frame(1720).copy(verifiedAnchors=true)).hint)
    }
    @Test fun requiresAtLeastFourSharpStableCurrentFrames() {
        val gate=AutoCaptureGate()
        assertEquals(AutoCaptureGate.Hint.HOLD_STILL,gate.update(frame(1000)).hint)
        assertEquals(AutoCaptureGate.Hint.HOLD_STILL,gate.update(frame(1300)).hint)
        assertEquals(AutoCaptureGate.Hint.HOLD_STILL,gate.update(frame(1600)).hint)
        assertEquals(AutoCaptureGate.Hint.CAPTURE,gate.update(frame(1800)).hint)
    }
    @Test fun movementResetsTheWholeStabilityWindow() {
        val gate=AutoCaptureGate()
        gate.update(frame(1000));gate.update(frame(1300))
        val moved=corners.mapIndexed { i,x -> if(i%2==0)x+.03f else x }
        assertEquals(0f,gate.update(frame(1500,points=moved)).progress,0f)
        assertEquals(AutoCaptureGate.Hint.HOLD_STILL,gate.update(frame(1700)).hint)
        assertEquals(AutoCaptureGate.Hint.CAPTURE,stable(gate,1900).hint)
    }
    @Test fun samePageCannotBePhotographedAgainAfterASuccessfulCapture() {
        val gate=AutoCaptureGate();assertEquals(AutoCaptureGate.Hint.CAPTURE,stable(gate).hint)
        gate.captureStarted()
        for(time in 2000L..5000L step 200)assertEquals(AutoCaptureGate.Hint.NEXT_PAGE,gate.update(frame(time)).hint)
    }
    @Test fun removingAndReturningTheSamePageDoesNotBypassDuplicateProtection() {
        val gate=AutoCaptureGate();stable(gate);gate.captureStarted()
        gate.update(frame(2000,points=null));gate.update(frame(2400,points=null))
        assertEquals(AutoCaptureGate.Hint.NEXT_PAGE,stable(gate,3000).hint)
    }
    @Test fun aNewStablePageCanBeCapturedWithoutWaitingForOmr() {
        val gate=AutoCaptureGate();stable(gate);gate.captureStarted()
        assertEquals(AutoCaptureGate.Hint.CAPTURE,stable(gate,2000,fingerprint(9)).hint)
    }
    @Test fun exposureChangesAloneDoNotUnlockTheCapturedPage() {
        val gate=AutoCaptureGate();stable(gate);gate.captureStarted()
        assertEquals(AutoCaptureGate.Hint.NEXT_PAGE,stable(gate,2200,fingerprint(exposure=30)).hint)
    }
    @Test fun aPassingObstructionDoesNotCauseAnImmediateSecondCapture() {
        val gate=AutoCaptureGate();stable(gate);gate.captureStarted()
        gate.update(frame(1900,signature=fingerprint(7)));gate.update(frame(2100,signature=fingerprint(7)))
        assertEquals(AutoCaptureGate.Hint.NEXT_PAGE,gate.update(frame(2300)).hint)
    }
    @Test fun photoFailureAllowsRetryOfTheSamePage() {
        val gate=AutoCaptureGate();stable(gate);gate.captureStarted();gate.captureFailed()
        assertEquals(AutoCaptureGate.Hint.CAPTURE,stable(gate,2200).hint)
    }
    @Test fun aRestoredCameraScreenKeepsItsDuplicateLock() {
        val first=AutoCaptureGate();stable(first);first.captureStarted()
        val restored=AutoCaptureGate();restored.restoreFingerprint(first.savedFingerprint())
        assertEquals(AutoCaptureGate.Hint.NEXT_PAGE,stable(restored,2200).hint)
    }
    @Test fun staleBlurredLowConfidenceAndNonfiniteObservationsNeverTrigger() {
        for(bad in listOf(frame(2000,sharpness=5.0),frame(2000,confidence=.01),frame(2000,age=2000),
            frame(2000,points=corners.toMutableList().apply { set(0,Float.NaN) }))) {
            val gate=AutoCaptureGate();gate.update(frame(1000));gate.update(frame(1300));gate.update(frame(1600))
            assertNotEquals(AutoCaptureGate.Hint.CAPTURE,gate.update(bad).hint)
        }
    }
    @Test fun clippedSmallAndCrossingQuadsAreRejected() {
        val gate=AutoCaptureGate()
        assertEquals(AutoCaptureGate.Hint.SHOW_EDGES,gate.update(frame(1000,points=corners.toMutableList().apply { set(0,.001f) })).hint)
        assertEquals(AutoCaptureGate.Hint.MOVE_CLOSER,gate.update(frame(1300,points=listOf(.4f,.4f,.6f,.4f,.6f,.6f,.4f,.6f))).hint)
        assertEquals(AutoCaptureGate.Hint.SHOW_EDGES,gate.update(frame(1600,points=listOf(.1f,.1f,.9f,.9f,.9f,.1f,.1f,.9f))).hint)
    }
    @Test fun aSheetOutsideTheFocusCenterCannotTriggerAutomaticCapture() {
        val shifted=listOf(.04f,.28f,.84f,.28f,.84f,.96f,.04f,.96f)
        val gate=AutoCaptureGate()
        assertEquals(AutoCaptureGate.Hint.CENTER_PAGE,gate.update(frame(1000,points=shifted)).hint)
    }
    @Test fun outOfOrderAndRepeatedFramesCannotSatisfyTheHoldTime() {
        val gate=AutoCaptureGate();gate.update(frame(1000))
        repeat(10) { assertNotEquals(AutoCaptureGate.Hint.CAPTURE,gate.update(frame(1000)).hint) }
        assertNotEquals(AutoCaptureGate.Hint.CAPTURE,gate.update(frame(500)).hint)
    }
    @Test fun descriptorSamplingHonorsCameraRotationAndOwnsOnly960Bytes() {
        val size=96
        val image=ByteArray(size*size) { i -> ((i/size*7+i%size*3)%180+40).toByte() }
        val rotated=ByteArray(image.size)
        for(y in 0 until size)for(x in 0 until size)rotated[x*size+(size-1-y)]=image[y*size+x]
        val a=PageFingerprint.sample(size,size,image,corners,90)
        val b=PageFingerprint.sample(size,size,rotated,corners,0)
        assertArrayEquals(a.values,b.values);assertEquals(960,a.values.size)
    }
    @Test fun similarPrintedSheetsWithDifferentQrCanBeCapturedAutomatically() {
        val gate=AutoCaptureGate()
        fun page(from:Long,identity:String):AutoCaptureGate.Decision {
            var decision=gate.update(frame(from).copy(pageId=identity))
            for(i in 1..3)decision=gate.update(frame(from+i*200).copy(pageId=identity))
            return decision
        }
        assertEquals(AutoCaptureGate.Hint.CAPTURE,page(1000,"T3:1:AbCd1234").hint)
        gate.captureStarted()
        assertEquals(AutoCaptureGate.Hint.NEXT_PAGE,page(2000,"T3:1:AbCd1234").hint)
        assertEquals(AutoCaptureGate.Hint.CAPTURE,page(3000,"T3:2:AbCd1234").hint)
    }
    @Test fun aChangedQrDoesNotBypassSharpnessOrStability() {
        val gate=AutoCaptureGate()
        for(i in 0..3)gate.update(frame(1000L+i*200).copy(pageId="one"))
        gate.captureStarted()
        assertEquals(AutoCaptureGate.Hint.SHARPEN,gate.update(frame(2000,sharpness=10.0).copy(pageId="two")).hint)
        assertEquals(AutoCaptureGate.Hint.HOLD_STILL,gate.update(frame(2200).copy(pageId="two")).hint)
        assertNotEquals(AutoCaptureGate.Hint.CAPTURE,gate.update(frame(2400).copy(pageId="three")).hint)
    }
    @Test fun cameraRecreationKeepsTheLastQrAndDuplicateLockTogether() {
        val gate=AutoCaptureGate()
        for(i in 0..3)gate.update(frame(1000L+i*200).copy(pageId="one"))
        gate.captureStarted()
        val restored=AutoCaptureGate();restored.restoreFingerprint(gate.savedFingerprint(),gate.savedIdentity())
        assertEquals(AutoCaptureGate.Hint.NEXT_PAGE,restored.update(frame(2000).copy(pageId="one")).hint)
        assertEquals(AutoCaptureGate.Hint.HOLD_STILL,restored.update(frame(2200).copy(pageId="two")).hint)
    }
    @Test fun aNonfiniteDetectorConfidenceCannotTrigger() {
        val gate=AutoCaptureGate()
        for(i in 0..9)assertNotEquals(AutoCaptureGate.Hint.CAPTURE,gate.update(frame(1000L+i*200,confidence=Double.NaN)).hint)
    }
    @Test fun originalPhotoIdentityCanCorrectAMissedPreviewQrWithoutUnlockingItsDuplicate() {
        val gate=AutoCaptureGate();stable(gate);gate.captureStarted()
        gate.confirmCapturedIdentity("original-page")
        assertEquals(AutoCaptureGate.Hint.NEXT_PAGE,gate.update(frame(2000).copy(pageId="original-page")).hint)
        for(i in 0..3) {
            val decision=gate.update(frame(2200L+i*200).copy(pageId="next-page"))
            assertEquals(if(i==3)AutoCaptureGate.Hint.CAPTURE else AutoCaptureGate.Hint.HOLD_STILL,decision.hint)
        }
    }
    @Test fun sameConfirmedIdentityStaysLockedWhenThePaperMovesOrLightingChangesItsFingerprint() {
        val gate=AutoCaptureGate()
        for(i in 0..3)gate.update(frame(1000L+i*200).copy(pageId="one"))
        gate.captureStarted()
        for(i in 0..7)assertEquals(AutoCaptureGate.Hint.NEXT_PAGE,
            gate.update(frame(2000L+i*200,signature=fingerprint(19)).copy(pageId="one")).hint)
    }
    @Test fun aPauseBetweenFramesRequiresANewCompleteStabilityWindow() {
        val gate=AutoCaptureGate()
        for(i in 0..2)gate.update(frame(1000L+i*200))
        assertEquals(AutoCaptureGate.Hint.HOLD_STILL,gate.update(frame(2100)).hint)
        assertEquals(AutoCaptureGate.Hint.CAPTURE,stable(gate,2300).hint)
    }

}
