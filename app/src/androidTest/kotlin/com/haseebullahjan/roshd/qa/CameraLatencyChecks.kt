package com.haseebullahjan.roshd.qa

import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.camera.view.PreviewView
import com.haseebullahjan.roshd.MainActivity
import com.haseebullahjan.roshd.core.*
import com.haseebullahjan.roshd.messenger.ApplicationLoader
import com.haseebullahjan.roshd.platform.CameraDocumentReader
import com.haseebullahjan.roshd.ui.camera.ScannerActivity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

/** Existing records/queue are read only. Automatic capture is disabled before activity creation. */
class CameraLatencyChecks(private val runner:Instrumentation) {
    private fun views(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    fun run():JSONObject {
        val context=runner.targetContext
        runner.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        val app=(context.applicationContext as ApplicationLoader).controller
        val started=SystemClock.elapsedRealtime()
        while(app.session==null && app.busy && SystemClock.elapsedRealtime()-started<30_000)Thread.sleep(50)
        val owner=checkNotNull(app.session)
        var idleSince=SystemClock.elapsedRealtime()
        while(SystemClock.elapsedRealtime()-idleSince<2_000) {
            if(app.busy || app.cameraProcessing || app.storage.cameraWorkCount(owner.scope)>0 ||
                app.storage.list(owner.scope,"scan").any { it.optBoolean("annotationPending") })idleSince=SystemClock.elapsedRealtime()
            check(SystemClock.elapsedRealtime()-started<90_000) { "Existing scan work did not become idle" };Thread.sleep(100)
        }
        val before=canonical(JSONArray(app.storage.list(owner.scope,"scan")))
        val queue=canonical(JSONArray(app.storage.queue(owner.scope)))
        val selected=app.storage.list(owner.scope,"scan").filter { it.s("state")=="ready" && it.s("inputSource")=="camera" && File(it.s("sourcePath")).isFile }
            .sortedByDescending { it.s("createdAt") }.take(2)
        check(selected.size==2)
        val folder=File(context.cacheDir,"camera-latency-${UUID.randomUUID()}").apply { mkdirs() }
        val reportFolder=File(context.getExternalFilesDir(null),"qa/camera-latency").apply { mkdirs() }
        val templates=SheetFamilies.templates.associateWith { n -> File(folder,n).also { f -> context.assets.open("templates/$n").use { i -> f.outputStream().use { i.copyTo(it) } } } }
        val bindings=OmrRosterIndex(app.packages.values,app.round)
        val inputs=selected.mapIndexed { i,r -> File(r.s("sourcePath")).copyTo(File(folder,"photo-$i.jpg")) }
        val rows=JSONArray();var live:ScannerActivity?=null
        val fingerprints=mutableMapOf<Int,String>();val imageHashes=mutableMapOf<Int,String>()
        val worker=Executors.newSingleThreadExecutor()
        try {
            val warm=File(folder,"warm").apply { mkdirs() }
            com.haseebullahjan.youngscientist.scanner.DocumentScanner.prepareForOmr(inputs.first(),warm);warm.deleteRecursively()
            for((phase,i) in listOf("live-background" to 0,"live-limited" to 0,"live-limited" to 1,"live-background" to 1)) {
                val priority=10
                if(phase.startsWith("live") && live==null) {
                    live=runner.startActivitySync(Intent(context,ScannerActivity::class.java).putExtra("questionCount",40).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ScannerActivity
                    runner.runOnMainSync {
                        ScannerActivity::class.java.getDeclaredField("autoEnabled").apply { isAccessible=true;setBoolean(live,false) }
                        (views(live!!.window.decorView).single { it.tag=="scanner-auto" } as android.widget.TextView).text="خودکار: خاموش"
                    }
                    val begun=SystemClock.elapsedRealtime()
                    while(true) {
                        var ready=false
                        runner.runOnMainSync { ready=views(live!!.window.decorView).filterIsInstance<PreviewView>().singleOrNull()?.previewStreamState?.value==PreviewView.StreamState.STREAMING }
                        if(ready)break
                        check(SystemClock.elapsedRealtime()-begun<20_000) { "Camera unavailable or phone locked" };Thread.sleep(100)
                    }
                }
                val input=inputs[i]
                run {
                    runner.sendStatus(1,Bundle().apply { putString("stream","Timing $phase / photo-${i+1}…\n") })
                    val row=worker.submit<JSONObject> {
                        android.os.Process.setThreadPriority(priority)
                        val dir=File(folder,"$phase-$i").apply { mkdirs() }
                        val count=selected[i].optInt("captureQuestionCount")
                        val req=json("templateJsonPaths" to SheetFamilies.templatesForSelection(count).map { templates.getValue(it).path },
                            "outputDirectory" to dir.path,"returnNormalizedImages" to true,"returnAnnotationGeometry" to true,"includeQuestions" to true,
                            "preferCalibratedHeader" to true,"maxInputDimension" to 2000,"qrBindings" to bindings.historicalBindings,
                            "limitLiveAnalysisDuringRead" to (phase=="live-limited"))
                        val begun=SystemClock.elapsedRealtime()
                        val result=CameraDocumentReader.read(input,selected[i],req)
                        val readAt=SystemClock.elapsedRealtime();val sheet=result.a("sheets").getJSONObject(0)
                        check(sheet.optBoolean("success"))
                        val fingerprint=canonical(sheet.a("questions"));val imageHash=EvidenceArchive.hash(File(sheet.s("normalizedImagePath")))
                        fingerprints[i]?.let { check(it==fingerprint) { "Answers changed" } };fingerprints[i]=fingerprint
                        imageHashes[i]?.let { check(it==imageHash) { "Normalized image changed" } };imageHashes[i]=imageHash
                        val match=bindings.matches(OmrResultParser.qr(sheet),selected[i]).single()
                        val crypto=ScannerCrypto(app.native)
                        val identity=crypto.identity(owner.scope(match.pack.exam.schoolId))
                        val score=crypto.offlineCount(owner.scope(match.pack.exam.schoolId),identity,match.pack,
                            OmrResultParser.observations(sheet,count).map { json("state" to it.s("state"),"selectedOption" to it.opt("selectedOption")) })
                        val end=SystemClock.elapsedRealtime()
                        dir.deleteRecursively()
                        json("phase" to phase,"photo" to i+1,"workerNice" to priority,"readMs" to readAt-begun,"scoringMs" to end-readAt,
                            "totalMs" to end-begun,"scoreAvailable" to (score!=null),"stages" to result.o("stageTimings"),"exactAnswers" to true,"exactNormalizedImage" to true)
                    }.get()
                    rows.put(row)
                    File(reportFolder,"report.json").writeText(json("completed" to false,"results" to rows).toString(2))
                }
            }
            val after=canonical(JSONArray(app.storage.list(owner.scope,"scan")))
            File(reportFolder,"before-private.json").writeText(before)
            File(reportFolder,"after-private.json").writeText(after)
            check(before==after) { "Stored scan records changed during measurement" }
            check(queue==canonical(JSONArray(app.storage.queue(owner.scope)))) { "Upload queue changed during measurement" }
            return json("completed" to true,"version" to com.haseebullahjan.roshd.BuildConfig.VERSION_NAME,"results" to rows,
                "recordsUnchanged" to true,"queueUnchanged" to true,"automaticCaptureDisabled" to true).also { File(reportFolder,"report.json").writeText(it.toString(2)) }
        } finally { runner.runOnMainSync { live?.finish() };worker.shutdownNow();folder.deleteRecursively() }
    }
}
