package com.haseebullahjan.roshd.ui

import android.app.AlertDialog
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import com.haseebullahjan.roshd.MainActivity
import com.haseebullahjan.youngscientist.DocumentImagePreparation
import com.haseebullahjan.roshd.core.*
import com.haseebullahjan.roshd.messenger.DispatchQueue
import com.haseebullahjan.youngscientist.scanner.*
import com.haseebullahjan.roshd.ui.actionbar.Route
import com.haseebullahjan.roshd.ui.components.*
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

/** Manual corrections are normalized coordinates on the upright original, not replacement evidence. */
class DocumentEditorFragment(activity:MainActivity,route:Route):PageFragment(activity,route,"تنظیم و صاف‌سازی برگه") {
    private val worker=Executors.newSingleThreadExecutor()
    private val directory=File(activity.cacheDir,"document-editor/${UUID.randomUUID()}")
    private val scanId=route.args.s("scanId")
    private val record=app.scans.singleOrNull { it.s("id")==scanId }
    private val source=File(route.args.s("path",record?.s("sourcePath") ?: ""))
    private var corners=route.args.optJSONArray("corners")?.let { a -> (0 until a.length()).map { a.getDouble(it) } }
        ?: record?.optJSONArray("documentCorners")?.let { a -> (0 until a.length()).map { a.getDouble(it) } }
        ?: DocumentQuad.inset
    private var turns=route.args.optInt("turns",record?.optInt("documentTurns",0) ?: 0)
    private var bitmap:Bitmap?=null
    private var previewFile:File?=null
    private var loading=false
    private var submitting=false
    private var exporting=false
    private var previewDialog:AlertDialog?=null
    private var error=""
    @Volatile private var destroyed=false
    override fun render() {
        note("چهار دستگیره را روی گوشه‌های واقعی برگه بگذارید. عکس اصلی محفوظ می‌ماند.")
        if(error.isNotEmpty())note(error,true)
        val image=bitmap
        if(image==null) {
            note("در حال آماده‌سازی عکس…")
            if(!loading)load()
            return
        }
        content.addView(CornerView(activity,image,corners,theme) { updated ->
            corners=updated;route.args.put("corners",wrap(corners))
        },LinearLayout.LayoutParams(-1,activity.dp(410)))
        action("تشخیص دوبارهٔ لبه‌ها") {
            val file=previewFile ?: return@action
            worker.execute {
                val detection=runCatching { DocumentScanner.inspectFile(file,File(directory,"detect")) }
                DispatchQueue.ui {
                    if(destroyed)return@ui
                    detection.onSuccess { if(it!=null) { corners=it;route.args.put("corners",wrap(corners));error="" } else error="مرز واضح پیدا نشد؛ گوشه‌ها را دستی تنظیم کنید." }
                        .onFailure { error="تشخیص لبه کامل نشد؛ گوشه‌ها را دستی تنظیم کنید." }
                    rerender()
                }
            }
        }
        action("استفاده از تمام عکس") { corners=listOf(0.0,0.0,1.0,0.0,1.0,1.0,0.0,1.0);route.args.put("corners",wrap(corners));rerender() }
        action("چرخش خروجی: ${turns*90} درجه") { turns=(turns+1)%4;route.args.put("turns",turns);rerender() }
        action("پیش‌نمایش صاف‌شده") {
            if(!DocumentQuad.valid(corners)) { error="چهار گوشه باید یک برگهٔ کامل و بدون تقاطع بسازند.";rerender();return@action }
            val q=corners.toList();val rotation=turns
            worker.execute {
                val result=runCatching {
                    val output=DocumentScanner.prepareForOmr(source,File(directory,"preview"),q,rotation,1400)
                    Pair(BitmapFactory.decodeFile(output.path),output.metadata.optBoolean("dewarped"))
                }
                DispatchQueue.ui {
                    if(destroyed) { result.getOrNull()?.first?.recycle();return@ui }
                    result.onSuccess { (rendered,dewarped) ->
                        if(rendered!=null) {
                            val view=ImageView(activity).apply { setImageBitmap(rendered);adjustViewBounds=true;contentDescription="پیش‌نمایش برگهٔ صاف‌شده" }
                            previewDialog?.dismiss()
                            previewDialog=AlertDialog.Builder(activity).setTitle(if(dewarped)"پرسپکتیو و انحنای برگه اصلاح شد" else "پرسپکتیو برگه اصلاح شد")
                                .setView(view).setPositiveButton("بستن",null)
                                .create().apply { setOnDismissListener { view.setImageDrawable(null);rendered.recycle();previewDialog=null };show() }
                        }
                    }.onFailure { error="این گوشه‌ها قابل صاف‌سازی نیست؛ دوباره تنظیم کنید.";rerender() }
                }
            }
        }
        action(if(exporting)"در حال آماده‌سازی خروجی…" else "ذخیرهٔ عکس صاف‌شده (JPEG)") {
            if(exporting)return@action
            if(!DocumentQuad.valid(corners)) { error="گوشه‌های برگه معتبر نیست.";rerender();return@action }
            exporting=true;rerender()
            val q=corners.toList();val rotation=turns
            // The system document picker can outlive this fragment; keep its file outside editor cleanup.
            val exportDirectory=File(activity.cacheDir,"document-exports/${UUID.randomUUID()}")
            worker.execute {
                val prepared=runCatching { DocumentScanner.prepareForOmr(source,exportDirectory,q,rotation,6000) }
                DispatchQueue.ui {
                    if(destroyed) { exportDirectory.deleteRecursively();return@ui }
                    prepared.onSuccess { output ->
                        app.native.call("saveFile",mapOf("path" to output.path,"mimeType" to "image/jpeg","fileName" to "document-scanned.jpg")) { outcome ->
                            exportDirectory.deleteRecursively();exporting=false
                            if(!destroyed) {
                                if(outcome.isFailure)error="ذخیرهٔ عکس کامل نشد؛ دوباره تلاش کنید."
                                else if(outcome.getOrNull()==true)activity.showResult(Result.success("عکس صاف‌شده ذخیره شد."))
                                rerender()
                            }
                        }
                    }.onFailure { exportDirectory.deleteRecursively();exporting=false;error="آماده‌سازی خروجی کامل نشد؛ گوشه‌ها را بررسی کنید.";rerender() }
                }
            }
        }
        action(if(submitting)"در حال ذخیره…" else "ذخیرهٔ گوشه‌ها و خوانش",true) {
            if(submitting)return@action
            if(!DocumentQuad.valid(corners)) { error="گوشه‌های برگه معتبر نیست.";rerender();return@action }
            submitting=true;rerender()
            val done:(Result<String>)->Unit={ outcome -> submitting=false;result(outcome);if(outcome.isSuccess) { present("history") } else rerender() }
            if(scanId.isNotEmpty())app.setDocumentCrop(scanId,corners,turns,done)
            else app.processFiles(listOf(source),corners,turns,done)
        }
    }
    private fun load() {
        loading=true
        worker.execute {
            val result=runCatching {
                require(source.isFile) { "عکس اصلی پیدا نشد." }
                val path=DocumentImagePreparation.render(source,File(directory,"upright"),1600)
                val detection=DocumentScanner.inspectFile(File(path),File(directory,"initial-detect"))
                Triple(File(path),BitmapFactory.decodeFile(path) ?: error("عکس خوانده نشد."),detection)
            }
            DispatchQueue.ui {
                if(destroyed) { result.getOrNull()?.second?.recycle();return@ui }
                result.onSuccess { (file,image,detection) ->
                    previewFile=file;bitmap=image
                    if(!route.args.has("corners") && record?.has("documentCorners")!=true && detection!=null)corners=detection
                    rerender()
                }.onFailure { error=it.message ?: "آماده‌سازی عکس کامل نشد.";note(error,true) }
            }
        }
    }
    override fun onFragmentDestroy() {
        destroyed=true;previewDialog?.dismiss();previewDialog=null;bitmap?.recycle();bitmap=null
        worker.execute { directory.deleteRecursively() };worker.shutdown();super.onFragmentDestroy()
    }
}

private class CornerView(context:android.content.Context,private val image:Bitmap,initial:List<Double>,theme:ClayTheme,
    private val changed:(List<Double>)->Unit):View(context) {
    private var corners=initial
    private val rect=RectF()
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val accent=theme.accent
    private var dragged=-1
    init { contentDescription="تنظیم چهار گوشه؛ دستگیره‌ها را جابه‌جا کنید";isFocusable=true }
    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas);if(image.isRecycled)return
        val scale=minOf(width.toFloat()/image.width,height.toFloat()/image.height)
        val w=image.width*scale;val h=image.height*scale
        rect.set((width-w)/2,(height-h)/2,(width+w)/2,(height+h)/2)
        canvas.drawBitmap(image,null,rect,paint)
        val path=Path().apply { moveTo(rect.left+(corners[0]*w).toFloat(),rect.top+(corners[1]*h).toFloat());for(i in 1..3)lineTo(rect.left+(corners[i*2]*w).toFloat(),rect.top+(corners[i*2+1]*h).toFloat());close() }
        paint.color=accent;paint.style=Paint.Style.STROKE;paint.strokeWidth=context.dp(2).toFloat();canvas.drawPath(path,paint)
        paint.style=Paint.Style.FILL
        for(i in 0..3)canvas.drawCircle(rect.left+(corners[i*2]*w).toFloat(),rect.top+(corners[i*2+1]*h).toFloat(),context.dp(8).toFloat(),paint)
    }
    override fun onTouchEvent(event:MotionEvent):Boolean {
        if(rect.width()<=0)return false
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val nearest=(0..3).minBy { i -> val x=rect.left+corners[i*2]*rect.width()-event.x;val y=rect.top+corners[i*2+1]*rect.height()-event.y;x*x+y*y }
                val x=rect.left+corners[nearest*2]*rect.width()-event.x;val y=rect.top+corners[nearest*2+1]*rect.height()-event.y
                if(x*x+y*y>context.dp(44).toDouble().let { it*it })return false
                dragged=nearest;parent.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> if(dragged>=0) {
                val q=corners.toMutableList();q[dragged*2]=((event.x-rect.left)/rect.width()).toDouble().coerceIn(0.0,1.0)
                q[dragged*2+1]=((event.y-rect.top)/rect.height()).toDouble().coerceIn(0.0,1.0)
                if(DocumentQuad.valid(q)) { corners=q;changed(q);invalidate() }
            }
            MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL -> { dragged=-1;parent.requestDisallowInterceptTouchEvent(false);performClick() }
        }
        return true
    }
    override fun performClick():Boolean { super.performClick();return true }
}
