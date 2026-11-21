package com.haseebullahjan.roshd.ui.components

import com.haseebullahjan.roshd.ui.camera.*
import java.io.File
import javax.imageio.ImageIO
import java.util.zip.ZipFile
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WideGuideReplayTest {
    private val root=generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }.first { File(it,"settings.gradle.kts").isFile }
    @Test fun offCenterSquaresAdmitQuicklyButFalseTargetsBlurAndOutOfWindowPagesDoNot() {
        replay("wide-guide-preview.zip","output/wide-guides-1.1.40")
    }
    @Test fun november15PrintsKeepTheSameFixedGuidesAndRejectUnusableFrames() {
        replay("nov15-guide-preview.zip","output/new-sheets-1.1.41")
    }
    private fun replay(resource:String,output:String) {
        val report=JSONArray();val failures=mutableListOf<String>()
        ZipFile(File(root,"app/src/test/resources/$resource")).use { zip ->
            val cases=JSONArray(zip.getInputStream(zip.getEntry("manifest.json")).bufferedReader().use { it.readText() })
            var cachedName="";var upright:CameraFramePacker.Frame?=null
            for(i in 0 until cases.length()) {
                val c=cases.getJSONObject(i);val file=c.getString("file")
                if(file!=cachedName) {
                    val im=zip.getInputStream(zip.getEntry(file)).use(ImageIO::read)
                    upright=CameraFramePacker.Frame(im.width,im.height,ByteArray(im.width*im.height) { p -> im.raster.getSample(p%im.width,p/im.width,0).toByte() },null)
                    cachedName=file
                }
                var frame=requireNotNull(upright);val rotation=c.getInt("rotation")
                repeat(rotation/90) {
                    val pixels=ByteArray(frame.bytes.size)
                    for(y in 0 until frame.height)for(x in 0 until frame.width)pixels[(frame.width-1-x)*frame.height+y]=frame.bytes[y*frame.width+x]
                    frame=CameraFramePacker.Frame(frame.height,frame.width,pixels,null)
                }
                val guide=ScanGuide.corners(requireNotNull(upright).width,requireNotNull(upright).height)
                val start=System.nanoTime();val q=GuidedScanFrame.analyze(frame,rotation,guide,c.getDouble("targetMm").toFloat())
                val elapsed=(System.nanoTime()-start)/1e6;val gate=AutoCaptureGate();var hint=AutoCaptureGate.Hint.FIND_PAGE
                var capturedAt:Long?=null
                for(j in 0..4) {
                    hint=gate.update(AutoCaptureGate.Observation(1000L+j*80,guide,q.sharpness,1.0,0,q.fingerprint,guided=true,alignedMarkers=q.aligned)).hint
                    if(hint==AutoCaptureGate.Hint.CAPTURE && capturedAt==null)capturedAt=j*80L
                }
                val accepted=capturedAt!=null
                report.put(JSONObject(c.toString()).put("accepted",accepted).put("holdMs",capturedAt ?: JSONObject.NULL)
                    .put("sharpness",q.sharpness).put("aligned",q.alignedCorners.count { it }).put("analysisMsHost",elapsed))
                if(accepted!=c.getBoolean("capture"))failures.add("$file rotation=$rotation target=${c.getInt("targetMm")} hint=$hint sharpness=${q.sharpness} aligned=${q.alignedCorners}")
                if(accepted)assertEquals("$file",240L,capturedAt)
            }
        }
        File(root,output).mkdirs()
        File(root,"$output/preview-results.json").writeText(report.toString(2)+"\n")
        assertTrue(failures.joinToString("\n"),failures.isEmpty())
    }
}
