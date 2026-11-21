package com.haseebullahjan.roshd.qa

import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import com.haseebullahjan.roshd.core.*
import com.haseebullahjan.roshd.platform.CameraDocumentReader
import com.haseebullahjan.roshd.platform.OmrNative
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Externally staged, independently labelled scenes; no account, records or network. */
class StressEngineTest:Instrumentation() {
    private var external=false
    private var summaryOnly=false
    override fun onCreate(arguments:Bundle?) {
        external=arguments?.getString("storage")=="external";summaryOnly=arguments?.getString("summary")=="true"
        super.onCreate(arguments);start()
    }
    override fun onStart() {
        val output=Bundle()
        try {
            require(targetContext.packageName.endsWith(".nativeqa"))
            check(java.util.TimeZone.getTimeZone("UTC").id=="UTC")
            val folder=File(if(external)targetContext.getExternalFilesDir(null) else targetContext.filesDir,"qa/stress")
            val manifest=JSONObject(File(folder,"manifest.json").readText()).a("cases")
            require(manifest.length() in 1..900)
            if(summaryOnly) {
                val report=JSONObject(File(folder,"android-report.json").readText())
                val rows=report.a("checks").objects()
                check(rows.size==manifest.length() && rows.map { it.s("name") }.toSet()==manifest.objects().map { it.s("name") }.toSet())
                output.putString("stream","\n${report.copy().apply { remove("checks") }.toString(2)}\n")
                finish(0,output);return
            }
            val templates=SheetFamilies.templates.associateWith { name -> File(folder,name).also { file ->
                targetContext.assets.open("templates/$name").use { input -> file.outputStream().use { input.copyTo(it) } }
            }.path }
            val rows=JSONArray();val journal=File(folder,"android-rows.jsonl")
            journal.writeText("")
            for((index,case) in manifest.objects().withIndex()) {
                val name=case.s("name");require(name.matches(Regex("[A-Za-z0-9-]+")))
                val source=File(folder,"$name.jpg");val before=EvidenceArchive.hash(source)
                require(before==case.s("sha256"));val count=case.getInt("count")
                val derived=File(folder,"work-$name").apply { mkdirs() }
                val request=json("templateJsonPaths" to SheetFamilies.templatesForSelection(count).map { templates.getValue(it) },
                    "outputDirectory" to derived.path,"returnNormalizedImages" to true,"includeQuestions" to true,
                    "includeRegistrationGeometry" to true,"maxInputDimension" to 2000,"preferCalibratedHeader" to true)
                val start=SystemClock.elapsedRealtime()
                val response=CameraDocumentReader.read(source,json("inputSource" to "camera","captureQuestionCount" to count),request)
                val sheet=response.a("sheets").getJSONObject(0)
                val identity=OmrResultParser.qr(sheet)==case.s("qr")
                val answers=runCatching { OmrResultParser.observations(sheet,count) }.getOrDefault(emptyList())
                val ready=sheet.optBoolean("success") && identity && answers.size==count
                var correct=0;var unsafe=0;var reviews=0;val errors=JSONArray()
                for(number in 1..count) {
                    val expected=case.a("expectedOptions").getJSONArray(number-1)
                    val answer=answers.getOrNull(number-1) ?: JSONObject()
                    val state=answer.s("state");val review=ScanReview.needsReview(answer)
                    val wanted=when(expected.length()) { 0->"blank";1->"selected";else->"multiple" }
                    val ok=state==wanted && when(state) {
                        "selected"->answer.optInt("selectedOption")==expected.getInt(0)
                        "multiple"->answer.a("markedOptions").toString()==expected.toString()
                        else->true
                    }
                    if(ok)correct++ else {
                        if(ready && !review)unsafe++
                        errors.put(json("question" to number,"expected" to expected,"state" to state,"selected" to answer.opt("selectedOption"),"review" to review))
                    }
                    if(review)reviews++
                }
                val row=json("name" to name,"count" to count,"ready" to ready,"identity" to identity,
                    "geometry" to CameraNormalizationPolicy.complete(sheet,count),"correct" to correct,"wrong" to count-correct,
                    "review" to reviews,"unsafeWrong" to unsafe,"errors" to errors,"elapsedMs" to SystemClock.elapsedRealtime()-start,
                    "stageTimings" to response.o("stageTimings"),"cameraFraming" to sheet.o("cameraFraming"))
                File(folder,"$name-result.json").writeText(sheet.toString())
                require(EvidenceArchive.hash(source)==before)
                derived.deleteRecursively();rows.put(row);journal.appendText(row.toString()+"\n")
                if((index+1)%5==0)sendStatus(0,Bundle().apply { putString("stream","\n${index+1}/${manifest.length()} Android scenes; unsafe=${rows.objects().sumOf { it.optInt("unsafeWrong") }}\n") })
            }
            val report=json("platform" to "Android emulator; physical camera not exercised","engine" to OmrNative.engineRevision(),
                "cases" to rows.length(),"questions" to rows.objects().sumOf { it.optInt("count") },"ready" to rows.objects().count { it.optBoolean("ready") },
                "unsafeWrong" to rows.objects().sumOf { it.optInt("unsafeWrong") },"checks" to rows)
            File(folder,"android-report.json").writeText(report.toString(2))
            // Binder parcels have a 1 MiB ceiling. Complete results live on
            // disk; the instrumentation channel carries only the summary.
            output.putString("stream","\n${report.copy().apply { remove("checks") }.toString(2)}\n");finish(0,output)
        } catch(error:Throwable) { output.putString("stream",error.stackTraceToString());finish(1,output) }
    }
}
