package com.haseebullahjan.roshd.ui.camera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.view.PreviewView
import androidx.camera.view.transform.ImageProxyTransformFactory
import androidx.camera.view.transform.CoordinateTransform
import com.haseebullahjan.youngscientist.scanner.*
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.haseebullahjan.roshd.messenger.ApplicationLoader
import com.haseebullahjan.roshd.platform.*
import com.haseebullahjan.roshd.ui.components.*
import com.haseebullahjan.roshd.core.SheetFamilies
import com.haseebullahjan.roshd.core.LiveScanFeedback
import com.haseebullahjan.roshd.core.s
import com.haseebullahjan.roshd.ui.Thumbs
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@androidx.annotation.OptIn(markerClass=[androidx.camera.core.ExperimentalGetImage::class,androidx.camera.view.TransformExperimental::class])
class ScannerActivity:com.haseebullahjan.roshd.NativeHostActivity() {
    private val app get()=(application as ApplicationLoader).controller
    private val worker=Executors.newSingleThreadExecutor()
    private val processing=AtomicBoolean(false)
    private val gate=AutoCaptureGate(holdMs=600)
    private val pageIdentity by lazy { PreviewPageIdentity(com.haseebullahjan.roshd.core.OmrRosterIndex(app.packages.values,app.round)
        .historicalBindings.map { it.s("qrText") }.toSet()) }
    private val qrScannerDelegate=lazy { BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()) }
    private val qrScanner get()=qrScannerDelegate.value
    private val qrRunning=AtomicBoolean(false)
    private var lastQrAt=0L
    @Volatile private var previewGeneration=0
    private var lastFocusAt=0L
    private var lastCaptureId:String?=null
    private var lastHint:AutoCaptureGate.Hint?=null
    private var lastDiagnosticAt=0L
    private var qrElapsedMs=0L
    private val transformFactory=ImageProxyTransformFactory().apply { isUsingCropRect=true;isUsingRotationDegrees=true }
    private var lastFrameAt=0L
    private var autoEnabled=true
    private var capturedCount=0
    @Volatile private var pendingPhoto:File?=null
    private var shutterAtEpochMs=0L
    private var queueStatus:TextView?=null
    private var queueSubscription:AutoCloseable?=null
    private var resultSubscription:AutoCloseable?=null
    private var deletionSubscription:AutoCloseable?=null
    private lateinit var liveResult:TextView
    private val capturedIds=linkedSetOf<String>()
    private var displayedResultId:String?=null
    private var displayedResult:LiveScanFeedback?=null
    @Volatile private var questionCount=0
    private val sheetSegments=mutableMapOf<Int,RadioButton>()
    private var preparationVisible=false
    private var preparationNotice:String?=null
    private var preparationSubscription:AutoCloseable?=null
    private var provider:ProcessCameraProvider?=null
    private var capture:ImageCapture?=null
    private var analysis:ImageAnalysis?=null
    private var boundUseCases=emptyArray<UseCase>()
    private var camera:androidx.camera.core.Camera?=null
    private var torch=false
    private var lastCode="";private var lastAt=0L
    private lateinit var status:TextView
    private lateinit var preview:PreviewView
    private lateinit var overlay:CameraOverlay
    private var shutter:TextView?=null
    @Volatile private var takingPhoto=false
    private val attendance=false
    override fun onCreate(savedInstanceState:Bundle?) {
        setTheme(ClayTheme(app.darkMode).activityStyle)
        super.onCreate(savedInstanceState)
        app.native.attach(this)
        if(app.session==null) {
            Toast.makeText(this,app.tr("ابتدا وارد حساب شوید.","لومړی حساب ته ننوځئ."),Toast.LENGTH_LONG).show()
            finish();return
        }
        preparationSubscription=app.notifications.observe(com.haseebullahjan.roshd.messenger.AppEvent.PROGRESS) {
            if(preparationVisible && !isFinishing && !isDestroyed)showPreparation()
        }
        questionCount=(savedInstanceState?.getInt("questionCount") ?: intent.getIntExtra("questionCount",0))
            .takeIf { it in SheetFamilies.scanCounts }
            ?: com.haseebullahjan.roshd.messenger.NativeSettings.open(this).getInt("scannerQuestionCount",40)
                .takeIf { it in SheetFamilies.scanCounts } ?: 40
        continueScanner(savedInstanceState)
    }
    private fun continueScanner(savedInstanceState:Bundle?=null) {
        if(app.currentYear==null || app.round !in app.openRounds || app.scannerPackageCount==0) {
            showPreparation();return
        }
        preparationVisible=false
        createScanner(savedInstanceState)
    }
    private fun showPreparation() {
        preparationVisible=true
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window,false)
        val theme=ClayTheme(app.darkMode)
        val root=LinearLayout(this).apply {
            tag="scanner-preparation";orientation=LinearLayout.VERTICAL;layoutDirection=View.LAYOUT_DIRECTION_RTL
            background=OrbitSkyDrawable(theme)
        }
        root.addView(orbitHeader(this,theme,app.tr("آماده‌سازی اسکن","د سکین چمتووالی")),LinearLayout.LayoutParams(-1,dp(88)))
        val scroll=ScrollView(this)
        val body=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(12),dp(20),dp(24)) }
        fun note(value:String) { body.full(text(this,theme,value,16f).apply { setPadding(0,dp(8),0,dp(12)) }) }
        fun action(label:String,tagValue:String,work:()->Unit) {
            body.full(button(this,theme,label,true,work).apply { tag=tagValue;isEnabled=!app.busy;alpha=if(isEnabled)1f else .55f })
            body.space(12)
        }
        fun completed(result:Result<String>) {
            if(isFinishing || isDestroyed)return
            preparationNotice=result.fold({it},{it.message ?: app.tr("عملیات کامل نشد؛ دوباره تلاش کنید.","عملیات بشپړ نه شول؛ بیا هڅه وکړئ.")})
            continueScanner()
        }
        val year=app.currentYear
        val open=app.openRounds
        if(year==null) {
            note(app.tr("برای شروع اسکن، ابتدا سال و دور امتحان را دریافت کنید.","د سکین پیل لپاره لومړی د ازموینې کال او پړاو ترلاسه کړئ."))
            action(app.tr("دریافت سال و تنظیمات","کال او تنظیمات ترلاسه کړئ"),"scanner-refresh-year") {
                preparationNotice=null;app.refreshConfiguration(::completed)
            }
        } else {
            note(year.s("label",year.s("academicYearKey")))
            if(open.isEmpty()) {
                note(app.tr("در حال حاضر دور باز وجود ندارد. پس از باز شدن دور امتحان، تنظیمات را تازه کنید.","اوس مهال پرانیستی پړاو نشته. د پړاو له پرانیستلو وروسته تنظیمات تازه کړئ."))
                action(app.tr("تازه‌سازی سال و تنظیمات","کال او تنظیمات تازه کړئ"),"scanner-refresh-year") {
                    preparationNotice=null;app.refreshConfiguration(::completed)
                }
            } else {
                if(app.round !in open)note(app.tr("دور امتحان انتخاب نشده است؛ برای شروع اسکن یک دور را انتخاب کنید.","د ازموینې پړاو نه دی ټاکل شوی؛ د سکین پیل لپاره یو پړاو وټاکئ."))
                action(if(app.round in open)app.tr("دور انتخاب‌شده: ${app.round}","ټاکل شوی پړاو: ${app.round}") else app.tr("انتخاب دور امتحان","د ازموینې پړاو وټاکئ"),"scanner-select-round") {
                    android.app.AlertDialog.Builder(this).setTitle(app.tr("انتخاب دور امتحان","د ازموینې پړاو وټاکئ"))
                        .setSingleChoiceItems(open.map { app.tr("دور $it","پړاو $it") }.toTypedArray(),open.indexOf(app.round)) { dialog,index ->
                            dialog.dismiss()
                            runCatching { app.selectRound(open[index]) }.fold({
                                preparationNotice=null;continueScanner()
                            }, { preparationNotice=it.message;showPreparation() })
                        }.setNegativeButton(app.tr("بازگشت","بېرته"),null).showThemed(theme)
                }
                if(app.round in open) {
                    if(app.scannerPackageCount==0) {
                        note(app.tr("بستهٔ معتبر پاسخ‌نامه‌های این دور در گوشی موجود نیست. معلومات همین دور را دریافت کنید تا خوانش برگه‌ها شروع شود.","د دې پړاو د ځواب پاڼو معتبره بسته په موبایل کې نشته. د پاڼو لوستلو لپاره د همدې پړاو معلومات ترلاسه کړئ."))
                        action(app.tr("دریافت معلومات دور ${app.round} و ادامه","د پړاو ${app.round} معلومات ترلاسه او دوام ورکړئ"),"scanner-download-information") {
                            preparationNotice=null;app.downloadInformation(::completed)
                        }
                    } else action(app.tr("ادامهٔ اسکن","سکین ته دوام ورکړئ"),"scanner-continue") { continueScanner() }
                }
            }
        }
        preparationNotice?.let(::note)
        if(app.busy) {
            body.full(ProgressBar(this))
            note(app.progress)
        }
        body.full(button(this,theme,app.tr("بازگشت","بېرته")) { finish() })
        scroll.addView(body);root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f));setContentView(root)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { _,insets ->
            val bars=insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout())
            root.setPadding(bars.left,bars.top,bars.right,bars.bottom);insets
        }
    }
    private fun sheetSelector(theme:ClayTheme):View {
        val group=RadioGroup(this).apply {
            tag="scanner-sheet-segments";orientation=LinearLayout.HORIZONTAL
            layoutDirection=View.LAYOUT_DIRECTION_RTL;setPadding(dp(4),dp(4),dp(4),dp(4))
            background=roundedShape(theme.field,dp(18).toFloat())
            contentDescription=app.tr("نوع برگه","د پاڼې ډول")
        }
        sheetSegments.clear()
        for(count in SheetFamilies.scanCounts) {
            val label=when(count) { 40->"۴۰";75->"۷۵";else->"۱۰۰" }
            val segment=RadioButton(this).apply {
                id=View.generateViewId();tag="scanner-choose-$count";buttonDrawable=null
                text=app.tr("$label سؤال","$label پوښتنې");textSize=15f;typeface=ClayTheme.bold(this@ScannerActivity)
                gravity=Gravity.CENTER;minHeight=dp(48);setPadding(dp(4),dp(8),dp(4),dp(8))
                setTextColor(android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked),intArrayOf()),
                    intArrayOf(theme.canvas,theme.ink)))
                background=android.graphics.drawable.StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_checked),roundedShape(theme.ink,dp(14).toFloat()))
                    addState(intArrayOf(),roundedShape(Color.TRANSPARENT,dp(14).toFloat()))
                }
            }
            sheetSegments[count]=segment
            group.addView(segment,RadioGroup.LayoutParams(0,-2,1f))
        }
        group.check(sheetSegments.getValue(questionCount).id)
        group.setOnCheckedChangeListener { _,checked ->
            val selected=sheetSegments.entries.singleOrNull { it.value.id==checked }?.key ?: return@setOnCheckedChangeListener
            if(selected==questionCount)return@setOnCheckedChangeListener
            if(takingPhoto || pendingPhoto!=null) {
                group.check(sheetSegments.getValue(questionCount).id);return@setOnCheckedChangeListener
            }
            questionCount=selected
            com.haseebullahjan.roshd.messenger.NativeSettings.open(this).edit().putInt("scannerQuestionCount",selected).apply()
            previewGeneration++;pageIdentity.clear();gate.clearStability();lastHint=null
        }
        return group
    }
    private fun updateSheetSegments() {
        sheetSegments.values.forEach { it.isEnabled=!takingPhoto && pendingPhoto==null;it.alpha=if(it.isEnabled)1f else .55f }
    }
    private fun createScanner(savedInstanceState:Bundle?) {
        app.prepareContinuousScanner()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        capturedCount=savedInstanceState?.getInt("capturedCount") ?: 0
        savedInstanceState?.getStringArrayList("capturedIds")?.let { capturedIds.addAll(it) }
        autoEnabled=savedInstanceState?.getBoolean("autoEnabled",true) ?: true
        gate.restoreFingerprint(savedInstanceState?.getByteArray("lastPageFingerprint"),savedInstanceState?.getString("lastPageIdentity"))
        lastCaptureId=savedInstanceState?.getString("lastCaptureId")
        pendingPhoto=savedInstanceState?.getString("pendingPhoto")?.let(::File)?.takeIf { it.isFile }
        shutterAtEpochMs=savedInstanceState?.getLong("shutterAtEpochMs") ?: 0L
        if(capturedCount>0)setResult(RESULT_OK,Intent().putExtra("scanCount",capturedCount))
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window,false)
        val theme=ClayTheme(app.darkMode)
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setBackgroundColor(theme.canvas) }
        val heading=row(this).apply { setPadding(dp(16),dp(4),dp(16),dp(4)) }
        heading.weight(text(this,theme,if(attendance)"اسکن کارت" else "اسکن برگه",22f,true))
        heading.addView(RoshdBrand.image(this),LinearLayout.LayoutParams(dp(34),dp(34)))
        root.addView(heading,LinearLayout.LayoutParams(-1,dp(if(resources.configuration.fontScale>1.3f)72 else 52)))
        val cameraArea=FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        root.addView(cameraArea,LinearLayout.LayoutParams(-1,0,1f))
        preview=PreviewView(this).apply { implementationMode=PreviewView.ImplementationMode.COMPATIBLE;scaleType=PreviewView.ScaleType.FIT_CENTER }
        cameraArea.addView(preview,FrameLayout.LayoutParams(-1,-1))
        preview.setOnTouchListener { _,event ->
            if(event.action==MotionEvent.ACTION_UP) {
                val point=preview.meteringPointFactory.createPoint(event.x,event.y)
                camera?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
            }
            true
        }
        overlay=CameraOverlay(this,attendance).apply { tag="scanner-corner-guides" };cameraArea.addView(overlay,FrameLayout.LayoutParams(-1,-1))
        val controls=ClayPanel(this,theme).apply { tag="scanner-controls"; setPadding(dp(16),dp(6),dp(16),dp(6)) }
        status=text(this,theme,if(attendance)"QR کارت را داخل کادر بگیرید" else app.tr("تمام برگه را داخل تصویر بگذارید تا لبه‌ها پیدا شوند.","ټوله پاڼه په انځور کې کېږدئ څو څنډې یې وموندل شي."),14f,true).apply { gravity=Gravity.CENTER }
        status.maxLines=2
        controls.full(status)
        if(!attendance) {
            queueStatus=text(this,theme,"",12f,muted=true).apply { gravity=Gravity.CENTER;contentDescription="وضعیت صف خوانش برگه‌ها" }
            queueStatus!!.maxLines=1;queueStatus!!.ellipsize=android.text.TextUtils.TruncateAt.END
            controls.full(queueStatus!!)
            liveResult=text(this,theme,"",13f,true).apply { gravity=Gravity.CENTER;tag="scanner-live-result" }
            liveResult.maxLines=3;liveResult.ellipsize=android.text.TextUtils.TruncateAt.END;liveResult.visibility=View.GONE
            controls.full(liveResult)
            resultSubscription=app.notifications.observe(com.haseebullahjan.roshd.messenger.AppEvent.SCAN_RESULT) { value ->
                (value as? com.haseebullahjan.roshd.core.LiveScanFeedback)?.let(::showFeedback)
            }
            deletionSubscription=app.notifications.observe(com.haseebullahjan.roshd.messenger.AppEvent.SCAN_DELETED) { value ->
                val id=value as? String
                if(id!=null && capturedIds.remove(id)) {
                    capturedCount=(capturedCount-1).coerceAtLeast(0)
                    if(lastCaptureId==id) {
                        lastCaptureId=capturedIds.lastOrNull();gate.forgetCapturedPage();pageIdentity.clear();previewGeneration++
                    }
                    if(displayedResultId==id) {
                        displayedResultId=null;displayedResult=null;liveResult.text="";liveResult.visibility=View.GONE
                        liveResult.setOnClickListener(null)
                    }
                    setResult(RESULT_OK,Intent().putExtra("scanCount",capturedCount));updateQueueStatus()
                }
            }
            app.latestCameraFeedback?.let(::showFeedback)
            queueSubscription=app.notifications.observe(com.haseebullahjan.roshd.messenger.AppEvent.CAPTURE) { updateQueueStatus() }
            updateQueueStatus()
            val autoButton=button(this,theme,if(autoEnabled)app.tr("خودکار: روشن","خپلکاري: چالان") else app.tr("خودکار: خاموش","خپلکاري: بنده")) {}
            autoButton.textSize=13f;autoButton.setPadding(dp(4),dp(8),dp(4),dp(8))
            autoButton.tag="scanner-auto"
            autoButton.setOnClickListener {
                autoEnabled=!autoEnabled;gate.clearStability()
                autoButton.text=if(autoEnabled)app.tr("خودکار: روشن","خپلکاري: چالان") else app.tr("خودکار: خاموش","خپلکاري: بنده")
            }
            val modes=row(this)
            modes.weight(autoButton)
            modes.weight(button(this,theme,"چراغ") { torch=!torch;camera?.cameraControl?.enableTorch(torch) })
            modes.weight(button(this,theme,"پایان") { finish() })
            for(index in 0 until modes.childCount)(modes.getChildAt(index) as? TextView)?.apply {
                minHeight=dp(48);setPadding(dp(4),dp(6),dp(4),dp(6))
            }
            controls.full(modes)
            controls.full(sheetSelector(theme))
            controls.full(button(this,theme,"گرفتن عکس",true) { pendingPhoto?.let(::saveCapturedPhoto) ?: takePhoto() }.also {
                shutter=it;it.tag="scanner-shutter";it.minHeight=dp(48);it.setPadding(dp(12),dp(8),dp(12),dp(8));it.isEnabled=false;it.alpha=.55f
            })
        }
        if(attendance) {
            val actions=row(this)
            actions.weight(button(this,theme,"چراغ") { torch=!torch;camera?.cameraControl?.enableTorch(torch) })
            actions.weight(button(this,theme,"پایان") { finish() });controls.full(actions)
        }
        root.addView(controls,LinearLayout.LayoutParams(-1,-2))
        setContentView(root)
        preview.previewStreamState.observe(this) { state ->
            shutter?.let { it.isEnabled=state==PreviewView.StreamState.STREAMING && !takingPhoto;it.alpha=if(it.isEnabled)1f else .55f }
        }
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { _,insets ->
            val bars=insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout())
            root.setPadding(bars.left,bars.top,bars.right,bars.bottom)
            insets
        }
        if(ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)requestPermissions(arrayOf(Manifest.permission.CAMERA),72)
        else startIfAllowed()
    }
    private fun startIfAllowed() {
        if(ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)return
        val future=ProcessCameraProvider.getInstance(this)
        future.addListener({
            if(isFinishing || isDestroyed)return@addListener
            try {
                val manager=future.get();provider=manager
                val rotation=preview.display?.rotation ?: Surface.ROTATION_0
                val analysisResolution=ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(ResolutionStrategy(android.util.Size(1280,960),ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build()
                val cameraPreview=Preview.Builder().setTargetRotation(rotation).setResolutionSelector(
                    ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build()).build()
                cameraPreview.setSurfaceProvider(preview.surfaceProvider)
                val analyzer=ImageAnalysis.Builder().setTargetRotation(rotation).setResolutionSelector(analysisResolution)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analysis=analyzer;analyzer.setAnalyzer(worker) { image -> analyze(image) }
                val group=UseCaseGroup.Builder().addUseCase(cameraPreview).addUseCase(analyzer)
                if(attendance)capture=null
                else {
                    capture=ImageCapture.Builder().setTargetRotation(rotation).setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                        .setJpegQuality(100).setResolutionSelector(ResolutionSelector.Builder()
                            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                            .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY).build()).build()
                    group.addUseCase(capture!!)
                }
                preview.viewPort?.let { group.setViewPort(it) }
                manager.unbindAll()
                boundUseCases=if(attendance)arrayOf(cameraPreview,analyzer) else arrayOf(cameraPreview,analyzer,capture!!)
                camera=manager.bindToLifecycle(this,CameraSelector.DEFAULT_BACK_CAMERA,group.build())
                camera!!.cameraInfo.cameraState.observe(this) { state ->
                    if(state.error!=null) { status.text="دوربین آماده نشد؛ دوباره صفحهٔ اسکن را باز کنید.";shutter?.isEnabled=false }
                }
            } catch(e:Exception) { android.util.Log.w("YoungScientistCamera","Camera binding failed",e);status.text="دوربین در دسترس نیست؛ اجازهٔ دوربین را در تنظیمات بررسی کنید." }
        },ContextCompat.getMainExecutor(this))
    }
    private fun analyze(image:ImageProxy) {
        val now=android.os.SystemClock.elapsedRealtime()
        // Keep the live surface running while still recognition owns the CPU.
        // The same detector/QR/quality checks run at ~3 fps during a read;
        // the first page retains the normal 80 ms admission interval.
        val frameInterval=if(CameraDocumentReader.isReading)350L else 80L
        if(isFinishing || isDestroyed || takingPhoto || pendingPhoto!=null || now-lastFrameAt<frameInterval || !processing.compareAndSet(false,true)) {
            image.close();return
        }
        lastFrameAt=now
        val generation=previewGeneration
        try {
            val crop=image.cropRect;val rotation=image.imageInfo.rotationDegrees
            val sourceTransform=transformFactory.getOutputTransform(image)
            // Match Young Scientist's native live detector: inspect the current
            // Y plane directly, with its full crop/stride and 960 px native limit.
            // Still photographs retain the independent Moji dewarp pipeline.
            val plane=image.planes[0]
            val detected=DocumentScanner.inspectFrame(plane.buffer,plane.rowStride,plane.pixelStride,
                crop.left,crop.top,crop.width(),crop.height(),rotation)
            val corners=detected.corners.takeIf { detected.found }?.map { it.toFloat() }
            val sharpness=detected.sharpness
            val confidence=detected.confidence
            val detectionMs=android.os.SystemClock.elapsedRealtime()-now
            // Copy luminance only when a page exists, for duplicate protection
            // and QR hints. Keep the camera resolution instead of halving 1024 px.
            val frame=if(corners!=null) {
                val buffer=plane.buffer.duplicate().apply {
                    position(position()+crop.top*plane.rowStride+crop.left*plane.pixelStride)
                }
                CameraFramePacker.pack(crop.width(),crop.height(),CameraFramePacker.Plane(buffer,plane.rowStride,plane.pixelStride),
                    null,null,maxEdge=1280)
            } else null
            val fingerprint=if(corners!=null && frame!=null)
                PageFingerprint.sample(frame.width,frame.height,frame.bytes,corners,rotation) else null
            if(fingerprint!=null && frame!=null && sharpness>=45) {
                analyzePreviewQr(frame,rotation,fingerprint,now,generation)
            }
            val sourceWidth=if(rotation%180==0)crop.width() else crop.height()
            val sourceHeight=if(rotation%180==0)crop.height() else crop.width()
            val points=corners?.mapIndexed { i,v -> v*(if(i%2==0)sourceWidth-1 else sourceHeight-1) }?.toFloatArray()
            runOnUiThread {
                if(isDestroyed || generation!=previewGeneration || takingPhoto || pendingPhoto!=null || !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))return@runOnUiThread
                val frameAge=android.os.SystemClock.elapsedRealtime()-now
                val age=frameAge
                if(frameAge>450) { overlay.update(null,false);gate.clearStability();return@runOnUiThread }
                val target=preview.outputTransform ?: return@runOnUiThread
                try { if(points!=null)CoordinateTransform(sourceTransform,target).mapPoints(points) }
                catch(_:IllegalArgumentException) { gate.clearStability();return@runOnUiThread }
                if(corners==null)pageIdentity.clear()
                val identity=fingerprint?.let { pageIdentity.current(now,it,true) }?.takeIf {
                    (!it.startsWith("T1:") || questionCount in listOf(75,100)) && (!it.startsWith("T3:") || questionCount==40)
                }
                val decision=gate.update(AutoCaptureGate.Observation(now,corners,sharpness,confidence,age,fingerprint,identity))
                overlay.update(points,decision.hint in setOf(AutoCaptureGate.Hint.HOLD_STILL,AutoCaptureGate.Hint.CAPTURE),decision.progress)
                if(decision.hint==AutoCaptureGate.Hint.SHARPEN && points!=null)focusOnGuide(points)
                status.text=when(decision.hint) {
                    AutoCaptureGate.Hint.SHARPEN->"تصویر تار است؛ گوشی را ثابت نگه دارید یا برای فوکوس تصویر را لمس کنید."
                    AutoCaptureGate.Hint.MOVE_CLOSER->"گوشی را به برگه نزدیک‌تر کنید."
                    AutoCaptureGate.Hint.CENTER_PAGE->"برگه را در وسط تصویر بگذارید."
                    AutoCaptureGate.Hint.SHOW_EDGES->"تمام چهار لبهٔ برگه باید داخل تصویر باشد."
                    AutoCaptureGate.Hint.SHOW_QR->app.tr("برگهٔ بعدی آماده است؛ QR آن را واضح و چند لحظه ثابت نگه دارید.","بله پاڼه چمتو ده؛ QR یې روښانه او څو شېبې ثابت ونیسئ.")
                    AutoCaptureGate.Hint.NEXT_PAGE->"عکس محفوظ شد؛ برگهٔ بعدی را بگذارید."
                    AutoCaptureGate.Hint.HOLD_STILL->if(autoEnabled)"ثابت نگه دارید… ${(decision.progress*100).toInt()}٪" else "برگه آماده است؛ دکمهٔ گرفتن عکس را بزنید."
                    AutoCaptureGate.Hint.CAPTURE->if(autoEnabled)"در حال گرفتن عکس…" else "برگه آماده است؛ دکمهٔ گرفتن عکس را بزنید."
                    else->app.tr("تمام برگه را داخل تصویر بگذارید تا لبه‌ها پیدا شوند.","ټوله پاڼه په انځور کې کېږدئ څو څنډې یې وموندل شي.")
                }
                if(decision.hint!=lastHint || now-lastDiagnosticAt>=1000) {
                    android.util.Log.i("RoshdCaptureTiming","preview_hint=${decision.hint} engine=young-scientist-native detection_ms=$detectionMs detection_age_ms=$age sharpness=${sharpness.toInt()} qr_ready=${identity!=null} preview=${sourceWidth}x$sourceHeight rotation=$rotation")
                    lastHint=decision.hint
                    lastDiagnosticAt=now
                }
                if(autoEnabled && hasWindowFocus() && decision.hint==AutoCaptureGate.Hint.CAPTURE && shutter?.isEnabled==true)takePhoto(automatic=true)
            }
        } catch(_:Exception) {
            runOnUiThread { if(!isDestroyed && !takingPhoto && pendingPhoto==null) {
                overlay.update(null,false);pageIdentity.clear();gate.clearStability();status.text="پیش‌نمایش آماده نیست؛ می‌توانید دستی عکس بگیرید."
            } }
        } finally { image.close();processing.set(false) }
    }
    private fun analyzePreviewQr(frame:CameraFramePacker.Frame,rotation:Int,fingerprint:PageFingerprint,time:Long,generation:Int) {
        if(time-lastQrAt<150 || !qrRunning.compareAndSet(false,true))return
        lastQrAt=time
        try {
            // Identity remains a hint; OMR verifies the original QR independently.
            val bytes=frame.bytes+(frame.chroma ?: ByteArray(frame.width*frame.height/2) { 128.toByte() })
            val begun=android.os.SystemClock.elapsedRealtime()
            qrScanner.process(InputImage.fromByteArray(bytes,frame.width,frame.height,rotation,InputImage.IMAGE_FORMAT_NV21))
                .addOnSuccessListener { codes ->
                    if(!isDestroyed && generation==previewGeneration && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                        val values=codes.mapNotNull { it.rawValue }.distinct()
                        if(values.size>1)pageIdentity.clear()
                        else pageIdentity.update(values.singleOrNull(),time,android.os.SystemClock.elapsedRealtime(),fingerprint)
                    }
                }.addOnCompleteListener {
                    val elapsed=android.os.SystemClock.elapsedRealtime()-begun
                    qrElapsedMs=elapsed
                    if(elapsed>500)android.util.Log.i("RoshdCaptureTiming","preview_qr_ms=$elapsed")
                    qrRunning.set(false)
                }
        } catch(_:Exception) { qrRunning.set(false) }
    }
    private fun focusOnGuide(points:FloatArray) {
        val now=android.os.SystemClock.elapsedRealtime()
        if(now-lastFocusAt<1800)return
        val active=camera ?: return
        val x=(0..3).sumOf { points[it*2].toDouble() }.toFloat()/4
        val y=(0..3).sumOf { points[it*2+1].toDouble() }.toFloat()/4
        val point=preview.meteringPointFactory.createPoint(x,y)
        val action=FocusMeteringAction.Builder(point,FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(3,java.util.concurrent.TimeUnit.SECONDS).build()
        if(active.cameraInfo.isFocusMeteringSupported(action)) {
            lastFocusAt=now;active.cameraControl.startFocusAndMetering(action)
        }
    }
    private fun takePhoto(automatic:Boolean=false) {
        if(takingPhoto || pendingPhoto!=null || shutter?.isEnabled!=true || !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
            return
        }
        val target=capture ?: return
        takingPhoto=true;shutter?.isEnabled=false;gate.captureStarted()
        updateSheetSegments()
        previewGeneration++;pageIdentity.clear()
        val begun=android.os.SystemClock.elapsedRealtime()
        shutterAtEpochMs=System.currentTimeMillis()
        android.util.Log.i("RoshdCaptureTiming","shutter_requested automatic=$automatic")
        val file=File(cacheDir,"native-camera/${UUID.randomUUID()}.jpg").apply { parentFile!!.mkdirs() }
        status.text="در حال گرفتن عکس…"
        target.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(),ContextCompat.getMainExecutor(this),object:ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output:ImageCapture.OutputFileResults) {
                android.util.Log.i("RoshdCaptureTiming","photo_saved automatic=$automatic camera_ms=${android.os.SystemClock.elapsedRealtime()-begun}")
                pendingPhoto=file;saveCapturedPhoto(file)
            }
            override fun onError(exception:ImageCaptureException) {
                file.delete();takingPhoto=false;gate.captureFailed()
                updateSheetSegments()
                shutter?.isEnabled=preview.previewStreamState.value==PreviewView.StreamState.STREAMING
                status.text="عکس گرفته نشد؛ دوباره تلاش کنید."
            }
        })
    }
    private fun saveCapturedPhoto(file:File) {
        takingPhoto=true;shutter?.isEnabled=false
        updateSheetSegments()
        app.enqueueCapturedPhoto(file,questionCount,{ capturedIds.add(it);lastCaptureId=it },shutterAtEpochMs) { result ->
            takingPhoto=false
            result.onSuccess {
                pendingPhoto=null;file.delete();capturedCount++
                setResult(RESULT_OK,Intent().putExtra("scanCount",capturedCount))
                if(!isDestroyed) {
                    status.text="عکس محفوظ شد؛ برگهٔ بعدی را بگذارید."
                    overlay.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                }
            }.onFailure {
                // Keep the full JPEG and offer an explicit retry if storage failed.
                if(!isDestroyed)status.text="ذخیره کامل نشد؛ عکس محفوظ است. برای تلاش دوباره دکمه را بزنید."
            }
            if(!isDestroyed) {
                updateSheetSegments()
                shutter?.text=if(pendingPhoto==null)"گرفتن عکس" else "تلاش دوباره برای ذخیره"
                shutter?.isEnabled=preview.previewStreamState.value==PreviewView.StreamState.STREAMING
                shutter?.alpha=if(shutter?.isEnabled==true)1f else .55f
                updateQueueStatus()
            }
        }
    }
    private fun showFeedback(value:LiveScanFeedback) {
        if(value.id !in capturedIds || !::liveResult.isInitialized || value==displayedResult)return
        if(value.id==lastCaptureId)gate.confirmCapturedIdentity(value.pageIdentity)
        // A late color image can enrich this score, but cannot replace a newer page.
        if(displayedResultId!=null && capturedIds.indexOf(value.id)<capturedIds.indexOf(displayedResultId))return
        displayedResultId=value.id
        displayedResult=value
        val score=when {
            value.rawScore!=null->app.tr("نمرهٔ موقت: ${value.rawScore}","لنډمهاله نمره: ${value.rawScore}")
            value.correct!=null->app.tr("${value.correct} پاسخ درست از ${value.count}","${value.count} کې ${value.correct} سم ځوابونه")
            value.state=="ready"->app.tr("خوانش کامل شد؛ نمره آماده نیست.","لوستل بشپړ شول؛ نمره چمتو نه ده.")
            else->app.tr("برگه نیاز به بررسی دارد.","پاڼه بیاکتنې ته اړتیا لري.")
        }
        val number=capturedIds.indexOf(value.id)+1
        val page=app.tr("برگهٔ $number","پاڼه $number")
        val count=if(value.rawScore!=null)"${value.correct}/${value.count}" else ""
        val timing=if(value.elapsedMs>0)app.tr("${"%.1f".format(java.util.Locale.ROOT,value.elapsedMs/1000.0)} ثانیه","${"%.1f".format(java.util.Locale.ROOT,value.elapsedMs/1000.0)} ثانیې") else ""
        val detail=listOf(page,value.student,count,timing).filter { it.isNotBlank() }.joinToString(" • ")
        val warning=if(value.needsReview)app.tr("پاسخ مبهم دارد؛ نمره نیاز به بازبینی دارد.","ناڅرګند ځوابونه لري؛ نمره بیاکتنه غواړي.") else I18n.phrase(this,value.message)
        val label=android.text.SpannableStringBuilder(listOf(score,detail,warning).filter { it.isNotBlank() }.joinToString("\n"))
        label.setSpan(android.text.style.RelativeSizeSpan(1.2f),0,score.length,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if(label.length>score.length)label.setSpan(android.text.style.RelativeSizeSpan(.75f),score.length,label.length,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        liveResult.text=label
        liveResult.visibility=View.VISIBLE
        value.coloredPath?.let { path ->
            val start=liveResult.length()
            liveResult.append(app.tr("\nبرگهٔ رنگی محفوظ شد؛ برای دیدن لمس کنید.","\nرنګینه پاڼه خوندي شوه؛ د لیدلو لپاره یې لمس کړئ."))
            (liveResult.text as? android.text.Spannable)?.setSpan(android.text.style.RelativeSizeSpan(.75f),start,liveResult.length(),android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            liveResult.isClickable=true;liveResult.isFocusable=true
            liveResult.setOnClickListener {
                val image=SheetPreviewView(this)
                val frame=FrameLayout(this).apply { addView(image,FrameLayout.LayoutParams(-1,minOf(dp(430),(resources.displayMetrics.heightPixels*.65f).toInt()))) }
                val dialog=android.app.AlertDialog.Builder(this)
                    .setTitle(app.tr("برگهٔ تصحیح‌شده","سمه شوې پاڼه"))
                    .setView(frame).setPositiveButton(app.tr("بستن","تړل"),null)
                    .setNeutralButton(app.tr("ذخیره در گوشی","په موبایل کې خوندي کول")) { _,_ ->
                        app.native.call("saveFile",mapOf("path" to path,"mimeType" to "image/jpeg","fileName" to "roshd-graded-${value.id}.jpg")) { result ->
                            result.exceptionOrNull()?.let { Toast.makeText(this,it.message,Toast.LENGTH_LONG).show() }
                        }
                    }.create()
                dialog.show();Thumbs.load(image,path,2200)
            }
        } ?: run { liveResult.setOnClickListener(null);liveResult.isClickable=false;liveResult.isFocusable=false }
        liveResult.contentDescription=liveResult.text
    }
    private fun updateQueueStatus() {
        queueStatus?.text="$capturedCount عکس محفوظ • ${app.cameraQueuedCount} برگه در صف • "+if(app.cameraProcessing)app.progress else "آمادهٔ برگهٔ بعدی"
    }
    override fun onSaveInstanceState(out:Bundle) {
        out.putStringArrayList("capturedIds",ArrayList(capturedIds));out.putInt("questionCount",questionCount);out.putInt("capturedCount",capturedCount);out.putBoolean("autoEnabled",autoEnabled);out.putByteArray("lastPageFingerprint",gate.savedFingerprint());out.putString("lastPageIdentity",gate.savedIdentity());out.putString("lastCaptureId",lastCaptureId)
        pendingPhoto?.let { out.putString("pendingPhoto",it.path) }
        out.putLong("shutterAtEpochMs",shutterAtEpochMs)
        super.onSaveInstanceState(out)
    }
    override fun onPause() {
        previewGeneration++;pageIdentity.clear();gate.clearStability()
        if(::overlay.isInitialized)overlay.update(null,false)
        super.onPause()
    }
    override fun onRequestPermissionsResult(code:Int,permissions:Array<String>,results:IntArray) {
        super.onRequestPermissionsResult(code,permissions,results)
        if(code==72 && results.firstOrNull()==PackageManager.PERMISSION_GRANTED)startIfAllowed() else status.text="برای اسکن، اجازهٔ دوربین لازم است."
    }
    override fun onWindowFocusChanged(hasFocus:Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if(hasFocus)androidx.core.view.WindowCompat.getInsetsController(window,window.decorView).apply {
            isAppearanceLightStatusBars=!app.darkMode;isAppearanceLightNavigationBars=!app.darkMode
        }
    }
    override fun onDestroy() { preparationSubscription?.close();queueSubscription?.close();resultSubscription?.close();deletionSubscription?.close();app.native.detach(this);analysis?.clearAnalyzer();provider?.unbind(*boundUseCases);boundUseCases=emptyArray();worker.shutdown();if(qrScannerDelegate.isInitialized())qrScanner.close();super.onDestroy() }
}

