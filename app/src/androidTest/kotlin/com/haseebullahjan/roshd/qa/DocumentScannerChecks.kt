package com.haseebullahjan.roshd.qa

import android.app.Instrumentation
import android.graphics.*
import com.haseebullahjan.roshd.core.*
import com.haseebullahjan.roshd.platform.OmrNative
import com.haseebullahjan.youngscientist.scanner.*
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.math.abs

/** Shipping JNI integration; no account, network or grade submission is used. */
class DocumentScannerChecks(private val runner:Instrumentation) {
    private val context get()=runner.targetContext
    private val report=StringBuilder()
    private var count=0
    private fun verify(value:Boolean,label:String) { check(value) { label };count++;report.append("PASS: $label\n") }
    fun run():String {
        val folder=File(context.cacheDir,"document-scanner-test-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val templates=listOf("yse_omr_a5_t1_100q_v2.json","yse_omr_a5_t3_40q_v1.json").map { name ->
                File(folder,name).also { f -> context.assets.open("templates/$name").use { i -> f.outputStream().use { i.copyTo(it) } } }.path
            }
            for(family in listOf(1,3)) {
                val decoded=runner.context.assets.open("omr/T$family.jpg").use { BitmapFactory.decodeStream(it) }
                // T2 is stored sideways; preserve the physical A5 aspect before projecting it.
                val page=if(decoded.width>decoded.height) Bitmap.createBitmap(decoded,0,0,decoded.width,decoded.height,
                    Matrix().apply { postRotate(90f) },true).also { decoded.recycle() } else decoded
                val photo=Bitmap.createBitmap(2200,3000,Bitmap.Config.ARGB_8888)
                val corners=floatArrayOf(320f,180f,1960f,330f,1870f,2790f,210f,2660f)
                val matrix=Matrix().apply { check(setPolyToPoly(floatArrayOf(0f,0f,(page.width-1).toFloat(),0f,(page.width-1).toFloat(),(page.height-1).toFloat(),0f,(page.height-1).toFloat()),0,corners,0,4)) }
                Canvas(photo).apply { drawColor(Color.rgb(64,70,74));drawBitmap(page,matrix,Paint(Paint.FILTER_BITMAP_FLAG)) };page.recycle()
                val original=File(folder,"camera-T$family.jpg")
                original.outputStream().use { check(photo.compress(Bitmap.CompressFormat.JPEG,96,it)) }
                val hash=com.haseebullahjan.roshd.core.EvidenceArchive.hash(original)
                val detection=DocumentDetection.parse(JSONObject(DocumentScannerNative.inspectFile(original.path)))
                verify(detection.found,"T$family: generic page detected without printed anchors")
                verify(detection.corners.indices.all { i -> abs(detection.corners[i]-corners[i]/(if(i%2==0)2199.0 else 2999.0))<.008 },"T$family: detected corners agree with known projected geometry")
                val prepared=DocumentScanner.prepareForOmr(original,File(folder,"prepared-$family"))
                verify(prepared.rectified && File(prepared.path).isFile,"T$family: rectified lossless recognition image exists")
                val sheet=JSONObject(OmrNative.processJson(json("templateJsonPaths" to templates,"includeQuestions" to true,"maxInputDimension" to 2000,
                    "pages" to listOf(json("pageNumber" to 1,"imagePath" to prepared.path))).toString())).a("sheets").objects().single()
                if(!sheet.optBoolean("success")) {
                    File(context.filesDir,"qa/new-scanner-failure").apply { mkdirs() }.let { debug ->
                        original.copyTo(File(debug,"camera-T$family.jpg"),overwrite=true)
                        File(prepared.path).copyTo(File(debug,"prepared-T$family.png"),overwrite=true)
                        File(debug,"T$family.json").writeText(sheet.toString())
                    }
                }
                val questions=listOf(100,60,40)[family-1]
                verify(sheet.optBoolean("success") && sheet.o("sheetType").s("code")=="T$family","T$family: unchanged OMR header and family engine accept the photograph: ${sheet.s("error")} ${sheet.o("sheetType").s("status")}")
                verify(OmrResultParser.observations(sheet,questions).all { it.s("state")=="selected" && it.optInt("selectedOption")==((it.optInt("questionNumber")-1)%4)+1 },"T$family: all $questions known answer choices survive rectification")
                verify(com.haseebullahjan.roshd.core.EvidenceArchive.hash(original)==hash,"T$family: original photograph SHA256 is preserved")
                if(family==1) {
                    val reduced=Bitmap.createScaledBitmap(photo,550,750,true);val pixels=IntArray(reduced.width*reduced.height);reduced.getPixels(pixels,0,reduced.width,0,0,reduced.width,reduced.height)
                    val luma=ByteArray(pixels.size) { i -> val p=pixels[i];((Color.red(p)*77+Color.green(p)*150+Color.blue(p)*29) shr 8).toByte() }
                    for(rotation in listOf(0,90,180,270)) {
                        val frame=DocumentDetection.parse(JSONObject(DocumentScannerNative.detectLuma(luma,reduced.width,reduced.height,rotation)))
                        verify(frame.found && DocumentQuad.valid(frame.corners),"Live luminance detection works at rotation $rotation")
                    }
                    val prefix=13;val stride=reduced.width+19;val left=5;val top=7
                    val plane=ByteBuffer.allocateDirect(prefix+stride*(reduced.height+14))
                    for(y in 0 until reduced.height)for(x in 0 until reduced.width)plane.put(prefix+(y+top)*stride+left+x,luma[y*reduced.width+x])
                    plane.position(prefix)
                    fun digest()=MessageDigest.getInstance("SHA-256").apply { update(plane.duplicate()) }.digest().toList()
                    val originalPlane=digest()
                    for(rotation in listOf(0,90,180,270)) {
                        val reference=DocumentDetection.parse(JSONObject(DocumentScannerNative.detectLuma(luma,reduced.width,reduced.height,rotation)))
                        val direct=DocumentScanner.inspectFrame(plane.asReadOnlyBuffer(),stride,1,left,top,reduced.width,reduced.height,rotation)
                        verify(direct.found && direct.corners.indices.all { abs(direct.corners[it]-reference.corners[it])<.004 },"Direct Y plane respects offsets, padding and rotation $rotation")
                    }
                    verify(plane.position()==prefix && digest()==originalPlane,"Direct JNI leaves read-only camera pixels and buffer position unchanged")
                    var invalidPlaneRejected=false
                    try { DocumentScannerNative.detectBuffer(plane,prefix,prefix+stride*reduced.height,stride,left,top,reduced.width,reduced.height,0) }
                    catch(_:IllegalArgumentException) { invalidPlaneRejected=true }
                    verify(invalidPlaneRejected,"Truncated direct camera plane is rejected before memory access")
                    reduced.recycle()
                    val manual=File(folder,"manual.png");DocumentScannerNative.rectifyFile(original.path,manual.path,detection.corners.toDoubleArray(),1600,1)
                    val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true };BitmapFactory.decodeFile(manual.path,bounds)
                    verify(bounds.outWidth>bounds.outHeight && maxOf(bounds.outWidth,bounds.outHeight)<=1600,"Manual crop and 90-degree rotation are bounded")
                    var rejected=false
                    try { DocumentScannerNative.rectifyFile(original.path,File(folder,"invalid.png").path,doubleArrayOf(0.0,0.0,1.0,1.0,1.0,0.0,0.0,1.0),1600,0) } catch(_:IllegalArgumentException) { rejected=true }
                    verify(rejected && !File(folder,"invalid.png").exists(),"Crossed manual corners are rejected without output")
                }
                photo.recycle()
            }
            val blank=Bitmap.createBitmap(800,1000,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
            val blankFile=File(folder,"blank.png");blankFile.outputStream().use { blank.compress(Bitmap.CompressFormat.PNG,100,it) };blank.recycle()
            verify(!DocumentScanner.prepareForOmr(blankFile,File(folder,"blank-output")).rectified,"No page does not invent a crop")
            val manual=DocumentScanner.prepareForOmr(blankFile,File(folder,"blank-manual"),DocumentQuad.inset)
            verify(manual.rectified && manual.metadata.o("detection").s("reason")=="manual_crop","Manual correction bypasses automatic detection entirely")
            report.append("$count checks passed; 140 known OMR answers matched after the new scanner.\n")
            return report.toString()
        } finally { folder.deleteRecursively() }
    }
}
