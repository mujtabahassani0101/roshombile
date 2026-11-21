package com.haseebullahjan.roshd.qa

import android.app.Instrumentation
import android.graphics.*
import android.os.Bundle
import android.os.SystemClock
import com.haseebullahjan.roshd.core.*
import com.haseebullahjan.roshd.messenger.ApplicationLoader
import com.haseebullahjan.roshd.platform.OmrNative
import com.haseebullahjan.youngscientist.scanner.DocumentScanner
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Opt-in, local timing of an existing photo. No network, scoring, or scan-record writes. */
class ScanPerformanceTest:Instrumentation() {
    private var probe=false
    private var production=false
    private var wide=false
    private var threadProbe=false
    private var inspect=false
    private var fixtures=true
    private var latest=false
    private var diagnostic=false
    override fun onCreate(arguments:Bundle?) { probe=arguments?.getString("mode")=="probe";production=arguments?.getString("mode")=="production";inspect=arguments?.getString("mode")=="inspect";diagnostic=arguments?.getString("mode")=="diagnostic";latest=arguments?.getString("latest")=="true";fixtures=arguments?.getString("fixtures")!="false";wide=arguments?.getString("wide")=="true";threadProbe=arguments?.getString("threads")=="compare";super.onCreate(arguments);start() }
    override fun onStart() {
        val output=Bundle()
        val folder=File(targetContext.cacheDir,"scan-performance-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            // Test L8 otherwise strips this retarget used by the target app's
            // ExifInterface while its partial j$ class shadows the app's copy.
            check(java.util.TimeZone.getTimeZone("UTC").id=="UTC")
            sendStatus(0,Bundle().apply { putString("stream","\nphase=application\n") })
            val startedApplication=SystemClock.elapsedRealtime()
            var controller:com.haseebullahjan.roshd.messenger.ExamsController?=null
            while(controller==null && SystemClock.elapsedRealtime()-startedApplication<30_000) {
                controller=runCatching { (targetContext.applicationContext as ApplicationLoader).controller }.getOrNull()
                if(controller==null)Thread.sleep(20)
            }
            val app=checkNotNull(controller)
            val wait=SystemClock.elapsedRealtime()
            while(app.busy && SystemClock.elapsedRealtime()-wait<30_000)Thread.sleep(20)
            check(!app.busy && !app.cameraProcessing)
            sendStatus(0,Bundle().apply { putString("stream","\nphase=local-data-ready\n") })
            // Read the existing encrypted snapshot directly. Timing a local
            // photograph does not require launching/restoring the animated UI.
            val savedOwner=app.session ?: app.storage.get("global","account","session")?.let { Session(it,Roshd.API_ORIGIN) }
            val savedScopes=app.storage.readableDatabase.rawQuery("SELECT DISTINCT scope FROM documents WHERE kind='scan'",null).use { cursor ->
                buildList { while(cursor.moveToNext())add(cursor.getString(0)) }
            }
            sendStatus(0,Bundle().apply { putString("stream","\n"+json("targetPackage" to targetContext.packageName,"testPackage" to context.packageName,"savedScanScopes" to savedScopes.size,"restoredSession" to (savedOwner!=null)).toString()+"\n") })
            val scope=savedOwner?.scope ?: savedScopes.singleOrNull() ?: error("No unique saved scan scope")
            val available=app.storage.list(scope,"scan").filter { File(it.s("sourcePath")).isFile }
            if(inspect) {
                output.putString("stream","\n"+org.json.JSONArray(available.map { record ->
                    val sheet=record.o("engine")
                    json("elapsedMs" to record.optLong("captureToResultMs"),"count" to record.optInt("captureQuestionCount"),
                        "success" to sheet.optBoolean("success"),"headerMs" to sheet.optDouble("headerProcessingTimeMs",0.0),
                        "answerMs" to sheet.optDouble("processingTimeMs",0.0),"strategy" to sheet.s("homographyStrategyUsed"),
                        "rows" to sheet.optInt("matchedRowsUsed"),"error" to sheet.optDouble("reprojectionError",0.0),
                        "documentApplied" to sheet.o("documentScanner").optBoolean("applied"),
                        "documentReason" to sheet.o("documentScanner").s("reason"),
                        "originalFallback" to sheet.o("documentScanner").optBoolean("originalFallback"),
                        "sourceFirst" to sheet.o("documentScanner").optBoolean("sourceNormalizationFirst"))
                }).toString(2)+"\n")
                finish(0,output);return
            }
            val snapshot=(if(latest)available else available.filter { it.o("engine").optBoolean("success") }.ifEmpty { available })
                .maxByOrNull { it.optLong("captureToResultMs") } ?: error("No saved photo")
            val source=File(snapshot.s("sourcePath"))
            val beforeHash=EvidenceArchive.hash(source)
            val beforeRecord=canonical(app.storage.get(scope,"scan",snapshot.s("id")))
            if(diagnostic) {
                val info=com.haseebullahjan.youngscientist.DocumentImagePreparation.inspect(source)
                val decoder=com.google.mlkit.vision.barcode.BarcodeScanning.getClient(com.google.mlkit.vision.barcode.BarcodeScannerOptions.Builder().setBarcodeFormats(com.google.mlkit.vision.barcode.common.Barcode.FORMAT_QR_CODE).build())
                val begun=SystemClock.elapsedRealtime()
                val image=com.google.mlkit.vision.common.InputImage.fromFilePath(targetContext,android.net.Uri.fromFile(source))
                val found=com.google.android.gms.tasks.Tasks.await(decoder.process(image),30,java.util.concurrent.TimeUnit.SECONDS)
                val report=json("count" to snapshot.optInt("captureQuestionCount"),"storedStatus" to snapshot.o("engine").s("status"),"storedError" to snapshot.o("engine").s("error"),
                    "width" to info.width,"height" to info.height,"orientation" to info.orientation,"elapsedMs" to SystemClock.elapsedRealtime()-begun,
                    "qrDetections" to found.size,"qrBounds" to found.map { b -> val r=b.boundingBox;json("left" to r?.left,"top" to r?.top,"right" to r?.right,"bottom" to r?.bottom,"payloadLength" to b.rawValue.orEmpty().length) })
                decoder.close()
                check(beforeHash==EvidenceArchive.hash(source) && beforeRecord==canonical(app.storage.get(scope,"scan",snapshot.s("id"))))
                output.putString("stream","\n"+report.toString(2)+"\n");finish(0,output);return
            }
            val templates=SheetFamilies.templates.map { name ->
                File(folder,name).also { f -> targetContext.assets.open("templates/$name").use { i -> f.outputStream().use { i.copyTo(it) } } }.path
            }
            val manual=snapshot.optJSONArray("documentCorners")?.let { a -> (0 until a.length()).map { a.getDouble(it) } }
            val start=SystemClock.elapsedRealtime()
            val prepared=if(production)DocumentScanner.Prepared(source.path,false,json()) else DocumentScanner.prepareForOmr(source,File(folder,"document"),manual,snapshot.optInt("documentTurns"))
            val documentMs=SystemClock.elapsedRealtime()-start
            val count=snapshot.optInt("captureQuestionCount")
            val round=snapshot.optInt("captureRound").takeIf { it>0 } ?: app.round
            val routing=OmrRosterIndex(app.packages.values,round)
            val bindings=(if(count>0)routing.historicalBindings else routing.recognitionBindings).toMutableList()
            // Historical payloads require a binding. Reuse only this scan's
            // already verified, frozen identity if the test classloader could
            // not restore a current package; never invent an identity or grade.
            val stored=snapshot.o("engine")
            val qr=OmrResultParser.qr(stored)
            val family=stored.o("sheetType").s("code")
            if(stored.optBoolean("success") && qr.isNotEmpty() && family in setOf("T1","T3") && bindings.none { it.s("qrText")==qr })
                bindings.add(json("qrText" to qr,"sheetType" to family))
            // A new failed capture may be the same physical sheet as an older
            // verified one. Test-only recovery accepts those exact frozen
            // identities, never a QR invented from the failed result.
            if(latest)for(record in available) {
                val verified=record.o("engine");val value=OmrResultParser.qr(verified);val code=verified.o("sheetType").s("code")
                if(verified.optBoolean("success") && value.isNotEmpty() && code in setOf("T1","T3") && bindings.none { it.s("qrText")==value })
                    bindings.add(json("qrText" to value,"sheetType" to code))
            }
            fun read(fast:Boolean,direct:Boolean=false):Pair<JSONObject,JSONObject> {
                val request=json("templateJsonPaths" to templates,"outputDirectory" to File(folder,if(direct)"direct" else if(fast)"fast" else "original").path,
                    "includeQuestions" to true,"returnNormalizedImages" to true,"returnAnnotationGeometry" to true,
                    "maxInputDimension" to 2000,"preferCalibratedHeader" to fast,"preferWideHeader" to wide,"qrBindings" to bindings)
                fun engine(path:String)=JSONObject(OmrNative.processJson(request.copy().put("pages",wrap(listOf(json(
                    "pageNumber" to 1,"imagePath" to path,"activeQuestionCount" to count)))).toString())).a("sheets").getJSONObject(0)
                val begun=SystemClock.elapsedRealtime()
                var stage=JSONObject()
                var sheet=if(production) {
                    val result=com.haseebullahjan.roshd.platform.CameraDocumentReader.read(source,snapshot,request)
                    stage=result.o("stageTimings");result.a("sheets").getJSONObject(0)
                } else engine(if(direct)source.path else prepared.path)
                var fallback=false
                if(!production && !direct && !sheet.optBoolean("success") && prepared.rectified && manual==null) {
                    fallback=true
                    val original=com.haseebullahjan.youngscientist.DocumentImagePreparation.prepareForHeader(source,File(folder,"original-input"),2000)
                    sheet=engine(original)
                }
                val stats=json("calibratedHeader" to fast,"recognitionMs" to SystemClock.elapsedRealtime()-begun,
                    "threads" to org.opencv.core.Core.getNumThreads(),"headerMs" to sheet.optDouble("headerProcessingTimeMs"),"answerMs" to sheet.optDouble("processingTimeMs"),
                    "documentMs" to stage.optLong("documentMs"),"engineMs" to stage.optLong("recognitionMs"),
                    "completeSourceGeometry" to CameraNormalizationPolicy.complete(sheet,count),
                    "sourceNormalizationFirst" to sheet.o("documentScanner").optBoolean("sourceNormalizationFirst"),
                    "matchedRows" to sheet.optInt("matchedRowsUsed"),"reprojectionError" to sheet.optDouble("reprojectionError"),
                    "geometryStrategy" to sheet.s("homographyStrategyUsed"),
                    "success" to sheet.optBoolean("success"),"status" to sheet.s("status"),"originalFallback" to fallback)
                return sheet to stats
            }
            val (before,beforeTiming)=if(production)stored to json("recordedElapsedMs" to snapshot.optLong("captureToResultMs")) else if(probe)read(true,true) else read(false)
            sendStatus(0,Bundle().apply { putString("stream","\nphase=reading-photo\n") })
            if(threadProbe)org.opencv.core.Core.setNumThreads(8)
            val (after,afterTiming)=read(true,threadProbe && probe)
            fun decisions(sheet:JSONObject)=canonical(json(*listOf("success","status","error","sheetType","qrCode","questions","annotationGeometry")
                .map { it to sheet.opt(it) }.toTypedArray()))
            check(beforeHash==EvidenceArchive.hash(source))
            check(beforeRecord==canonical(app.storage.get(scope,"scan",snapshot.s("id"))))
            val report=json("documentMs" to documentMs,"recordedElapsedMs" to snapshot.optLong("captureToResultMs"),
                "storedReadSuccess" to stored.optBoolean("success"),"cachedPackages" to app.packages.size,"selectedQuestionCount" to count,
                "sourceWidth" to com.haseebullahjan.youngscientist.DocumentImagePreparation.inspect(source).width,
                "rectified" to prepared.rectified,"original" to beforeTiming,"calibrated" to afterTiming,"directProbe" to probe,"productionPipeline" to production,"wideHeader" to wide,"threadProbe" to threadProbe,
                "storedOriginalFallback" to stored.o("documentScanner").optBoolean("originalFallback"),
                "storedHeaderMs" to stored.optDouble("headerProcessingTimeMs"),"storedAnswerMs" to stored.optDouble("processingTimeMs"),
                "identicalAnswerStates" to (runCatching { canonical(OmrResultParser.observations(before,count)) }.getOrNull()==runCatching { canonical(OmrResultParser.observations(after,count)) }.getOrNull()),
                "identicalRecognition" to (decisions(before)==decisions(after)),"originalPhotoUnchanged" to true,"savedResultUnchanged" to true)
            if(!probe && stored.optBoolean("success"))check(report.optBoolean("identicalRecognition")) { "Recognition decisions changed" }
            if(production && !stored.optBoolean("success") && !(after.optBoolean("success") && CameraNormalizationPolicy.complete(after,count))) {
                output.putString("stream","\n"+report.toString(2)+"\nRecovery check failed\n");finish(1,output);return
            }
            if(production && fixtures) {
                sendStatus(0,Bundle().apply { putString("stream","\nphoto-comparison="+report.toString()+"\n") })
                val fixtureResults=org.json.JSONArray()
                val fixtures=org.json.JSONArray(context.assets.open("preview/expected.json").bufferedReader().use { it.readText() })
                for(fixture in fixtures.objects()) {
                    val n=fixture.optInt("count")
                    for(projected in listOf(false,true)) {
                        val file=File(folder,"fixture-$n-$projected.jpg")
                        if(!projected)context.assets.open("preview/${n}q.jpg").use { input -> file.outputStream().use { input.copyTo(it) } }
                        else {
                            val page=context.assets.open("preview/${n}q.jpg").use { BitmapFactory.decodeStream(it) }
                            val photo=Bitmap.createBitmap(2200,3000,Bitmap.Config.ARGB_8888)
                            val matrix=Matrix().apply { check(setPolyToPoly(floatArrayOf(0f,0f,(page.width-1).toFloat(),0f,(page.width-1).toFloat(),(page.height-1).toFloat(),0f,(page.height-1).toFloat()),0,
                                if(n==75)floatArrayOf(320f,450f,1960f,450f,1960f,2750f,320f,2750f)
                                else floatArrayOf(320f,180f,1960f,330f,1870f,2790f,210f,2660f),0,4)) }
                            Canvas(photo).apply { drawColor(Color.rgb(64,70,74));drawBitmap(page,matrix,Paint(Paint.FILTER_BITMAP_FLAG)) }
                            file.outputStream().use { check(photo.compress(Bitmap.CompressFormat.JPEG,96,it)) };page.recycle();photo.recycle()
                        }
                        val hash=EvidenceArchive.hash(file)
                        val request=json("templateJsonPaths" to templates,"outputDirectory" to File(folder,"fixture-output-$n-$projected").path,
                            "includeQuestions" to true,"returnNormalizedImages" to true,"returnAnnotationGeometry" to true,
                            "maxInputDimension" to 2000,"preferCalibratedHeader" to true,"preferWideHeader" to true)
                        val start=SystemClock.elapsedRealtime()
                        val result=com.haseebullahjan.roshd.platform.CameraDocumentReader.read(file,json("inputSource" to "camera","captureQuestionCount" to n),request)
                        val sheet=result.a("sheets").getJSONObject(0)
                        sendStatus(0,Bundle().apply { putString("stream","\nfixture="+json("count" to n,"projected" to projected,"success" to sheet.optBoolean("success"),"status" to sheet.s("status"),"error" to sheet.s("error"),"questions" to sheet.a("questions").length(),"documentApplied" to sheet.o("documentScanner").optBoolean("applied"),"originalFallback" to sheet.o("documentScanner").optBoolean("originalFallback")).toString()+"\n") })
                        if(n==75 && projected && !sheet.optBoolean("success")) {
                            check(sheet.a("questions").length()==0 && hash==EvidenceArchive.hash(file))
                            fixtureResults.put(json("count" to n,"projected" to true,"offsetHeader" to true,"success" to false,
                                "elapsedMs" to SystemClock.elapsedRealtime()-start,"incompleteSourceReadRejected" to true,"questions" to 0))
                            continue
                        }
                        val answers=OmrResultParser.observations(sheet,n)
                        check(sheet.optBoolean("success") && OmrResultParser.qr(sheet)==fixture.s("qr")) { "Fixture identity failed: $n/$projected" }
                        check(answers.all { q -> q.s("state")=="selected" && q.optInt("selectedOption")==fixture.a("answers").getInt(q.optInt("questionNumber")-1) }) { "Fixture answer mismatch: $n/$projected" }
                        check(hash==EvidenceArchive.hash(file))
                        fixtureResults.put(json("count" to n,"projected" to projected,"offsetHeader" to (n==75 && projected),"documentApplied" to sheet.o("documentScanner").optBoolean("applied"),"elapsedMs" to SystemClock.elapsedRealtime()-start,
                            "allKnownAnswersMatched" to true,"completeSourceGeometry" to CameraNormalizationPolicy.complete(sheet,n)))
                    }
                }
                report.put("pipelineFixtures",fixtureResults)
            }
            output.putString("stream","\n"+report.toString(2)+"\n")
            finish(0,output)
        } catch(e:Throwable) {
            // Only class/stack locations: exception text can contain private source paths.
            val linkage=if(e is LinkageError)e.message.orEmpty() else ""
            output.putString("stream","\nPerformance check failed: ${e.javaClass.simpleName} $linkage\n"+e.stackTrace.take(5).joinToString("\n"))
            finish(1,output)
        } finally { folder.deleteRecursively() }
    }
}
