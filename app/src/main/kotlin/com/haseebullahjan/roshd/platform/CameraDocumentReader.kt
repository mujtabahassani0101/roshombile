package com.haseebullahjan.roshd.platform

import android.os.SystemClock
import com.haseebullahjan.roshd.core.*
import com.haseebullahjan.youngscientist.DocumentImagePreparation
import com.haseebullahjan.youngscientist.scanner.DocumentScanner
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Prove flat printed sheets with the source reader; retain Moji for unproved camera geometry. */
object CameraDocumentReader {
    private val activeReads=AtomicInteger()
    val isReading:Boolean get()=activeReads.get()>0
    fun read(source:File,record:JSONObject,request:JSONObject):JSONObject {
        val limitLiveAnalysis=request.optBoolean("limitLiveAnalysisDuringRead",true)
        if(limitLiveAnalysis)activeReads.incrementAndGet()
        return try { readDocument(source,record,request) } finally { if(limitLiveAnalysis)activeReads.decrementAndGet() }
    }
    private fun readDocument(source:File,record:JSONObject,request:JSONObject):JSONObject {
        require(CameraNormalizationPolicy.eligible(record))
        val output=File(request.s("outputDirectory"))
        val temporary=File(output,"camera-document")
        var documentMs=0L;var recognitionMs=0L;var framingMs=0L
        val attempts=mutableListOf<File>()
        var selectedOutput:File?=null
        val count=record.optInt("captureQuestionCount")
        val original by lazy { DocumentImagePreparation.prepareForHeader(source,File(temporary,"original"),2000) }
        fun engine(path:String,quickHeader:Boolean=false):JSONObject {
            val start=SystemClock.elapsedRealtime()
            val attempt=File(output,"camera-read-${attempts.size+1}").apply { mkdirs() };attempts.add(attempt)
            try { return JSONObject(OmrNative.processJson(request.copy().put("outputDirectory",attempt.path)
                .put("fastHeaderOnly",quickHeader).put("preferCalibratedHeader",quickHeader || request.optBoolean("preferCalibratedHeader"))
                .put("requirePrintedCircleGeometry",quickHeader)
                .put("pages",wrap(listOf(json(
                "pageNumber" to 1,"imagePath" to path,"activeQuestionCount" to count)))).toString())) }
            finally { recognitionMs+=SystemClock.elapsedRealtime()-start }
        }
        fun finish(result:JSONObject,metadata:JSONObject):JSONObject {
            result.a("sheets").optJSONObject(0)?.let {
                it.put("documentScanner",metadata)
                CameraNormalizationPolicy.markGeometryForReview(it,count)
            }
            result.put("stageTimings",json("documentMs" to documentMs,"recognitionMs" to recognitionMs,
                "framingMs" to framingMs,"recognitionAttempts" to attempts.size))
            return result
        }
        try {
            check(temporary.isDirectory || temporary.mkdirs())
            val framingStarted=SystemClock.elapsedRealtime()
            val framed=File(temporary,"printed-frame.bmp")
            // The sheets in use have side dot rails and no four 7 mm corner squares,
            // so this search never succeeded on them and cost 0.4–0.7 s per photo.
            // Squared prints still read correctly through Moji; callers that use
            // them can opt back in with verifiedPrintFastPath=true.
            val framing=if(request.optBoolean("verifiedPrintFastPath",false))
                JSONObject(OmrNative.prepareCameraJson(json("imagePath" to original,
                    "outputPath" to framed.path,"forceRectification" to true).toString()))
            else json("applied" to false,"reason" to "disabled")
            framingMs=SystemClock.elapsedRealtime()-framingStarted
            documentMs+=framingMs
            var printResult:JSONObject?=null
            var printOutput:File?=null
            if(framing.optBoolean("applied") && framing.s("reason")=="four_printed_squares_rectified" && framed.isFile) {
                printResult=engine(framed.path,quickHeader=true);printOutput=attempts.last()
                val printSheet=printResult.a("sheets").optJSONObject(0) ?: JSONObject()
                // Printed anchors alone cannot bypass dewarping. The unchanged
                // source reader must prove QR, every rail row, reprojection fit,
                // selected family/count and all answer/annotation coordinates.
                val warnings=printSheet.a("warnings")
                val circlesVerified=(0 until warnings.length()).any { warnings.optString(it)=="roshd_printed_circle_geometry_verified" }
                if(circlesVerified && CameraNormalizationPolicy.complete(printSheet,count)) {
                    selectedOutput=printOutput
                    return finish(printResult,json("engine" to "roshd-verified-print","applied" to true,
                        "dewarped" to false,"sourceNormalizationFirst" to true,"printedCircleGeometryVerified" to true,"framing" to framing))
                }
            }
            val started=SystemClock.elapsedRealtime()
            val prepared=DocumentScanner.prepareForOmr(source,temporary,maxDimension=request.optInt("mojiMaxDimension",2600),
                fastUpright=request.optBoolean("fastUprightImport",true),uprightOutsideLock=request.optBoolean("uprightOutsideLock",true))
            documentMs+=SystemClock.elapsedRealtime()-started
            var result=engine(prepared.path)
            selectedOutput=attempts.last()
            val metadata=prepared.metadata.put("sourceNormalizationFirst",false)
            val first=result.a("sheets").optJSONObject(0) ?: JSONObject()
            // Unproved printed geometry retains the complete Moji pipeline and
            // original-photo fallback, including difficult curved photographs.
            if((prepared.rectified || prepared.path!=source.path) && !CameraNormalizationPolicy.complete(first,count)) {
                val before=SystemClock.elapsedRealtime()
                documentMs+=SystemClock.elapsedRealtime()-before
                val retry=engine(original)
                if(CameraNormalizationPolicy.preferCandidate(first,retry.a("sheets").optJSONObject(0) ?: JSONObject(),count)) {
                    result=retry;selectedOutput=attempts.last();metadata.put("originalFallback",true)
                }
            }
            if(printResult!=null && CameraNormalizationPolicy.preferCandidate(
                result.a("sheets").optJSONObject(0) ?: JSONObject(),printResult.a("sheets").optJSONObject(0) ?: JSONObject(),count)) {
                result=printResult;selectedOutput=printOutput
                metadata.put("printedFrameFallback",true)
            }
            metadata.put("framing",framing)
            return finish(result,metadata)
        } finally {
            temporary.deleteRecursively()
            // A rejected retry must never overwrite the selected image whose
            // annotation coordinates are used later for colored answers.
            attempts.filter { it!=selectedOutput }.forEach { it.deleteRecursively() }
        }
    }
}
