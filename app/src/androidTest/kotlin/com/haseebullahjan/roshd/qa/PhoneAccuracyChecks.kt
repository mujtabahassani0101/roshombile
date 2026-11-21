package com.haseebullahjan.roshd.qa

import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import com.haseebullahjan.roshd.MainActivity
import com.haseebullahjan.roshd.core.*
import com.haseebullahjan.roshd.messenger.ApplicationLoader
import com.haseebullahjan.roshd.platform.CameraDocumentReader
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Replay the owner's exported photographs locally. Never replace scans or enqueue grades. */
class PhoneAccuracyChecks(private val runner:Instrumentation) {
    fun run():JSONObject {
        val context=runner.targetContext
        runner.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        val app=(context.applicationContext as ApplicationLoader).controller
        val begun=SystemClock.elapsedRealtime()
        while(app.busy && SystemClock.elapsedRealtime()-begun<30_000)Thread.sleep(50)
        check(!app.busy && !app.cameraProcessing)
        val owner=checkNotNull(app.session)
        val originalRecords=app.storage.list(owner.scope,"scan")
        val before=canonical(JSONArray(originalRecords))
        val queue=canonical(JSONArray(app.storage.queue(owner.scope)))
        val evidence=File(checkNotNull(context.getExternalFilesDir(null)),"qa/accuracy-evidence-1150")
        val labels=JSONObject(File(evidence,"manual-labels.json").readText())
        val cases=JSONObject(File(evidence,"manifest.json").readText()).a("cases").objects()
        val output=File(evidence,"replay-1151").apply { mkdirs() }
        val scratch=File(context.cacheDir,"phone-accuracy-${UUID.randomUUID()}").apply { mkdirs() }
        val results=JSONArray();var verifiedAnswers=0;var decoded=0;var successful=0
        try {
            val templates=SheetFamilies.templates.associateWith { name -> File(scratch,name).also { f ->
                context.assets.open("templates/$name").use { i -> f.outputStream().use { i.copyTo(it) } }
            } }
            val bindings=OmrRosterIndex(app.packages.values,app.round).recognitionBindings
            for(c in cases) {
                val name=c.s("case");val count=c.optInt("questionCount")
                runner.sendStatus(1,Bundle().apply { putString("stream","Checking real photo $name…\n") })
                // Reuse the frozen original already owned by the app instead
                // of retaining a second set of large camera files on the phone.
                val frozen=File(File(evidence,name),"source.jpg")
                val input=if(frozen.isFile)frozen else File(originalRecords.single {
                    it.s("sourceImageSha256")==c.s("sourceSha256") }.s("sourcePath"))
                val hash=EvidenceArchive.hash(input)
                check(hash==c.s("sourceSha256"))
                val label=labels.optJSONObject(name)
                if(label!=null)check(hash==label.s("sourceSha256")) { "$name: manual labels belong to another photograph" }
                val started=SystemClock.elapsedRealtime()
                val result=CameraDocumentReader.read(input,json("inputSource" to "camera","captureQuestionCount" to count),
                    json("templateJsonPaths" to SheetFamilies.templatesForSelection(count).map { templates.getValue(it).path },
                        "outputDirectory" to File(scratch,name).apply { mkdirs() }.path,"includeQuestions" to true,
                        "returnNormalizedImages" to true,"returnAnnotationGeometry" to true,"preferCalibratedHeader" to true,
                        "maxInputDimension" to 2000,"qrBindings" to bindings))
                val ms=SystemClock.elapsedRealtime()-started
                File(output,"$name-result.json").writeText(result.toString(2)) // private diagnostics only
                val s=result.a("sheets").getJSONObject(0)
                val qr=OmrResultParser.qr(s)
                if(qr.isNotBlank()) {
                    decoded++
                    val probe=JSONObject(File(File(evidence,name),"qr-probe.json").readText()).a("codes").objects()
                    if(probe.size==1)check(qr==probe.single().s("text")) { "$name: independent QR decoders disagree" }
                    check(bindings.any { it.s("qrText")==qr }) { "$name: unknown photo identity" }
                }
                if(qr.isBlank())check(!s.optBoolean("success") && s.a("questions").length()==0) {
                    "$name: missing QR must never authorize answers"
                }
                if(s.optBoolean("success"))successful++
                var matched=0
                if(label!=null) {
                    check(s.optBoolean("success")) { "$name: manually labelled photograph failed: ${s.s("error")}" }
                    val expected=label.a("expected");val questions=s.a("questions").objects()
                    check(questions.size==count && expected.length()==count)
                    for(i in 0 until count) {
                        val q=questions[i]
                        check(q.optInt("question")==i+1)
                        check(if(expected.isNull(i))q.s("status")=="BLANK" else q.s("status")=="MARKED" && q.optInt("selectedOptionNumber")==expected.getInt(i)) {
                            "$name: visible ink location differs from manual label at question ${i+1}"
                        };matched++;verifiedAnswers++
                    }
                    check(s.a("warnings").strings().any { it.startsWith("roshd_printed_layout_verified:t3-") && !it.contains("20261115") })
                    check(CameraNormalizationPolicy.complete(s,count)) { "$name: incomplete registration" }
                }
                check(EvidenceArchive.hash(input)==hash)
                results.put(json("case" to name,"success" to s.optBoolean("success"),"qrDecoded" to qr.isNotBlank(),
                    "complete" to CameraNormalizationPolicy.complete(s,count),"rows" to s.optInt("matchedRowsUsed"),
                    "error" to s.s("error"),"totalMs" to ms,"stages" to result.o("stageTimings"),
                    "manuallyVerifiedLocations" to matched,"originalPreserved" to true))
                File(scratch,name).deleteRecursively()
            }
            val expectedManual=cases.sumOf { labels.optJSONObject(it.s("case"))?.a("expected")?.length() ?: 0 }
            val readableQrCases=cases.count {
                JSONObject(File(File(evidence,it.s("case")),"qr-probe.json").readText()).a("codes").length()==1
            }
            check(verifiedAnswers==expectedManual);check(decoded>=readableQrCases);check(successful>=readableQrCases)
            val recordsUnchanged=before==canonical(JSONArray(app.storage.list(owner.scope,"scan")))
            val queueUnchanged=queue==canonical(JSONArray(app.storage.queue(owner.scope)))
            val report=json("version" to com.haseebullahjan.roshd.BuildConfig.VERSION_NAME,"realPhotos" to cases.size,
                "qrDecoded" to decoded,"successfulReads" to successful,"manuallyVerifiedLocations" to verifiedAnswers,
                "recordsUnchanged" to recordsUnchanged,"queueUnchanged" to queueUnchanged,"results" to results)
            File(output,"report.json").writeText(report.toString(2))
            check(recordsUnchanged && queueUnchanged) { "Stored records changed during the read-only test; inspect concurrent activity" }
            return report
        } finally { scratch.deleteRecursively() }
    }
}
