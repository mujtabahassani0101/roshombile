package com.haseebullahjan.roshd.qa

import android.app.Instrumentation
import android.os.SystemClock
import com.haseebullahjan.roshd.core.*
import com.haseebullahjan.roshd.platform.CameraDocumentReader
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Independently labelled real print layouts; no account, database or network access. */
class OmrSpeedChecks(private val runner:Instrumentation) {
    fun run():JSONObject {
        val folder=File(runner.targetContext.cacheDir,"omr-speed-${UUID.randomUUID()}").apply { mkdirs() }
        val results=org.json.JSONArray();var answers=0
        try {
            val templates=SheetFamilies.templates.associateWith { name -> File(folder,name).also { f ->
                runner.targetContext.assets.open("templates/$name").use { i -> f.outputStream().use { i.copyTo(it) } }
            } }
            val cases=runner.context.assets.open("omr-speed/manifest.json").bufferedReader().use { JSONObject(it.readText()) }.a("cases").objects()
            for(c in cases) {
                val name=c.s("name");val count=c.optInt("count")
                runner.sendStatus(1,android.os.Bundle().apply { putString("stream","Checking $name…\n") })
                val input=File(folder,"$name.jpg").also { f -> runner.context.assets.open("omr-speed/$name.jpg").use { i -> f.outputStream().use { i.copyTo(it) } } }
                val before=EvidenceArchive.hash(input)
                val begun=SystemClock.elapsedRealtime()
                val result=CameraDocumentReader.read(input,json("inputSource" to "camera","captureQuestionCount" to count),
                    json("templateJsonPaths" to SheetFamilies.templatesForSelection(count).map { templates.getValue(it).path },
                        "outputDirectory" to File(folder,name).apply { mkdirs() }.path,"includeQuestions" to true,
                        "returnNormalizedImages" to true,"returnAnnotationGeometry" to true,"preferCalibratedHeader" to true,"maxInputDimension" to 2000))
                val elapsed=SystemClock.elapsedRealtime()-begun
                val sheet=result.a("sheets").getJSONObject(0)
                check(sheet.optBoolean("success")) { "$name: ${sheet.s("status")} ${sheet.s("error")}" }
                check(OmrResultParser.qr(sheet)==c.s("qr")) { "$name: QR identity changed" }
                val observed=OmrResultParser.observations(sheet,count)
                val expected=c.a("expected")
                check(observed.size==count)
                for(i in 0 until count) {
                    val q=observed[i]
                    check(if(expected.isNull(i))q.s("state")=="blank" else q.s("state")=="selected" && q.optInt("selectedOption")==expected.getInt(i)) {
                        "$name: known answer ${i+1} changed"
                    };answers++
                }
                val metadata=sheet.o("documentScanner")
                if(metadata.s("engine")=="roshd-verified-print")check(CameraNormalizationPolicy.complete(sheet,count))
                check(File(sheet.s("normalizedImagePath")).isFile)
                check(before==EvidenceArchive.hash(input))
                results.put(json("case" to name,"questionCount" to count,"totalMs" to elapsed,
                    "engine" to metadata.s("engine"),"complete" to CameraNormalizationPolicy.complete(sheet,count),
                    "knownAnswersMatched" to count,"originalPreserved" to true,"stages" to result.o("stageTimings")))
            }
            for(name in listOf("T3-anchors-no-qr","T3-anchors-wrong-qr","central-bend")) {
                val input=File(folder,"$name.jpg").also { f -> runner.context.assets.open(if(name=="central-bend")"omr-speed/$name.jpg" else "omr/$name.jpg").use { i -> f.outputStream().use { i.copyTo(it) } } }
                val result=CameraDocumentReader.read(input,json("inputSource" to "camera","captureQuestionCount" to 40),
                    json("templateJsonPaths" to SheetFamilies.templatesForSelection(40).map { templates.getValue(it).path },
                        "outputDirectory" to File(folder,name).apply { mkdirs() }.path,"includeQuestions" to true,
                        "returnNormalizedImages" to true,"returnAnnotationGeometry" to true,"preferCalibratedHeader" to true,"maxInputDimension" to 2000))
                val sheet=result.a("sheets").getJSONObject(0)
                if(name!="central-bend")check(!sheet.optBoolean("success") && sheet.a("questions").length()==0) { "$name: invalid identity authorized answers" }
                check(sheet.o("documentScanner").s("engine")!="roshd-verified-print")
                results.put(json("case" to name,"fastPathRejected" to true,"invalidIdentityRejected" to (name!="central-bend")))
            }
            return json("version" to com.haseebullahjan.roshd.BuildConfig.VERSION_NAME,"casesPassed" to results.length(),
                "knownAnswersMatched" to answers,"results" to results)
        } finally { folder.deleteRecursively() }
    }
}
