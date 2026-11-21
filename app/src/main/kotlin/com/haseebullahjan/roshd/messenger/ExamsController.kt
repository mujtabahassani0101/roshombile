package com.haseebullahjan.roshd.messenger

import android.content.Context
import com.haseebullahjan.roshd.BuildConfig
import com.haseebullahjan.roshd.core.*
import com.haseebullahjan.roshd.platform.NativeGateway
import com.haseebullahjan.roshd.platform.OmrNative
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class ExamsController(val context:Context,val storage:MessagesStorage,val network:ConnectionsManager,val native:NativeGateway,val notifications:NotificationCenter) {
    val academicArchive=AcademicYearArchive(network,storage::get,storage::put)
    private val archivePhotos=ArchivePhotos(storage::get,storage::put,storage::delete)
    private val stageQueue=DispatchQueue("roshd-stage")
    private val captureQueue=DispatchQueue("roshd-capture-store")
    private val cameraDrainScheduled=AtomicBoolean(false)
    private val cameraEngines=java.util.concurrent.Executors.newFixedThreadPool(CameraWorkPolicy.BATCH_SIZE)
    private val cameraAnnotations=java.util.concurrent.Executors.newSingleThreadExecutor { task ->
        Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);task.run() },"roshd-camera-colors")
    }
    private val cameraActive=mutableSetOf<String>()
    @Volatile var latestCameraFeedback:LiveScanFeedback?=null;private set
    @Volatile var cameraQueuedCount=0;private set
    @Volatile var cameraProcessing=false;private set
    private var routingPackages:Map<String,OfflineExam>?=null
    private var rosterIndex:OmrRosterIndex?=null
    private var installedTemplatePaths:List<String>?=null
    private val running=AtomicBoolean(false)
    private val crypto=ScannerCrypto(native)
    private val verifier=OfflineVerifier()
    private var initialized=false
    @Volatile var session:Session?=null;private set
    @Volatile var configuration=JSONObject();private set
    @Volatile var exams:List<Exam> = emptyList();private set
    @Volatile var packages:Map<String,OfflineExam> = emptyMap();private set
    @Volatile var queue:List<JSONObject> = emptyList();private set
    @Volatile var scans:List<JSONObject> = emptyList();private set
    @Volatile var archive:List<JSONObject> = emptyList();private set
    @Volatile private var removedArchiveIds:Set<String> = emptySet()
    val archiveScans get()=scans.filter { it.s("id") !in removedArchiveIds }
    @Volatile var imageStatuses:Map<String,String> = emptyMap();private set
    @Volatile var imageErrors:Map<String,String> = emptyMap();private set
    @Volatile var round=0;private set
    @Volatile var progress="";private set
    @Volatile var startupError:String?=null;private set
    private var directory=emptyMap<String,String>()
    private var studentRows=JSONArray()
    private val identities=mutableMapOf<String,DeviceIdentity>()
    private val settings=NativeSettings.open(context)
    val busy get()=running.get()
    val uploadedAnswerCount get():Int {
        val delivered=queue.asSequence().filter { it.s("status")=="delivered" }.map { it.s("id") }.toHashSet()
        return scans.count { it.s("scanId") in delivered }
    }
    fun hasUploadedImage(record:JSONObject)=imageStatuses[record.s("scanId")]=="verified" &&
        (record.o("uploadEvidence").s("variant")==UploadSheet.VARIANT || record.optBoolean("sealed"))
    val uploadedImageCount get()=scans.count(::hasUploadedImage)
    val reviewBlockedScanCount get()=scans.count { it.s("state")=="ready" && !it.optBoolean("sealed") && ScanReview.pendingCount(it)>0 }
    val uploadedScanCount get():Int {
        val delivered=queue.filter { it.s("status")=="delivered" }.map { it.s("id") }.toSet()
        return scans.count { it.s("scanId") in delivered && hasUploadedImage(it) }
    }
    val currentYear get()=configuration.optJSONObject("currentAcademicYear") ?: session?.raw?.optJSONObject("currentAcademicYear")
    val openRounds get()=ExamPeriod.openRounds(currentYear)
    val scannerPackageCount get()=packages.values.count {
        it.valid && it.exam.round==round && it.exam.raw.s("editionId")==currentYear?.s("editionId") && it.roster.isNotEmpty()
    }
    val darkMode get()=settings.getBoolean("dark",false)
    val language get()=settings.getString("language","fa") ?: "fa"
    fun setDark(value:Boolean) { settings.edit().putBoolean("dark",value).apply();notifications.post(AppEvent.THEME) }
    fun setLanguage(value:String) { require(value in setOf("fa","ps"));settings.edit().putString("language",value).apply();notifications.post(AppEvent.THEME) }
    fun tr(dari:String,pashto:String)=if(language=="ps")pashto else dari
    fun selectRound(value:Int) {
        require(!busy) { "ابتدا عملیات فعلی را تمام کنید." }
        require(value in openRounds) { "فقط یکی از دورهای باز سرور را انتخاب کنید." }
        round=value;settings.edit().putInt("round",value).putString("roundPeriod",roundScope()).apply();notifications.post(AppEvent.DATA)
    }
    private fun roundScope()=session?.scope.orEmpty()+"\n"+ExamPeriod.key(currentYear)
    private fun updateRound() {
        val saved=settings.getInt("round",0)
        round=if(settings.getString("roundPeriod",null)==roundScope() && saved in openRounds)saved else ExamPeriod.currentRound(currentYear)
    }
    fun run(label:String,done:(Result<String>)->Unit={},work:()->String) {
        if(!running.compareAndSet(false,true)) { DispatchQueue.ui { done(Result.failure(IllegalStateException("عملیات قبلی در جریان است."))) };return }
        progress=label;notifications.post(AppEvent.PROGRESS)
        stageQueue.post {
            val result=runCatching(work)
            result.exceptionOrNull()?.let { failure ->
                // Stack locations only: never log account data, credentials, server bodies or exception messages.
                android.util.Log.e("RoshdFailure",failure.javaClass.simpleName+"\n"+failure.stackTrace.take(8).joinToString("\n"))
            }
            running.set(false);progress=""
            notifications.post(AppEvent.PROGRESS)
            DispatchQueue.ui { done(result) }
            scheduleCameraProcessing()
        }
    }
    fun initialize(done:(Result<String>)->Unit) {
        if(initialized) { done(Result.success(""));return }
        run("بازیابی معلومات محفوظ…",done) {
            val saved=storage.get("global","account","session")
                ?: crypto.secureRead("roshd.auth.session.v1")?.let(::JSONObject)?.also { storage.put("global","account","session",it) }
            if(saved!=null) {
                uuid(saved.s("accountId"));val api=origin(saved.s("apiOrigin",Roshd.API_ORIGIN));require(api==Roshd.API_ORIGIN) { "نشست محفوظ مربوط به رشد نیست؛ دوباره وارد شوید." }
                session=Session(saved,api)
                restoreInformation()
                migrateAnswerPolicy()
                reloadLocal()
                updateRound()
                recoverCameraWork()
            }
            initialized=true;notifications.post(AppEvent.SESSION);""
        }
    }
    fun login(username:String,password:String,done:(Result<String>)->Unit) = run("در حال ورود…",done) {
        require(username.trim().isNotEmpty() && password.isNotEmpty()) { "نام کاربری و رمز عبور را وارد کنید." }
        val response=network.request("POST","/api/v1/scanner/login",body=json("username" to username.trim(),"password" to password))
        val owner=Session.login(response,Roshd.API_ORIGIN)
        storage.put("global","account","session",owner.raw.copy().put("apiOrigin",owner.apiOrigin).put("loginUsername",username.trim()))
        session=owner;latestCameraFeedback=null;identities.clear();configuration=JSONObject();exams=emptyList();packages=emptyMap();directory=emptyMap()
        restoreInformation()
        migrateAnswerPolicy()
        owner.raw.optJSONObject("currentAcademicYear")?.let {
            configuration.put("currentAcademicYear",it.copy());storage.put(owner.scope,"config","current",configuration)
        }
        reloadLocal()
        // Always follow login with configuration: the server's annual default may have changed.
        run {
            runCatching {
                configuration=network.request("GET","/api/v1/scanner/configuration",owner).data()
                storage.put(owner.scope,"config","current",configuration)
            }.onFailure { startupError="ورود انجام شد؛ دریافت تنظیمات کامل نشد: ${it.message.orEmpty()}" }
        }
        updateRound()
        recoverCameraWork();notifications.post(AppEvent.SESSION);"خوش آمدید، ${owner.name}"
    }
    fun logout(done:(Result<String>)->Unit) {
        if(busy) { network.cancel();done(Result.failure(IllegalStateException("عملیات در حال پایان است؛ پس از آن خارج شوید.")));return }
        run("خروج از حساب…",done) {
            val previous=session
            if(previous?.valid==true)runCatching { network.request("POST","/api/v1/scanner/logout",previous,body=JSONObject()) }
            network.cancel();storage.delete("global","account","session");crypto.secureWrite("roshd.auth.session.v1",null)
            session=null;latestCameraFeedback=null;identities.clear();exams=emptyList();packages=emptyMap();queue=emptyList();scans=emptyList();archive=emptyList();removedArchiveIds=emptySet();imageStatuses=emptyMap();imageErrors=emptyMap();configuration=JSONObject();directory=emptyMap();round=0
            notifications.post(AppEvent.SESSION);"از حساب خارج شدید. معلومات محفوظ است."
        }
    }
    fun refreshConfiguration(done:(Result<String>)->Unit) = run("تازه‌سازی سال و تنظیمات…",done) {
        val owner=requireSession();val config=network.request("GET","/api/v1/scanner/configuration",owner).data()
        storage.put(owner.scope,"config","current",config);configuration=config
        updateRound()
        notifications.post(AppEvent.DATA);"تنظیمات تازه شد."
    }
    private fun device(school:String):DeviceIdentity = identities.getOrPut(school) { crypto.identity(requireSession().scope(school)) }
    fun upgradeDevice(school:String,done:(Result<String>)->Unit)=run("ارتقای کلید دستگاه…",done) {
        val owner=requireSession();require(owner.valid && school in owner.schools)
        require(storage.queue(owner.scope).none { it.o("metadata").s("schoolId")==school && it.s("status")!="delivered" }) { "ابتدا ثبت‌های دستگاه قبلی را ارسال کنید؛ کلید قبلی محفوظ است." }
        val records=storage.list(owner.scope,"scan").filter { it.s("schoolId")==school }
        require(records.all { record -> record.optBoolean("sealed") && storage.get(owner.scope,"image",record.s("scanId"))?.s("phase")=="verified" }) { "ابتدا پاسخ‌ها و اصل عکس‌های قبلی را ارسال کنید؛ ارتقا صف‌ها را بازنویسی نمی‌کند." }
        crypto.upgrade(owner.scope(school));identities.remove(school)
        packages=packages.filterValues { it.exam.schoolId!=school };notifications.post(AppEvent.DATA)
        "کلید ارتقا یافت؛ دستگاه را ثبت و معلومات دور را دوباره دریافت کنید. کلید قبلی محفوظ است."
    }
    private fun requireSession():Session = session ?: error("ابتدا وارد حساب شوید.")
    fun feature(name:String):Boolean {
        val value=configuration.o("features").opt(name)
        return value==true || (value is JSONObject && value.optBoolean("enabled"))
    }
    private fun validateDevice(receipt:JSONObject,school:String,identity:DeviceIdentity) {
        val device=receipt.optJSONObject("device") ?: receipt.data().optJSONObject("device") ?: receipt.data()
        require(device.s("deviceUuid",device.s("uuid"))==identity.deviceUuid && device.s("accountId").equals(requireSession().accountId,true) && device.s("schoolId").equals(school,true)
            && device.s("signingPublicKeySha256",device.s("publicKeySha256"))==identity.signingHash
            && (!identity.keys.optBoolean("encryptionSupported") || device.s("encryptionPublicKeySha256")==identity.encryptionHash)
            && !device.optBoolean("isRevoked") && device.s("status")!="revoked" && device.isNull("revokedAt") && device.isNull("revokedAtUtc")) { "رسید دستگاه به حساب و کلید فعلی تعلق ندارد." }
    }
    fun downloadInformation(done:(Result<String>)->Unit)=run("دریافت معلومات این دور…",done) {
        try { downloadInformationOnce() } catch(e:ApiFailure) {
            when(e.code) {
                // Server year or round moved: take its fresh metadata and retry the download once, no guessing.
                "information_period_changed"-> {
                    val owner=requireSession();configuration=network.request("GET","/api/v1/scanner/configuration",owner).data()
                    storage.put(owner.scope,"config","current",configuration);updateRound();downloadInformationOnce()
                }
                // The account's schools come from login; the unsent queue survives logout.
                "information_scope_changed"->throw ApiFailure(e.code,"فهرست مکاتب حساب در سرور تغییر کرده است؛ خارج و دوباره وارد شوید. صف ارسال‌نشده محفوظ می‌ماند.",e.status)
                else->throw e
            }
        }
    }
    private fun downloadInformationOnce():String {
        val owner=requireSession();require(owner.valid) { "برای دریافت معلومات، دوباره وارد شوید." }
        val year=currentYear ?: error(if(configuration.has("currentAcademicYear"))"برای این مکتب در سال جاری داده‌ای ثبت نشده است؛ این خطای شبکه یا ورود نیست." else "ابتدا سال جاری را از سرور دریافت کنید.")
        require(round in openRounds) { "ابتدا دور امتحان را انتخاب کنید." }
        val period=json("academicYearId" to year.s("academicYearId"),"editionId" to year.s("editionId"),"provinceId" to year.s("provinceId"),"round" to round,"currentYearVersion" to year.s("version"))
        val schools=owner.schools.toSet();require(schools.isNotEmpty()) { "فهرست مکاتب حساب کامل نیست؛ دوباره وارد شوید." }
        val devices=schools.map { school -> val local=device(school);json("schoolId" to school,"deviceUuid" to local.deviceUuid,"deviceKeyId" to local.keyId,"signingPublicKeySpki" to local.keys.s("signingPublicKeySpki"),"encryptionPublicKeySpki" to local.keys.opt("encryptionPublicKeySpki"),"registration" to crypto.registration(owner.scope(school),local)) }
        val data=network.request("POST","/api/v1/scanner/information-bundle",owner,body=json("schemaVersion" to "roshd.information-request.v2","format" to "qr-lite-v1","period" to period,"devices" to devices),readTimeoutMs=600_000).data()
        require(data.s("schemaVersion")=="roshd.information-bundle.v2" && data.s("format")=="qr-lite-v1" && data.optBoolean("complete") && data.isNull("nextCursor") && data.s("accountId")==owner.accountId && data.s("schoolId")==owner.schoolId && canonical(data.o("period"))==canonical(period)) { "بستهٔ سرور کامل یا مربوط به دور فعلی نیست؛ معلومات قبلی محفوظ است." }
        require(data.a("managerResponses").length()==0)
        val config=data.getJSONObject("configuration").data();val incomingYear=config.getJSONObject("currentAcademicYear")
        require(listOf("id","academicYearId","editionId","provinceId","version").all { incomingYear.s(it)==year.s(it) }) { "سال جاری در هنگام دریافت تغییر کرده است." }
        val names=verifier.names(data.getJSONArray("students"))
        val incomingExams=data.getJSONArray("exams").objects().map(::Exam)
        require(incomingExams.map { it.key }.distinct().size==incomingExams.size)
        incomingExams.forEach { exam -> uuid(exam.id);require(exam.schoolId in schools && exam.round==round && exam.raw.s("editionId")==year.s("editionId")) }
        val registered=mutableSetOf<String>()
        data.getJSONArray("devices").objects().forEach { row -> val school=uuid(row.s("schoolId"));require(school in schools && registered.add(school));validateDevice(row.o("registration"),school,device(school)) }
        val verified=linkedMapOf<String,OfflineExam>()
        data.getJSONArray("packages").objects().forEach { row ->
            val exam=incomingExams.singleOrNull { it.id==row.s("examId") && it.schoolId==row.s("schoolId") } ?: error("بستهٔ امتحان خارج از فهرست است.")
            require(exam.schoolId in registered && !verified.containsKey(exam.key) && !row.has("answerKey") && !row.has("studentNames") && !row.has("pages"))
            verified[exam.key]=verifier.verify(exam,row.getJSONObject("package"),owner.scope(exam.schoolId),device(exam.schoolId),config,names)
        }
        incomingExams.forEach { if(it.raw.optInt("issuedSheetCount",it.raw.optInt("rosterCount"))>0 && it.raw.optBoolean("canDownload",true))require(verified.containsKey(it.key)) { "تمام بسته‌های امتحان دریافت نشده‌اند." } }
        val retained=packages.filterValues { it.exam.round!=round && it.exam.raw.s("editionId")==year.s("editionId") && it.exam.schoolId in schools }
        val mergedPackages=retained+verified
        val mergedExams=(exams.filter { it.round!=round && it.raw.s("editionId")==year.s("editionId") && it.schoolId in schools }+incomingExams).distinctBy { it.key }
        val mergedRows=linkedMapOf<String,JSONArray>()
        for(rows in listOf(studentRows,data.a("students")))for(i in 0 until rows.length()) { val row=rows.getJSONArray(i);mergedRows[studentKey(row.getString(0))]=row }
        val rows=JSONArray(mergedRows.values.toList())
        val cache=json("configuration" to config,"students" to rows,"exams" to mergedExams.map { it.raw },"packages" to mergedPackages.values.map { json("exam" to it.exam.raw,"package" to it.envelope) },"school" to data.opt("school"),"downloadedAt" to now())
        storage.put(owner.scope,"information","current",cache)
        configuration=config;updateRound();exams=mergedExams;packages=mergedPackages;studentRows=rows;directory=verifier.names(rows)
        notifications.post(AppEvent.DATA);return "معلومات ${verified.size} امتحان دریافت و تأیید شد."
    }
    private fun restoreInformation() {
        val owner=requireSession()
        val savedConfig=storage.get(owner.scope,"config","current")
        configuration=savedConfig ?: owner.raw.o("configuration")
        val cache=storage.get(owner.scope,"information","current")
        if(cache==null)return
        val config=cache.o("configuration");val names=verifier.names(cache.a("students"))
        val restored=linkedMapOf<String,OfflineExam>()
        var failures=0
        for(row in cache.a("packages").objects()) {
            runCatching { val exam=Exam(row.o("exam"));require(exam.schoolId in owner.schools);verifier.verify(exam,row.o("package"),owner.scope(exam.schoolId),device(exam.schoolId),config,names) }
                .onSuccess { restored[it.exam.key]=it }.onFailure { failures++ }
        }
        configuration=savedConfig ?: config;exams=cache.a("exams").objects().map(::Exam);packages=restored;directory=names;studentRows=cache.a("students")
        startupError=if(failures>0)"اعتبار $failures بسته نیاز به دریافت دوباره دارد؛ نتیجه‌ها و صف‌ها محفوظ‌اند." else null
    }
    private fun reloadLocal() {
        val owner=requireSession();queue=storage.queue(owner.scope,"scan",includeRequests=false);scans=storage.list(owner.scope,"scan")
        removedArchiveIds=storage.list(owner.scope,"archive-removed").map { it.s("id") }.toSet()
        archive=storage.list(owner.scope,"archive").filter { it.s("id") !in removedArchiveIds }
        val images=storage.list(owner.scope,"image")
        fun imageId(value:JSONObject)=value.s("scanId",value.o("request").s("scanId",value.o("receipt").s("scanId")))
        imageStatuses=images.associate { imageId(it) to it.s("phase") }.filterKeys { it.isNotEmpty() }
        imageErrors=images.associate { imageId(it) to it.s("lastError") }.filter { it.key.isNotEmpty() && it.value.isNotEmpty() }
        cameraQueuedCount=storage.cameraWorkCount(owner.scope)
        notifications.post(AppEvent.QUEUE);notifications.post(AppEvent.SCANS)
    }
    private fun migrateAnswerPolicy() {
        val owner=requireSession()
        val records=storage.list(owner.scope,"scan").filter { !it.optBoolean("sealed") && it.s("state")=="ready" && it.optInt("answerPolicyVersion")<ScanReview.ANSWER_POLICY_VERSION }
        records.forEachIndexed { index,record ->
            uploadProgress("به‌روزکردن پاسخ‌های سفید و چندعلامتی: ${index+1} از ${records.size}…")
            if(!ScanReview.applyAnswerPolicy(record))return@forEachIndexed
            record.remove("offlineScore");record.remove("scoreWarning");GradedSheet.invalidate(record)
            runCatching { calculateOfflineScore(owner,record) }
                .onSuccess { record.put("offlineScore",it) }
                .onFailure { record.put("scoreWarning","نمرهٔ موقت باید با کلید معتبر دوباره محاسبه شود؛ پاسخ‌های خوانده‌شده محفوظ‌اند.") }
            // Reuse saved geometry; migrating review policy must not reread photos.
            val engine=record.o("engine")
            val recolor=record.has("offlineScore") && engine.o("annotationGeometry").optInt("width")>0 && File(engine.s("normalizedImagePath")).isFile
            if(recolor)record.put("annotationPending",true)
            storage.transaction { storage.put(owner.scope,"scan",record.s("id"),record);syncColoredArchive(owner.scope,record) }
            if(recolor)queueCameraAnnotation(owner,record.copy())
        }
    }
    fun deleteArchivePhoto(id:String,done:(Result<String>)->Unit)=run("حذف عکس از آرشیف…",done) {
        val owner=requireSession()
        val entry=storage.get(owner.scope,"archive",id)
        storage.transaction { archivePhotos.remove(owner.scope,id) }
        // Scan originals and graded images are also used by review/retry/upload. Only
        // unreferenced imported files can be physically removed by an archive action.
        val path=entry?.s("sourcePath").orEmpty()
        if(path.isNotBlank()) {
            val referenced=storage.list(owner.scope,"scan").any { it.s("sourcePath")==path || it.o("annotation").s("path")==path }
                || storage.list(owner.scope,"archive").any { it.s("sourcePath")==path }
            if(!referenced) {
                val file=File(path)
                val media=storage.mediaFile(owner.scope,"archive-check").parentFile!!.canonicalFile
                if(file.canonicalPath.startsWith(media.path+File.separator))file.delete()
            }
        }
        reloadLocal();"عکس از آرشیف حذف شد."
    }
    /** Local deletion is serialized with result persistence, including camera completions. */
    fun deleteScan(id:String,done:(Result<String>)->Unit) {
        val owner=session ?: return done(Result.failure(IllegalStateException("ابتدا وارد حساب شوید.")))
        if(busy && !cameraProcessing)return done(Result.failure(IllegalStateException("ابتدا عملیات ارسال یا خوانش فعلی را تمام کنید.")))
        stageQueue.post {
            val result=runCatching {
                check(session===owner) { "حساب تغییر کرده است؛ فهرست برگه‌ها را دوباره باز کنید." }
                require(!busy || cameraProcessing) { "ابتدا عملیات ارسال یا خوانش فعلی را تمام کنید." }
                val record=storage.get(owner.scope,"scan",id) ?: error("برگه پیدا نشد.")
                require(record.s("state")!="capturing") { "ذخیرهٔ عکس در جریان است؛ چند لحظه صبر کنید." }
                storage.transaction {
                    val archives=storage.list(owner.scope,"archive").filter {
                        it.s("id") in setOf(id,"graded:$id") || it.s("sourceScanRecordId")==id
                    }
                    val paths=(scanFilePaths(record)+archives.map { it.s("sourcePath") }).filter { it.isNotBlank() }.toMutableSet()
                    val oldColors=Regex("^graded-${Regex.escape(id)}-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.jpg$",RegexOption.IGNORE_CASE)
                    storage.mediaFile(owner.scope,"delete-check").parentFile?.listFiles()?.filter { it.isFile && oldColors.matches(it.name) }
                        ?.forEach { paths.add(it.path) }
                    for(key in setOf(id,record.s("scanId")))if(key.matches(Regex("^[A-Za-z0-9_-]+$")))
                        paths.add(storage.mediaFile(owner.scope,"$key-restored.jpg").path)
                    // Durable cleanup journal survives process death. It contains no answers or photo bytes.
                    storage.put(owner.scope,"scan-trash",id,json("id" to id,"paths" to paths.toList(),"deletedAt" to now()))
                    storage.delete(owner.scope,"scan",id);storage.deleteCameraWork(owner.scope,id)
                    for(archiveId in (archives.map { it.s("id") }+listOf(id,"graded:$id")).distinct()) {
                        storage.delete(owner.scope,"archive",archiveId);storage.delete(owner.scope,"archive-removed",archiveId)
                    }
                    val packetId=record.s("scanId")
                    if(packetId.isNotBlank() && storage.list(owner.scope,"scan").none { it.s("scanId")==packetId }) {
                        storage.deleteQueuedScan(owner.scope,packetId);storage.delete(owner.scope,"image",packetId)
                    }
                }
                if(latestCameraFeedback?.id==id)latestCameraFeedback=null
                reloadLocal();publishCameraQueue();notifications.post(AppEvent.SCAN_DELETED,id)
                if(id !in cameraActive)scheduleDeletedScanCleanup(owner.scope,id)
                "برگهٔ اسکن‌شده از گوشی حذف شد."
            }
            DispatchQueue.ui { done(result) }
        }
    }
    private fun scanFilePaths(record:JSONObject)=setOf(record.s("sourcePath"),record.o("annotation").s("path"),
        record.o("engine").s("normalizedImagePath"),record.s("annotationBasePath"),record.o("uploadEvidence").s("path"))+
        record.a("previousImageCaptures").objects().map { it.o("uploadEvidence").s("path") }
    /** Queue behind color jobs so a deleted photo is never unlinked while a worker uses it. */
    private fun scheduleDeletedScanCleanup(scope:String,id:String) {
        cameraAnnotations.execute { stageQueue.post { runCatching { cleanupDeletedScan(scope,id) } } }
    }
    private fun cleanupDeletedScan(scope:String,id:String) {
        val journal=storage.get(scope,"scan-trash",id) ?: return
        if(id in cameraActive)return
        val media=storage.mediaFile(scope,"delete-check").parentFile!!.canonicalFile
        val referenced=(storage.list(scope,"scan").flatMap(::scanFilePaths)+storage.list(scope,"archive").map { it.s("sourcePath") })
            .filter { it.isNotBlank() }.map { File(it).canonicalPath }.toSet()
        val remaining=journal.a("paths").strings().distinct().filter { path ->
            val file=File(path).canonicalFile
            if(!file.path.startsWith(media.path+File.separator) || file.path in referenced || !file.isFile)false
            else if(!file.delete())true
            else {
                val parent=file.parentFile
                if(parent!=null && parent!=media && parent.list()?.isEmpty()==true)parent.delete()
                false
            }
        }
        if(remaining.isEmpty())storage.delete(scope,"scan-trash",id)
        else storage.put(scope,"scan-trash",id,journal.put("paths",wrap(remaining)))
    }
    fun <T:Any> readAcademicArchive(label:String,done:(Result<T>)->Unit,load:(Session)->T) {
        val owner=session ?: return done(Result.failure(IllegalStateException("ابتدا وارد حساب شوید.")))
        var value:T?=null
        run(label,{ outcome -> done(outcome.mapCatching {
            check(session===owner) { "حساب تغییر کرده است؛ صفحهٔ سال‌های گذشته را دوباره باز کنید." }
            requireNotNull(value)
        }) }) { check(session===owner);value=load(owner);"" }
    }
    data class CameraCaptureTicket(val id:String,val path:String,val scope:String)
    private fun publishCameraQueue() {
        session?.let { owner -> runCatching { cameraQueuedCount=storage.cameraWorkCount(owner.scope) } }
        notifications.post(AppEvent.CAPTURE)
    }
    /** Update one photo; decrypting the complete history per shot grows with every scanned page. */
    private fun refreshCameraRecord(scope:String,id:String) {
        if(session?.scope!=scope)return
        runCatching {
            val record=storage.get(scope,"scan",id) ?: return@runCatching
            scans=listOf(record)+scans.filter { it.s("id")!=id }
            for(archiveId in listOf(id,"graded:$id")) {
                val entry=storage.get(scope,"archive",archiveId)?.takeUnless { archiveId in removedArchiveIds }
                archive=if(entry!=null)listOf(entry)+archive.filter { it.s("id")!=archiveId }
                    else archive.filter { it.s("id")!=archiveId }
            }
            notifications.post(AppEvent.SCANS)
        }.onFailure { startupError="تازه‌سازی سابقه کامل نشد؛ اصل عکس در صف محفوظ است." }
    }
    /** Warm immutable resources while the operator positions the first page. No network operation. */
    fun prepareContinuousScanner() { stageQueue.post {
        runCatching { nativeTemplatePaths();OmrNative.engineRevision() }
    } }
    private fun nativeTemplatePaths():List<String> = installedTemplatePaths ?: run {
        val folder=File(context.filesDir,"native-templates").apply { mkdirs() }
        // Remove only the retired cached template; saved student photos/results remain readable.
        File(folder,"yse_omr_a5_t2_60q_v1.json").delete()
        SheetFamilies.templates.map { name ->
            val target=File(folder,name);val file=android.util.AtomicFile(target)
            val stream=file.startWrite()
            try {
                context.assets.open("templates/$name").use { it.copyTo(stream) };file.finishWrite(stream)
            } catch(error:Exception) { file.failWrite(stream);throw error }
            target.path
        }.also { installedTemplatePaths=it }
    }
    fun reserveCameraCapture(questionCount:Int=0,shutterAtEpochMs:Long=0,done:(Result<CameraCaptureTicket>)->Unit) {
        val owner=session ?: return done(Result.failure(IllegalStateException("ابتدا وارد حساب شوید.")))
        val capturedRound=round;val capturedPeriod=CameraWorkPolicy.period(currentYear)
        captureQueue.post {
            val result=runCatching {
                require(questionCount==0 || questionCount in SheetFamilies.scanCounts) { "نوع برگهٔ انتخاب‌شده معتبر نیست." }
                require(capturedRound>0 && capturedPeriod.isNotEmpty()) { "ابتدا دور امتحان را انتخاب کنید." }
                require(storage.cameraWorkCount(owner.scope)<CameraWorkPolicy.MAX_PENDING) { "صف خوانش پر است؛ چند لحظه صبر کنید تا برگه‌های محفوظ بررسی شوند." }
                require(android.os.StatFs(context.noBackupFilesDir.path).availableBytes>64L*1024*1024) { "فضای کافی برای حفظ اصل عکس وجود ندارد." }
                val id=UUID.randomUUID().toString();val file=storage.mediaFile(owner.scope,"$id.jpg")
                val record=json("id" to id,"sourcePath" to file.path,"createdAt" to now(),"state" to "capturing", "inputSource" to "camera",
                    "captureRound" to capturedRound,"capturePeriod" to capturedPeriod,"capturedAtEpochMs" to System.currentTimeMillis(),
                    "captureQuestionCount" to questionCount).apply {
                    if(shutterAtEpochMs in 1..System.currentTimeMillis())put("shutterAtEpochMs",shutterAtEpochMs)
                }
                storage.transaction {
                    storage.put(owner.scope,"scan",id,record)
                    storage.addCameraWork(owner.scope,id,capturedRound,capturedPeriod)
                }
                stageQueue.post { refreshCameraRecord(owner.scope,id) }
                CameraCaptureTicket(id,file.path,owner.scope)
            }
            publishCameraQueue()
            DispatchQueue.ui { done(result) }
        }
    }
    fun enqueueCapturedPhoto(source:File,questionCount:Int,onReserved:(String)->Unit,shutterAtEpochMs:Long=0,done:(Result<String>)->Unit) {
        reserveCameraCapture(questionCount,shutterAtEpochMs) { reservation ->
            val ticket=reservation.getOrElse { done(Result.failure(it));return@reserveCameraCapture }
            onReserved(ticket.id)
            captureQueue.post {
                val copied=runCatching {
                    source.inputStream().use { input -> java.io.FileOutputStream(ticket.path).use { output -> input.copyTo(output);output.fd.sync() } }
                }
                if(copied.isSuccess)completeCameraCapture(ticket,done)
                else { failCameraCapture(ticket);DispatchQueue.ui { done(Result.failure(copied.exceptionOrNull()!!)) } }
            }
        }
    }
    private fun finalizeCameraCapture(scope:String,id:String) {
        val record=storage.get(scope,"scan",id) ?: error("سابقهٔ عکس محفوظ پیدا نشد.")
        if(record.s("state")!="capturing")return
        val file=File(record.s("sourcePath"));require(CameraEvidence.complete(file)) { "ذخیرهٔ عکس کامل نشد؛ دوباره عکس بگیرید." }
        val info=com.haseebullahjan.roshd.DocumentImagePreparation.inspect(file)
        require(maxOf(info.width,info.height)>=1200) { "وضوح عکس برای خوانش دقیق کافی نیست؛ دوباره عکس بگیرید." }
        val hash=EvidenceArchive.hash(file)
        record.put("sourceImageSha256",hash).put("state","pending")
        storage.transaction {
            storage.put(scope,"scan",id,record);storage.cameraWorkStatus(scope,id,"pending")
            val archive=storage.get(scope,"archive",id) ?: json("id" to id,"name" to "برگهٔ دوربین","sourcePath" to file.path,"createdAt" to record.s("createdAt"),"mimeType" to "image/jpeg")
            if(!archivePhotos.removed(scope,id))storage.put(scope,"archive",id,archive.put("sha256",hash))
        }
    }
    fun completeCameraCapture(ticket:CameraCaptureTicket,done:(Result<String>)->Unit) {
        captureQueue.post {
            val result=runCatching { finalizeCameraCapture(ticket.scope,ticket.id);"عکس محفوظ شد." }
            if(result.isFailure)runCatching { markCameraFailure(ticket.scope,ticket.id,result.exceptionOrNull()?.message ?: "عکس کامل نشد.") }
            stageQueue.post { refreshCameraRecord(ticket.scope,ticket.id) }
            publishCameraQueue();scheduleCameraProcessing()
            DispatchQueue.ui { done(result) }
        }
    }
    fun failCameraCapture(ticket:CameraCaptureTicket) { captureQueue.post {
        runCatching { markCameraFailure(ticket.scope,ticket.id,"گرفتن عکس کامل نشد؛ دوباره تلاش کنید.") }
        stageQueue.post { refreshCameraRecord(ticket.scope,ticket.id) };publishCameraQueue()
    } }
    private fun markCameraFailure(scope:String,id:String,message:String) {
        val record=storage.get(scope,"scan",id) ?: return
        if(record.s("state")!="capturing")return
        storage.transaction {
            storage.put(scope,"scan",id,record.put("state","capture_failed").put("error",message))
            storage.cameraWorkStatus(scope,id,"failed")
        }
    }
    private fun recoverCameraWork() {
        val owner=session ?: return
        for(job in storage.cameraWork(owner.scope)) {
            val id=job.s("id")
            when(job.s("status")) {
                "capturing"->runCatching { finalizeCameraCapture(owner.scope,id) }.onFailure { markCameraFailure(owner.scope,id,"عکس پیش از بسته‌شدن برنامه کامل نشد؛ اصل موجود محفوظ است.") }
                "processing"->{
                    val record=storage.get(owner.scope,"scan",id) ?: continue
                    storage.transaction {
                        storage.put(owner.scope,"scan",id,record.put("state","pending"))
                        storage.cameraWorkStatus(owner.scope,id,"pending")
                    }
                }
            }
        }
        cameraQueuedCount=storage.cameraWorkCount(owner.scope)
        // A process exit after the score was saved must not lose the color job.
        storage.list(owner.scope,"scan").filter { it.optBoolean("annotationPending") && it.s("state")=="ready" && it.has("offlineScore") }
            .forEach { queueCameraAnnotation(owner,it.copy()) }
        storage.list(owner.scope,"scan-trash").forEach { scheduleDeletedScanCleanup(owner.scope,it.s("id")) }
    }
    /** Stage queue owns admission/persistence; workers only run immutable native requests. */
    private fun scheduleCameraProcessing(delay:Long=0) {
        if(session==null || !cameraDrainScheduled.compareAndSet(false,true))return
        stageQueue.post(delay) {
            cameraDrainScheduled.set(false)
            val owner=session ?: return@post
            if(running.get() && !cameraProcessing) { scheduleCameraProcessing(100);return@post }
            val slots=CameraWorkPolicy.BATCH_SIZE-cameraActive.size
            if(slots<=0)return@post
            val available=packages.values.filter { it.valid }.map { it.exam.round }.toSet()
            val jobs=CameraWorkPolicy.select(storage.cameraWork(owner.scope),CameraWorkPolicy.period(currentYear),available,round,slots)
            if(jobs.isEmpty()) {
                if(cameraActive.isEmpty()) {
                    if(cameraProcessing) { cameraProcessing=false;running.set(false);progress="";notifications.post(AppEvent.PROGRESS) }
                    publishCameraQueue()
                }
                return@post
            }
            if(!cameraProcessing && !running.compareAndSet(false,true)) { scheduleCameraProcessing(100);return@post }
            cameraProcessing=true;notifications.post(AppEvent.CAPTURE)
            for(job in jobs) {
                val id=job.s("id")
                cameraActive.add(id)
                val prepared=runCatching {
                    val record=storage.get(owner.scope,"scan",id) ?: error("عکس صف پیدا نشد.")
                    storage.transaction {
                        storage.put(owner.scope,"scan",id,record.put("state","processing"))
                        storage.cameraWorkStatus(owner.scope,id,"processing")
                    }
                    refreshCameraRecord(owner.scope,id)
                    prepareRead(listOf(ScanInput(id,File(record.s("sourcePath")))),job.optInt("round").takeIf { it>0 } ?: round)
                }
                if(prepared.isFailure) { finishCameraRead(owner.scope,id,prepared.exceptionOrNull());continue }
                val read=prepared.getOrThrow()
                cameraEngines.execute {
                    read.records.single().put("recognitionStartedAtEpochMs",System.currentTimeMillis())
                    val response=runCatching { recognizeRead(read,reportProgress=false) }
                    stageQueue.post {
                        val saved=runCatching {
                            if(storage.get(owner.scope,"scan",id)==null)discardDeletedReadOutput(read)
                            else persistRead(read,response.getOrThrow())
                        }
                        finishCameraRead(owner.scope,id,saved.exceptionOrNull())
                    }
                }
            }
        }
    }
    private fun finishCameraRead(scope:String,id:String,error:Throwable?) {
        try {
            if(error!=null) {
                val record=storage.get(scope,"scan",id)
                if(record!=null && record.s("state") in setOf("pending","processing")) {
                    record.put("state","read_failed").put("error","خوانش کامل نشد؛ اصل عکس محفوظ است و می‌توانید دوباره بخوانید.")
                    storage.transaction { storage.put(scope,"scan",id,record);storage.cameraWorkStatus(scope,id,"failed") }
                    publishCameraResult(record)
                }
            }
        } finally {
            cameraActive.remove(id)
            if(storage.get(scope,"scan-trash",id)!=null)scheduleDeletedScanCleanup(scope,id)
            publishCameraQueue();refreshCameraRecord(scope,id)
            scheduleCameraProcessing()
        }
    }
    private fun publishCameraResult(record:JSONObject) {
        if(record.s("inputSource")!="camera")return
        latestCameraFeedback=LiveScanFeedback.from(record)
        notifications.post(AppEvent.SCAN_RESULT,latestCameraFeedback)
    }
    private fun uploadProgress(message:String) { progress=message;notifications.post(AppEvent.PROGRESS) }
    private data class SubmissionResult(val text:String,val stopped:Boolean)
    fun submitPending(done:(Result<String>)->Unit)=run("ارسال پاسخ‌نامه‌ها…",done) { submitPendingWork().text }
    fun sendAllScans(done:(Result<String>)->Unit)=run("ارسال همهٔ برگه‌ها…",done) {
        val owner=requireSession();require(owner.valid) { "برای ارسال با همان حساب دوباره وارد شوید." }
        if(storage.list(owner.scope,"scan").isEmpty())return@run "هنوز برگه‌ای برای ارسال اسکن نشده است."
        try {
            val answers=if(feature("batchScan") || feature("singleScan"))submitPendingWork()
                else SubmissionResult("پذیرش پاسخ‌ها در سرور غیرفعال است؛ پاسخ‌ها در گوشی محفوظ‌اند.",false)
            if(answers.stopped)return@run answers.text+"\nارسال عکس‌ها پس از برطرف‌شدن خطای اتصال یا دسترسی ادامه می‌یابد."
            val images=if(feature("originalImageUpload"))sendImagesWork() else "دریافت عکس برگه در سرور غیرفعال است؛ عکس‌ها در گوشی محفوظ‌اند."
            answers.text+"\n"+images
        } finally { reloadLocal() }
    }
    private fun submitPendingWork():SubmissionResult {
        val owner=requireSession();require(owner.valid) { "برای ارسال با همان حساب دوباره وارد شوید." }
        require(feature("batchScan") || feature("singleScan")) { "پذیرش پاسخ‌نامه در سرور فعال نیست؛ اصل برگه‌ها و پاسخ‌های شما محفوظ‌اند." }
        var preparedErrors=0;var reviewBlocked=0
        try {
            val ready=storage.list(owner.scope,"scan").filter { !it.optBoolean("sealed") && it.s("state")=="ready" }
            ready.forEachIndexed { index,scan ->
                uploadProgress("آماده‌سازی برگهٔ ${index+1} از ${ready.size}…")
                if(ScanReview.pendingCount(scan)>0) { reviewBlocked++;return@forEachIndexed }
                runCatching { sealScan(scan.copy().apply { remove("submissionError") }) }.onFailure {
                    preparedErrors++
                    storage.put(owner.scope,"scan",scan.s("id"),scan.copy().put("submissionError",it.message ?: "آماده‌سازی این برگه کامل نشد."))
                }
            }
            val scoped=storage.queue(owner.scope,"scan",includeRequests=false)
            val result=ScanQueueSender(network).send(owner,scoped,feature("batchScan"),feature("singleScan"),object:ScanOutbox {
                override fun load(id:String)=storage.queue(owner.scope,"scan",includeRequests=true,id=id).singleOrNull()
                override fun validate(item:JSONObject)=validateQueued(item)
                override fun sending(id:String) { storage.transaction { storage.status(owner.scope,id,"sending");storage.bumpAttempt(owner.scope,id) } }
                override fun delivered(id:String,receipt:JSONObject) { storage.status(owner.scope,id,"delivered",receipt=receipt) }
                override fun failed(id:String,failure:DeliveryFailure) {
                    storage.status(owner.scope,id,if(failure.retry)"retry_wait" else "rejected",failure.message,failure.retryAt)
                }
            }) { completed,total -> uploadProgress("ارسال پاسخ‌ها: $completed از $total برگه…") }
            return SubmissionResult("${result.sent} پاسخ‌نامه ارسال شد؛ ${result.remaining} پاسخ‌نامه نیاز به تلاش دوباره یا بررسی دارد."+
                (if(result.deferred>0)" ${result.deferred} پاسخ‌نامه در انتظار زمان تلاش بعدی است." else "")+
                (if(reviewBlocked>0)"\nپاسخ‌های $reviewBlocked برگه ارسال نشد: پاسخ‌های کم‌اطمینان را در بازبینی تأیید کنید. اصل عکس‌ها جداگانه قابل ارسال‌اند." else "")+
                (if(preparedErrors>0)" $preparedErrors پاسخ‌نامه آمادهٔ ارسال نشد؛ اصل آن‌ها محفوظ است." else ""),result.stopped)
        } finally { reloadLocal() }
    }
    private fun validateQueued(item:JSONObject) {
        val owner=requireSession();val request=item.o("request");val payload=ScannerCrypto.decode(request.s("payloadBase64"));val school=item.o("metadata").s("schoolId")
        require(school in owner.schools && sha256(payload)==request.s("inputSha256")) { "محتوای صف با اثر انگشت آن مطابقت ندارد." }
        val current=device(school)
        val keys=if(request.s("deviceUuid")==current.deviceUuid)current.keys else {
            val alias=item.o("metadata").s("alias");require(alias.startsWith("roshd.scanner."));wrap(native.blocking("p256Identity",mapOf("alias" to alias))) as JSONObject
        }
        val kind=item.s("kind");require(kind=="scan" && request.s("schemaVersion")=="roshd.scan.v1");val id=uuid(request.s("scanId"))
        require(ScannerCrypto.verify(keys.s("signingPublicKeySpki"),listOf("roshd.$kind.v1",school,owner.accountId,request.s("deviceUuid"),id,request.s("inputSha256")).joinToString("\n").toByteArray(),request.s("signatureBase64"))) { "امضای درخواست محفوظ معتبر نیست." }
    }
    fun processFiles(files:List<File>,done:(Result<String>)->Unit)=processFiles(files,null,0,done)
    fun processFiles(files:List<File>,corners:List<Double>?,turns:Int,done:(Result<String>)->Unit)=run("خوانش پاسخ‌نامه‌ها…",done) {
        val owner=requireSession();require(files.isNotEmpty())
        require(corners==null || (files.size==1 && com.haseebullahjan.youngscientist.scanner.DocumentQuad.valid(corners))) { "گوشه‌های برگه معتبر نیست." }
        val originals=files.map { source ->
            val file=storage.stage(owner.scope,source)
            storage.put(owner.scope,"archive",file.nameWithoutExtension,json("id" to file.nameWithoutExtension,"name" to source.name,"sourcePath" to file.path,"sha256" to EvidenceArchive.hash(file),"createdAt" to now(),"mimeType" to if(source.extension.equals("pdf",true))"application/pdf" else if(source.extension.equals("png",true))"image/png" else "image/jpeg"))
            file
        }
        val images=mutableListOf<File>()
        for(file in originals) {
            if(file.extension.equals("pdf",true)) {
                val output=storage.mediaFile(owner.scope,"render-${UUID.randomUUID()}").apply { mkdirs() }
                @Suppress("UNCHECKED_CAST") val rendered=native.blocking("rasterizeDocument",mapOf("inputPath" to file.path,"mimeType" to "application/pdf","outputDirectory" to output.path,"maxDimension" to 2600)) as List<String>
                images.addAll(rendered.map(::File))
            } else images.add(file)
        }
        val staged=images.map { if(it in originals)it else storage.stage(owner.scope,it) }
        staged.forEach { file -> storage.put(owner.scope,"scan",file.nameWithoutExtension,json("id" to file.nameWithoutExtension,"sourcePath" to file.path,"createdAt" to now(),"state" to "pending","sourceImageSha256" to EvidenceArchive.hash(file)).apply {
            if(corners!=null)put("documentCorners",wrap(corners)).put("documentTurns",turns)
        }) }
        reloadLocal()
        processStaged(staged.map { ScanInput(it.nameWithoutExtension,it) })
        reloadLocal();"${staged.size} برگه خوانده شد؛ نتیجه‌ها در تاریخچه محفوظ‌اند."
    }
    private data class ScanInput(val id:String,val file:File)
    private data class PreparedRead(val owner:Session,val inputs:List<ScanInput>,val records:List<JSONObject>,
                                    val routing:OmrRosterIndex,val request:JSONObject,val progressId:String)
    private fun processStaged(inputs:List<ScanInput>,processingRound:Int=round) {
        val read=prepareRead(inputs,processingRound)
        persistRead(read,recognizeRead(read,reportProgress=true))
    }
    private fun prepareRead(inputs:List<ScanInput>,processingRound:Int):PreparedRead {
        val owner=requireSession()
        require(inputs.isNotEmpty() && inputs.map { it.id }.distinct().size==inputs.size)
        val records=inputs.map { input ->
            val record=storage.get(owner.scope,"scan",input.id) ?: error("عکس محفوظ پیدا نشد.")
            require(!record.optBoolean("sealed")) { "درخواست امضاشده قابل تغییر نیست." }
            if(record.s("inputSource")=="camera")require(record.optInt("captureRound")==processingRound && record.s("capturePeriod")==CameraWorkPolicy.period(currentYear)) {
                "این عکس به دور یا سال انتخاب‌شده تعلق ندارد؛ اصل آن محفوظ است."
            }
            require(input.file.isFile && input.file.canonicalPath==File(record.s("sourcePath")).canonicalPath) { "عکس اصلی در دسترس نیست؛ سابقهٔ برگه محفوظ است." }
            val hash=EvidenceArchive.hash(input.file)
            require(record.s("sourceImageSha256").let { it.isEmpty() || it==hash }) { "اثر انگشت عکس اصلی تغییر کرده است؛ سابقهٔ برگه محفوظ است." }
            record.put("sourceImageSha256",hash)
        }
        val selected=records.map { it.optInt("captureQuestionCount",0) }.distinct().singleOrNull() ?: 0
        require(records.all { it.optInt("captureQuestionCount",0).let { count -> count==0 || count in SheetFamilies.scanCounts } }) { "نوع برگهٔ محفوظ معتبر نیست." }
        val selectedTemplates=SheetFamilies.templatesForSelection(selected)
        val templates=nativeTemplatePaths().filter { File(it).name in selectedTemplates }
        val output=storage.mediaFile(owner.scope,"engine-${UUID.randomUUID()}").apply { mkdirs() }
        val routing=rosterIndex?.takeIf { routingPackages===packages && it.round==processingRound && it.current }
            ?: OmrRosterIndex(packages.values,processingRound).also { rosterIndex=it;routingPackages=packages }
        val progressId=UUID.randomUUID().toString()
        // Camera pages already carry their selected count. Avoid serializing
        // every printed 75Q roster row for each photo; imports need that map.
        val bindings=if(records.any { it.optInt("captureQuestionCount",0)==0 })routing.recognitionBindings else routing.historicalBindings
        val maxDimension=2000
        val request=json("templateJsonPaths" to templates,"outputDirectory" to output.path,"returnDebugImages" to false,"returnNormalizedImages" to true,"returnAnnotationGeometry" to true,"compactResult" to false,"includeQuestions" to true,"maxInputDimension" to maxDimension,"preferCalibratedHeader" to true,"progressId" to progressId,
            "qrBindings" to bindings)
        return PreparedRead(owner,inputs,records,routing,request,progressId)
    }
    private fun recognizeRead(read:PreparedRead,reportProgress:Boolean):JSONObject {
        if(!reportProgress && read.inputs.size==1 && CameraNormalizationPolicy.eligible(read.records.single())) {
            try {
                val result=com.haseebullahjan.roshd.platform.CameraDocumentReader.read(read.inputs.single().file,read.records.single(),read.request)
                val timing=result.o("stageTimings")
                android.util.Log.i("RoshdScanTiming","pages=1 document_ms=${timing.optLong("documentMs")} recognition_ms=${timing.optLong("recognitionMs")}")
                return result
            } finally { OmrNative.releaseProgress(read.progressId) }
        }
        val readStarted=android.os.SystemClock.elapsedRealtime()
        // Inspect/decode unusual encodings on the image workers, never the queue
        // that admits the next photo or persists another page's completed score.
        val output=File(read.request.s("outputDirectory"))
        val prepared=read.inputs.mapIndexed { index,input ->
            val record=read.records[index]
            val manual=record.optJSONArray("documentCorners")?.let { a -> (0 until a.length()).map { a.getDouble(it) } }
            com.haseebullahjan.youngscientist.scanner.DocumentScanner.prepareForOmr(input.file,File(output,"prepared-${index+1}"),manual,record.optInt("documentTurns"))
        }
        val preparedAt=android.os.SystemClock.elapsedRealtime()
        val request=read.request.copy().put("pages",JSONArray(prepared.mapIndexed { index,page ->
            json("pageNumber" to index+1,"imagePath" to page.path,"activeQuestionCount" to read.records[index].optInt("captureQuestionCount",0))
        }))
        val timer=if(reportProgress)java.util.Timer("roshd-omr-progress",true) else null
        timer?.schedule(object:java.util.TimerTask() { override fun run() {
            runCatching { OmrNative.progress(read.progressId)?.let { raw ->
                val pages=JSONObject(raw).a("pages").objects();val done=pages.count { it.s("state") in setOf("done","failed") }
                progress="خوانش پاسخ‌نامه‌ها: $done از ${read.inputs.size}";notifications.post(AppEvent.PROGRESS)
            } }
        } },100,200)
        return try {
            val result=JSONObject(OmrNative.processJson(request.toString()))
            val sheets=result.a("sheets")
            for(index in prepared.indices) {
                val first=sheets.optJSONObject(index) ?: continue
                val page=prepared[index]
                // Match the source app: automatic crops may retry the upright
                // original once. An explicit manual crop never silently reverts.
                if(!first.optBoolean("success") && page.rectified && !read.records[index].has("documentCorners")) {
                    val original=com.haseebullahjan.youngscientist.DocumentImagePreparation.prepareForHeader(read.inputs[index].file,File(output,"original-${index+1}"),2000)
                    val retry=read.request.copy().put("pages",wrap(listOf(json("pageNumber" to 1,"imagePath" to original,"activeQuestionCount" to read.records[index].optInt("captureQuestionCount",0)))))
                    val replacement=JSONObject(OmrNative.processJson(retry.toString())).a("sheets").optJSONObject(0)
                    if(replacement!=null)sheets.put(index,replacement.put("page",index+1))
                    page.metadata.put("originalFallback",true)
                }
                sheets.optJSONObject(index)?.put("documentScanner",page.metadata)
            }
            val finishedAt=android.os.SystemClock.elapsedRealtime()
            result.put("stageTimings",json("documentMs" to preparedAt-readStarted,"recognitionMs" to finishedAt-preparedAt))
            android.util.Log.i("RoshdScanTiming","pages=${read.inputs.size} document_ms=${preparedAt-readStarted} recognition_ms=${finishedAt-preparedAt}")
            result
        }
            finally {
                timer?.cancel();OmrNative.releaseProgress(read.progressId)
                // Like the source app, recognition copies are temporary. Keep
                // native normalized images for Roshd's colored-sheet feature.
                for(index in read.inputs.indices) {
                    File(output,"prepared-${index+1}").deleteRecursively()
                    File(output,"original-${index+1}").deleteRecursively()
                }
            }
    }
    private fun persistRead(read:PreparedRead,response:JSONObject) {
        val (owner,inputs,records,routing)=read
        val sheets=response.a("sheets").objects()
        require(sheets.size==inputs.size && sheets.map { it.optInt("page") }.toSet()==(1..inputs.size).toSet()) { "تعداد نتیجه‌های موتور با عکس‌ها مطابق نیست؛ عکس‌ها محفوظ‌اند." }
        for(sheet in sheets) {
            val index=sheet.getInt("page")-1;val id=inputs[index].id
            // A camera worker may finish after local deletion. Never recreate its snapshot.
            if(storage.get(owner.scope,"scan",id)==null) {
                discardDeletedReadOutput(read)
                continue
            }
            val record=ScanReview.freshRead(records[index])
            val scoreStarted=android.os.SystemClock.elapsedRealtime()
            val qr=OmrResultParser.qr(sheet)
            val matches=routing.matches(qr,record)
            record.put("engine",sheet).put("qrText",qr)
            val detectedFamily=sheet.o("sheetType").s("code")
            if(detectedFamily in setOf("T1","T3") && !SheetFamilies.matchesSelection(record.optInt("captureQuestionCount",0),detectedFamily)) {
                record.put("state","read_failed").put("error","برگه با انتخاب ${record.optInt("captureQuestionCount")}سؤالی مطابق نیست؛ نوع برگه را بررسی کنید.")
            } else if(qr.startsWith("T2:") || sheet.s("status")=="UNSUPPORTED_SHEET_TYPE" || matches.any { it.pack.exam.template.isEmpty() }) {
                record.put("state","unsupported_sheet").put("error","این نوع برگه پشتیبانی نمی‌شود؛ فقط برگه‌های ۴۰، ۷۵ و ۱۰۰سؤاله را اسکن کنید.")
            } else if(sheet.s("error") in setOf("printed_answer_grid_mismatch","printed_answer_layout_unconfirmed")) {
                record.put("state","read_failed").put("error","چیدمان گزینه‌ها با برگهٔ فعلی مطابق نیست؛ از برگهٔ جدید استفاده کنید و عکس واضح بگیرید.")
            } else if(matches.size!=1) {
                record.put("state","identity_required").put("error",if(matches.isEmpty())"QR برگه در بستهٔ معتبر دور جاری پیدا نشد." else "QR برگه چند هویت متفاوت دارد.")
            } else if(!SheetFamilies.matchesExamSelection(record.optInt("captureQuestionCount",0),matches.single().pack.exam.count) || sheet.s("error")=="selected_question_count_mismatch") {
                record.put("state","read_failed").put("error","تعداد سؤال انتخاب‌شده با امتحان این برگه مطابق نیست؛ نوع برگه را دوباره انتخاب کنید.")
            } else {
                val (pack,row)=matches.single()
                record.put("schoolId",pack.exam.schoolId).put("examId",pack.exam.id).put("exam",pack.exam.raw).put("studentPublicId",row.code).put("studentName",row.name).put("clientSheetId",row.sheetId)
                val ordered=runCatching {
                    val type=sheet.o("sheetType")
                    require(sheet.optBoolean("success") && type.s("code")==pack.exam.template && type.optBoolean("detected") && type.s("status")=="DETECTED")
                    OmrResultParser.observations(sheet,pack.exam.count)
                }.getOrDefault(emptyList())
                val complete=ordered.map { it.optInt("questionNumber") }==(1..pack.exam.count).toList()
                record.put("answers",JSONArray(ordered)).put("answerPolicyVersion",ScanReview.ANSWER_POLICY_VERSION).put("state",if(complete)"ready" else "read_failed").put("error",if(complete)JSONObject.NULL else "خوانش تمام سؤال‌ها کامل نشد.")
                ScanReview.updateWarning(record)
                if(complete)runCatching { crypto.offlineCount(owner.scope(pack.exam.schoolId),device(pack.exam.schoolId),pack,ordered.map { json("state" to it.s("state"),"selectedOption" to it.opt("selectedOption")) }) }.onSuccess { if(it!=null)record.put("offlineScore",temporaryScore(it,record.a("answers"))) else record.put("scoreWarning","کلید نمره‌دهی آفلاین این امتحان موجود نیست؛ معلومات امتحان را دوباره دریافت کنید.") }.onFailure { record.put("scoreWarning","نمرهٔ موقت آماده نشد؛ نتیجهٔ خوانش محفوظ است.") }
            }
            val deferredColor=record.s("inputSource")=="camera" && record.s("state")=="ready" && record.has("offlineScore")
            if(deferredColor)record.put("annotationPending",true)
            else if(record.s("state")=="ready" && record.has("offlineScore"))renderColoredSheet(owner,record,record.o("offlineScore"))
            else record.put("annotationWarning","برای رنگ‌کردن برگه، خوانش کامل و کلید معتبر نمره‌دهی لازم است.")
            val resultAt=System.currentTimeMillis()
            val captureAt=record.optLong("shutterAtEpochMs").takeIf { it>0 } ?: record.optLong("capturedAtEpochMs")
            if(captureAt>0)record.put("captureToResultMs",(resultAt-captureAt).coerceAtLeast(0))
            val timing=response.o("stageTimings").copy().put("scoringAndRoutingMs",android.os.SystemClock.elapsedRealtime()-scoreStarted)
            record.optLong("recognitionStartedAtEpochMs").takeIf { it>0 }?.let { begun ->
                timing.put("captureAndQueueMs",(begun-captureAt).coerceAtLeast(0))
            }
            record.put("scanTimings",timing)
            if(record.s("inputSource")=="camera")android.util.Log.i("RoshdScanTiming",
                "capture_to_result_ms=${record.optLong("captureToResultMs")} capture_queue_ms=${timing.optLong("captureAndQueueMs")} document_ms=${timing.optLong("documentMs")} recognition_ms=${timing.optLong("recognitionMs")} scoring_routing_ms=${timing.optLong("scoringAndRoutingMs")} attempts=${timing.optInt("recognitionAttempts")}")
            storage.transaction {
                storage.put(owner.scope,"scan",id,record)
                syncColoredArchive(owner.scope,record)
                storage.cameraWorkStatus(owner.scope,id,"done")
            }
            publishCameraResult(record)
            if(deferredColor)queueCameraAnnotation(owner,record.copy())
        }
    }
    private fun discardDeletedReadOutput(read:PreparedRead) {
        if(read.inputs.size!=1)return
        val output=File(read.request.s("outputDirectory")).canonicalFile
        val media=storage.mediaFile(read.owner.scope,"delete-check").parentFile!!.canonicalFile
        if(output.path.startsWith(media.path+File.separator))output.deleteRecursively()
    }
    private fun queueCameraAnnotation(owner:Session,snapshot:JSONObject) {
        cameraAnnotations.execute {
            runCatching { renderColoredSheet(owner,snapshot,snapshot.o("offlineScore")) }.onFailure {
                snapshot.put("annotationWarning","نسخهٔ رنگی ساخته نشد؛ اصل عکس و نمره محفوظ‌اند. دوباره تلاش کنید.")
            }
            stageQueue.post { persistCameraAnnotation(owner,snapshot) }
        }
    }
    private fun persistCameraAnnotation(owner:Session,snapshot:JSONObject) {
        val id=snapshot.s("id")
        val current=storage.get(owner.scope,"scan",id)
        if(current==null || !GradedSheet.acceptsDerivedImage(current,snapshot)) {
            if(current==null) {
                val journal=storage.get(owner.scope,"scan-trash",id) ?: json("id" to id,"deletedAt" to now())
                storage.put(owner.scope,"scan-trash",id,journal.put("paths",wrap((journal.a("paths").strings()+scanFilePaths(snapshot)).filter { it.isNotBlank() }.distinct())))
                scheduleDeletedScanCleanup(owner.scope,id)
            } else snapshot.optJSONObject("annotation")?.s("path")?.takeIf { it.isNotBlank() }?.let { File(it).delete() }
            return
        }
        current.remove("annotationPending")
        snapshot.s("annotationBasePath").takeIf { it.isNotBlank() }?.let { current.put("annotationBasePath",it) }
        if(snapshot.o("engine").o("annotationGeometry").optInt("width")>0)current.put("engine",current.o("engine").copy()
            .put("annotationGeometry",snapshot.o("engine").o("annotationGeometry")).put("normalizedImagePath",snapshot.o("engine").s("normalizedImagePath")))
        snapshot.optJSONObject("annotation")?.let { current.put("annotation",it);current.remove("annotationWarning") }
            ?: current.put("annotationWarning",snapshot.s("annotationWarning"))
        storage.transaction { storage.put(owner.scope,"scan",id,current);syncColoredArchive(owner.scope,current) }
        refreshCameraRecord(owner.scope,id)
        if(session?.scope==owner.scope && latestCameraFeedback?.id==id)publishCameraResult(current)
    }
    fun retryScan(id:String,done:(Result<String>)->Unit)=run("خوانش دوباره…",done) {
        val record=storage.get(requireSession().scope,"scan",id) ?: error("برگه پیدا نشد.")
        require(!record.optBoolean("sealed")) { "درخواست امضاشده قابل تغییر نیست." }
        processStaged(listOf(ScanInput(id,File(record.s("sourcePath")))),record.optInt("captureRound").takeIf { it>0 } ?: round);reloadLocal();"خوانش دوباره تمام شد."
    }
    fun setDocumentCrop(id:String,corners:List<Double>,turns:Int,done:(Result<String>)->Unit)=run("تنظیم برگه و خوانش دوباره…",done) {
        val owner=requireSession();val record=storage.get(owner.scope,"scan",id) ?: error("برگه پیدا نشد.")
        require(!record.optBoolean("sealed") && record.s("state") !in setOf("capturing","processing")) { "این برگه اکنون قابل تغییر نیست." }
        require(com.haseebullahjan.youngscientist.scanner.DocumentQuad.valid(corners) && turns in 0..3) { "گوشه‌های برگه معتبر نیست." }
        val fresh=ScanReview.freshRead(record).put("documentCorners",wrap(corners)).put("documentTurns",turns)
        storage.put(owner.scope,"scan",id,fresh)
        processStaged(listOf(ScanInput(id,File(fresh.s("sourcePath")))),fresh.optInt("captureRound").takeIf { it>0 } ?: round)
        reloadLocal();"گوشه‌ها ذخیره و برگه دوباره خوانده شد."
    }
    fun editAnswer(id:String,number:Int,option:Int?,done:(Result<String>)->Unit)=run("ذخیرهٔ بازبینی…",done) {
        val owner=requireSession();val record=storage.get(owner.scope,"scan",id) ?: error("برگه پیدا نشد.")
        require(!record.optBoolean("sealed") && option in listOf(null,1,2,3,4)) { "نتیجهٔ امضاشده قابل تغییر نیست." }
        val answer=record.a("answers").objects().single { it.optInt("questionNumber")==number }
        ScanReview.confirmAnswer(answer,option)
        ScanReview.updateWarning(record)
        record.remove("submissionError")
        record.put("reviewedAt",now()).remove("offlineScore");record.remove("scoreWarning");GradedSheet.invalidate(record)
        storage.transaction { storage.put(owner.scope,"scan",id,record);syncColoredArchive(owner.scope,record) }
        reloadLocal();"بازبینی ذخیره شد؛ برای تازه‌شدن رنگ‌ها نمره را دوباره محاسبه کنید."
    }
    fun recalculateScore(id:String,done:(Result<String>)->Unit)=run("محاسبهٔ نمرهٔ موقت…",done) {
        val owner=requireSession();val record=storage.get(owner.scope,"scan",id) ?: error("برگه پیدا نشد.")
        require(!record.optBoolean("sealed")) { "درخواست امضاشده قابل تغییر نیست." }
        record.remove("offlineScore");record.remove("scoreWarning");GradedSheet.invalidate(record)
        val result=runCatching { calculateOfflineScore(owner,record) }
        result.onSuccess { record.put("offlineScore",it) }.onFailure { record.put("scoreWarning",it.message ?: "نمرهٔ موقت آماده نشد؛ پاسخ‌های بازبینی‌شده محفوظ‌اند.") }
        if(result.isSuccess)renderColoredSheet(owner,record,record.o("offlineScore"))
        storage.transaction { storage.put(owner.scope,"scan",id,record);syncColoredArchive(owner.scope,record) };reloadLocal()
        result.getOrThrow();"نمرهٔ موقت از پاسخ‌های بازبینی‌شده محاسبه شد."
    }
    private fun calculateOfflineScore(owner:Session,record:JSONObject):JSONObject {
        val pack=packages["${record.s("schoolId")}/${record.s("examId")}"] ?: error("بستهٔ معتبر این برگه را دوباره دریافت کنید.")
        require(pack.valid && record.s("state")=="ready") { "بستهٔ معتبر و خوانش کامل برگه لازم است." }
        require(pack.roster.any { it.sheetQr==record.s("qrText") && it.code==record.s("studentPublicId") && ScanReview.matchesFrozenSource(record,pack,it) }) { "هویت برگه تأیید نشد." }
        val score=crypto.offlineCount(owner.scope(pack.exam.schoolId),device(pack.exam.schoolId),pack,ScanReview.observations(record,pack.exam.count))
            ?: error("نمره‌دهی آفلاین برای این بسته فعال نیست.")
        return temporaryScore(score,record.a("answers"))
    }
    private fun temporaryScore(value:JSONObject,answers:JSONArray)=value.copy().apply {
        if(!feature("offlineScore"))remove("rawScore")
        put("answersSha256",sha256(canonical(answers))).put("answerPolicyVersion",ScanReview.ANSWER_POLICY_VERSION)
    }
    private fun colorGeometry(owner:Session,record:JSONObject):JSONObject {
        val old=record.o("engine")
        if(old.optBoolean("success") && old.o("annotationGeometry").optInt("width")>0 && File(old.s("normalizedImagePath")).isFile)return old
        val source=EvidenceArchive.restore(File(record.s("sourcePath")),storage.mediaFile(owner.scope,"${record.s("id")}-restored.jpg"),record.s("sourceImageSha256"))
        require(source.isFile && EvidenceArchive.hash(source)==record.s("sourceImageSha256")) { "اصل عکس تغییر کرده یا موجود نیست." }
        val output=storage.mediaFile(owner.scope,"color-base-${UUID.randomUUID()}").apply { mkdirs() }
        val manual=record.optJSONArray("documentCorners")?.let { a -> (0 until a.length()).map { a.getDouble(it) } }
        val prepared=com.haseebullahjan.youngscientist.scanner.DocumentScanner.prepareForOmr(source,File(output,"prepared"),manual,record.optInt("documentTurns"))
        val selectedRound=record.optInt("captureRound").takeIf { it>0 } ?: record.o("exam").optInt("round",round)
        val routing=OmrRosterIndex(packages.values,selectedRound)
        val request=json("templateJsonPaths" to nativeTemplatePaths(),"outputDirectory" to output.path,
            "returnNormalizedImages" to true,"returnAnnotationGeometry" to true,"includeQuestions" to true,"maxInputDimension" to 2000,
            "qrBindings" to routing.historicalBindings,"pages" to listOf(json("pageNumber" to 1,"imagePath" to prepared.path)))
        var result=JSONObject(OmrNative.processJson(request.toString())).a("sheets").objects().single()
        if(!result.optBoolean("success") && prepared.rectified && manual==null) {
            val original=com.haseebullahjan.youngscientist.DocumentImagePreparation.prepareForHeader(source,File(output,"original"),2000)
            request.put("pages",wrap(listOf(json("pageNumber" to 1,"imagePath" to original))))
            result=JSONObject(OmrNative.processJson(request.toString())).a("sheets").objects().single()
        }
        return result.also {
            require(it.optBoolean("success") && OmrResultParser.qr(it)==record.s("qrText") &&
                it.o("sheetType").s("code")==Exam(record.o("exam")).template) { "هویت عکس برای رنگ‌کردن تأیید نشد." }
            record.put("annotationBasePath",it.s("normalizedImagePath"))
            record.put("engine",old.copy().put("annotationGeometry",it.o("annotationGeometry")).put("normalizedImagePath",it.s("normalizedImagePath")))
        }
    }
    private fun renderColoredSheet(owner:Session,record:JSONObject,score:JSONObject) {
        GradedSheet.invalidate(record)
        val output=storage.mediaFile(owner.scope,"graded-${record.s("id")}-${UUID.randomUUID()}.jpg")
        runCatching {
            val sheet=colorGeometry(owner,record)
            require(OmrResultParser.qr(sheet)==record.s("qrText") && sheet.o("sheetType").s("code")==Exam(record.o("exam")).template)
            val pack=packages["${record.s("schoolId")}/${record.s("examId")}"] ?: error("کلید معتبر این امتحان موجود نیست.")
            require(pack.valid && pack.roster.any { it.sheetQr==record.s("qrText") && it.code==record.s("studentPublicId") && ScanReview.matchesFrozenSource(record,pack,it) })
            val rendered=crypto.offlineCount(owner.scope(pack.exam.schoolId),device(pack.exam.schoolId),pack,
                ScanReview.observations(record,pack.exam.count),GradedSheet.nativeRequest(record,sheet,output.path,feature("offlineScore"))) ?: error("کلید معتبر نمره‌دهی موجود نیست.")
            val result=rendered.getJSONObject("annotationResult");rendered.remove("annotationResult")
            require(canonical(temporaryScore(rendered,record.a("answers")))==canonical(score)) { "کلید نمره‌دهی تغییر کرده؛ نمره را دوباره محاسبه کنید." }
            require(result.optBoolean("success") && result.s("outputPath")==output.path && CameraEvidence.complete(output))
            val metadata=GradedSheet.metadata(record,score,output.path,result).put("sha256",EvidenceArchive.hash(output))
            record.put("annotation",metadata)
        }.onFailure {
            output.delete();record.put("annotationWarning","نسخهٔ رنگی ساخته نشد؛ اصل عکس و نمره محفوظ‌اند. دوباره تلاش کنید.")
        }
    }
    private fun syncColoredArchive(scope:String,record:JSONObject) {
        val id="graded:${record.s("id")}";val annotation=GradedSheet.current(record)
        if(annotation==null || archivePhotos.removed(scope,id)) { storage.delete(scope,"archive",id);return }
        storage.put(scope,"archive",id,json("id" to id,"name" to "برگهٔ تصحیح‌شدهٔ ${record.s("studentName",record.s("studentPublicId"))}",
            "sourcePath" to annotation.s("path"),"mimeType" to "image/jpeg","createdAt" to annotation.s("createdAt"),
            "sha256" to annotation.s("sha256"),"sourceScanRecordId" to record.s("id"),"variant" to "graded"))
    }
    fun createColoredSheet(id:String,done:(Result<String>)->Unit)=run("ساخت برگهٔ رنگی…",done) {
        val owner=requireSession();val record=storage.get(owner.scope,"scan",id) ?: error("برگه پیدا نشد.")
        val result=runCatching {
            val pack=packages["${record.s("schoolId")}/${record.s("examId")}"] ?: error("معلومات معتبر این امتحان را دریافت کنید.")
            require(pack.valid && record.s("state")=="ready" && pack.roster.any { it.sheetQr==record.s("qrText") && it.code==record.s("studentPublicId") && ScanReview.matchesFrozenSource(record,pack,it) }) { "هویت و بستهٔ معتبر برگه لازم است." }
            val score=crypto.offlineCount(owner.scope(pack.exam.schoolId),device(pack.exam.schoolId),pack,ScanReview.observations(record,pack.exam.count))
                ?: error("کلید معتبر نمره‌دهی آفلاین موجود نیست.")
            val current=temporaryScore(score,record.a("answers"))
            if(!record.optBoolean("sealed"))record.put("offlineScore",current)
            renderColoredSheet(owner,record,current)
            require(GradedSheet.current(record)!=null) { "نسخهٔ رنگی آماده نشد؛ اصل عکس محفوظ است." }
        }.onFailure { record.put("annotationWarning",it.message ?: "نسخهٔ رنگی آماده نشد؛ اصل عکس محفوظ است.") }
        storage.transaction { storage.put(owner.scope,"scan",id,record);syncColoredArchive(owner.scope,record) };reloadLocal()
        result.getOrThrow();"برگهٔ رنگی محفوظ شد."
    }
    private fun sourceFor(record:JSONObject):JSONObject {
        if(record.optBoolean("sealed")) {
            val source=record.optJSONObject("sourceSnapshot") ?: error("مشخصات عکس درخواست امضاشده موجود نیست.")
            UploadSheet.sourcePath(record,source)
            require(source.s("qrText")==record.s("qrText") && source.s("capturedAt")==record.s("createdAt"))
            return source.copy()
        }
        val key="${record.s("schoolId")}/${record.s("examId")}";val pack=packages[key] ?: error("برای این برگه بستهٔ معتبر لازم است.")
        require(pack.valid)
        val row=pack.roster.singleOrNull { it.sheetQr==record.s("qrText") && it.code==record.s("studentPublicId") } ?: error("هویت برگه تأیید نشد.")
        var annotation=GradedSheet.current(record)
        if(annotation?.optInt("colorPolicyVersion")!=GradedSheet.COLOR_POLICY_VERSION || GradedSheet.image(record)==null || !GradedSheet.matchesKey(record,pack)) {
            record.put("offlineScore",calculateOfflineScore(requireSession(),record))
            renderColoredSheet(requireSession(),record,record.o("offlineScore"))
            annotation=GradedSheet.current(record)
        }
        require(annotation?.optInt("colorPolicyVersion")==GradedSheet.COLOR_POLICY_VERSION) { "نسخهٔ صاف‌شده و تصحیح‌شده آماده نشد؛ کلید امتحان و خوانش برگه را بررسی کنید." }
        val evidence=UploadSheet.bind(record)
        record.optJSONObject("sourceSnapshot")?.let { source ->
            require(source.s("sourceImageSha256")==evidence.s("sha256") && source.s("qrText")==record.s("qrText") && source.s("capturedAt")==record.s("createdAt"))
            return source.copy()
        }
        return json("identityMode" to "qr-lite-v1","examId" to pack.exam.id,"editionId" to pack.exam.raw.s("editionId"),"round" to pack.exam.round,"grade" to pack.exam.grade,
            "packageId" to pack.manifest.s("packageId"),"packageSha256" to pack.envelope.s("payloadSha256"),"packageSignatureBase64" to pack.envelope.s("signatureBase64",pack.envelope.o("signature").s("value")),"clientSheetId" to row.sheetId,"studentPublicId" to row.code,"qrText" to row.sheetQr,"sheetAlias" to row.alias,
            "capturedAt" to record.s("createdAt"),"sourceImageSha256" to evidence.s("sha256"),"sourcePageIndex" to 0)
    }
    private fun sealScan(record:JSONObject) {
        val owner=requireSession();val key="${record.s("schoolId")}/${record.s("examId")}";val pack=packages[key] ?: error("بستهٔ این برگه موجود نیست.")
        require(record.a("answers").length()==pack.exam.count && record.s("state")=="ready")
        ScanReview.requireReviewed(record)
        val source=sourceFor(record)
        val answers=record.a("answers").objects().map { answer -> json("questionNumber" to answer.optInt("questionNumber"),"state" to answer.s("state"),"selectedOption" to answer.opt("selectedOption")).apply { if(answer.s("state")=="multiple")put("markedOptions",answer.a("markedOptions")) } }
        val payload=source.copy().put("schemaVersion","roshd.scan-payload.v2").put("appVersion","${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}").put("questionCount",pack.exam.count).put("answers",wrap(answers))
        val local=device(pack.exam.schoolId)
        val capture=storage.get(owner.scope,"image",record.s("scanId"))?.optJSONObject("captureRequest")
        if(capture!=null)require(capture.s("deviceUuid")==local.deviceUuid) { "ثبت عکس به هویت قبلی دستگاه تعلق دارد؛ اصل معلومات محفوظ است." }
        val packet=crypto.packet(owner.scope(pack.exam.schoolId),local,"scan",payload,record.s("scanId").ifEmpty { UUID.randomUUID().toString() })
        storage.transaction {
            val added=storage.enqueue(owner.scope,"scan",packet.s("scanId"),"${pack.exam.key}/${source.s("clientSheetId")}",packet,record.copy().put("alias",local.alias).put("deviceKeyId",local.keyId))
            require(added) { "برای این برگه قبلاً درخواست محفوظ وجود دارد." }
            record.put("sealed",true).put("scanId",packet.s("scanId")).put("sourceSnapshot",source);storage.put(owner.scope,"scan",record.s("id"),record)
        }
    }
    fun sendImages(done:(Result<String>)->Unit)=run("ارسال برگه‌های صاف‌شده و تصحیح‌شده…",done) {
        try { sendImagesWork() } finally { reloadLocal() }
    }
    fun sendImage(id:String,done:(Result<String>)->Unit)=run("ادامهٔ ارسال برگهٔ تصحیح‌شده…",done) {
        try { sendImagesWork(setOf(id)) } finally { reloadLocal() }
    }
    private fun sendImagesWork(only:Set<String>?=null):String {
        val owner=requireSession();require(owner.valid) { "برای ارسال با همان حساب دوباره وارد شوید." }
        require(feature("originalImageUpload")) { "قابلیت ارسال عکس برگه در سرور فعال نیست؛ عکس‌ها محفوظ‌اند." }
        var firstError=""
        val scanStatus=storage.queue(owner.scope,"scan",includeRequests=false).associate { it.s("id") to it.s("status") }
        val records=storage.list(owner.scope,"scan").filter { only==null || it.s("id") in only }
        val identified=records.filter { it.s("examId").isNotEmpty() }
        val transferVersion=network.cancellationVersion
        var stopped=false
        for((index,record) in identified.withIndex()) {
            uploadProgress("ارسال برگه‌های تصحیح‌شده: ${index+1} از ${identified.size} برگه…")
            var retries=0
            while(true) {
            try {
                val source=sourceFor(record)
                val id=record.s("scanId").ifEmpty { UUID.randomUUID().toString().also { record.put("scanId",it) } }
                val state=(storage.get(owner.scope,"image",id) ?: JSONObject()).put("scanId",id)
                if(state.s("phase")=="verified")break
                if(state.optLong("retryAt")>System.currentTimeMillis())break
                val transferPath=UploadSheet.transferPath(record,source)
                val school=record.s("schoolId");val local=device(school)
                if(scanStatus[id]!="delivered" && !state.has("captureReceipt")) {
                    require(feature("imageFirstUpload")) { "برای ارسال عکس مستقل، ارتقای سرور لازم است؛ پاسخ‌ها پنهانی ارسال نشدند." }
                    val request=state.optJSONObject("captureRequest") ?: run {
                        val payload=source.copy().put("schemaVersion",if(source.s("identityMode")=="qr-lite-v1")"roshd.image-capture-payload.v2" else "roshd.image-capture-payload.v1")
                        ScannerPacket.create("image-capture",owner.scope(school),local.deviceUuid,id,payload) { crypto.sign(local,it) }
                    }
                    state.put("captureRequest",request);record.put("sourceSnapshot",source)
                    storage.transaction { storage.put(owner.scope,"image",id,state);storage.put(owner.scope,"scan",record.s("id"),record) }
                    val receipt=network.request("POST","/api/v1/scanner/image-captures",owner,request,school).data()
                    val identityMatches=if(source.s("identityMode")=="qr-lite-v1")receipt.s("clientSheetId")==source.s("clientSheetId") && receipt.s("studentPublicId")==source.s("studentPublicId") && receipt.s("qrText")==source.s("qrText") else receipt.s("sheetInstanceId")==source.s("sheetInstanceId")
                    require(receipt.s("schemaVersion")=="roshd.image-capture-result.v1" && receipt.s("status")=="registered" && receipt.s("captureId")==id && receipt.s("inputSha256")==request.s("inputSha256")
                        && identityMatches && receipt.s("examId")==source.s("examId")) { "رسید ثبت هویت عکس مطابقت ندارد." }
                    state.put("captureReceipt",receipt);storage.put(owner.scope,"image",id,state)
                }
                val sourceFile=EvidenceArchive.restore(File(transferPath),storage.mediaFile(owner.scope,"$id-restored.jpg"),source.s("sourceImageSha256"))
                OriginalImageUploader(network,crypto).upload(owner.scope(school),local,id,source,sourceFile,state,feature("zipImageParts"),
                    onPart={ part,total -> uploadProgress("ارسال عکس ${index+1} از ${identified.size}؛ بخش $part از $total…") }) { value ->
                    value.remove("lastError");value.remove("retryAt");value.remove("lastErrorCode");value.remove("lastErrorStatus");storage.put(owner.scope,"image",id,value)
                }
                break
            } catch(e:Exception) {
                val failure=DeliveryFailure.from(e,System.currentTimeMillis())
                val id=record.s("scanId");if(id.isNotEmpty()) {
                    val state=(storage.get(owner.scope,"image",id) ?: JSONObject()).put("scanId",id)
                    state.put("lastError",failure.message).put("retryAt",failure.retryAt)
                        .put("lastErrorCode",(e as? ApiFailure)?.code ?: e.javaClass.simpleName).put("lastErrorStatus",(e as? ApiFailure)?.status ?: 0)
                    storage.transaction { storage.put(owner.scope,"image",id,state);storage.put(owner.scope,"scan",record.s("id"),record) }
                }
                // Retry only transient server pressure, with the same signed identity
                // and a fresh acknowledged-part query. Do not retry access failures.
                if(e is ApiFailure && e.status in setOf(429,503) && retries<2 &&
                    failure.retryAt-System.currentTimeMillis() in 0..30_000) {
                    retries++
                    uploadProgress("سرور مصروف است؛ ادامهٔ عکس ${index+1} پس از زمان انتظار (تلاش $retries از ۲)…")
                    network.awaitRetry(failure.retryAt,transferVersion)
                    continue
                }
                if(firstError.isBlank())firstError=failure.message
                stopped=failure.stop
                break
            }
            }
            if(stopped)break
        }
        val states=identified.map { record ->
            if(record.o("uploadEvidence").s("variant")==UploadSheet.VARIANT || record.optBoolean("sealed"))storage.get(owner.scope,"image",record.s("scanId")) ?: JSONObject() else JSONObject()
        }
        val verified=states.count { it.s("phase")=="verified" }
        val deferred=states.count { it.s("phase")!="verified" && it.optLong("retryAt")>System.currentTimeMillis() }
        val remaining=identified.size-verified-deferred
        return "$verified عکس برگه تأیید سرور دارد؛ $remaining عکس نیاز به ادامه یا بررسی دارد."+
            (if(deferred>0)" $deferred عکس در انتظار زمان تلاش بعدی است." else "")+
            (if(firstError.isNotBlank())"\nارسال کامل نشد: $firstError" else "")+
            (if(records.size>identified.size)" ${records.size-identified.size} برگه ابتدا به شناسایی QR و بازبینی نیاز دارد." else "")
    }
    fun refreshReceipts(done:(Result<String>)->Unit)=run("تازه‌سازی رسیدهای ارسال…",done) {
        val owner=requireSession();require(owner.valid) { "برای دریافت رسید با همان حساب دوباره وارد شوید." }
        var received=0;var pending=0
        for(item in storage.queue(owner.scope,"scan")) {
            if(item.s("status")=="storage_error")continue
            val request=item.o("request")
            try {
                val receipt=network.request("GET","/api/v1/scanner/scans/${uuid(item.s("id"))}/receipt",owner,school=item.o("metadata").s("schoolId"),query=mapOf("deviceUuid" to request.s("deviceUuid"))).data()
                ReceiptVerifier.validate("scan",request,receipt)
                storage.status(owner.scope,item.s("id"),"delivered",receipt=receipt);received++
            } catch(_:Exception) { pending++ }
        }
        reloadLocal();"$received رسید ارسال تأیید شد؛ $pending ثبت هنوز رسید قابل دریافت ندارد. نمرهٔ رسمی از بخش نتیجه‌های رسمی دریافت می‌شود."
    }
    fun exportHistory(done:(Result<String>)->Unit)=run("آماده‌سازی خروجی…",done) {
        val owner=requireSession();val file=storage.mediaFile(owner.scope,"results-${System.currentTimeMillis()}.json")
        file.writeText(json("schemaVersion" to "roshd.native-export.v1","exportedAt" to now(),"scans" to scans,"queue" to storage.queue(owner.scope)).toString(2))
        native.call("shareFile",mapOf("path" to file.path,"mimeType" to "application/json")){}
        "خروجی آماده شد."
    }
    fun managerRequest(path:String,query:Map<String,String>,done:(Result<JSONObject>)->Unit) {
        var received:JSONObject?=null
        run("دریافت گزارش…",{ result -> done(result.map { received ?: error("گزارش دریافت نشد.") }) }) {
            val owner=requireSession();require(owner.manager || !owner.province) { "ابتدا مکتب گزارش را انتخاب کنید." }
            val response=network.request("GET",path,owner,query=query).data()
            storage.put(owner.scope,"manager",sha256(path+canonical(wrap(query))),response)
            received=response;""
        }
    }
}
