package com.haseebullahjan.roshd.qa

import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import com.haseebullahjan.roshd.core.*
import com.haseebullahjan.roshd.platform.CameraDocumentReader
import com.haseebullahjan.roshd.platform.OmrNative
import com.haseebullahjan.youngscientist.scanner.DocumentScanner
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Paired timings on isolated photos: no account, database, queue or network access. */
class OmrGeometrySpeedChecks(private val runner:Instrumentation) {
    private val diagnosticWarnings=setOf("primary_layout_geometry_verified","normalization_probe_reused","header_orientation_geometry_verified")
    private fun scrub(value:Any?):Any?=when(value) {
        is JSONObject -> JSONObject().apply {
            value.keys().asSequence().filter { it !in setOf("processingTimeMs","headerProcessingTimeMs","normalizedImagePath","documentScanner") }
                .forEach { put(it,scrub(value.get(it))) }
        }
        is JSONArray -> JSONArray().apply { (0 until value.length()).map { value.get(it) }.filter { !(it is String && it in diagnosticWarnings) }.forEach { put(scrub(it)) } }
        else -> value
    }
    fun run():JSONObject {
        val context=runner.targetContext
        com.haseebullahjan.roshd.platform.OpenCvRuntime.ensure()
        DocumentScanner.initialize(context)
        val input=File(checkNotNull(context.getExternalFilesDir(null)),"qa/omr-speed-1152/input")
        val reports=File(input.parentFile,"results").apply { mkdirs() }
        val scratch=File(context.cacheDir,"geometry-speed-${UUID.randomUUID()}").apply { mkdirs() }
        val bindings=JSONArray(File(input,"bindings.json").readText()).objects()
        val labels=JSONObject(File(input,"manual-labels.json").readText())
        val templates=SheetFamilies.templates.associateWith { n -> File(scratch,n).also { f ->
            context.assets.open("templates/$n").use { i -> f.outputStream().use { i.copyTo(it) } }
        } }
        val cases=mutableListOf<JSONObject>()
        for(n in listOf("02","06","07","09","11"))cases.add(json("name" to "photo-$n","count" to 40,"path" to File(input,"case-$n/source.jpg").path,"label" to "case-$n","private" to true))
        val supplied=runner.context.assets.open("omr-speed/manifest.json").bufferedReader().use { JSONObject(it.readText()) }.a("cases").objects()
        for(c in supplied) {
            val f=File(scratch,c.s("name")+".jpg")
            runner.context.assets.open("omr-speed/${c.s("name")}.jpg").use { i -> f.outputStream().use { i.copyTo(it) } }
            cases.add(c.copy().put("path",f.path))
        }
        val rows=JSONArray();var manual=0
        val oldThreads=org.opencv.core.Core.getNumThreads()
        try {
            runner.sendStatus(1,Bundle().apply { putString("stream","Warming the unchanged document models…\n") })
            val warm=File(scratch,"warm").apply { mkdirs() }
            DocumentScanner.prepareForOmr(File(cases.first().s("path")),warm);warm.deleteRecursively()
            for((index,c) in cases.withIndex()) {
                val name=c.s("name");val count=c.optInt("count");val source=File(c.s("path"));val hash=EvidenceArchive.hash(source)
                val results=mutableMapOf<Boolean,JSONObject>();val elapsed=mutableMapOf<Boolean,Long>();val stage=mutableMapOf<Boolean,JSONObject>();val pixels=mutableMapOf<Boolean,String?>()
                // Alternate order so warm-up and device temperature do not always favor one mode.
                for(enabled in if(index%2==0)listOf(false,true) else listOf(true,false)) {
                    runner.sendStatus(1,Bundle().apply { putString("stream","Timing $name / ${if(enabled)"optimized" else "baseline"}…\n") })
                    val dir=File(scratch,"$name-$enabled").apply { mkdirs() }
                    val request=json("templateJsonPaths" to SheetFamilies.templatesForSelection(count).map { templates.getValue(it).path },
                        "outputDirectory" to dir.path,"includeQuestions" to true,"returnNormalizedImages" to true,"returnAnnotationGeometry" to true,
                        "preferCalibratedHeader" to true,"maxInputDimension" to 2000,"qrBindings" to if(c.optBoolean("private"))bindings else emptyList<JSONObject>(),
                        "reuseNormalizationGeometry" to enabled,"preferLegacyGeometry" to enabled)
                    val begun=SystemClock.elapsedRealtime()
                    val result=CameraDocumentReader.read(source,json("inputSource" to "camera","captureQuestionCount" to count),request)
                    elapsed[enabled]=SystemClock.elapsedRealtime()-begun;stage[enabled]=result.o("stageTimings")
                    val sheet=result.a("sheets").getJSONObject(0);results[enabled]=sheet
                    val normalized=File(sheet.s("normalizedImagePath"));pixels[enabled]=if(normalized.isFile)EvidenceArchive.hash(normalized) else null
                    if(c.optBoolean("private"))File(reports,"$name-$enabled.json").writeText(result.toString(2))
                    val expected=if(c.optBoolean("private"))labels.optJSONObject(c.s("label"))?.optJSONArray("expected") else c.optJSONArray("expected")
                    if(expected!=null) {
                        check(sheet.optBoolean("success")) { "$name: known photo failed" }
                        val qs=sheet.a("questions").objects();check(qs.size==count && expected.length()==count)
                        for(i in 0 until count)check(if(expected.isNull(i))qs[i].s("status")=="BLANK" else qs[i].s("status")=="MARKED" && qs[i].optInt("selectedOptionNumber")==expected.getInt(i)) {
                            "$name: known location changed at question ${i+1}"
                        }
                        if(enabled)manual+=count
                    }
                    if(OmrResultParser.qr(sheet).isBlank())check(!sheet.optBoolean("success") && sheet.a("questions").length()==0)
                    check(EvidenceArchive.hash(source)==hash)
                    dir.deleteRecursively()
                }
                check(canonical(scrub(results.getValue(false)) as JSONObject)==canonical(scrub(results.getValue(true)) as JSONObject)) { "$name: reading fields changed" }
                check(pixels[false]==pixels[true]) { "$name: normalized pixels changed" }
                val before=elapsed.getValue(false);val after=elapsed.getValue(true)
                rows.put(json("case" to name,"count" to count,"success" to results.getValue(true).optBoolean("success"),
                    "beforeMs" to before,"afterMs" to after,"reductionPercent" to (100.0*(before-after)/before),
                    "beforeStages" to stage[false],"afterStages" to stage[true],"exactReadings" to true,"exactNormalizedImage" to true,"originalPreserved" to true))
                File(reports,"report.json").writeText(json("version" to com.haseebullahjan.roshd.BuildConfig.VERSION_NAME,"completed" to false,"results" to rows).toString(2))
            }
            // The prepared old print isolates OMR from the unchanged Moji cost.
            val threadRows=JSONArray();val normalized=File(input,"case-02/normalized.jpg")
            var referenceFingerprint:String?=null
            for(threads in listOf(1,2,4,8)) {
                org.opencv.core.Core.setNumThreads(threads)
                val dir=File(scratch,"threads-$threads").apply { mkdirs() }
                val req=json("templateJsonPaths" to SheetFamilies.templatesForSelection(40).map { templates.getValue(it).path },
                    "outputDirectory" to dir.path,"includeQuestions" to true,"returnNormalizedImages" to true,"returnAnnotationGeometry" to true,
                    "preferCalibratedHeader" to true,"maxInputDimension" to 2000,"qrBindings" to bindings,
                    "pages" to listOf(json("pageNumber" to 1,"imagePath" to normalized.path,"activeQuestionCount" to 40)))
                val times=mutableListOf<Long>();var fingerprint:String?=null
                repeat(2) {
                    val begun=SystemClock.elapsedRealtime();val s=JSONObject(OmrNative.processJson(req.toString())).a("sheets").getJSONObject(0);times.add(SystemClock.elapsedRealtime()-begun)
                    check(s.optBoolean("success"));val f=canonical(scrub(s) as JSONObject);if(fingerprint==null)fingerprint=f else check(fingerprint==f)
                    if(referenceFingerprint==null)referenceFingerprint=f else check(referenceFingerprint==f) { "Thread count changed a reading" }
                }
                threadRows.put(json("threads" to threads,"samplesMs" to times));dir.deleteRecursively()
            }
            val report=json("version" to com.haseebullahjan.roshd.BuildConfig.VERSION_NAME,"completed" to true,"casesPassed" to rows.length(),
                "knownLocationsMatched" to manual,"results" to rows,"nativeThreadTimings" to threadRows,"databaseAccessed" to false,"networkAccessed" to false)
            File(reports,"report.json").writeText(report.toString(2));return report
        } finally { org.opencv.core.Core.setNumThreads(oldThreads);scratch.deleteRecursively() }
    }
}
