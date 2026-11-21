package com.haseebullahjan.roshd.qa

import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import com.haseebullahjan.roshd.core.*
import com.haseebullahjan.roshd.messenger.ApplicationLoader
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant

/** Opt-in device diagnosis. No answers, tokens, names, QR text or signed bytes are printed. */
class UploadDiagnosticsTest:Instrumentation() {
    private var online=false
    private var export=false
    private var retry=false
    private var retryAll=false
    private var fresh=false
    private var sendAll=false
    private var initializeOnly=false
    private var exportGraded=false
    override fun onCreate(arguments:Bundle?) { online=arguments?.getString("online")=="true";export=arguments?.getString("export")=="true";retry=arguments?.getString("retry")=="true";retryAll=arguments?.getString("retryAll")=="true";fresh=arguments?.getString("fresh")=="true";sendAll=arguments?.getString("sendAll")=="true";initializeOnly=arguments?.getString("initializeOnly")=="true";exportGraded=arguments?.getString("exportGraded")=="true";super.onCreate(arguments);start() }
    override fun onStart() {
        val output=Bundle()
        try {
            check(java.util.TimeZone.getTimeZone("UTC").id=="UTC")
            // Retain the time-library entry points also used by the target APK.
            check(java.time.OffsetDateTime.parse("2099-01-01T00:00:00Z",java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant().isAfter(Instant.now()))
            val begin=SystemClock.elapsedRealtime()
            var controller:com.haseebullahjan.roshd.messenger.ExamsController?=null
            while(controller==null && SystemClock.elapsedRealtime()-begin<30000) {
                controller=runCatching { (targetContext.applicationContext as ApplicationLoader).controller }.getOrNull()
                if(controller==null)Thread.sleep(20)
            }
            val app=checkNotNull(controller)
            val storage=app.storage
            val saved=storage.get("global","account","session") ?: error("No saved session")
            val owner=Session(saved,Roshd.API_ORIGIN)
            fun safe(value:String)=value.replace(Regex("[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}"),"{id}")
                .replace(Regex("(?:T[123]:[0-9]+:)?S2[0-9A-Z]{18}"),"{qr}").take(400)
            val records=storage.list(owner.scope,"scan")
            val queue=storage.queue(owner.scope,"scan",includeRequests=true)
            val images=storage.list(owner.scope,"image")
            val report=json("appVersion" to com.haseebullahjan.roshd.BuildConfig.VERSION_NAME,"sessionValid" to owner.valid,"deviceNow" to now(),"sessionExpiresAt" to owner.raw.s("expiresAt"),"sessionHasToken" to owner.token.isNotBlank(),"expiryParseError" to runCatching { parseServerInstant(owner.raw.s("expiresAt"));"" }.exceptionOrNull()?.let { it.javaClass.simpleName+":"+it.message },"scans" to records.size,
                "scanStates" to records.groupingBy { it.s("state") }.eachCount(),"queueStates" to queue.groupingBy { it.s("status") }.eachCount(),
                "imagePhases" to images.groupingBy { it.s("phase") }.eachCount(),"cameraWork" to storage.cameraWorkCount(owner.scope))
            report.put("records",JSONArray(records.mapIndexed { index,r ->
                val state=images.firstOrNull { it.s("scanId")==r.s("scanId") } ?: JSONObject()
                json("row" to index,"state" to r.s("state"),"sealed" to r.optBoolean("sealed"),"hasScanId" to r.s("scanId").isNotEmpty(),
                    "pendingReview" to ScanReview.pendingCount(r),"answers" to r.a("answers").length(),"captureCount" to r.optInt("captureQuestionCount"),
                    "answerStates" to r.a("answers").objects().groupingBy { it.s("state") }.eachCount(),
                    "pendingSelected" to r.a("answers").objects().count { it.s("state")=="selected" && ScanReview.needsReview(it) },
                    "pendingUnknown" to r.a("answers").objects().count { it.s("state") in setOf("ambiguous","unreadable") },
                    "pendingBlank" to r.a("answers").objects().count { it.s("state")=="blank" && ScanReview.needsReview(it) },
                    "pendingMultiple" to r.a("answers").objects().count { it.s("state")=="multiple" && ScanReview.needsReview(it) },
                    "submissionError" to safe(r.s("submissionError")),"error" to safe(r.s("error")),"hasIdentity" to r.s("examId").isNotEmpty(),
                    "sourceBytes" to File(r.s("sourcePath")).length(),"imagePhase" to state.s("phase"),"imageError" to safe(state.s("lastError")),
                    "imageHasReceipt" to state.has("receipt"),"imageHasCaptureReceipt" to state.has("captureReceipt"),"readElapsedMs" to r.optLong("captureToResultMs"))
            }))
            if(export && records.isNotEmpty()) {
                val record=records.first();val folder=File(targetContext.getExternalFilesDir(null),"upload-diagnosis").apply { mkdirs() }
                val original=File(record.s("sourcePath"));val before=EvidenceArchive.hash(original)
                original.copyTo(File(folder,"source.jpg"),overwrite=true)
                val normalized=File(record.o("engine").s("normalizedImagePath"))
                if(normalized.isFile)normalized.copyTo(File(folder,"normalized.jpg"),overwrite=true)
                val engine=record.o("engine")
                report.put("selectedEngine",json("warnings" to engine.a("warnings"),"reviewReasons" to engine.a("reviewReasons"),
                    "rows" to engine.optInt("matchedRowsUsed"),"strategy" to engine.s("homographyStrategyUsed"),"reprojectionError" to engine.optDouble("reprojectionError"),
                    "inkVerification" to engine.o("inkVerification"),"answersByState" to record.a("answers").objects().groupingBy { it.s("state") }.eachCount(),
                    "reviewByReason" to engine.a("questions").objects().filter { it.optBoolean("needsReview") }.groupingBy { it.s("reviewReason") }.eachCount(),
                    "headerMs" to engine.optDouble("headerProcessingTimeMs"),"answerMs" to engine.optDouble("processingTimeMs"),"framing" to engine.o("cameraFraming"),
                    "hasManualReview" to record.s("reviewedAt").isNotBlank()))
                check(before==EvidenceArchive.hash(original))
                report.put("exportDirectory",folder.absolutePath)
            }
            report.put("queue",JSONArray(queue.mapIndexed { index,r ->
                val request=r.o("request")
                val payload=runCatching { JSONObject(String(ScannerCrypto.decode(request.s("payloadBase64")),Charsets.UTF_8)) }.getOrDefault(JSONObject())
                json("row" to index,"state" to r.s("status"),"attempts" to r.optInt("attempts"),"retryInMs" to (r.optLong("retryAt")-System.currentTimeMillis()).coerceAtLeast(0),
                    "error" to safe(r.s("error")),"receiptStatus" to r.o("receipt").s("status"),"receiptSchema" to r.o("receipt").s("schemaVersion"),
                    "packetSchema" to request.s("schemaVersion"),"payloadSchema" to payload.s("schemaVersion"),"payloadAnswers" to payload.a("answers").length())
            }))
            val configuration=storage.get(owner.scope,"configuration","current") ?: storage.get(owner.scope,"information","current")?.o("configuration") ?: JSONObject()
            report.put("savedConfigurationKeys",configuration.keys().asSequence().toList())
            report.put("savedFeatures",configuration.opt("features"))
            if(online && owner.valid) {
                val at=SystemClock.elapsedRealtime()
                try {
                    val config=app.network.request("GET","/api/v1/scanner/configuration",owner,readTimeoutMs=15000).data()
                    report.put("configurationGet",json("success" to true,"elapsedMs" to SystemClock.elapsedRealtime()-at,"keys" to config.keys().asSequence().toList(),"features" to config.opt("features")))
                } catch(e:Exception) {report.put("configurationGet",json("success" to false,"type" to e.javaClass.simpleName,"code" to (e as? ApiFailure)?.code,"status" to (e as? ApiFailure)?.status,"message" to safe(e.message.orEmpty()),"elapsedMs" to SystemClock.elapsedRealtime()-at))}
                report.put("serverReceipts",JSONArray(queue.take(12).mapIndexed { index,row ->
                    val begin=SystemClock.elapsedRealtime()
                    try {
                        val receipt=app.network.request("GET","/api/v1/scanner/scans/${uuid(row.s("id"))}/receipt",owner,
                            school=row.o("metadata").s("schoolId"),query=mapOf("deviceUuid" to row.o("request").s("deviceUuid")),readTimeoutMs=15000).data()
                        val valid=runCatching { ReceiptVerifier.validate("scan",row.o("request"),receipt) }.isSuccess
                        json("row" to index,"found" to true,"valid" to valid,"schema" to receipt.s("schemaVersion"),"status" to receipt.s("status"),"elapsedMs" to SystemClock.elapsedRealtime()-begin)
                    } catch(e:Exception) {json("row" to index,"found" to false,"type" to e.javaClass.simpleName,"code" to (e as? ApiFailure)?.code,"status" to (e as? ApiFailure)?.status,"message" to safe(e.message.orEmpty()),"elapsedMs" to SystemClock.elapsedRealtime()-begin)}
                }))
                report.put("serverImages",JSONArray(images.take(12).mapIndexed { index,state ->
                    val id=state.s("scanId");val device=state.o("request").s("deviceUuid").ifEmpty { state.o("captureRequest").s("deviceUuid") }
                    val record=records.firstOrNull { it.s("scanId")==id }
                    val begin=SystemClock.elapsedRealtime()
                    try {
                        val path=if(state.s("uploadId").isNotEmpty() && state.s("phase")!="verified")"/api/v1/scanner/original-image-uploads/${uuid(state.s("uploadId"))}" else "/api/v1/scanner/scans/${uuid(id)}/original-image"
                        val receipt=app.network.request("GET",path,owner,school=record?.s("schoolId"),query=mapOf("deviceUuid" to device),readTimeoutMs=15000).data()
                        json("row" to index,"found" to true,"status" to receipt.s("status"),"parts" to receipt.a("parts").length(),"partCount" to receipt.optInt("partCount"),"elapsedMs" to SystemClock.elapsedRealtime()-begin)
                    } catch(e:Exception) {json("row" to index,"found" to false,"type" to e.javaClass.simpleName,"code" to (e as? ApiFailure)?.code,"status" to (e as? ApiFailure)?.status,"message" to safe(e.message.orEmpty()),"elapsedMs" to SystemClock.elapsedRealtime()-begin)}
                }))
            }
            if((retry || sendAll || initializeOnly) && owner.valid) {
                startActivitySync(android.content.Intent(targetContext,com.haseebullahjan.roshd.MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                val initializeAt=SystemClock.elapsedRealtime()
                while((app.session==null || app.busy) && SystemClock.elapsedRealtime()-initializeAt<40000)Thread.sleep(50)
                check(app.session?.valid==true && !app.busy)
                val record=if(sendAll || initializeOnly)records.first() else records.first { r -> images.none { it.s("scanId")==r.s("scanId") && (it.s("phase")=="verified" || (fresh && it.s("uploadId").isNotBlank())) } }
                val beforeAnswers=sha256(canonical(record.a("answers")));val beforeEvidence=EvidenceArchive.hash(File(record.s("sourcePath")))
                val allAnswers=records.associate { it.s("id") to sha256(canonical(it.a("answers"))) }
                fun observations(r:JSONObject)=canonical(JSONArray(r.a("answers").objects().map { a -> json("questionNumber" to a.optInt("questionNumber"),"state" to a.s("state"),"selectedOption" to a.opt("selectedOption"),"markedOptions" to a.a("markedOptions")) }))
                val originalObservations=records.associate { it.s("id") to observations(it) }
                val latch=java.util.concurrent.CountDownLatch(1);var result:Result<String>?=null
                val at=SystemClock.elapsedRealtime()
                val done:(Result<String>)->Unit={ result=it;latch.countDown() }
                if(initializeOnly)done(Result.success("Initialized without network mutation")) else if(sendAll)app.sendAllScans(done) else if(retryAll)app.sendImages(done) else app.sendImage(record.s("id"),done)
                var seconds=0
                while(!latch.await(5,java.util.concurrent.TimeUnit.SECONDS) && seconds<(if(retryAll || sendAll)1200 else 240)) {
                    seconds+=5
                    val phases=storage.list(owner.scope,"image").groupingBy { it.s("phase") }.eachCount()
                    sendStatus(1,Bundle().apply { putString("stream","Upload test elapsed ${seconds}s; phases=$phases; ${app.network.diagnostics.lastOrNull()?.s("path").orEmpty()}\n") })
                }
                if(result==null) { app.network.cancel();check(latch.await(40,java.util.concurrent.TimeUnit.SECONDS)) }
                val after=storage.get(owner.scope,"scan",record.s("id"))!!
                val state=storage.get(owner.scope,"image",after.s("scanId")) ?: JSONObject()
                report.put("retryImage",json("success" to result?.isSuccess,"message" to safe(result?.getOrNull().orEmpty()),"elapsedMs" to SystemClock.elapsedRealtime()-at,
                    "phase" to state.s("phase"),"errorCode" to state.s("lastErrorCode"),"httpStatus" to state.optInt("lastErrorStatus"),
                    "evidenceUnchanged" to (beforeEvidence==EvidenceArchive.hash(File(after.s("sourcePath")))),"answersUnchanged" to (beforeAnswers==sha256(canonical(after.a("answers"))))))
                report.put("afterImagePhases",storage.list(owner.scope,"image").groupingBy { it.s("phase") }.eachCount())
                report.put("allAnswersUnchanged",allAnswers.all { (id,hash) -> hash==sha256(canonical(storage.get(owner.scope,"scan",id)!!.a("answers"))) })
                report.put("allObservationsUnchanged",originalObservations.all { (id,value) -> value==observations(storage.get(owner.scope,"scan",id)!!) })
                report.put("requestTimings",JSONArray(app.network.diagnostics.toList()))
            }
            if(exportGraded) {
                val at=SystemClock.elapsedRealtime()
                while(storage.list(owner.scope,"scan").any { it.optBoolean("annotationPending") } && SystemClock.elapsedRealtime()-at<120000)Thread.sleep(500)
                val after=storage.list(owner.scope,"scan")
                report.put("afterRecords",JSONArray(after.mapIndexed { index,r ->
                    val artifact=r.o("uploadEvidence");val a=GradedSheet.current(r) ?: JSONObject()
                    val receipt=storage.get(owner.scope,"image",r.s("scanId"))?.o("receipt") ?: JSONObject()
                    json("row" to index,"pendingReview" to ScanReview.pendingCount(r),"sealed" to r.optBoolean("sealed"),"hasScore" to r.has("offlineScore"),"scoreWarning" to safe(r.s("scoreWarning")),
                        "annotationWarning" to safe(r.s("annotationWarning")),"submissionError" to safe(r.s("submissionError")),"green" to a.optInt("correctMarks"),"red" to a.optInt("incorrectMarks"),"yellow" to a.optInt("blankCorrectMarks"),
                        "artifactBytes" to artifact.optLong("sizeBytes"),"variant" to artifact.s("variant"),"width" to a.optInt("width"),"height" to a.optInt("height"),"sourceFrozenToArtifact" to (artifact.s("sha256").isNotBlank() && artifact.s("sha256")==r.o("sourceSnapshot").s("sourceImageSha256")),
                        "receiptMatchesArtifact" to (artifact.s("sha256").isNotBlank() && artifact.s("sha256")==receipt.s("sha256")))
                }))
                report.put("afterCounters",json("scans" to app.scans.size,"deliveredAnswers" to app.uploadedAnswerCount,"verifiedProcessedImages" to app.uploadedImageCount,"reviewBlockedScans" to app.reviewBlockedScanCount))
                val chosen=after.filter { GradedSheet.image(it)!=null }.maxByOrNull { r -> r.o("annotation").let { a -> listOf("correctMarks","incorrectMarks","blankCorrectMarks").count { a.optInt(it)>0 } } }
                if(chosen!=null) {
                    val folder=File(targetContext.getExternalFilesDir(null),"upload-diagnosis").apply { mkdirs() }
                    File(GradedSheet.image(chosen)!!).copyTo(File(folder,"graded.jpg"),overwrite=true)
                    report.put("gradedExport",json("directory" to folder.absolutePath,"sha256" to EvidenceArchive.hash(File(folder,"graded.jpg")),"colors" to chosen.o("annotation").let { a -> json("green" to a.optInt("correctMarks"),"red" to a.optInt("incorrectMarks"),"yellow" to a.optInt("blankCorrectMarks")) }))
                }
            }
            output.putString("stream","\n"+report.toString(2)+"\n");finish(0,output)
        } catch(e:Throwable) {output.putString("stream","\nDIAGNOSIS FAILED: "+e.javaClass.simpleName+"\n"+e.stackTrace.take(8).joinToString("\n"));finish(1,output)}
    }
}
