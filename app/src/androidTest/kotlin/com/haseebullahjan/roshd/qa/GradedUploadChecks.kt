package com.haseebullahjan.roshd.qa

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.haseebullahjan.roshd.MainActivity
import com.haseebullahjan.roshd.core.*
import com.haseebullahjan.roshd.messenger.*
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Isolated emulator-only fixtures: actual Android crypto, JNI colors and frozen packet bytes. No network. */
class GradedUploadChecks(private val runner:NativeSmokeTest) {
    fun run():String {
        check(runner.targetContext.packageName.endsWith(".nativeqa")) { "Use the isolated QA application" }
        val activity=runner.startActivitySync(Intent(runner.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val app=(runner.targetContext.applicationContext as ApplicationLoader).controller
        val deadline=android.os.SystemClock.elapsedRealtime()+30000
        while(app.busy && android.os.SystemClock.elapsedRealtime()<deadline)Thread.sleep(50)
        check(!app.busy)
        val savedSession=app.session;val savedPacks=app.packages;val savedConfig=app.configuration
        fun set(name:String,value:Any?) { ExamsController::class.java.getDeclaredField(name).apply { isAccessible=true;set(app,value) } }
        fun invoke(name:String,record:JSONObject):Any?=ExamsController::class.java.getDeclaredMethod(name,JSONObject::class.java).apply { isAccessible=true }.invoke(app,record)
        val owner=Session(json("schoolId" to UUID.randomUUID().toString(),"accountId" to UUID.randomUUID().toString(),"role" to "ScannerOperator",
            "accessToken" to "fixture-never-sent","expiresAt" to Instant.now().plusSeconds(3600).toString()),"https://example.org")
        val report=StringBuilder();var checks=0
        fun verify(value:Boolean,label:String) { check(value) { label };checks++;report.append("PASS: $label\n") }
        fun complete(action:((Result<String>)->Unit)->Unit) {
            var result:Result<String>?=null;val latch=CountDownLatch(1)
            runner.runOnMainSync { action { result=it;latch.countDown() } }
            check(latch.await(60,TimeUnit.SECONDS));result!!.getOrThrow()
        }
        try {
            runner.runOnMainSync { set("session",owner);set("configuration",json("features" to json("offlineScore" to true))) }
            val crypto=ScannerCrypto(app.native);val identity=crypto.identity(owner)
            for(count in listOf(40,75,100)) {
                val exam=Exam(json("id" to UUID.randomUUID().toString(),"schoolId" to owner.schoolId,"questionCount" to count,"round" to 1,"grade" to 6))
                val row=RosterRow("123456","QA","test-only-$count","","FIXTURE",exam.id)
                val manifest=json("packageId" to UUID.randomUUID().toString(),"packageVersion" to "test-only","expiresAt" to Instant.now().plusSeconds(3600).toString())
                manifest.put("offlineGrading",runner.gradingCapsule(owner,identity,exam,manifest,List(count) { listOf(1) }))
                val pack=OfflineExam(exam,json(),manifest,listOf(row))
                runner.runOnMainSync { set("packages",mapOf(exam.key to pack)) }
                val id=UUID.randomUUID().toString()
                val raw=app.storage.mediaFile(owner.scope,"$id-camera.jpg")
                val normalized=app.storage.mediaFile(owner.scope,"$id-normalized.jpg")
                fun jpeg(file:File,width:Int,height:Int,draw:(Canvas)->Unit={}) {
                    val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);bitmap.eraseColor(Color.WHITE)
                    draw(Canvas(bitmap));file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG,95,it) };bitmap.recycle()
                }
                jpeg(raw,1200,1600)
                val questions=(1..count).map { q -> json("questionNumber" to q,"options" to (1..4).map { option ->
                    json("option" to option,"x" to 60+((q-1)/25)*230+option*35,"y" to 200+((q-1)%25)*42,"radius" to 10) }) }
                jpeg(normalized,1000,1400) { canvas ->
                    val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.DKGRAY;style=Paint.Style.STROKE;strokeWidth=1.5f }
                    questions.forEach { q -> q.a("options").objects().forEach { p -> canvas.drawCircle(p.optDouble("x").toFloat(),p.optDouble("y").toFloat(),10f,paint) } }
                    paint.textSize=30f;paint.style=Paint.Style.FILL;canvas.drawText("TEST FIXTURE $count",60f,70f,paint)
                }
                val nativeQuestions=(1..count).map { q -> json("question" to q,"status" to if(q<=3)"MARKED" else "BLANK",
                    "selectedOptions" to when(q) { 1,3->listOf("1");2->listOf("2");else->emptyList<String>() },"confidence" to 1).apply {
                        if(q==3)put("verifiedMultipleOptions",wrap(listOf(1,4))).put("verificationPasses",2).put("reviewReason","multiple_ink_confirmed")
                        if(q>=4)put("needsReview",true).put("reviewReason","faint_ink_in_blank_candidate")
                    } }
                val answers=OmrResultParser.observations(json("questions" to nativeQuestions),count)
                val record=json("id" to id,"state" to "ready","exam" to exam.raw,"schoolId" to owner.schoolId,"examId" to exam.id,
                    "studentPublicId" to row.code,"qrText" to row.sheetQr,"sourcePath" to raw.path,"sourceImageSha256" to EvidenceArchive.hash(raw),"createdAt" to now(),"answers" to answers,
                    "engine" to json("success" to true,"qrCode" to json("found" to true,"status" to "FOUND","text" to row.sheetQr),"sheetType" to json("code" to exam.template),
                        "normalizedImagePath" to normalized.path,"annotationGeometry" to json("width" to 1000,"height" to 1400,"questions" to questions)))
                val rawHash=EvidenceArchive.hash(raw);val normalizedHash=EvidenceArchive.hash(normalized)
                ScanReview.applyAnswerPolicy(record);verify(ScanReview.pendingCount(record)==0,"$count: blank and definite multiple answers require no review")
                app.storage.put(owner.scope,"scan",id,record)
                complete { app.createColoredSheet(id,it) }
                val colored=app.storage.get(owner.scope,"scan",id)!!;val annotation=GradedSheet.current(colored)!!
                verify(annotation.optInt("correctMarks")==1 && annotation.optInt("incorrectMarks")==3 && annotation.optInt("blankCorrectMarks")==count-3,
                    "$count: authenticated encrypted key renders one green, three red and ${count-3} yellow marks")
                val image=BitmapFactory.decodeFile(annotation.s("path"))!!
                verify(image.width==1000 && image.height==1400,"$count: the output uses normalized dimensions, not camera dimensions")
                fun hasColor(question:Int,option:Int,hue:Float):Boolean {
                    val point=questions[question-1].a("options").getJSONObject(option-1);val x=point.optInt("x");val y=point.optInt("y");val hsv=FloatArray(3)
                    for(dy in -15..15)for(dx in -15..15) {
                        Color.colorToHSV(image.getPixel(x+dx,y+dy),hsv)
                        val delta=kotlin.math.abs(hsv[0]-hue)
                        if(hsv[1]>.5f && hsv[2]>.45f && minOf(delta,360-delta)<20)return true
                    };return false
                }
                verify(hasColor(1,1,150f) && hasColor(2,2,0f) && hasColor(3,1,0f) && hasColor(3,4,0f) && hasColor(4,1,48f),"$count: decoded JPEG colors lie on the exact intended bubbles")
                image.recycle()
                verify(rawHash==EvidenceArchive.hash(raw) && normalizedHash==EvidenceArchive.hash(normalized),"$count: both camera and normalized source bytes remain unchanged")
                val previousId=UUID.randomUUID().toString()
                colored.put("scanId",previousId).put("sourceSnapshot",json("sourceImageSha256" to rawHash,"qrText" to row.sheetQr,"capturedAt" to colored.s("createdAt")))
                val source=invoke("sourceFor",colored) as JSONObject
                verify(source.s("sourceImageSha256")==annotation.s("sha256") && source.s("sourceImageSha256")!=rawHash && !colored.has("scanId") &&
                    colored.a("previousImageCaptures").getJSONObject(0).s("scanId")==previousId,"$count: processed bytes receive a new identity while the previous capture remains preserved")
                invoke("sealScan",colored)
                val sealed=app.storage.get(owner.scope,"scan",id)!!;val packet=app.storage.queue(owner.scope,"scan",includeRequests=true,id=sealed.s("scanId")).single().o("request")
                val payload=JSONObject(String(ScannerCrypto.decode(packet.s("payloadBase64")),Charsets.UTF_8))
                verify(payload.s("sourceImageSha256")==EvidenceArchive.hash(File(UploadSheet.sourcePath(sealed,payload))) && payload.a("answers").getJSONObject(2).s("state")=="multiple",
                    "$count: the signed packet binds exactly the graded JPEG and preserves the multiple-answer state")
                val before=canonical(sealed);val frozen=invoke("sourceFor",sealed) as JSONObject
                verify(canonical(sealed)==before && frozen.s("sourceImageSha256")==source.s("sourceImageSha256"),"$count: retries preserve the sealed source and artifact")
                val export=File(runner.targetContext.getExternalFilesDir(null),"qa/graded-upload-$count.jpg").apply { parentFile!!.mkdirs() }
                File(annotation.s("path")).copyTo(export,overwrite=true)
            }
            verify(app.network.diagnostics.isEmpty(),"No network request was made")
            return "$report$checks Android graded-upload checks passed.\n"
        } finally {
            runner.runOnMainSync { set("session",savedSession);set("packages",savedPacks);set("configuration",savedConfig);activity.finish() }
            app.storage.writableDatabase.delete("outbox","scope=?",arrayOf(owner.scope))
            for(namespace in listOf("scan","archive"))app.storage.list(owner.scope,namespace).forEach { app.storage.delete(owner.scope,namespace,it.s("id")) }
            app.storage.mediaFile(owner.scope,"cleanup").parentFile?.deleteRecursively()
        }
    }
}
