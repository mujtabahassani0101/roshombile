package com.haseebullahjan.roshd.qa

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.view.View
import android.view.ViewGroup
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.view.PreviewView
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.haseebullahjan.roshd.MainActivity
import com.haseebullahjan.roshd.core.*
import com.haseebullahjan.roshd.messenger.*
import com.haseebullahjan.roshd.platform.OmrNative
import com.haseebullahjan.roshd.ui.actionbar.Route
import com.haseebullahjan.roshd.ui.camera.ScannerActivity
import com.haseebullahjan.roshd.ui.components.*
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.concurrent.TimeUnit

/** Synthetic scanner checks only. Never authenticates or submits answers. */
class ScannerChecks(private val runner:Instrumentation) {
    private val context=runner.targetContext
    private var count=0
    private val report=StringBuilder()
    private fun verify(value:Boolean,label:String) { check(value) { label };count++;report.append("PASS: $label\n") }
    private fun main(action:()->Unit) {
        var failure:Throwable?=null
        runner.runOnMainSync { try { action() } catch(t:Throwable) { failure=t } }
        failure?.let { throw it }
    }
    private fun views(v:View):List<View> = listOf(v)+(if(v is ViewGroup)(0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList())
    private fun capture(name:String) {
        runner.waitForIdleSync()
        val b=runner.uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
        val file=File(context.filesDir,"qa/scanner/$name.png");file.parentFile!!.mkdirs()
        file.outputStream().use { b.compress(Bitmap.CompressFormat.PNG,100,it) };b.recycle()
    }
    fun run(cameraCount:Int=100):String {
        require(context.packageName.endsWith(".nativeqa"))
        val folder=File(context.cacheDir,"scanner-regression").apply { mkdirs() }
        val prefs=NativeSettings.open(context);val original=prefs.all
        var home:MainActivity?=null;var controller:ExamsController?=null
        val savedController=mutableMapOf<String,Any?>();var fixtureScope:String?=null
        try {
            val templates=listOf("yse_omr_a5_t1_100q_v2.json","yse_omr_a5_t3_40q_v1.json").map { name ->
                File(folder,name).also { f -> context.assets.open("templates/$name").use { input -> f.outputStream().use { input.copyTo(it) } } }.path
            }
            val images=listOf(1,3).map { i -> File(folder,"T$i.jpg").also { f -> runner.context.assets.open("omr/T$i.jpg").use { input -> f.outputStream().use { input.copyTo(it) } } } }
            verify(OmrNative.engineRevision().isNotEmpty(),"Original native engine loads")
            val response=JSONObject(OmrNative.processJson(json("templateJsonPaths" to templates,"outputDirectory" to File(folder,"out").path,"includeQuestions" to true,"compactResult" to false,"maxInputDimension" to 2000,
                "pages" to images.mapIndexed { i,f -> json("pageNumber" to i+1,"imagePath" to f.path) }).toString()))
            val sheets=response.a("sheets").objects()
            verify(sheets.size==2,"Native JNI returns both T1/T3 sheets")
            val counts=listOf(100,40)
            for((i,sheet) in sheets.withIndex()) {
                val qr=if(i==1)"T3:213327:rZeGOHtv" else "T1:001234:S2000000000000000000"
                verify(sheet.optBoolean("success") && sheet.o("sheetType").s("code")==(if(i==0)"T1" else "T3") && OmrResultParser.qr(sheet)==qr,"T${i+1} exact QR and calibrated template survive rotation")
                val answers=OmrResultParser.observations(sheet,counts[i])
                verify(answers.size==counts[i] && answers.all { it.s("state")=="selected" && it.optInt("selectedOption")==((it.optInt("questionNumber")-1)%4)+1 },"T${i+1}: all ${counts[i]} independently known choices are correct")
            }
            val barcode=BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
            try {
                for((i,file) in images.withIndex()) {
                    val source=BitmapFactory.decodeFile(file.path)
                    try {
                        for(rotation in listOf(0,90,180,270)) {
                            val rotated=if(rotation==0)source else Bitmap.createBitmap(source,0,0,source.width,source.height,Matrix().apply { postRotate(rotation.toFloat()) },true)
                            try {
                                val codes=Tasks.await(barcode.process(InputImage.fromBitmap(rotated,0)),20,TimeUnit.SECONDS).mapNotNull { it.rawValue }
                                val expected=if(i==1)"T3:213327:rZeGOHtv" else "T1:001234:S2000000000000000000"
                                verify(expected in codes,"ML Kit QR T${i+1} reads at $rotation degrees")
                            } finally { if(rotated!==source)rotated.recycle() }
                        }
                    } finally { source.recycle() }
                }
            } finally { barcode.close() }
            prefs.edit().putString("visualStyle","study").putBoolean("dark",false).putString("language","fa").commit()
            home=runner.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            controller=home.app
            for(name in listOf("session","configuration","packages","round","scans","archive","cameraQueuedCount","latestCameraFeedback")) {
                savedController[name]=ExamsController::class.java.getDeclaredField(name).apply { isAccessible=true }.get(controller)
            }
            repeat(100) { if(controller.busy)Thread.sleep(50) }
            val fixtureOwner=Session(json("accountId" to java.util.UUID.randomUUID().toString(),"schoolId" to java.util.UUID.randomUUID().toString(),"role" to "ScannerOperator","displayName" to "آزمایشی","expiresAt" to Instant.EPOCH.toString()),"https://example.org")
            fixtureScope=fixtureOwner.scope
            main {
                fun set(name:String,value:Any?) { ExamsController::class.java.getDeclaredField(name).apply { isAccessible=true;set(controller,value) } }
                set("session",fixtureOwner);set("round",1);set("packages",emptyMap<String,OfflineExam>())
                set("configuration",json("currentAcademicYear" to json("id" to "qa-year","editionId" to "qa-edition","openRounds" to listOf(1))))
                home.navigation.reset(Route("home"))
            }
            Thread.sleep(300);runner.waitForIdleSync()
            val glass=views(home.window.decorView).filterIsInstance<StudyGlassDock>().single()
            runner.uiAutomation.grantRuntimePermission(context.packageName,android.Manifest.permission.CAMERA)
            for(selectedCount in SheetFamilies.scanCounts) {
                val mode="sheet-$selectedCount"
                val scanner=runner.startActivitySync(Intent(context,ScannerActivity::class.java).putExtra("mode",mode).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ScannerActivity
                try {
                    main {
                        verify(views(scanner.window.decorView).count { it.tag in SheetFamilies.scanCounts.map { count -> "scanner-choose-$count" } }==3,"All three question counts are offered before the camera starts")
                        verify(views(scanner.window.decorView).none { it is PreviewView },"Camera does not start before a paper is selected")
                    }
                    capture("sheet-choice")
                    main {
                        views(scanner.window.decorView).single { it.tag=="scanner-choose-$selectedCount" }.performClick()
                        views(scanner.window.decorView).single { it.tag=="scanner-auto" }.performClick()
                        verify(ScannerActivity::class.java.getDeclaredField("questionCount").apply { isAccessible=true }.getInt(scanner)==selectedCount,"Chosen $selectedCount-question layout reaches the scanner")
                    }
                    val field=ScannerActivity::class.java.getDeclaredField("analysis").apply { isAccessible=true }
                    var analysis:ImageAnalysis?=null;var streaming=false
                    repeat(150) { if(!streaming) { Thread.sleep(100);main {
                        analysis=field.get(scanner) as? ImageAnalysis
                        streaming=analysis?.resolutionInfo!=null && views(scanner.window.decorView).filterIsInstance<PreviewView>().single().previewStreamState.value==PreviewView.StreamState.STREAMING
                    } } }
                    main {
                        val preview=views(scanner.window.decorView).filterIsInstance<PreviewView>().single()
                        verify(preview.scaleType==PreviewView.ScaleType.FIT_CENTER,"$mode shows the complete camera frame without preview cropping")
                        verify(streaming,"$mode camera analysis binds and streams")
                        report.append("ANALYSIS $mode: ${analysis?.resolutionInfo?.resolution}\n")
                        val photo=ScannerActivity::class.java.getDeclaredField("capture").apply { isAccessible=true }.get(scanner) as? ImageCapture
                        verify(photo?.captureMode==ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY,"$mode uses only its required camera streams and full-quality photo capture")
                    }
                    Thread.sleep(800)
                    main {
                        val panel=views(scanner.window.decorView).single { it.tag=="scanner-controls" }
                        val height=panel.height/context.resources.displayMetrics.density
                        verify(height<=310,"Document scanner controls remain outside the preview (actual $height dp)")
                        val overlay=views(scanner.window.decorView).single { it.tag=="scanner-corner-guides" }
                        verify(overlay.width>0 && overlay.height>0,"Copied document guide covers the live preview")
                        val result=views(scanner.window.decorView).single { it.tag=="scanner-live-result" }
                        verify(result.parent===panel,"Score card cannot obscure any corner of the camera preview")
                    }
                    main { verify(analysis?.resolutionInfo!=null,"$mode remains bound after the previous scanner finishes destroying") }
                    Thread.sleep(200);val paused=glass.renderedFrames
                    main { repeat(10) { glass.refresh() } };Thread.sleep(300)
                    verify(glass.renderedFrames==paused,"$mode camera has no background glass capture competing for CPU")
                    capture("$mode-preview")
                    val before=controller.storage.list(fixtureOwner.scope,"scan").map { it.s("id") }.toSet()
                    repeat(2) { index ->
                        main { views(scanner.window.decorView).single { it.tag=="scanner-shutter" }.performClick() }
                        var saved=false
                        repeat(150) { if(!saved) {
                            Thread.sleep(100)
                            main { saved=ScannerActivity::class.java.getDeclaredField("capturedCount").apply { isAccessible=true }.getInt(scanner)>=index+1 }
                        } }
                        verify(saved && !scanner.isFinishing,"Camera stays open after photo ${index+1}, without an editor or OMR wait")
                    }
                    val photos=controller.storage.list(fixtureOwner.scope,"scan").filter { it.s("id") !in before }.sortedBy { it.optLong("capturedAtEpochMs") }
                    verify(photos.size==2 && photos.all { it.s("state")=="pending" },"Two originals are durably pending without requiring an exam package")
                    verify(photos.all { it.optInt("captureQuestionCount")==selectedCount },"Every saved photo retains the chosen paper type")
                    for(photo in photos) {
                        val file=File(photo.s("sourcePath"));val info=com.haseebullahjan.roshd.DocumentImagePreparation.inspect(file)
                        verify(CameraEvidence.complete(file) && maxOf(info.width,info.height)>=1200 && EvidenceArchive.hash(file)==photo.s("sourceImageSha256"),"Full-quality JPEG, EXIF and persisted hash remain intact")
                    }
                    val feedback=LiveScanFeedback.from(photos.last().copy().put("state","ready").put("studentName","QA student")
                        .put("exam",json("questionCount" to 40)).put("offlineScore",json("correctCount" to 33,"questionCount" to 40,"rawScore" to "82.5","scoringPolicyVersion" to 1)))
                    main { controller.notifications.post(AppEvent.SCAN_RESULT,feedback) };runner.waitForIdleSync()
                    main {
                        val result=views(scanner.window.decorView).single { it.tag=="scanner-live-result" } as android.widget.TextView
                        verify(result.text.contains("82.5") && result.text.contains("QA student"),"Independent score completion appears inside the live camera")
                    }
                    main { controller.notifications.post(AppEvent.SCAN_RESULT,feedback.copy(coloredPath=photos.last().s("sourcePath"))) };runner.waitForIdleSync()
                    main {
                        val result=views(scanner.window.decorView).single { it.tag=="scanner-live-result" } as android.widget.TextView
                        verify(result.isClickable && result.text.contains("رنگی"),"Later color completion updates the same page without suppressing its score")
                    }
                    main { controller.notifications.post(AppEvent.SCAN_RESULT,feedback.copy(id=photos.first().s("id"),rawScore="777")) };runner.waitForIdleSync()
                    main {
                        val result=views(scanner.window.decorView).single { it.tag=="scanner-live-result" } as android.widget.TextView
                        verify(!result.text.contains("777"),"An older page completion cannot replace the newer displayed score")
                    }
                    main { controller.notifications.post(AppEvent.SCAN_RESULT,feedback.copy(id="unrelated-photo",rawScore="999")) };runner.waitForIdleSync()
                    main {
                        val result=views(scanner.window.decorView).single { it.tag=="scanner-live-result" } as android.widget.TextView
                        verify(!result.text.contains("999"),"A different camera session cannot replace this session's score")
                    }
                    capture("continuous-score")
                } finally { main { scanner.finish() };Thread.sleep(200);runner.waitForIdleSync() }
            }
            report.append(ContinuousGradeChecks(runner as NativeSmokeTest).run(controller,fixtureOwner,cameraCount))
            return "$report$count scanner checks passed.\n"
        } catch(error:Throwable) { throw IllegalStateException("$report\n${error.message}",error) } finally {
            main {
                if(controller!=null)savedController.forEach { (name,value) -> ExamsController::class.java.getDeclaredField(name).apply { isAccessible=true;set(controller,value) } }
                home?.finish()
            }
            fixtureScope?.let { scope -> controller?.storage?.let { storage ->
                storage.list(scope,"scan").forEach { File(it.s("sourcePath")).delete() }
                storage.writableDatabase.delete("camera_work","scope=?",arrayOf(scope))
                storage.writableDatabase.delete("documents","scope=?",arrayOf(scope))
            } }
            folder.deleteRecursively()
            prefs.edit().apply {
                clear();original.forEach { (k,v) -> when(v) { is String->putString(k,v);is Boolean->putBoolean(k,v);is Int->putInt(k,v);is Long->putLong(k,v);is Float->putFloat(k,v) } }
            }.commit()
        }
    }
}
