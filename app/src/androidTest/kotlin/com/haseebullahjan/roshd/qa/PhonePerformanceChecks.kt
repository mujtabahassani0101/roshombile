package com.haseebullahjan.roshd.qa

import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import com.haseebullahjan.roshd.MainActivity
import com.haseebullahjan.roshd.core.*
import com.haseebullahjan.roshd.messenger.ApplicationLoader
import com.haseebullahjan.roshd.platform.CameraDocumentReader
import com.haseebullahjan.roshd.platform.OmrNative
import com.haseebullahjan.youngscientist.DocumentImagePreparation
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Read-only timings: local photos and synthetic observations; no scan writes or submissions. */
class PhonePerformanceChecks(private val runner:Instrumentation) {
    private fun sha256(value:String)=java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    fun run(probe:Boolean=false,compare:Boolean=false):JSONObject {
        check(java.util.TimeZone.getTimeZone("UTC").id=="UTC")
        runner.startActivitySync(Intent(runner.targetContext,MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        val app=(runner.targetContext.applicationContext as ApplicationLoader).controller
        val start=SystemClock.elapsedRealtime()
        while(app.busy && SystemClock.elapsedRealtime()-start<30_000)Thread.sleep(50)
        check(!app.busy && !app.cameraProcessing)
        val owner=checkNotNull(app.session)
        val beforeScans=app.storage.list(owner.scope,"scan")
        val beforeQueue=app.storage.queue(owner.scope).size
        val folder=File(runner.targetContext.cacheDir,"phone-performance-${UUID.randomUUID()}").apply { mkdirs() }
        val report=json("version" to runner.targetContext.packageManager.getPackageInfo(runner.targetContext.packageName,0).versionName)
        val readings=JSONArray();val scores=JSONArray()
        try {
            val templates=SheetFamilies.templates.map { name -> File(folder,name).also { file ->
                runner.targetContext.assets.open("templates/$name").use { input -> file.outputStream().use { input.copyTo(it) } }
            } }
            val samples=beforeScans.filter { it.s("inputSource")=="camera" && File(it.s("sourcePath")).isFile }
                .sortedByDescending { it.optLong("capturedAtEpochMs") }.take(2)
                .mapIndexed { index,record -> Triple("saved-photo-${index+1}",File(record.s("sourcePath")),record.copy()) }.toMutableList()
            for((family,count) in listOf(1 to 100,3 to 40)) {
                val input=File(folder,"fixture-$count.jpg")
                runner.context.assets.open(if(family==3)"omr/T3-anchors.jpg" else "omr/T1.jpg")
                    .use { stream -> input.outputStream().use { stream.copyTo(it) } }
                samples.add(Triple("fixture-$count",input,json("inputSource" to "camera","captureQuestionCount" to count)))
            }
            for((index,sample) in samples.withIndex()) {
                val (label,input,record)=sample
                runner.sendStatus(1,Bundle().apply { putString("stream","Timing $label…\n") })
                val hash=EvidenceArchive.hash(input)
                val count=record.optInt("captureQuestionCount")
                val request=json("templateJsonPaths" to templates.filter { it.name in SheetFamilies.templatesForSelection(count) }.map { it.path },
                    "outputDirectory" to File(folder,"read-$index").apply { mkdirs() }.path,
                    "includeQuestions" to true,"returnNormalizedImages" to true,"returnAnnotationGeometry" to true,
                    "preferCalibratedHeader" to true,"maxInputDimension" to 2000,
                    "qrBindings" to if(label.startsWith("saved"))OmrRosterIndex(app.packages.values,app.round).historicalBindings else emptyList<JSONObject>())
                val begun=SystemClock.elapsedRealtime()
                if(probe) {
                    val original=DocumentImagePreparation.prepareForHeader(input,File(folder,"original-$index"),2000)
                    val framed=File(folder,"framed-$index.bmp")
                    val framingAt=SystemClock.elapsedRealtime()
                    val framing=JSONObject(OmrNative.prepareCameraJson(json("imagePath" to original,"outputPath" to framed.path,
                        "forceRectification" to true).toString()))
                    val framingMs=SystemClock.elapsedRealtime()-framingAt
                    for((strategy,path) in listOf("original" to original,"printed-squares" to if(framing.optBoolean("applied"))framed.path else original)) {
                        runner.sendStatus(1,Bundle().apply { putString("stream","Timing $label / $strategy…\n") })
                        val readAt=SystemClock.elapsedRealtime()
                        val result=JSONObject(OmrNative.processJson(request.copy().put("outputDirectory",File(folder,"$index-$strategy").apply { mkdirs() }.path)
                            .put("pages",JSONArray().put(json("pageNumber" to 1,"imagePath" to path,"activeQuestionCount" to count))).toString()))
                        val sheet=result.a("sheets").getJSONObject(0)
                        readings.put(json("case" to label,"strategy" to strategy,"questionCount" to count,
                            "totalMs" to SystemClock.elapsedRealtime()-readAt,"framingMs" to framingMs,"framing" to framing,
                            "success" to sheet.optBoolean("success"),"status" to sheet.s("status"),"error" to sheet.s("error"),
                            "complete" to CameraNormalizationPolicy.complete(sheet,count),"matchedRows" to sheet.optInt("matchedRowsUsed"),
                            "geometry" to sheet.s("homographyStrategyUsed"),"headerMs" to sheet.optDouble("headerProcessingTimeMs",0.0),
                            "answersMs" to sheet.optDouble("processingTimeMs",0.0),"inkVerification" to sheet.o("inkVerification"),
                            "openCvThreads" to org.opencv.core.Core.getNumThreads()))
                    }
                    check(hash==EvidenceArchive.hash(input));continue
                }
                for(optimized in if(compare)listOf(false,true) else listOf(true)) {
                    val readAt=SystemClock.elapsedRealtime()
                    val timedRequest=request.copy().put("outputDirectory",File(folder,"read-$index-$optimized").apply { mkdirs() }.path)
                        .put("verifiedPrintFastPath",optimized).put("reuseBarcodeDecodes",optimized)
                    val result=CameraDocumentReader.read(input,record,timedRequest)
                    val sheet=result.a("sheets").optJSONObject(0) ?: JSONObject()
                    readings.put(json("case" to label,"questionCount" to count,"sourceSha256" to hash,
                        "optimized" to optimized,"totalMs" to SystemClock.elapsedRealtime()-readAt,
                        "stages" to result.o("stageTimings"),"success" to sheet.optBoolean("success"),
                        "complete" to CameraNormalizationPolicy.complete(sheet,count),
                        "answerFingerprint" to sha256(canonical(wrap(sheet.a("questions").objects().map { question ->
                            json("question" to question.optInt("question"),"status" to question.s("status"),
                                "selectedOption" to question.s("selectedOption"),"selectedOptionNumber" to question.optInt("selectedOptionNumber"),
                                "needsReview" to question.optBoolean("needsReview")) }))),
                        "headerMs" to sheet.optDouble("headerProcessingTimeMs",0.0),"answersMs" to sheet.optDouble("processingTimeMs",0.0),
                        "documentScanner" to sheet.o("documentScanner"),"recordedCaptureToResultMs" to record.optLong("captureToResultMs")))
                }
                check(hash==EvidenceArchive.hash(input)) { "Original photograph changed" }
            }
            val crypto=ScannerCrypto(app.native)
            for(pack in app.packages.values.filter { it.valid }.take(2)) {
                val scoped=owner.scope(pack.exam.schoolId)
                val identity=crypto.identity(scoped)
                val observations=(1..pack.exam.count).map { json("state" to "selected","selectedOption" to ((it-1)%4)+1) }
                val times=mutableListOf<Long>();var available=false
                repeat(3) {
                    val begun=SystemClock.elapsedRealtime()
                    available=crypto.offlineCount(scoped,identity,pack,observations)!=null
                    times.add(SystemClock.elapsedRealtime()-begun)
                }
                scores.put(json("questionCount" to pack.exam.count,"samplesMs" to times,"scoreAvailable" to available))
            }
            check(canonical(JSONArray(beforeScans))==canonical(JSONArray(app.storage.list(owner.scope,"scan"))))
            check(beforeQueue==app.storage.queue(owner.scope).size)
            return report.put("readings",readings).put("scoring",scores)
                .put("savedResultsUnchanged",true).put("queueCountUnchanged",true)
        } finally { folder.deleteRecursively() }
    }
}
