package com.haseebullahjan.roshd.qa

import com.haseebullahjan.roshd.core.*
import com.haseebullahjan.roshd.messenger.*
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real saved-photo queue -> OMR -> encrypted key -> grade -> background colors. */
class ContinuousGradeChecks(private val runner:NativeSmokeTest) {
    fun run(app:ExamsController,owner:Session,cameraCount:Int=100):String {
        val context=runner.targetContext
        runner.runOnMainSync { app.configuration.put("features",json("offlineScore" to true)) }
        val report=StringBuilder();var count=0
        fun verify(ok:Boolean,label:String) { check(ok) { label };count++;report.append("PASS: $label\n") }
        val crypto=ScannerCrypto(app.native);val identity=crypto.identity(owner)
        val packs=listOf(100,40).map { questions ->
            val exam=Exam(json("id" to UUID.randomUUID().toString(),"schoolId" to owner.schoolId,"round" to 1,"questionCount" to questions))
            val qr=if(questions==100)"T1:001234:S2000000000000000000" else "T3:213327:rZeGOHtv"
            val row=RosterRow(if(questions==100)"001234" else "213327","QA",qr,"","شاگرد آزمایشی",exam.id)
            val manifest=json("packageId" to UUID.randomUUID().toString(),"packageVersion" to "test-only","expiresAt" to Instant.now().plusSeconds(3600).toString())
            val accepted=List(questions) { i -> listOf(if(i<questions*4/5)i%4+1 else (i%4+1)%4+1) }
            manifest.put("offlineGrading",runner.gradingCapsule(owner,identity,exam,manifest,accepted))
            OfflineExam(exam,json(),manifest,listOf(row))
        }
        // Older camera fixtures intentionally had no roster; keep this test scoped
        // to two independently known physical sheets, without touching user data.
        app.storage.cameraWork(owner.scope).forEach { app.storage.cameraWorkStatus(owner.scope,it.s("id"),"failed") }
        runner.runOnMainSync { ExamsController::class.java.getDeclaredField("packages").apply { isAccessible=true;set(app,packs.associateBy { it.exam.key }) } }
        val events=java.util.Collections.synchronizedList(mutableListOf<LiveScanFeedback>())
        var subscription:AutoCloseable?=null
        runner.runOnMainSync { subscription=app.notifications.observe(AppEvent.SCAN_RESULT) { (it as? LiveScanFeedback)?.let(events::add) } }
        val tickets=mutableListOf<ExamsController.CameraCaptureTicket>()
        try {
            for(family in listOf(1,3)) {
                var reservation:Result<ExamsController.CameraCaptureTicket>?=null
                val reserved=CountDownLatch(1)
                runner.runOnMainSync { app.reserveCameraCapture { reservation=it;reserved.countDown() } }
                check(reserved.await(30,TimeUnit.SECONDS))
                val ticket=reservation!!.getOrThrow();tickets.add(ticket)
                runner.context.assets.open("omr/T$family.jpg").use { input -> File(ticket.path).outputStream().use { input.copyTo(it) } }
                val saved=CountDownLatch(1);var failure:Throwable?=null
                runner.runOnMainSync { app.completeCameraCapture(ticket) { failure=it.exceptionOrNull();saved.countDown() } }
                check(saved.await(30,TimeUnit.SECONDS));check(failure==null) { failure.toString() }
            }
            val deadline=android.os.SystemClock.elapsedRealtime()+90000
            while(android.os.SystemClock.elapsedRealtime()<deadline && tickets.any { ticket ->
                    synchronized(events) { events.none { it.id==ticket.id && it.coloredPath!=null } } })Thread.sleep(100)
            for((index,ticket) in tickets.withIndex()) {
                val questions=if(index==0)100 else 40;val correct=questions*4/5
                val results=synchronized(events) { events.filter { it.id==ticket.id }.toList() }
                val first=results.firstOrNull()
                verify(first?.rawScore==correct.toString() && first.correct==correct && first.count==questions && first.coloredPath==null,
                    "T${if(index==0)1 else 3}: real OMR/encrypted-key score $correct/$questions publishes before color rendering")
                verify(results.any { it.coloredPath!=null && it.rawScore==correct.toString() },"Same page receives its color image after the immediate score")
                val record=app.storage.get(owner.scope,"scan",ticket.id)!!
                val annotation=GradedSheet.current(record)
                verify(record.s("state")=="ready" && !record.optBoolean("annotationPending") && annotation?.optInt("correctMarks")==correct && annotation.optInt("incorrectMarks")==questions-correct,
                    "Background annotation is durably attached with $correct green and ${questions-correct} red marks")
                verify(GradedSheet.image(record)?.let { CameraEvidence.complete(File(it)) }==true && EvidenceArchive.hash(File(ticket.path))==record.s("sourceImageSha256"),
                    "Colored JPEG is complete and the saved camera original retains its exact hash")
                report.append("CAPTURE_TO_SCORE_MS T${if(index==0)1 else 3}: ${first!!.elapsedMs}\n")
            }
            // A selected family is durable and cannot be bypassed by a valid QR
            // from another family, including queued recovery and final scoring.
            var wrongReservation:Result<ExamsController.CameraCaptureTicket>?=null
            val wrongReserved=CountDownLatch(1)
            runner.runOnMainSync { app.reserveCameraCapture(100) { wrongReservation=it;wrongReserved.countDown() } }
            check(wrongReserved.await(30,TimeUnit.SECONDS))
            val wrong=wrongReservation!!.getOrThrow();tickets.add(wrong)
            runner.context.assets.open("omr/T3-anchors.jpg").use { input -> File(wrong.path).outputStream().use { input.copyTo(it) } }
            val wrongSaved=CountDownLatch(1)
            runner.runOnMainSync { app.completeCameraCapture(wrong) { check(it.isSuccess);wrongSaved.countDown() } }
            check(wrongSaved.await(30,TimeUnit.SECONDS))
            val wrongDeadline=android.os.SystemClock.elapsedRealtime()+30000
            var rejected=app.storage.get(owner.scope,"scan",wrong.id)!!
            while(rejected.s("state") in setOf("pending","processing") && android.os.SystemClock.elapsedRealtime()<wrongDeadline) {
                Thread.sleep(100);rejected=app.storage.get(owner.scope,"scan",wrong.id)!!
            }
            verify(rejected.s("state")=="read_failed" && rejected.optInt("captureQuestionCount")==100 && !rejected.has("offlineScore") && rejected.a("answers").length()==0,
                "40-question photo selected as 100 retains its original and fails without answers or score")
            val scanner=runner.startActivitySync(android.content.Intent(context,com.haseebullahjan.roshd.ui.camera.ScannerActivity::class.java)
                .putExtra("questionCount",cameraCount).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) as com.haseebullahjan.roshd.ui.camera.ScannerActivity
            try {
                val ids=tickets.map { it.id }.toSet()
                val cameraDeadline=android.os.SystemClock.elapsedRealtime()+90000
                var actual:LiveScanFeedback?=null
                while(android.os.SystemClock.elapsedRealtime()<cameraDeadline && actual==null) {
                    actual=synchronized(events) { events.lastOrNull { it.id !in ids && it.coloredPath!=null } }
                    if(actual==null)Thread.sleep(100)
                }
                val cameraCorrect=cameraCount*4/5
                verify(actual?.correct==cameraCorrect && actual.count==cameraCount && actual.rawScore==cameraCorrect.toString(),
                    "Automatic CameraX capture of the sample paper produces an actual $cameraCorrect/$cameraCount grade without tapping the shutter")
                runner.waitForIdleSync()
                runner.runOnMainSync {
                    fun views(v:android.view.View):List<android.view.View> = listOf(v)+(if(v is android.view.ViewGroup)(0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList())
                    val card=views(scanner.window.decorView).single { it.tag=="scanner-live-result" } as android.widget.TextView
                    verify(card.visibility==android.view.View.VISIBLE && card.text.contains(cameraCorrect.toString()) && card.isClickable,
                        "Measured camera grade and completed red/green image appear in the compact panel")
                    val panel=views(scanner.window.decorView).single { it.tag=="scanner-controls" }
                    val dp=panel.height/context.resources.displayMetrics.density
                    verify(dp<=220,"Panel remains compact with the actual grade and all controls visible ($dp dp)")
                }
                val screenshot=runner.uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
                val target=File(context.filesDir,"qa/scanner/real-graded-camera.png");target.parentFile!!.mkdirs()
                target.outputStream().use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };screenshot.recycle()
                report.append("REAL_CAMERA_CAPTURE_TO_SCORE_MS: ${actual!!.elapsedMs}\n")
            } finally { runner.runOnMainSync { scanner.finish() };runner.waitForIdleSync() }
            return "$report$count continuous-grade checks passed.\n"
        } catch(error:Throwable) { throw IllegalStateException("$report\n${error.message}",error) }
        finally { runner.runOnMainSync { subscription?.close() } }
    }
}
