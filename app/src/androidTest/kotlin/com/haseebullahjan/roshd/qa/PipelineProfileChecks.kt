package com.haseebullahjan.roshd.qa

import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.SystemClock
import com.haseebullahjan.roshd.core.*
import com.haseebullahjan.roshd.platform.CameraDocumentReader
import com.haseebullahjan.roshd.platform.OmrNative
import com.haseebullahjan.youngscientist.DocumentImagePreparation
import com.haseebullahjan.youngscientist.scanner.DocumentScanner
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/** Stage timings of the unchanged camera pipeline on isolated copies. No account, database, queue or network. */
class PipelineProfileChecks(private val runner:Instrumentation,private val arguments:Bundle?) {
    private fun status(text:String)=runner.sendStatus(1,Bundle().apply { putString("stream",text+"\n") })
    private inline fun <T> timed(into:JSONObject,key:String,block:()->T):T {
        val start=SystemClock.elapsedRealtime()
        return try { block() } finally { into.put(key,SystemClock.elapsedRealtime()-start) }
    }

    fun run():JSONObject {
        val context=runner.targetContext
        com.haseebullahjan.roshd.platform.OpenCvRuntime.ensure()
        DocumentScanner.initialize(context)
        val input=File(checkNotNull(context.getExternalFilesDir(null)),"qa/omr-speed-1154/input")
        val reports=File(input.parentFile,"results").apply { mkdirs() }
        val scratch=File(context.cacheDir,"pipeline-profile-${UUID.randomUUID()}").apply { mkdirs() }
        val bindings=JSONArray(File(input,"bindings.json").readText()).objects()
        val templates=SheetFamilies.templates.associateWith { n -> File(scratch,n).also { f ->
            context.assets.open("templates/$n").use { i -> f.outputStream().use { i.copyTo(it) } }
        } }
        val names=(arguments?.getString("cases") ?: "02,06,07,09,11").split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val loopSeconds=arguments?.getString("loopSeconds")?.toIntOrNull() ?: 0
        val stagesOnly=arguments?.getString("mode")!="loop"
        fun requestFor(dir:File,count:Int,withBindings:Boolean)=json("templateJsonPaths" to SheetFamilies.templatesForSelection(count).map { templates.getValue(it).path },
            "outputDirectory" to dir.path,"returnDebugImages" to false,"returnNormalizedImages" to true,"returnAnnotationGeometry" to true,
            "compactResult" to false,"includeQuestions" to true,"maxInputDimension" to 2000,"preferCalibratedHeader" to true,
            "qrBindings" to if(withBindings)bindings else emptyList<JSONObject>())
        fun request(dir:File)=requestFor(dir,40,true)
        val rows=JSONArray()
        try {
            status("Warming the unchanged document models…")
            File(scratch,"warm").apply { mkdirs() }.also { DocumentScanner.prepareForOmr(File(input,"case-${names.first()}/source.jpg"),it);it.deleteRecursively() }
            if(!stagesOnly) {
                // Continuous full reads for an external sampling profiler.
                val until=SystemClock.elapsedRealtime()+loopSeconds*1000L;var reads=0
                while(SystemClock.elapsedRealtime()<until) for(n in names) {
                    val dir=File(scratch,"loop-$reads").apply { mkdirs() }
                    CameraDocumentReader.read(File(input,"case-$n/source.jpg"),json("inputSource" to "camera","captureQuestionCount" to 40),request(dir))
                    dir.deleteRecursively();reads++
                }
                return json("mode" to "loop","reads" to reads)
            }
            if(arguments?.getString("mode")=="ab") return abRun(names,input,reports,scratch,::request)
            if(arguments?.getString("mode")=="moji-ab") return mojiAb(names,input,reports,scratch)
            if(arguments?.getString("mode")=="strips") return stripEquality(names,input,reports)
            if(arguments?.getString("mode")=="full-ab") return fullAb(names,input,reports,scratch,::request)
            if(arguments?.getString("mode")=="batch-ab") return batchAb(names,input,reports,scratch,::request)
            if(arguments?.getString("mode")=="size-ab") return sizeAb(names,input,reports,scratch,::requestFor)
            for(n in names) {
                status("Profiling case $n…")
                val source=File(input,"case-$n/source.jpg");val hash=EvidenceArchive.hash(source)
                val t=JSONObject();val dir=File(scratch,"case-$n").apply { mkdirs() }
                // 1. Complete production read (cold for this photo).
                val full=timed(t,"fullReadMs") { CameraDocumentReader.read(source,json("inputSource" to "camera","captureQuestionCount" to 40),request(File(dir,"full").apply { mkdirs() })) }
                t.put("fullReadStages",full.o("stageTimings"))
                t.put("fullReadSuccess",full.a("sheets").optJSONObject(0)?.optBoolean("success"))
                // 2. Stages one by one, as CameraDocumentReader runs them.
                val original=timed(t,"prepareForHeaderMs") { DocumentImagePreparation.prepareForHeader(source,File(dir,"original"),2000) }
                t.put("originalIsSource",original==source.path)
                timed(t,"framingMs") { OmrNative.prepareCameraJson(json("imagePath" to original,"outputPath" to File(dir,"framed.bmp").path,"forceRectification" to true).toString()) }
                val moji=File(dir,"moji").apply { mkdirs() }
                val prepared=timed(t,"mojiPrepareMs") { DocumentScanner.prepareForOmr(source,moji) }
                val document=File(prepared.path)
                t.put("documentBytes",document.length())
                val omrDir=File(dir,"omr").apply { mkdirs() }
                val sheet=timed(t,"omrOnDocumentMs") { JSONObject(OmrNative.processJson(request(omrDir).put("pages",JSONArray().put(json("pageNumber" to 1,"imagePath" to document.path,"activeQuestionCount" to 40))).toString())) }
                    .a("sheets").getJSONObject(0)
                t.put("omrHeaderMs",sheet.optDouble("headerProcessingTimeMs")).put("omrNativeMs",sheet.optDouble("processingTimeMs"))
                val omrOriginal=File(dir,"omr-original").apply { mkdirs() }
                timed(t,"omrOnOriginalMs") { OmrNative.processJson(request(omrOriginal).put("pages",JSONArray().put(json("pageNumber" to 1,"imagePath" to original,"activeQuestionCount" to 40))).toString()) }
                // 3. Android codec costs inside the protected Moji calls (replayed, not modified).
                val upright=File(moji,"upright.jpg")
                val decoded=timed(t,"decodeSourceMs") { BitmapFactory.decodeFile(source.path) }
                t.put("sourcePixels",decoded.width.toLong()*decoded.height)
                timed(t,"encodeSourceQ98Ms") { ByteArrayOutputStream(8 shl 20).use { decoded.compress(Bitmap.CompressFormat.JPEG,98,it) } }
                decoded.recycle()
                if(upright.isFile) timed(t,"decodeUprightMs") { BitmapFactory.decodeFile(upright.path)?.recycle() }
                val doc=timed(t,"decodeDocumentMs") { BitmapFactory.decodeFile(document.path) }
                t.put("documentWidth",doc.width).put("documentHeight",doc.height)
                timed(t,"encodeDocumentQ96Ms") { ByteArrayOutputStream(4 shl 20).use { doc.compress(Bitmap.CompressFormat.JPEG,96,it) } }
                doc.recycle()
                check(EvidenceArchive.hash(source)==hash)
                rows.put(t.put("case",n))
                dir.deleteRecursively()
                File(reports,"pipeline-profile.json").writeText(json("version" to com.haseebullahjan.roshd.BuildConfig.VERSION_NAME,"completed" to false,"cases" to rows).toString(2))
            }
            val report=json("version" to com.haseebullahjan.roshd.BuildConfig.VERSION_NAME,"completed" to true,
                "openCvThreads" to org.opencv.core.Core.getNumThreads(),"cpus" to Runtime.getRuntime().availableProcessors(),
                "cases" to rows,"databaseAccessed" to false,"networkAccessed" to false)
            File(reports,"pipeline-profile.json").writeText(report.toString(2));return report
        } finally { scratch.deleteRecursively() }
    }

    private fun scrub(value:Any?):Any?=when(value) {
        is JSONObject -> JSONObject().apply {
            value.keys().asSequence().filter { it !in setOf("processingTimeMs","headerProcessingTimeMs","normalizedImagePath","documentScanner","stageTimings") }
                .forEach { put(it,scrub(value.get(it))) }
        }
        is JSONArray -> JSONArray().apply { (0 until value.length()).forEach { put(scrub(value.get(it))) } }
        else -> value
    }
    private fun canonical(value:Any?):String=when(value) {
        is JSONObject -> value.keys().asSequence().sorted().joinToString(",","{","}") { JSONObject.quote(it)+":"+canonical(value.get(it)) }
        is JSONArray -> (0 until value.length()).joinToString(",","[","]") { canonical(value.get(it)) }
        is String -> JSONObject.quote(value)
        else -> value.toString()
    }

    private fun scrubMoji(value:JSONObject)=JSONObject(value.toString()).apply {
        listOf("elapsedMs","analysisMs","path").forEach { remove(it) }
    }

    /** Moji preparation with and without the upright copy: identical files and metadata, every EXIF quarter turn. */
    private fun mojiAb(names:List<String>,input:File,reports:File,scratch:File):JSONObject {
        val rows=JSONArray();var compared=0
        for(n in names) {
            val original=File(input,"case-$n/source.jpg");val hash=EvidenceArchive.hash(original)
            // Same JPEG pixels with each quarter-turn tag; only the EXIF field is rewritten.
            for(orientation in listOf(6,3,8,1)) {
                status("Moji A/B case $n orientation $orientation…")
                val source=File(scratch,"case-$n-o$orientation.jpg")
                original.copyTo(source,overwrite=true)
                if(orientation!=6) androidx.exifinterface.media.ExifInterface(source.path).apply {
                    setAttribute(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,orientation.toString());saveAttributes()
                }
                val times=mutableMapOf(false to mutableListOf<Long>(),true to mutableListOf<Long>())
                val files=mutableMapOf<Boolean,List<String>>();val meta=mutableMapOf<Boolean,String>()
                for(enabled in listOf(false,true,true,false)) {
                    val dir=File(scratch,"moji-$n-$orientation-$enabled-${times.getValue(enabled).size}").apply { mkdirs() }
                    val begun=SystemClock.elapsedRealtime()
                    val prepared=DocumentScanner.prepareForOmr(source,dir,fastUpright=enabled)
                    times.getValue(enabled).add(SystemClock.elapsedRealtime()-begun)
                    check(prepared.rectified) { "case $n: Moji did not rectify" }
                    val produced=listOf(File(dir,"upright.jpg"),File(prepared.path)).map { EvidenceArchive.hash(it) }
                    val metadata=canonical(scrubMoji(prepared.metadata))
                    files[enabled]?.let { check(it==produced) { "case $n o$orientation: Moji files changed between runs (enabled=$enabled)" } }
                    meta[enabled]?.let { check(it==metadata) { "case $n o$orientation: Moji metadata changed between runs" } }
                    files[enabled]=produced;meta[enabled]=metadata
                    check(!File(dir,"upright-input.bmp").exists()) { "temporary upright copy left behind" }
                    dir.deleteRecursively()
                }
                check(files[false]==files[true]) { "case $n o$orientation: upright/document bytes differ" }
                check(meta[false]==meta[true]) { "case $n o$orientation: Moji metadata differs" }
                compared++
                val before=times.getValue(false).sorted();val after=times.getValue(true).sorted()
                rows.put(json("case" to n,"orientation" to orientation,"baselineMs" to JSONArray(before),"optimizedMs" to JSONArray(after),
                    "identicalUprightAndDocument" to true))
                source.delete()
                File(reports,"moji-ab.json").writeText(json("completed" to false,"cases" to rows).toString(2))
            }
            check(EvidenceArchive.hash(original)==hash)
        }
        val report=json("version" to com.haseebullahjan.roshd.BuildConfig.VERSION_NAME,"completed" to true,"comparedInputs" to compared,
            "cases" to rows,"databaseAccessed" to false,"networkAccessed" to false)
        File(reports,"moji-ab.json").writeText(report.toString(2));return report
    }

    /** The engine's overlapping-strip adaptive threshold must equal one whole-image call on this device's OpenCV. */
    private fun stripEquality(names:List<String>,input:File,reports:File):JSONObject {
        val random=java.util.Random(1154);var cases=0;var failures=0
        val images=names.map { n ->
            val bitmap=BitmapFactory.decodeFile(File(input,"case-$n/source.jpg").path,BitmapFactory.Options().apply { inSampleSize=2 })
            val rgba=org.opencv.core.Mat();org.opencv.android.Utils.bitmapToMat(bitmap,rgba);bitmap.recycle()
            org.opencv.core.Mat().also { org.opencv.imgproc.Imgproc.cvtColor(rgba,it,org.opencv.imgproc.Imgproc.COLOR_RGBA2GRAY);rgba.release() }
        }
        val started=SystemClock.elapsedRealtime();var wholeMs=0L;var stripMs=0L
        repeat(400) { iteration ->
            var rows=40+random.nextInt(1360);var cols=20+random.nextInt(1280)
            val image=if(iteration%2==0) {
                val base=images[iteration/2%images.size];rows=minOf(rows,base.rows());cols=minOf(cols,base.cols())
                val y=random.nextInt(base.rows()-rows+1);val x=random.nextInt(base.cols()-cols+1)
                val crop=base.submat(y,y+rows,x,x+cols).clone()
                if(iteration%4==0) { val up=org.opencv.core.Mat();org.opencv.imgproc.Imgproc.resize(crop,up,org.opencv.core.Size(),2.0,2.0,org.opencv.imgproc.Imgproc.INTER_CUBIC)
                    crop.release();up.submat(0,minOf(up.rows(),1600),0,minOf(up.cols(),1400)).clone().also { up.release() } } else crop
            } else org.opencv.core.Mat(rows,cols,org.opencv.core.CvType.CV_8UC1).also { org.opencv.core.Core.randu(it,0.0,256.0) }
            val shortest=minOf(image.rows(),image.cols());var block=3+2*random.nextInt(40)
            block=minOf(block,if(shortest%2==0)shortest-1 else shortest)
            if(block>=3) {
                val method=if(iteration%5==0)org.opencv.imgproc.Imgproc.ADAPTIVE_THRESH_MEAN_C else org.opencv.imgproc.Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C
                val type=if(iteration%7==0)org.opencv.imgproc.Imgproc.THRESH_BINARY_INV else org.opencv.imgproc.Imgproc.THRESH_BINARY
                val delta=if(iteration%3==0)5.0 else random.nextDouble()*20-10
                val whole=org.opencv.core.Mat();var t=SystemClock.elapsedRealtime()
                org.opencv.imgproc.Imgproc.adaptiveThreshold(image,whole,255.0,method,type,block,delta);wholeMs+=SystemClock.elapsedRealtime()-t
                // Same strip rule as BarcodeReader.cpp adaptiveThresholdStrips (8 strips when tall enough).
                val radius=block/2+1;val strips=minOf(8,image.rows()/maxOf(64,4*block))
                val joined=org.opencv.core.Mat(image.size(),org.opencv.core.CvType.CV_8UC1);t=SystemClock.elapsedRealtime()
                if(strips<2) org.opencv.imgproc.Imgproc.adaptiveThreshold(image,joined,255.0,method,type,block,delta)
                else for(strip in 0 until strips) {
                    val top=image.rows()*strip/strips;val bottom=image.rows()*(strip+1)/strips
                    val first=maxOf(0,top-radius);val last=minOf(image.rows(),bottom+radius)
                    val part=org.opencv.core.Mat()
                    org.opencv.imgproc.Imgproc.adaptiveThreshold(image.rowRange(first,last),part,255.0,method,type,block,delta)
                    part.rowRange(top-first,bottom-first).copyTo(joined.rowRange(top,bottom));part.release()
                }
                stripMs+=SystemClock.elapsedRealtime()-t
                val different=org.opencv.core.Mat();org.opencv.core.Core.compare(whole,joined,different,org.opencv.core.Core.CMP_NE)
                cases++;if(org.opencv.core.Core.countNonZero(different)!=0)failures++
                whole.release();joined.release();different.release()
            }
            image.release()
        }
        images.forEach { it.release() }
        check(failures==0) { "strip adaptive threshold differs on this device in $failures of $cases cases" }
        val report=json("completed" to true,"cases" to cases,"failures" to failures,"elapsedMs" to SystemClock.elapsedRealtime()-started,
            "sequentialWholeMs" to wholeMs,"sequentialStripsMs" to stripMs,"openCvThreads" to org.opencv.core.Core.getNumThreads())
        File(reports,"strip-equality.json").writeText(report.toString(2));return report
    }

    /** Complete production reads with every 1.1.54 and 1.1.56 change off/on, alternating order. */
    private fun fullAb(names:List<String>,input:File,reports:File,scratch:File,request:(File)->JSONObject):JSONObject {
        val repeats=arguments?.getString("repeats")?.toIntOrNull() ?: 2
        val rows=JSONArray()
        for(n in names) {
            val source=File(input,"case-$n/source.jpg");val hash=EvidenceArchive.hash(source)
            val times=mutableMapOf(false to mutableListOf<Long>(),true to mutableListOf<Long>())
            val stages=mutableMapOf(false to JSONArray(),true to JSONArray())
            var reference:String?=null;var referencePixels:String?=null
            for(r in 0 until repeats) for(enabled in if(r%2==0)listOf(false,true) else listOf(true,false)) {
                status("Full A/B case $n repeat $r enabled=$enabled…")
                val dir=File(scratch,"full-$n-$r-$enabled").apply { mkdirs() }
                val req=request(dir).put("reuseParsedTemplates",enabled).put("stripAdaptiveThreshold",enabled).put("fastUprightImport",enabled).put("verifiedPrintFastPath",!enabled)
                    .put("denseRingSearch",enabled).put("deferProbeWarps",enabled)
                val begun=SystemClock.elapsedRealtime()
                val result=CameraDocumentReader.read(source,json("inputSource" to "camera","captureQuestionCount" to 40),req)
                times.getValue(enabled).add(SystemClock.elapsedRealtime()-begun);stages.getValue(enabled).put(result.o("stageTimings"))
                val sheet=result.a("sheets").getJSONObject(0)
                val fingerprint=canonical(scrub(sheet))
                val pixels=sheet.s("normalizedImagePath").takeIf { it.isNotBlank() }?.let { File(it) }?.takeIf { it.isFile }?.let { EvidenceArchive.hash(it) }
                if(reference==null) { reference=fingerprint;referencePixels=pixels }
                check(fingerprint==reference) { "case $n: reading changed (enabled=$enabled, repeat=$r)" }
                check(pixels==referencePixels) { "case $n: normalized pixels changed (enabled=$enabled, repeat=$r)" }
                dir.deleteRecursively()
            }
            check(EvidenceArchive.hash(source)==hash)
            val before=times.getValue(false).sorted();val after=times.getValue(true).sorted()
            rows.put(json("case" to n,"baselineMs" to JSONArray(before),"optimizedMs" to JSONArray(after),
                "baselineStages" to stages[false],"optimizedStages" to stages[true],"exactReadings" to true,"exactNormalizedImage" to true))
            File(reports,"full-ab.json").writeText(json("completed" to false,"cases" to rows).toString(2))
        }
        val report=json("version" to com.haseebullahjan.roshd.BuildConfig.VERSION_NAME,"completed" to true,
            "cases" to rows,"databaseAccessed" to false,"networkAccessed" to false)
        File(reports,"full-ab.json").writeText(report.toString(2));return report
    }

    /** The app's two-worker camera queue: upright copy inside vs outside the Moji lock, identical readings. */
    private fun batchAb(names:List<String>,input:File,reports:File,scratch:File,request:(File)->JSONObject):JSONObject {
        val repeats=arguments?.getString("repeats")?.toIntOrNull() ?: 2
        val pool=java.util.concurrent.Executors.newFixedThreadPool(CameraWorkPolicy.BATCH_SIZE)
        val reference=mutableMapOf<String,Pair<String,String?>>()
        val totals=mutableMapOf(false to mutableListOf<Long>(),true to mutableListOf<Long>())
        val sources=names.flatMap { n -> listOf(n,n) }
        try {
            for(r in 0 until repeats) for(outside in if(r%2==0)listOf(false,true) else listOf(true,false)) {
                status("Batch A/B repeat $r outsideLock=$outside (${sources.size} reads, ${CameraWorkPolicy.BATCH_SIZE} workers)…")
                val begun=SystemClock.elapsedRealtime()
                val futures=sources.mapIndexed { index,n -> pool.submit(java.util.concurrent.Callable<Pair<String,Pair<String,String?>>> {
                    val dir=File(scratch,"batch-$r-$outside-$index").apply { mkdirs() }
                    try {
                        val result=CameraDocumentReader.read(File(input,"case-$n/source.jpg"),json("inputSource" to "camera","captureQuestionCount" to 40),
                            request(dir).put("uprightOutsideLock",outside))
                        val sheet=result.a("sheets").getJSONObject(0)
                        val pixels=sheet.s("normalizedImagePath").takeIf { it.isNotBlank() }?.let { File(it) }?.takeIf { it.isFile }?.let { EvidenceArchive.hash(it) }
                        n to (canonical(scrub(sheet)) to pixels)
                    } finally { dir.deleteRecursively() }
                }) }
                for(f in futures) {
                    val (n,observed)=f.get()
                    val expected=reference.getOrPut(n) { observed }
                    check(observed==expected) { "case $n: batch reading changed (outsideLock=$outside, repeat=$r)" }
                }
                totals.getValue(outside).add(SystemClock.elapsedRealtime()-begun)
            }
        } finally { pool.shutdownNow() }
        val before=totals.getValue(false);val after=totals.getValue(true)
        val report=json("version" to com.haseebullahjan.roshd.BuildConfig.VERSION_NAME,"completed" to true,"reads" to sources.size,
            "workers" to CameraWorkPolicy.BATCH_SIZE,"insideLockMs" to JSONArray(before),"outsideLockMs" to JSONArray(after),
            "insideMeanMs" to before.average(),"outsideMeanMs" to after.average(),
            "reductionPercent" to 100.0*(before.average()-after.average())/before.average(),"exactReadings" to true,
            "databaseAccessed" to false,"networkAccessed" to false)
        File(reports,"batch-ab.json").writeText(report.toString(2));return report
    }

    /** Moji output at 2600 vs 2000 px: answers, statuses and review must match; margins are recorded. */
    private fun sizeAb(names:List<String>,input:File,reports:File,scratch:File,request:(File,Int,Boolean)->JSONObject):JSONObject {
        val repeats=arguments?.getString("repeats")?.toIntOrNull() ?: 2
        val labels=File(input,"manual-labels.json").takeIf { it.isFile }?.let { JSONObject(it.readText()) }
        data class Case(val name:String,val file:File,val count:Int,val bindings:Boolean,val expected:JSONArray?)
        val cases=mutableListOf<Case>()
        for(n in names) cases.add(Case("photo-$n",File(input,"case-$n/source.jpg"),40,true,labels?.optJSONObject("case-$n")?.optJSONArray("expected")))
        val fixtures=File(scratch,"fixtures").apply { mkdirs() }
        fun asset(path:String)=File(fixtures,path.substringAfterLast('/')).also { f -> runner.context.assets.open(path).use { i -> f.outputStream().use { i.copyTo(it) } } }
        for(c in runner.context.assets.open("omr-speed/manifest.json").bufferedReader().use { JSONObject(it.readText()) }.a("cases").objects())
            cases.add(Case(c.s("name"),asset("omr-speed/${c.s("name")}.jpg"),c.optInt("count"),false,c.optJSONArray("expected")))
        cases.add(Case("T1",asset("omr/T1.jpg"),100,false,JSONArray((0 until 100).map { it%4+1 })))
        cases.add(Case("T3-anchors",asset("omr/T3-anchors.jpg"),40,false,JSONArray((0 until 40).map { it%4+1 })))
        val rows=JSONArray();var answerMismatches=0;var knownChecked=0;var knownWrong=0
        for(c in cases) {
            status("Size A/B ${c.name}…")
            val times=mutableMapOf(2600 to mutableListOf<Long>(),2000 to mutableListOf<Long>())
            val sheets=mutableMapOf<Int,JSONObject>()
            for(r in 0 until repeats) for(size in if(r%2==0)listOf(2600,2000) else listOf(2000,2600)) {
                val dir=File(scratch,"size-${c.name}-$r-$size").apply { mkdirs() }
                val begun=SystemClock.elapsedRealtime()
                val result=CameraDocumentReader.read(c.file,json("inputSource" to "camera","captureQuestionCount" to c.count),
                    request(dir,c.count,c.bindings).put("mojiMaxDimension",size))
                times.getValue(size).add(SystemClock.elapsedRealtime()-begun)
                val sheet=result.a("sheets").getJSONObject(0)
                sheets[size]?.let { check(canonical(scrub(it))==canonical(scrub(sheet))) { "${c.name}: repeated read at $size changed" } }
                sheets[size]=sheet
                dir.deleteRecursively()
            }
            val a=sheets.getValue(2600);val b=sheets.getValue(2000)
            fun answers(s:JSONObject)=s.a("questions").objects().map { q -> q.s("status")+":"+(if(q.s("status")=="MARKED")q.optInt("selectedOptionNumber") else 0) }
            fun reasons(s:JSONObject)=(0 until s.a("reviewReasons").length()).map { s.a("reviewReasons").getString(it) }.sorted()
            val sameAnswers=answers(a)==answers(b)
            if(!sameAnswers)answerMismatches++
            var knownWrongHere=0
            c.expected?.let { exp -> for(s in listOf(a,b)) {
                val qs=s.a("questions").objects()
                for(i in 0 until c.count) {
                    knownChecked++
                    val ok=i<qs.size && (if(exp.isNull(i))qs[i].s("status")=="BLANK" else qs[i].s("status")=="MARKED" && qs[i].optInt("selectedOptionNumber")==exp.getInt(i))
                    if(!ok) { knownWrong++;knownWrongHere++ }
                }
            } }
            val conf=a.a("questions").objects().zip(b.a("questions").objects()) { x,y -> kotlin.math.abs(x.optDouble("confidence")-y.optDouble("confidence")) }
            fun minMarked(s:JSONObject)=s.a("questions").objects().filter { it.s("status")=="MARKED" }.minOfOrNull { it.optDouble("confidence") } ?: 1.0
            rows.put(json("case" to c.name,"success2600" to a.optBoolean("success"),"success2000" to b.optBoolean("success"),
                "sameAnswers" to sameAnswers,"sameReview" to (a.optBoolean("reviewRequired")==b.optBoolean("reviewRequired") && reasons(a)==reasons(b)),
                "review2600" to JSONArray(reasons(a)),"review2000" to JSONArray(reasons(b)),
                "maxConfidenceChange" to (conf.maxOrNull() ?: 0.0),"minMarkedConfidence2600" to minMarked(a),"minMarkedConfidence2000" to minMarked(b),
                "knownWrong" to knownWrongHere,"ms2600" to JSONArray(times.getValue(2600)),"ms2000" to JSONArray(times.getValue(2000))))
            File(reports,"size-ab.json").writeText(json("completed" to false,"cases" to rows).toString(2))
        }
        val report=json("version" to com.haseebullahjan.roshd.BuildConfig.VERSION_NAME,"completed" to true,"inputs" to cases.size,
            "answerMismatches" to answerMismatches,"knownAnswersChecked" to knownChecked,"knownAnswersWrong" to knownWrong,
            "cases" to rows,"databaseAccessed" to false,"networkAccessed" to false)
        File(reports,"size-ab.json").writeText(report.toString(2));return report
    }

    /** Paired engine timings on the same Moji page: optimizations off/on in alternating order. */
    private fun abRun(names:List<String>,input:File,reports:File,scratch:File,request:(File)->JSONObject):JSONObject {
        val repeats=arguments?.getString("repeats")?.toIntOrNull() ?: 3
        val keep=arguments?.getString("keepDocuments")=="true"
        val documents=File(reports,"documents").apply { if(keep)mkdirs() }
        val rows=JSONArray();var identical=0
        for(n in names) {
            status("A/B case $n…")
            val source=File(input,"case-$n/source.jpg");val hash=EvidenceArchive.hash(source)
            val prepared=DocumentScanner.prepareForOmr(source,File(scratch,"moji-$n").apply { mkdirs() })
            val document=File(prepared.path)
            if(keep)document.copyTo(File(documents,"case-$n.jpg"),overwrite=true)
            val times=mutableMapOf(false to mutableListOf<Long>(),true to mutableListOf<Long>())
            var reference:String?=null;var referencePixels:String?=null
            for(r in 0 until repeats) for(enabled in if(r%2==0)listOf(false,true) else listOf(true,false)) {
                val dir=File(scratch,"ab-$n-$r-$enabled").apply { mkdirs() }
                val req=request(dir).put("reuseParsedTemplates",enabled).put("stripAdaptiveThreshold",enabled)
                    .put("denseRingSearch",enabled).put("deferProbeWarps",enabled)
                    .put("pages",JSONArray().put(json("pageNumber" to 1,"imagePath" to document.path,"activeQuestionCount" to 40)))
                val begun=SystemClock.elapsedRealtime()
                val sheet=JSONObject(OmrNative.processJson(req.toString())).a("sheets").getJSONObject(0)
                times.getValue(enabled).add(SystemClock.elapsedRealtime()-begun)
                val fingerprint=canonical(scrub(sheet))
                val pixels=sheet.s("normalizedImagePath").takeIf { it.isNotBlank() }?.let { File(it) }?.takeIf { it.isFile }?.let { EvidenceArchive.hash(it) }
                if(reference==null) { reference=fingerprint;referencePixels=pixels }
                check(fingerprint==reference) { "case $n: reading fields changed (enabled=$enabled, repeat=$r)" }
                check(pixels==referencePixels) { "case $n: normalized pixels changed (enabled=$enabled, repeat=$r)" }
                identical++
                dir.deleteRecursively()
            }
            check(EvidenceArchive.hash(source)==hash)
            val before=times.getValue(false).sorted();val after=times.getValue(true).sorted()
            rows.put(json("case" to n,"baselineMs" to JSONArray(before),"optimizedMs" to JSONArray(after),
                "baselineMedianMs" to before[before.size/2],"optimizedMedianMs" to after[after.size/2],
                "reductionPercent" to 100.0*(before[before.size/2]-after[after.size/2])/before[before.size/2],"exactReadings" to true))
            File(reports,"pipeline-ab.json").writeText(json("version" to com.haseebullahjan.roshd.BuildConfig.VERSION_NAME,"completed" to false,"cases" to rows).toString(2))
        }
        val report=json("version" to com.haseebullahjan.roshd.BuildConfig.VERSION_NAME,"completed" to true,"identicalRuns" to identical,
            "cases" to rows,"databaseAccessed" to false,"networkAccessed" to false)
        File(reports,"pipeline-ab.json").writeText(report.toString(2));return report
    }
}
