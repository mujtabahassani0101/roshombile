package com.haseebullahjan.roshd.ui.components

import com.haseebullahjan.roshd.ui.camera.*
import java.io.File
import javax.imageio.ImageIO
import java.util.zip.ZipFile
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Runs the actual preview admission on unrectified camera frames, including bad ones. */
class PreviewReplayTest {
    private val root=generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }.first { File(it,"settings.gradle.kts").isFile }
    private val folder=File(root,"output/auto-capture-1.1.35/preview-replay")
    private val resource=File(root,"app/src/test/resources/preview-replay.zip")
    private fun read(file:String):CameraFramePacker.Frame {
        val rotated=Regex("^(.*)-(90|180|270)\\.png$").matchEntire(file)
        val upright=rotated?.let { it.groupValues[1]+"-0.png" } ?: file
        val image=ZipFile(resource).use { archive -> archive.getInputStream(archive.getEntry(upright)).use(ImageIO::read) }
        var frame=CameraFramePacker.Frame(image.width,image.height,ByteArray(image.width*image.height) { i ->
            image.raster.getSample(i%image.width,i/image.width,0).toByte()
        },null)
        repeat(rotated?.groupValues?.get(2)?.toInt()?.div(90) ?: 0) {
            val pixels=ByteArray(frame.bytes.size)
            for(y in 0 until frame.height)for(x in 0 until frame.width)pixels[(frame.width-1-x)*frame.height+y]=frame.bytes[y*frame.width+x]
            frame=CameraFramePacker.Frame(frame.height,frame.width,pixels,null)
        }
        return frame
    }
    private fun inspect(frame:CameraFramePacker.Frame,rotation:Int):Pair<List<Float>,GuidedScanFrame.Quality> {
        val guide=ScanGuide.corners(if(rotation%180==0)frame.width else frame.height,if(rotation%180==0)frame.height else frame.width)
        return guide to GuidedScanFrame.analyze(frame,rotation,guide)
    }
    @Test fun clearFaintDimAndRotatedFramesCaptureButBlurNoiseAndMisalignmentDoNot() {
        val cases=JSONArray(ZipFile(resource).use { archive -> archive.getInputStream(archive.getEntry("manifest.json")).bufferedReader().use { it.readText() } })
        val report=JSONArray();val failures=mutableListOf<String>()
        for(i in 0 until cases.length()) {
            val c=cases.getJSONObject(i);val frame=read(c.getString("file"));val started=System.nanoTime()
            val (guide,q)=inspect(frame,c.getInt("rotation"));val elapsed=(System.nanoTime()-started)/1_000_000.0
            val gate=AutoCaptureGate();var hint=AutoCaptureGate.Hint.FIND_PAGE
            // First photograph must not depend on completion of asynchronous QR.
            for(j in 0..4)hint=gate.update(AutoCaptureGate.Observation(1000L+j*80,guide,q.sharpness,1.0,0,q.fingerprint,
                guided=true,alignedMarkers=q.aligned)).hint
            val accepted=hint==AutoCaptureGate.Hint.CAPTURE
            report.put(JSONObject().put("case",c.getString("file")).put("sharpness",q.sharpness).put("coherence",q.edgeCoherence)
                .put("contrast",q.contrast).put("noiseFraction",q.noiseFraction).put("aligned",q.alignedCorners.count { it }).put("capture",accepted).put("analysisMsHost",elapsed))
            // These historical 30/35 px shifts are now intentionally inside
            // the larger windows. The source fixtures remain unchanged.
            val expected=c.getBoolean("capture") || c.getString("kind") in listOf("shift-x","shift-y")
            if(accepted!=expected)failures.add("${c.getString("file")}: $hint sharpness=${q.sharpness} corners=${q.alignedCorners}")
        }
        requireNotNull(folder.parentFile).mkdirs();File(folder.parentFile,"preview-results.json").writeText(report.toString(2)+"\n")
        assertTrue(failures.joinToString("\n"),failures.isEmpty())
    }
    @Test fun ordinaryHandTremorDoesNotResetTheHoldButLargeMotionDoes() {
        for(count in listOf(40,75,100)) {
            val gate=AutoCaptureGate();var hint=AutoCaptureGate.Hint.FIND_PAGE
            for((i,file) in listOf("$count-clear-0.png","$count-jitter-1.png","$count-jitter--2.png","$count-jitter-3.png","$count-jitter-2.png").withIndex()) {
                val (guide,q)=inspect(read(file),0)
                hint=gate.update(AutoCaptureGate.Observation(1000L+i*80,guide,q.sharpness,1.0,0,q.fingerprint,guided=true,alignedMarkers=q.aligned)).hint
            }
            assertEquals("$count small tremor",AutoCaptureGate.Hint.CAPTURE,hint)
            val (guide,q)=inspect(read("$count-shift-x-0.png"),0)
            assertNotEquals(AutoCaptureGate.Hint.CAPTURE,gate.update(AutoCaptureGate.Observation(1400,guide,q.sharpness,1.0,0,q.fingerprint,guided=true,alignedMarkers=q.aligned)).hint)
        }
    }
    @Test fun captureRearmsForTheNextSimilarFormAfterASlowQrAndMissedDecode() {
        val (guide,q)=inspect(read("100-clear-0.png"),0);val gate=AutoCaptureGate();val identity=PreviewPageIdentity()
        fun update(time:Long)=gate.update(AutoCaptureGate.Observation(time,guide,q.sharpness,1.0,0,q.fingerprint,
            identity.current(time,q.fingerprint,true),guided=true,alignedMarkers=q.aligned))
        for(i in 0..4)update(1000L+i*80)
        gate.captureStarted();gate.confirmCapturedIdentity("T1:1:AbCd1234")
        assertEquals(AutoCaptureGate.Hint.SHOW_QR,update(1500).hint)
        identity.update("T1:2:AbCd1234",1600,2392,q.fingerprint)
        identity.update(null,2400,2943,q.fingerprint)
        var decision=update(2950)
        for(i in 1..4)decision=update(2950L+i*80)
        assertEquals(AutoCaptureGate.Hint.CAPTURE,decision.hint)
        gate.captureStarted()
        assertEquals(AutoCaptureGate.Hint.NEXT_PAGE,update(3350).hint)
    }
}
