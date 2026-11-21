package com.haseebullahjan.roshd.qa

import android.app.Instrumentation
import android.graphics.*
import android.os.Bundle
import android.os.SystemClock
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.haseebullahjan.roshd.core.*
import com.haseebullahjan.roshd.ui.camera.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Opt-in MLKit fixture benchmark. Synthetic placement does not establish live-camera accuracy. */
class PreviewPerformanceTest:Instrumentation() {
    override fun onCreate(arguments:Bundle?) { super.onCreate(arguments);start() }
    override fun onStart() {
        val result=Bundle()
        val folder=File(targetContext.cacheDir,"preview-performance-${UUID.randomUUID()}").apply { mkdirs() }
        val scanner=BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
        val rows=JSONArray()
        try {
            check(java.util.TimeZone.getTimeZone("UTC").id=="UTC")
            fun scene(page:Bitmap,white:Boolean):CameraFramePacker.Frame {
                val bitmap=Bitmap.createBitmap(960,1280,Bitmap.Config.ARGB_8888)
                val guide=ScanGuide.corners(960,1280)
                Canvas(bitmap).apply {
                    drawColor(if(white)Color.WHITE else Color.rgb(50,55,60))
                    drawBitmap(page,null,RectF(guide[0]*960,guide[1]*1280,guide[2]*960,guide[7]*1280),Paint(Paint.FILTER_BITMAP_FLAG))
                }
                val pixels=IntArray(960*1280);bitmap.getPixels(pixels,0,960,0,0,960,1280);bitmap.recycle()
                return CameraFramePacker.Frame(960,1280,ByteArray(pixels.size) { i -> val p=pixels[i];((Color.red(p)*77+Color.green(p)*150+Color.blue(p)*29) shr 8).toByte() },null)
            }
            fun rotate(frame:CameraFramePacker.Frame):CameraFramePacker.Frame {
                val bytes=ByteArray(frame.bytes.size)
                for(y in 0 until frame.height)for(x in 0 until frame.width)bytes[x*frame.height+frame.height-1-y]=frame.bytes[y*frame.width+x]
                return CameraFramePacker.Frame(frame.height,frame.width,bytes,null)
            }
            fun inspect(name:String,source:CameraFramePacker.Frame,rotation:Int,expected:String?=null,upsideDown:Boolean=false) {
                val start=SystemClock.elapsedRealtime()
                val frame=PreviewLumaFrame.copy(ByteBuffer.wrap(source.bytes),source.width,1,0,0,source.width,source.height)
                val width=if(rotation%180==0)frame.width else frame.height
                val height=if(rotation%180==0)frame.height else frame.width
                val guide=ScanGuide.corners(width,height)
                val quality=GuidedScanFrame.analyze(frame,rotation,guide)
                val input=PreviewLumaFrame.headers(frame,rotation,guide)
                val preparationMs=SystemClock.elapsedRealtime()-start
                val qrStart=SystemClock.elapsedRealtime()
                val image=InputImage.fromByteArray(input.bytes,input.width,input.height,0,InputImage.IMAGE_FORMAT_NV21)
                val decoded=(0..1).map { Tasks.await(scanner.process(image),10,TimeUnit.SECONDS).mapNotNull { it.rawValue }.distinct().singleOrNull() }
                val qrMs=SystemClock.elapsedRealtime()-qrStart
                val qr=decoded.first()
                check(qr!=null && decoded.all { it==qr } && (expected==null || qr==expected)) { "QR did not survive header crop: $name" }
                val identity=PreviewPageIdentity(setOf(qr))
                identity.update(qr,1000);identity.update(qr,1150)
                val gate=AutoCaptureGate()
                var hint=AutoCaptureGate.Hint.SHOW_QR
                for(i in 0..4)hint=gate.update(AutoCaptureGate.Observation(1150L+i*80,guide,quality.sharpness,1.0,0,quality.fingerprint,qr,guided=true,alignedMarkers=quality.aligned)).hint
                check(hint==AutoCaptureGate.Hint.CAPTURE) { "Clear page was blocked: $name sharpness=${quality.sharpness} contrast=${quality.contrast}" }
                gate.captureStarted()
                check(gate.update(AutoCaptureGate.Observation(1600,guide,quality.sharpness,1.0,0,quality.fingerprint,qr,guided=true,alignedMarkers=quality.aligned)).hint==AutoCaptureGate.Hint.NEXT_PAGE)
                rows.put(json("case" to name,"rotation" to rotation,"preparationMs" to preparationMs,"twoQrReadsMs" to qrMs,
                    "sharpness" to quality.sharpness,"contrast" to quality.contrast,"qrMatched" to true,"captureHoldMs" to 320,"captureReady" to (hint==AutoCaptureGate.Hint.CAPTURE)))
            }
            for(count in listOf(40,75,100)) {
                val page=context.assets.open("preview/${count}q.jpg").use { BitmapFactory.decodeStream(it) }
                val qr="${if(count==40)"T3" else "T1"}:${count*1000}:R0000000"
                for(white in listOf(false,true)) {
                    var frame=scene(page,white)
                    for(turn in 0..3) {
                        inspect("${count}q-${if(white)"white" else "dark"}",frame,(360-turn*90)%360,qr)
                        frame=rotate(frame)
                    }
                    val reversed=rotate(rotate(scene(page,white)))
                    inspect("${count}q-upside-down-${if(white)"white" else "dark"}",reversed,0,qr,true)
                }
                // A small hand tremor must not restart the whole hold window.
                val base=scene(page,true);val guide=ScanGuide.corners(base.width,base.height)
                val gate=AutoCaptureGate()
                for((index,offset) in listOf(0,2,-2,3,1).withIndex()) {
                    val shifted=ByteArray(base.bytes.size) { n ->
                        val x=(n%base.width+offset).coerceIn(0,base.width-1)
                        val y=(n/base.width-offset).coerceIn(0,base.height-1)
                        base.bytes[y*base.width+x]
                    }
                    val quality=GuidedScanFrame.analyze(CameraFramePacker.Frame(base.width,base.height,shifted,null),0,guide)
                    val decision=gate.update(AutoCaptureGate.Observation(2000L+index*80,guide,quality.sharpness,1.0,0,quality.fingerprint,qr,guided=true,alignedMarkers=quality.aligned))
                    if(index==4)check(decision.hint==AutoCaptureGate.Hint.CAPTURE) { "Small hand tremor blocked ${count}q" }
                }
                val moved=ByteArray(base.bytes.size) { n -> base.bytes[(n/base.width)*base.width+(n%base.width+20).coerceAtMost(base.width-1)] }
                val quality=GuidedScanFrame.analyze(CameraFramePacker.Frame(base.width,base.height,moved,null),0,guide)
                check(gate.update(AutoCaptureGate.Observation(2400,guide,quality.sharpness,1.0,0,quality.fingerprint,qr,guided=true,alignedMarkers=quality.aligned)).hint!=AutoCaptureGate.Hint.CAPTURE)
                page.recycle()
            }
            for(brightness in listOf(0,20,100,255)) {
                val blank=CameraFramePacker.Frame(960,1280,ByteArray(960*1280) { brightness.toByte() },null)
                check(GuidedScanFrame.analyze(blank,0,ScanGuide.corners(960,1280)).sharpness==0.0)
            }
            result.putString("stream","\n"+json("cases" to rows,"blankFramesRejected" to 4,"handJitterCasesPassed" to 3,"largeMovementCasesRejected" to 3,
                "syntheticPlacement" to true,"liveCameraTested" to false).toString(2)+"\n")
            finish(0,result)
        } catch(e:Throwable) {
            result.putString("stream","\nPreview check failed: ${e.javaClass.simpleName}: ${e.message?.takeIf { e is IllegalStateException }}\n"+e.stackTrace.take(5).joinToString("\n"))
            finish(1,result)
        } finally { scanner.close();folder.deleteRecursively() }
    }
}
