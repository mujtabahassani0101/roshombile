package com.haseebullahjan.roshd.qa

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import com.haseebullahjan.roshd.MainActivity
import com.haseebullahjan.roshd.core.*
import com.haseebullahjan.roshd.messenger.*
import com.haseebullahjan.roshd.platform.OmrNative
import com.haseebullahjan.roshd.ui.actionbar.Route
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.UUID

/** Test-only fixtures and screenshots are never packaged in the application APK. */
class NativeSmokeTest:Instrumentation() {
    private var phoneArguments:Bundle?=null
    private var phoneEvidenceOnly=false
    private var phoneEvidenceAuditOnly=false
    private var phoneAccuracyOnly=false
    private var phoneDiagnosticCleanupOnly=false
    private var omrSpeedOnly=false
    private var latencyOnly=false
    private var geometrySpeedOnly=false
    private var liveEdgesOnly=false
    private var phoneCameraOnly=false
    private var phonePerformanceOnly=false
    private var performanceCompare=false
    private var performanceProbe=false
    private var mojiOnly=false
    private var fortyOnly=false
    private var cameraCount=100
    private var checks=0
    private var serverTimeOnly=false
    private var themesOnly=false
    private var dockOnly=false
    private var storageOnly=false
    private var startupThemeOnly=false
    private var scannerOnly=false
    private var scannerRestoreOnly=false
    private var fileAuditOnly=false
    private var gradedUploadOnly=false
    private var pipelineProfileArguments:Bundle?=null
    private val report=StringBuilder()
    private fun checkThat(value:Boolean,label:String) { check(value) { label };checks++;report.append("PASS: ").append(label).append('\n') }
    override fun callActivityOnCreate(activity:android.app.Activity,state:Bundle?) {
        super.callActivityOnCreate(activity,if(latencyOnly && activity is com.haseebullahjan.roshd.ui.camera.ScannerActivity) (state ?: Bundle()).apply { putBoolean("autoEnabled",false);putInt("questionCount",40) } else state)
    }
    override fun onCreate(arguments:Bundle?) { pipelineProfileArguments=arguments?.takeIf { it.getString("suite")=="pipeline-profile" }; latencyOnly=arguments?.getString("suite")=="camera-latency"; phoneDiagnosticCleanupOnly=arguments?.getString("suite")=="phone-diagnostic-cleanup";phoneAccuracyOnly=arguments?.getString("suite")=="phone-accuracy";phoneEvidenceAuditOnly=arguments?.getString("suite")=="phone-evidence-audit";phoneEvidenceOnly=arguments?.getString("suite")=="phone-evidence"; geometrySpeedOnly=arguments?.getString("suite")=="geometry-speed";omrSpeedOnly=arguments?.getString("suite")=="omr-speed"; performanceCompare=arguments?.getString("compare")=="true"; liveEdgesOnly=arguments?.getString("suite")=="live-edges"; performanceProbe=arguments?.getString("probe")=="true";phonePerformanceOnly=arguments?.getString("suite")=="phone-performance";phoneCameraOnly=arguments?.getString("suite")=="phone-camera";phoneArguments=arguments?.takeIf { it.getString("suite")=="phone-information" }; mojiOnly=arguments?.getString("suite")=="moji"; gradedUploadOnly=arguments?.getString("suite")=="graded-upload";fortyOnly=arguments?.getString("suite")=="sheet-40";cameraCount=arguments?.getString("count")?.toIntOrNull() ?: 100; scannerRestoreOnly=arguments?.getString("suite") in setOf("scanner-restore","document-scanner"); fileAuditOnly=arguments?.getString("suite")=="file-audit"; scannerOnly=arguments?.getString("suite")=="scanner"; startupThemeOnly=arguments?.getString("suite")=="startup-theme";dockOnly=arguments?.getString("suite")=="dock";themesOnly=arguments?.getString("suite")=="themes";serverTimeOnly=arguments?.getString("suite")=="server-time";storageOnly=arguments?.getString("suite")=="storage";super.onCreate(arguments);start() }
    override fun onStart() {
        val output=Bundle()
        try {
            if(phoneDiagnosticCleanupOnly) { output.putString("stream","\n"+PhoneDiagnosticCleanupChecks(this).run().toString(2)+"\n");finish(0,output);return }
            if(phoneAccuracyOnly) { output.putString("stream","\n"+PhoneAccuracyChecks(this).run().toString(2)+"\n");finish(0,output);return }
            if(phoneEvidenceAuditOnly) { output.putString("stream","\n"+PhoneEvidenceChecks(this).audit().toString(2)+"\n");finish(0,output);return }
            if(phoneEvidenceOnly) { output.putString("stream","\n"+PhoneEvidenceChecks(this).run().toString(2)+"\n");finish(0,output);return }
            pipelineProfileArguments?.let { output.putString("stream","\n"+PipelineProfileChecks(this,it).run().toString(2)+"\n");finish(0,output);return }
            if(latencyOnly) { output.putString("stream","\n"+CameraLatencyChecks(this).run().toString(2)+"\n");finish(0,output);return }
            if(geometrySpeedOnly) { output.putString("stream","\n"+OmrGeometrySpeedChecks(this).run().toString(2)+"\n");finish(0,output);return }
            if(omrSpeedOnly) { output.putString("stream","\n"+OmrSpeedChecks(this).run().toString(2)+"\n");finish(0,output);return }
            if(liveEdgesOnly) { output.putString("stream","\n"+LiveEdgeChecks(this).run().toString(2)+"\n");finish(0,output);return }
            if(phonePerformanceOnly) { output.putString("stream","\n"+PhonePerformanceChecks(this).run(performanceProbe,performanceCompare).toString(2)+"\n");finish(0,output);return }
            if(phoneCameraOnly) { output.putString("stream","\n"+PhoneCameraChecks(this).run().toString(2)+"\n");finish(0,output);return }
            phoneArguments?.let { args ->
                val result=PhoneInformationChecks(this).run(args)
                output.putString("stream","\n"+result.toString(2)+"\n")
                finish(if(result.optBoolean("informationDownloaded"))0 else 1,output);return
            }
            if(mojiOnly) { output.putString("stream","\n"+MojiScannerChecks(this).run());finish(0,output);return }
            if(gradedUploadOnly) { output.putString("stream","\n"+GradedUploadChecks(this).run());finish(0,output);return }
            if(fortyOnly) { output.putString("stream","\n"+NewFortySheetChecks(this).run());finish(0,output);return }
            if(scannerRestoreOnly) { output.putString("stream","\n"+DocumentScannerChecks(this).run());finish(0,output);return }
            if(fileAuditOnly) { output.putString("stream","\n"+FileEngineAuditChecks(this).run());finish(0,output);return }
            if(scannerOnly) { output.putString("stream","\n"+ScannerChecks(this).run(cameraCount));finish(0,output);return }
            if(startupThemeOnly) {
                output.putString("stream","\n"+ThemeStartupChecks(this).run());finish(0,output);return
            }
            if(themesOnly || dockOnly) {
                output.putString("stream","\n"+ThemeUiChecks(this).run(dockOnly));finish(0,output);return
            }
            serverTimeFixtures()
            if(!serverTimeOnly)storageFixtures()
            // This branch never opens an activity, touches storage, starts the camera or calls the network.
            if(serverTimeOnly || storageOnly) {
                output.putString("stream","\n$report$checks checks passed.\n")
                output.putInt("checks",checks);finish(0,output);return
            }
            // The first-use chooser has its own suite; this fixture starts after that choice.
            targetContext.getSharedPreferences("roshd_native_settings",0).edit().putString("visualStyle","study").putString("language","fa").putBoolean("dark",false).commit()
            val intent=Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            val activity=startActivitySync(intent) as MainActivity
            val app=(targetContext.applicationContext as ApplicationLoader).controller
            repeat(100) { if(app.busy)Thread.sleep(50) }
            waitForIdleSync()
            runOnMainSync {
                val fields=descendants(activity.window.decorView).filterIsInstance<EditText>()
                checkThat(fields.size==2,"Native login form renders")
                val password=fields.single { !it.isSaveEnabled }
                checkThat(password.transformationMethod is PasswordTransformationMethod,"Password is masked and excluded from saved view state")
            }
            capture("login")
            val scope="qa:${UUID.randomUUID()}";val id=UUID.randomUUID().toString()
            val request=json("payloadBase64" to "bytes-preserved","signatureBase64" to "signature-preserved")
            try {
                checkThat(app.storage.enqueue(scope,"scan",id,"unique-sheet",request,json("name" to "آزمایشی")),"Outbox append is durable")
                checkThat(!app.storage.enqueue(scope,"scan",UUID.randomUUID().toString(),"unique-sheet",request,JSONObject()),"Duplicate sheet submission cannot create a second record")
                app.storage.status(scope,id,"delivered",receipt=json("scanId" to id))
                val restored=app.storage.queue(scope).single()
                checkThat(restored.o("request").toString()==request.toString() && restored.s("status")=="delivered","Delivery state does not rewrite signed request bytes")
                runCatching { app.storage.transaction { app.storage.put(scope,"qa","rollback",json("x" to 1));error("rollback") } }
                checkThat(app.storage.get(scope,"qa","rollback")==null,"Interrupted transactions roll back")
                checkThat(app.storage.queue("different:$scope").isEmpty(),"Account scopes cannot see another outbox")
                val raw=app.storage.readableDatabase.rawQuery("SELECT request FROM outbox WHERE id=?",arrayOf(id)).use { it.moveToFirst();it.getBlob(0) }
                checkThat(!String(raw).contains("bytes-preserved"),"Queue payload is encrypted at rest")
            } finally { app.storage.writableDatabase.delete("outbox","scope=?",arrayOf(scope)) }
            val crypto=ScannerCrypto(app.native)
            val key="roshd.qa.${UUID.randomUUID()}"
            crypto.secureWrite(key,"local-value")
            checkThat(crypto.secureRead(key)=="local-value","Keystore-backed values survive a round trip")
            crypto.secureWrite(key,null)
            checkThat(crypto.secureRead(key)==null,"Secure deletion removes only the selected value")
            val school="10000000-0000-0000-0000-000000000001";val account="20000000-0000-0000-0000-000000000001"
            val owner=Session(json("schoolId" to school,"accountId" to account,"role" to "ScannerOperator"),"https://example.org")
            val identity=crypto.identity(owner)
            val transcript=listOf("yse.qa.v1",school,account)
            val signature=crypto.sign(identity,transcript)
            checkThat(ScannerCrypto.verify(identity.keys.s("signingPublicKeySpki"),transcript.joinToString("\n").toByteArray(),signature),"Hardware signing returns a valid P1363 signature")
            checkThat(!ScannerCrypto.verify(identity.keys.s("signingPublicKeySpki"),"changed".toByteArray(),signature),"Modified signed content is rejected")
            checkThat(OmrNative.engineRevision().isNotEmpty(),"JNI loads the existing recognition engine")
            val empty=JSONObject(OmrNative.processJson(json("pages" to emptyList<Any>()).toString()))
            checkThat(empty.s("status")=="ERROR","Native recognition rejects an empty job without crashing")
            navigationFixtures(activity,app)
            reviewFixtures(activity,app,crypto)
            noticeFixture()
            orientationFixture()
            themeFixture(activity)
            cameraFixture()
            output.putString("stream","\n$report$checks checks passed.\n")
            output.putInt("checks",checks);finish(0,output)
        } catch(error:Throwable) {
            output.putString("stream","\n$report\nFAILED: ${error.stackTraceToString()}");finish(1,output)
        }
    }
    private fun storageFixtures() {
        val storage=MessagesStorage(targetContext)
        val scope="qa:storage:${UUID.randomUUID()}";val id=UUID.randomUUID().toString();val next=UUID.randomUUID().toString()
        val request=json("payloadBase64" to "immutable-fixture","signatureBase64" to "test-signature")
        try {
            checkThat(storage.enqueue(scope,"scan",id,"first",request,json("fixture" to true)),"Pending row can be appended in isolated QA scope")
            val pending=storage.queue(scope).single()
            checkThat(pending.s("status")=="pending" && pending.isNull("receipt") && pending.o("request").toString()==request.toString(),"Absent receipt remains valid for a pending packet")
            fun raw():ByteArray=storage.readableDatabase.rawQuery("SELECT request FROM outbox WHERE id=? AND scope=?",arrayOf(id,scope)).use { it.moveToFirst();it.getBlob(0) }
            val original=raw()
            storage.writableDatabase.update("outbox",android.content.ContentValues().apply { put("metadata",ByteArray(0)) },"id=? AND scope=?",arrayOf(id,scope))
            val damaged=storage.queue(scope).single()
            checkThat(damaged.s("status")=="storage_error" && damaged.s("error").isNotEmpty(),"Unreadable metadata is reported and excluded from pending submission")
            checkThat(damaged.o("request").toString()==request.toString() && raw().contentEquals(original),"Damaged-row handling preserves original signed request bytes")
            storage.enqueue(scope,"scan",next,"second",request,json("fixture" to true))
            checkThat(storage.queue(scope).size==2 && storage.queue(scope).single { it.s("id")==next }.s("status")=="pending","One unreadable row cannot hide another pending packet")
            storage.status(scope,next,"delivered")
            checkThat(storage.queue(scope).single { it.s("id")==next }.s("status")=="storage_error","Delivery without a receipt cannot be presented as confirmed")
            storage.status(scope,next,"delivered",receipt=json("scanId" to next))
            checkThat(storage.queue(scope).single { it.s("id")==next }.s("status")=="delivered","A readable receipt restores the normal delivered state")
            val large=json("padding" to "x".repeat(9*1024*1024));storage.put(scope,"information","large",large)
            val legacy=runCatching { storage.readableDatabase.query("documents",arrayOf("payload"),"scope=? AND kind=? AND id=?",arrayOf(scope,"information","large"),null,null,null).use { it.moveToFirst();it.getBlob(0) } }
            report.append("Legacy full BLOB unavailable: ").append(legacy.isFailure || legacy.getOrNull()==null).append('\n')
            checkThat(storage.get(scope,"information","large")?.s("padding")==large.s("padding"),"Nine-megabyte encrypted document restores through bounded reads")
            checkThat(storage.list(scope,"information").single().s("padding")==large.s("padding"),"Document lists retain full large payloads through bounded reads")
        } finally { storage.writableDatabase.delete("outbox","scope=?",arrayOf(scope));storage.delete(scope,"information","large");storage.close() }
    }
    private fun serverTimeFixtures() {
        val wire="2026-11-08T11:08:24+00:00"
        report.append("Android API: ").append(android.os.Build.VERSION.SDK_INT)
            .append("; legacy Instant.parse rejects server offset: ").append(runCatching { Instant.parse(wire) }.isFailure).append('\n')
        val expected=Instant.ofEpochSecond(1794136104)
        checkThat(parseServerInstant(wire)==expected,"Exact reported +00:00 login timestamp parses on device")
        checkThat(parseServerInstant("2026-11-08T11:08:24Z")==expected,"Existing Z timestamps remain compatible")
        checkThat(parseServerInstant("2026-11-08T15:38:24+04:30")==expected,"Kabul offset preserves the instant")
        checkThat(parseServerInstant("2026-11-08T06:08:24-05:00")==expected,"Negative offsets preserve the instant")
        checkThat(parseServerInstant("2026-11-08T11:08:24.1234567+00:00")==expected.plusNanos(123456700),"Seven-digit server fractions preserve precision")
        checkThat(parseServerInstant("2026-11-08T15:38:24.123456789+04:30")==expected.plusNanos(123456789),"Nanosecond server fractions preserve precision")
        checkThat(runCatching { parseServerInstant("2099-11-08T11:08:24") }.isFailure,"Timestamps without a zone remain invalid")
        checkThat(runCatching { parseServerInstant("2026-02-30T11:08:24+00:00") }.isFailure,"Invalid calendar dates remain invalid")
        fun response(expiry:String)=json("data" to json("accountId" to "20000000-0000-0000-0000-000000000001",
            "schoolId" to "10000000-0000-0000-0000-000000000001","role" to "ScannerOperator","accessScope" to "school",
            "accessToken" to "test-token","tokenType" to "Bearer","apiVersion" to "v1","expiresIn" to 3600,"expiresAt" to expiry))
        val expiry=Instant.now().plusSeconds(3600).toString().removeSuffix("Z")+"+00:00"
        val login=response(expiry);val original=login.toString()
        val owner=Session.login(login,"https://example.org")
        checkThat(owner.valid && Session(owner.raw.copy(),owner.apiOrigin).valid,"Login and restored session accept server offset expiry")
        checkThat(owner.raw.s("expiresAt")==expiry && login.toString()==original,"Session parsing preserves raw server data")
        val expired=Instant.now().minusSeconds(60).toString().removeSuffix("Z")+"+00:00"
        checkThat(runCatching { Session.login(response(expired),"https://example.org") }.isFailure && !Session(response(expired).data(),"https://example.org").valid,"Expired offset tokens remain rejected")
    }
    private fun navigationFixtures(activity:MainActivity,app:ExamsController) {
        val saved=mapOf("session" to app.session,"configuration" to app.configuration,"exams" to app.exams,"round" to app.round,"scans" to app.scans)
        fun set(name:String,value:Any?) { ExamsController::class.java.getDeclaredField(name).apply { isAccessible=true;set(app,value) } }
        val year=json("id" to "qa-year","academicYearId" to "qa-year","academicYearKey" to "۱۴۰۵","editionId" to "30000000-0000-0000-0000-000000000001","provinceId" to "40000000-0000-0000-0000-000000000001","version" to "qa-v1","label" to "سال آزمایشی","openRounds" to listOf(1,2,3))
        val owner=Session(json("accountId" to "20000000-0000-0000-0000-000000000001","schoolId" to "10000000-0000-0000-0000-000000000001","role" to "SchoolManager","displayName" to "کاربر آزمایشی","expiresAt" to Instant.EPOCH.toString()),"https://example.org")
        try {
            runOnMainSync {
                set("session",owner);set("configuration",json("currentAcademicYear" to year));set("round",1)
                set("exams",listOf(Exam(json("id" to "50000000-0000-0000-0000-000000000001","schoolId" to owner.schoolId,"name" to "آزمون آزمایشی صنف ششم","round" to 1,"grade" to 6,"questionCount" to 40))))
                activity.navigation.reset(Route("home"))
            }
            waitForIdleSync();capture("home-light")
            runOnMainSync {
                descendants(activity.window.decorView).single { it.tag=="dock:history" }.performClick()
                checkThat(activity.navigation.current?.name=="history","Telegram-style dock opens the results tab")
                checkThat(descendants(activity.window.decorView).single { it.tag=="dock:history" }.isSelected,"Dock selection follows the active screen")
            }
            capture("history-light")
            runOnMainSync {
                descendants(activity.window.decorView).single { it.tag=="dock:archive" }.performClick()
                checkThat(activity.navigation.current?.name=="archive","Dock switches between real application routes")
                activity.navigation.reset(Route("home"))
            }
            runOnMainSync {
                activity.navigation.presentFragment(Route("exams"));activity.navigation.presentFragment(Route("history"))
                checkThat(activity.navigation.closeLastFragment(),"Navigation pops to the preceding page")
                checkThat(activity.navigation.current?.name=="exams","Back navigation restores the correct route")
                val restored=activity.navigation.serialize();checkThat(restored.contains("exams"),"Page stack has a saved-state representation")
                activity.navigation.restore(restored)
                checkThat(activity.navigation.current?.name=="exams","Saved page stack can be restored without duplicate pages")
                activity.navigation.presentFragment(Route("settings"))
            }
            waitForIdleSync();capture("settings-light")
            runOnMainSync {
                activity.navigation.reset(Route("home"))
                val width=activity.resources.displayMetrics.widthPixels
                val views=descendants(activity.window.decorView)
                checkThat(views.filter { it.visibility==View.VISIBLE }.all { view -> val rect=android.graphics.Rect();!view.getGlobalVisibleRect(rect) || (rect.left>=0 && rect.right<=width) },"Visible controls remain inside the screen")
            }
        } finally {
            runOnMainSync { saved.forEach { (name,value) -> set(name,value) };activity.navigation.reset(Route(if(app.session==null)"login" else "home")) }
        }
    }
    private fun complete(action:((Result<String>)->Unit)->Unit):Result<String> {
        val latch=java.util.concurrent.CountDownLatch(1)
        var result:Result<String>?=null
        runOnMainSync { action { result=it;latch.countDown() } }
        check(latch.await(60,java.util.concurrent.TimeUnit.SECONDS)) { "Controller operation did not complete" }
        waitForIdleSync();return result ?: error("Controller did not return a result")
    }
    private fun reviewFixtures(activity:MainActivity,app:ExamsController,crypto:ScannerCrypto) {
        val saved=mapOf("session" to app.session,"packages" to app.packages,"round" to app.round,"scans" to app.scans,"queue" to app.queue,"archive" to app.archive)
        fun set(name:String,value:Any?) { ExamsController::class.java.getDeclaredField(name).apply { isAccessible=true;set(app,value) } }
        val owner=Session(json("schoolId" to UUID.randomUUID().toString(),"accountId" to UUID.randomUUID().toString(),"role" to "ScannerOperator","expiresAt" to Instant.EPOCH.toString()),"https://example.org")
        val id=UUID.randomUUID().toString();val source=app.storage.mediaFile(owner.scope,"legacy-$id.jpg")
        try {
            runOnMainSync { set("session",owner);set("packages",emptyMap<String,OfflineExam>());set("round",1) }
            val bitmap=Bitmap.createBitmap(600,850,Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.WHITE) }
            source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG,95,it) };bitmap.recycle()
            val hash=EvidenceArchive.hash(source)
            app.storage.put(owner.scope,"scan",id,json("id" to id,"sourcePath" to source.path,"sourceImageSha256" to hash,"createdAt" to "2026-01-01T00:00:00Z","state" to "ready",
                "studentName" to "stale-student","studentPublicId" to "old-card","schoolId" to owner.schoolId,"examId" to "old-exam","answers" to listOf(json("questionNumber" to 1,"state" to "selected","selectedOption" to 1)),"offlineScore" to json("correctCount" to 1),"serverScore" to json("rawScore" to 99)))
            val retry=complete { app.retryScan(id,it) }
            if(retry.isFailure) {
                val templates=listOf("yse_omr_a5_t1_100q_v2.json","yse_omr_a5_t3_40q_v1.json").map { File(targetContext.filesDir,"native-templates/$it").path }
                val diagnostic=OmrNative.processJson(json("templateJsonPaths" to templates,"pages" to listOf(json("pageNumber" to 1,"imagePath" to source.path))).toString())
                report.append("Engine fixture diagnostic: ").append(diagnostic).append('\n')
            }
            checkThat(retry.isSuccess,"A migrated photo with a different filename can be reread by its original record ID: ${retry.exceptionOrNull()?.message ?: "OK"}")
            val reread=app.storage.get(owner.scope,"scan",id)!!
            checkThat(reread.s("id")==id && reread.s("sourcePath")==source.path && reread.s("sourceImageSha256")==hash && EvidenceArchive.hash(source)==hash && app.storage.get(owner.scope,"scan","legacy-$id")==null,"Rereading preserves evidence bytes and does not create a second legacy record")
            checkThat(reread.s("state")=="identity_required" && !reread.has("answers") && !reread.has("studentName") && !reread.has("offlineScore") && !reread.has("serverScore"),"An unidentified reread cannot display stale answers, student identity or grades")
            val before=canonical(reread)
            source.appendBytes(byteArrayOf(1))
            val changed=complete { app.retryScan(id,it) }
            checkThat(changed.isFailure && changed.exceptionOrNull()?.message?.contains("اثر انگشت")==true && canonical(app.storage.get(owner.scope,"scan",id))==before,"Changed source bytes are rejected without changing the stored review")
            app.storage.put(owner.scope,"scan",id,reread.copy().put("sealed",true))
            checkThat(complete { app.retryScan(id,it) }.isFailure && app.storage.get(owner.scope,"scan",id)!!.optBoolean("sealed"),"A signed scan remains immutable during retry")
            source.delete();app.storage.put(owner.scope,"scan",id,reread)
            checkThat(complete { app.retryScan(id,it) }.isFailure && canonical(app.storage.get(owner.scope,"scan",id))==before,"A missing source leaves its saved review intact")

            val identity=crypto.identity(owner)
            val exam=Exam(json("id" to UUID.randomUUID().toString(),"schoolId" to owner.schoolId,"round" to 1,"questionCount" to 2))
            val row=RosterRow("001234","QA","qa-sheet-qr","qa-card","شاگرد آزمایشی",exam.id)
            val manifest=json("expiresAt" to Instant.now().plusSeconds(3600).toString(),"packageId" to UUID.randomUUID().toString(),"packageVersion" to "qa-1")
            manifest.put("offlineGrading",gradingCapsule(owner,identity,exam,manifest))
            val pack=OfflineExam(exam,json(),manifest,listOf(row))
            runOnMainSync { set("packages",mapOf(exam.key to pack)) }
            val answers=listOf(json("questionNumber" to 1,"state" to "selected","selectedOption" to 1),json("questionNumber" to 2,"state" to "selected","selectedOption" to 2))
            app.storage.put(owner.scope,"scan",id,json("id" to id,"state" to "ready","schoolId" to owner.schoolId,"examId" to exam.id,"studentPublicId" to row.code,"qrText" to row.sheetQr,"answers" to answers))
            val score=complete { app.recalculateScore(id,it) }
            checkThat(score.isSuccess && app.storage.get(owner.scope,"scan",id)!!.o("offlineScore").optInt("correctCount")==2,"Temporary grading decrypts a real ECDH/HKDF/AES-GCM capsule through the Android bridge")
            checkThat(complete { app.editAnswer(id,1,4,it) }.isSuccess && !app.storage.get(owner.scope,"scan",id)!!.has("offlineScore"),"Editing an answer invalidates its previous temporary grade")
            checkThat(complete { app.recalculateScore(id,it) }.isSuccess && app.storage.get(owner.scope,"scan",id)!!.o("offlineScore").optInt("correctCount")==1 && app.storage.queue(owner.scope).isEmpty(),"Recalculation uses the edited answers without rereading or submitting them")
            val reviewed=canonical(app.storage.get(owner.scope,"scan",id)!!.a("answers"))
            manifest.o("offlineGrading").o("encryption").put("tagBase64",ScannerCrypto.encode(ByteArray(16)))
            val tampered=complete { app.recalculateScore(id,it) }
            val failed=app.storage.get(owner.scope,"scan",id)!!
            checkThat(tampered.isFailure && !failed.has("offlineScore") && failed.s("scoreWarning").isNotEmpty() && canonical(failed.a("answers"))==reviewed,"A tampered grading capsule clears the stale grade and retains reviewed answers")
            manifest.put("expiresAt",Instant.EPOCH.toString())
            checkThat(complete { app.recalculateScore(id,it) }.isFailure && !app.storage.get(owner.scope,"scan",id)!!.has("offlineScore"),"Expired offline information cannot authorize a recalculated grade")
            val fixtureDirectory=File(targetContext.cacheDir,"qa-omr-$id").apply { mkdirs() }
            try {
                val counts=listOf(100,40)
                val packs=counts.mapIndexed { index,count ->
                    val sampleExam=Exam(json("id" to UUID.randomUUID().toString(),"schoolId" to owner.schoolId,"round" to 1,"questionCount" to count))
                    val qr=if(index==1)"T3:213327:rZeGOHtv" else "T1:001234:S2000000000000000000"
                    val student=RosterRow(if(index==1)"213327" else "001234","QA",qr,"qa-card-$index","برگهٔ آزمایشی ${sampleExam.template}",sampleExam.id)
                    OfflineExam(sampleExam,json(),json("expiresAt" to Instant.now().plusSeconds(3600).toString()),listOf(student))
                }
                runOnMainSync { set("packages",packs.associateBy { it.exam.key }) }
                val files=listOf(1,3).map { number -> File(fixtureDirectory,"T$number.jpg").also { file -> context.assets.open("omr/T$number.jpg").use { input -> file.outputStream().use { input.copyTo(it) } } } }
                val processed=complete { app.processFiles(files,it) }
                checkThat(processed.isSuccess,"Photo import runs both supported native OMR templates end to end: ${processed.exceptionOrNull()?.message ?: "OK"}")
                for(pack in packs) {
                    val found=app.storage.list(owner.scope,"scan").singleOrNull { it.s("qrText")==pack.roster.single().sheetQr }
                    val answers=found?.a("answers")?.objects() ?: emptyList()
                    checkThat(found?.s("state")=="ready" && found.o("engine").o("sheetType").s("code")==pack.exam.template && answers.size==pack.exam.count && answers.all { it.s("state")=="selected" && it.optInt("selectedOption")==((it.optInt("questionNumber")-1)%4)+1 },"${pack.exam.template}: all ${pack.exam.count} known answers and exact QR identity survive the Kotlin/JNI pipeline; ${found?.s("state")} ${found?.o("engine")?.s("error")}")
                }
                val imported=app.storage.list(owner.scope,"scan").filter { it.s("id")!=id }
                val sourceHashes=files.map { EvidenceArchive.hash(it) }.toSet()
                checkThat(imported.size==2 && imported.all { it.s("sourceImageSha256") in sourceHashes && EvidenceArchive.hash(File(it.s("sourcePath")))==it.s("sourceImageSha256") } && app.storage.queue(owner.scope).isEmpty(),"Mixed rotated sheets preserve each original image and remain local until explicit submission")
                runOnMainSync {
                    activity.navigation.reset(Route("review",json("id" to imported.first().s("id"))))
                    checkThat(descendants(activity.window.decorView).filterIsInstance<android.widget.TextView>().any { it.text=="محاسبهٔ نمرهٔ موقت" },"The native clay review exposes temporary-grade recalculation")
                }
                capture("review-native-omr")
            } finally { fixtureDirectory.deleteRecursively() }
        } finally {
            app.storage.writableDatabase.delete("documents","scope=?",arrayOf(owner.scope))
            app.storage.writableDatabase.delete("outbox","scope=?",arrayOf(owner.scope))
            source.parentFile?.deleteRecursively()
            runOnMainSync { saved.forEach { (name,value) -> set(name,value) };activity.navigation.reset(Route(if(app.session==null)"login" else "home")) }
        }
    }
    /** Test-side server envelope; answer options exist only in the instrumentation APK. */
    internal fun gradingCapsule(owner:Session,identity:DeviceIdentity,exam:Exam,manifest:JSONObject,
                               accepted:List<List<Int>> = listOf(listOf(1),listOf(2))):JSONObject {
        val schema="roshd.offline-correct-count.v2";val release="qa-release";val answerVersion="qa-answers"
        val aad=listOf(schema,owner.schoolId,owner.accountId,identity.deviceUuid,exam.id,manifest.s("packageId"),manifest.s("packageVersion"),identity.encryptionHash,release,answerVersion,exam.count.toString(),Instant.parse(manifest.s("expiresAt")).epochSecond.toString()).joinToString("\n").toByteArray()
        val ephemeral=java.security.KeyPairGenerator.getInstance("EC").apply { initialize(java.security.spec.ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val remote=java.security.KeyFactory.getInstance("EC").generatePublic(java.security.spec.X509EncodedKeySpec(ScannerCrypto.decode(identity.keys.s("encryptionPublicKeySpki"))))
        val secret=javax.crypto.KeyAgreement.getInstance("ECDH").run { init(ephemeral.private);doPhase(remote,true);generateSecret() }
        val salt=ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        val extract=javax.crypto.Mac.getInstance("HmacSHA256").run { init(javax.crypto.spec.SecretKeySpec(salt,"HmacSHA256"));doFinal(secret) }
        val key=javax.crypto.Mac.getInstance("HmacSHA256").run { init(javax.crypto.spec.SecretKeySpec(extract,"HmacSHA256"));doFinal(aad+byteArrayOf(1)) }
        try {
            val plaintext=json("schemaVersion" to schema,"releaseVersion" to release,"answerKeyVersion" to answerVersion,"keySemanticsVersion" to "roshd.answer-choices.v2","questionCount" to exam.count,"acceptedOptions" to accepted,
                "scoringPolicy" to json("schemaVersion" to "roshd.points.v1","version" to 1,"correctPoints" to "1","incorrectPoints" to "0","blankPoints" to "0")).toString().toByteArray()
            val cipher=javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply { init(javax.crypto.Cipher.ENCRYPT_MODE,javax.crypto.spec.SecretKeySpec(key,"AES"));updateAAD(aad) }
            val bytes=try { cipher.doFinal(plaintext) } finally { plaintext.fill(0) }
            return json("schemaVersion" to schema,"recipientKeySha256" to identity.encryptionHash,"releaseVersion" to release,"answerKeyVersion" to answerVersion,"encryption" to json("ephemeralPublicKeySpki" to ScannerCrypto.encode(ephemeral.public.encoded),"saltBase64" to ScannerCrypto.encode(salt),"nonceBase64" to ScannerCrypto.encode(cipher.iv),"ciphertextBase64" to ScannerCrypto.encode(bytes.copyOfRange(0,bytes.size-16)),"tagBase64" to ScannerCrypto.encode(bytes.takeLast(16).toByteArray())))
        } finally { secret.fill(0);extract.fill(0);key.fill(0) }
    }
    private fun noticeFixture() {
        val data=json("rendererVersion" to "roshd.notice-a4-120.v4","title" to "اطلاع‌نامهٔ آزمایشی","studentPublicId" to "1234560000","firstName" to "شاگرد","fatherName" to "آزمایشی","lastName" to "نمونه","schoolName" to "مکتب آزمایشی","examDate" to Instant.now().toString(),"grade" to 6,"round" to 1,"publicationVersion" to 1,"totalScore" to 120,"totalTaraz" to 5000,"ranks" to json("overall" to 1),
            "subjects" to (1..8).map { json("name" to "مضمون $it","count" to 15,"correct" to 15,"incorrect" to 0,"blank" to 0,"percentage" to 100,"score" to 15) },
            "questions" to (1..120).map { json("number" to it,"correctOption" to 1,"selectedOption" to 1,"status" to "Correct","originalState" to "selected","corrected" to false) },
            "history" to listOf(json("round" to 1,"totalScore" to 120)))
        val file=File(targetContext.filesDir,"qa/notice.pdf")
        NoticeRenderer(targetContext).render(file,listOf(data,data))
        android.os.ParcelFileDescriptor.open(file,android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> android.graphics.pdf.PdfRenderer(fd).use { renderer ->
            checkThat(renderer.pageCount==2,"Authenticated notice data renders as a valid two-page native PDF")
            renderer.openPage(0).use { page ->
                val bitmap=Bitmap.createBitmap(800,1132,Bitmap.Config.ARGB_8888);page.render(bitmap,null,null,android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                File(targetContext.filesDir,"qa/notice.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
            }
        } }
    }
    private fun themeFixture(previous:MainActivity) {
        val settings=targetContext.getSharedPreferences("roshd_native_settings",0)
        val dark=settings.getBoolean("dark",false);val language=settings.getString("language","fa")
        var fresh:MainActivity?=null
        try {
            settings.edit().putBoolean("dark",true).putString("language","ps").commit()
            runOnMainSync { previous.finish() };waitForIdleSync()
            fresh=startActivitySync(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            waitForIdleSync()
            runOnMainSync {
                val words=descendants(fresh!!.window.decorView).filterIsInstance<android.widget.TextView>().map { it.text.toString() }
                checkThat(words.any { it=="رشد" } && words.any { it=="حساب ته ننوتل" },"Roshd identity and Pashto labels render on the native dark login")
                checkThat(com.haseebullahjan.roshd.ui.components.ClayTheme(true).surface!=com.haseebullahjan.roshd.ui.components.ClayTheme(false).surface,"Light and dark themes use separate clay surfaces")
            }
            capture("login-dark-pashto")
        } finally {
            settings.edit().putBoolean("dark",dark).putString("language",language).commit()
            runOnMainSync { fresh?.finish() }
        }
    }
    private fun orientationFixture() {
        val folder=File(targetContext.cacheDir,"qa-orientation").apply { mkdirs() }
        try {
            val source=File(folder,"original.jpg")
            val image=Bitmap.createBitmap(120,80,Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.WHITE) }
            source.outputStream().use { image.compress(Bitmap.CompressFormat.JPEG,95,it) };image.recycle()
            androidx.exifinterface.media.ExifInterface(source).apply { setAttribute(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,"6");saveAttributes() }
            val hash=EvidenceArchive.hash(source)
            val path=com.haseebullahjan.roshd.DocumentImagePreparation.prepareForHeader(source,File(folder,"prepared"),2000)
            val bounds=android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds=true };android.graphics.BitmapFactory.decodeFile(path,bounds)
            checkThat(bounds.outWidth==80 && bounds.outHeight==120 && EvidenceArchive.hash(source)==hash,"Recognition flattens EXIF rotation while preserving original evidence bytes")
        } finally { folder.deleteRecursively() }
    }
    private fun cameraFixture() {
        report.append(ScannerChecks(this).run(cameraCount))
        checkThat(true,"Current continuous-capture and in-camera score instrumentation completed")
    }
    private fun descendants(root:View):List<View> = listOf(root)+(if(root is ViewGroup)(0 until root.childCount).flatMap { descendants(root.getChildAt(it)) } else emptyList())
    private fun capture(name:String) {
        waitForIdleSync()
        Thread.sleep(250)
        waitForIdleSync()
        val bitmap=uiAutomation.takeScreenshot() ?: error("Screenshot is unavailable")
        val file=File(targetContext.filesDir,"qa/$name.png");file.parentFile!!.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
    }
}
