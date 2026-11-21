package com.haseebullahjan.roshd.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.widget.LinearLayout
import com.haseebullahjan.roshd.MainActivity
import com.haseebullahjan.roshd.core.*
import com.haseebullahjan.roshd.ui.actionbar.Route
import com.haseebullahjan.roshd.ui.components.dp
import com.haseebullahjan.roshd.ui.components.SheetPreviewView
import java.io.File
import java.util.concurrent.Executors

class FilePreviewFragment(activity:MainActivity,route:Route):PageFragment(activity,route,"فایل آرشیف") {
    private var page=0;private var total=1
    override fun render() {
        val file=app.archive.singleOrNull { it.s("id")==route.args.s("id") } ?: return empty("فایل موجود نیست","به آرشیف برگردید.")
        note(file.s("name"))
        val preview=SheetPreviewView(activity)
        content.addView(preview,LinearLayout.LayoutParams(-1,activity.dp(440)))
        Executors.newSingleThreadExecutor().also { executor -> executor.execute {
            val bitmap=runCatching {
                val source=File(file.s("sourcePath"))
                if(file.s("mimeType")=="application/pdf") {
                    ParcelFileDescriptor.open(source,ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor -> PdfRenderer(descriptor).use { renderer ->
                        total=renderer.pageCount
                        renderer.openPage(page.coerceAtMost(total-1)).use { pdf -> Bitmap.createBitmap(800,(800f*pdf.height/pdf.width).toInt(),Bitmap.Config.ARGB_8888).also { pdf.render(it,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) } }
                    } }
                } else BitmapFactory.decodeFile(source.path,BitmapFactory.Options().apply { inSampleSize=2 })
            }.getOrNull()
            activity.runOnUiThread { preview.setImageBitmap(bitmap) };executor.shutdown()
        } }
        if(file.s("mimeType")=="application/pdf") {
            action("صفحهٔ بعد") { if(page+1<total) { page++;rerender() } }
            action("صفحهٔ قبل") { if(page>0) { page--;rerender() } }
        }
        action("اشتراک‌گذاری فایل") { app.native.call("shareFile",mapOf("path" to file.s("sourcePath"),"mimeType" to file.s("mimeType"))) { result(it.map { "" }) } }
        action("ذخیرهٔ فایل در گوشی") { app.native.call("saveFile",mapOf("path" to file.s("sourcePath"),"mimeType" to file.s("mimeType"),"fileName" to File(file.s("sourcePath")).name)) { result(it.map { saved -> if(saved==true)"فایل ذخیره شد." else "" }) } }
    }
}
