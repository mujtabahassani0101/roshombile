package com.haseebullahjan.roshd.ui.components

import com.haseebullahjan.roshd.ui.camera.*
import org.junit.Assert.*
import org.junit.Test

class GuidedScanFrameTest {
    private val corners=ScanGuide.corners(960,1280)
    private fun texture()=CameraFramePacker.Frame(960,1280,ByteArray(960*1280) { i ->
        if((i%960/5+i/960/5)%2==0)235.toByte() else 65.toByte()
    },null)
    @Test fun guidesKeepA5ProportionsInPortraitAndLandscapeWithoutFollowingDetections() {
        for((width,height) in listOf(960 to 1280,1280 to 960,720 to 1600)) {
            val guide=ScanGuide.corners(width,height)
            assertEquals(148.0/210,(guide[2]-guide[0])*width/((guide[7]-guide[1])*height).toDouble(),.00001)
            assertTrue(guide.all { it in .04f.. .96f })
            assertEquals(guide,ScanGuide.corners(width,height))
        }
    }
    @Test fun fourFixedTargetsMatchThePrintedSquareCentersRatherThanThePaperEdges() {
        val page=listOf(0f,0f,148f,0f,148f,210f,0f,210f)
        val expected=listOf(9.5f to 9.5f,138.5f to 9.5f,138.5f to 200.5f,9.5f to 200.5f)
        ScanGuide.anchorCenters(page).zip(expected).forEach { (actual,wanted) ->
            assertEquals(wanted.first,actual.first,.0001f);assertEquals(wanted.second,actual.second,.0001f)
        }
    }
    @Test fun blankDarkAndBlurredFramesCannotBecomeSharpPages() {
        for(brightness in listOf(0,20,100,255)) {
            val frame=CameraFramePacker.Frame(960,1280,ByteArray(960*1280) { brightness.toByte() },null)
            assertEquals(0.0,GuidedScanFrame.analyze(frame,0,corners).sharpness,0.0)
        }
        val sharp=GuidedScanFrame.analyze(texture(),0,corners)
        assertTrue(sharp.sharpness>65);assertEquals(PageFingerprint.SIZE,sharp.fingerprint.values.size)
    }
    @Test fun guidanceRequiresFourAlignedTargetsAndClearFramesWithoutWaitingForTheFirstQr() {
        val quality=GuidedScanFrame.analyze(texture(),0,corners)
        val gate=AutoCaptureGate()
        fun observation(time:Long,id:String?)=AutoCaptureGate.Observation(time,corners,quality.sharpness,1.0,0,quality.fingerprint,id,guided=true,alignedMarkers=true)
        assertEquals(AutoCaptureGate.Hint.ALIGN_GUIDES,gate.update(observation(900,null).copy(alignedMarkers=false)).hint)
        assertEquals(AutoCaptureGate.Hint.HOLD_STILL,gate.update(observation(1000,null)).hint)
        gate.clearStability()
        for(i in 0..4) {
            val decision=gate.update(observation(1100L+i*80,"T1:1:AbCd1234"))
            assertEquals(if(i>=3)AutoCaptureGate.Hint.CAPTURE else AutoCaptureGate.Hint.HOLD_STILL,decision.hint)
        }
        gate.captureStarted()
        assertEquals(AutoCaptureGate.Hint.SHOW_QR,gate.update(observation(1550,null)).hint)
        assertEquals(AutoCaptureGate.Hint.NEXT_PAGE,gate.update(observation(1600,"T1:1:AbCd1234")).hint)
        assertEquals(AutoCaptureGate.Hint.SHARPEN,gate.update(observation(1700,"T1:2:AbCd1234").copy(sharpness=5.0)).hint)
    }
    @Test fun cameraRotationDoesNotMoveTheGuidesOrChangeTheMeasuredContent() {
        val frame=texture();val rotated=ByteArray(frame.bytes.size)
        for(y in 0 until frame.height)for(x in 0 until frame.width)rotated[x*frame.height+(frame.height-1-y)]=frame.bytes[y*frame.width+x]
        val upright=CameraFramePacker.Frame(frame.height,frame.width,rotated,null)
        val guide=ScanGuide.corners(upright.width,upright.height)
        val a=GuidedScanFrame.analyze(frame,90,guide);val b=GuidedScanFrame.analyze(upright,0,guide)
        assertEquals(a.sharpness,b.sharpness,.00001);assertArrayEquals(a.fingerprint.values,b.fingerprint.values)
    }
    @Test fun largerWindowsShareTheirPhysicalSamplingScaleWithTheDisplay() {
        assertEquals(22f,ScanGuide.targetSizeMm(360f,1f),.001f)
        assertEquals(26f,ScanGuide.targetSizeMm(220f,1f),.001f)
        for(density in listOf(1f,2f,3f)) {
            val pageWidth=280*density
            assertEquals(44*density,pageWidth*ScanGuide.targetSizeMm(pageWidth,density)/148,.001f)
        }
    }
    @Test fun aSharpHeaderCannotHideBlurredAnswerAreasEvenOnAnUpsideDownPage() {
        val source=texture()
        for(bottomHeader in listOf(false,true)) {
            val pixels=ByteArray(source.bytes.size) { i ->
                val y=i/source.width
                if(if(bottomHeader)y>source.height*.74 else y<source.height*.26)source.bytes[i] else 230.toByte()
            }
            val frame=CameraFramePacker.Frame(source.width,source.height,pixels,null)
            assertEquals(0.0,GuidedScanFrame.analyze(frame,0,corners).sharpness,0.0)
        }
    }

}
