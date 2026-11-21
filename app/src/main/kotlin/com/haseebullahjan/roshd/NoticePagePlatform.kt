package com.haseebullahjan.roshd

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.os.Handler
import android.os.Looper
import com.haseebullahjan.roshd.platform.NativeCall
import com.haseebullahjan.roshd.platform.NativeResult
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Only consumes validated local vector commands. No HTTP or student cache. */
internal class NoticePagePlatform(private val context: Context) {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val cancelled = ConcurrentHashMap<String, AtomicBoolean>()
    private val regular by lazy { Typeface.createFromAsset(context.assets, "notice_fonts/Vazirmatn-Regular.ttf") }
    private val semibold by lazy { Typeface.createFromAsset(context.assets, "notice_fonts/Vazirmatn-SemiBold.ttf") }
    fun close() { cancelled.values.forEach { it.set(true) }; worker.shutdownNow() }
    fun handle(call: NativeCall, result: NativeResult) {
        val operation = call.argument<String>("operation") ?: return result.error("notice_integrity_invalid", "Invalid operation", null)
        if (call.method == "noticeCancelRender") {
            cancelled.getOrPut(operation) { AtomicBoolean(false) }.set(true)
            result.success(null)
            return
        }
        val flag = cancelled.getOrPut(operation) { AtomicBoolean(false) }
        worker.execute {
            var temporary: File? = null
            try {
                val path = call.argument<String>("path") ?: error("Missing path")
                val file = File(path).canonicalFile
                val root = File(context.applicationInfo.dataDir).canonicalFile
                require(file.path.startsWith(root.path + File.separator) && file.path.contains("/notices/"))
                require(file.name.endsWith(".pdf") || file.name.endsWith(".png"))
                val commands = call.argument<List<List<Any?>>>("commands") ?: error("Missing commands")
                require(commands.size in 1..10000)
                val temp = File(file.path + ".native-partial"); temporary = temp
                file.parentFile!!.mkdirs()
                check(!flag.get())
                if (file.name.endsWith(".png")) {
                    val bitmap = Bitmap.createBitmap(1190, 1684, Bitmap.Config.ARGB_8888)
                    try {
                        val canvas = Canvas(bitmap); canvas.drawColor(Color.WHITE); canvas.scale(2f, 2f)
                        draw(canvas, commands, flag)
                        FileOutputStream(temp).use { output -> check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)); output.fd.sync() }
                    } finally { bitmap.recycle() }
                } else {
                    val document = PdfDocument()
                    try {
                        val page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
                        try { page.canvas.drawColor(Color.WHITE); draw(page.canvas, commands, flag) }
                        finally { document.finishPage(page) }
                        check(!flag.get())
                        FileOutputStream(temp).use { output -> document.writeTo(output); output.fd.sync() }
                    } finally { document.close() }
                }
                check(!flag.get()); check(temp.length() in 1..(8L * 1024 * 1024))
                check(temp.renameTo(file))
                main.post { result.success(file.length()) }
            } catch (_: Exception) {
                temporary?.delete()
                main.post { result.error(if (flag.get()) "notice_cancelled" else "notice_render_failed", "Local notice rendering did not complete.", null) }
            }
        }
    }
    private fun color(value: Any?): Int = if (value == "transparent") Color.TRANSPARENT else Color.parseColor(value as String)
    private fun draw(canvas: Canvas, commands: List<List<Any?>>, cancel: AtomicBoolean) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        var clips = 0
        for (c in commands) {
            check(!cancel.get() && !Thread.currentThread().isInterrupted)
            fun f(i: Int): Float = (c[i] as Number).toFloat().also { require(it.isFinite() && kotlin.math.abs(it) < 10000) }
            paint.reset(); paint.isAntiAlias = true
            when (c[0]) {
                "box" -> {
                    val rect = RectF(f(1), f(2), f(1)+f(3), f(2)+f(4))
                    paint.color = color(c[6]); canvas.drawRoundRect(rect, f(5), f(5), paint)
                    paint.style = Paint.Style.STROKE; paint.strokeWidth = f(8); paint.color = color(c[7])
                    if (paint.color != Color.TRANSPARENT && paint.strokeWidth > 0) canvas.drawRoundRect(rect, f(5), f(5), paint)
                }
                "rect" -> { paint.color = color(c[5]); canvas.drawRect(f(1), f(2), f(1)+f(3), f(2)+f(4), paint) }
                "line" -> { paint.color = color(c[5]); paint.strokeWidth = f(6); canvas.drawLine(f(1), f(2), f(3), f(4), paint) }
                "circle" -> {
                    paint.color = color(c[4]); canvas.drawCircle(f(1), f(2), f(3), paint)
                    paint.style = Paint.Style.STROKE; paint.color = color(c[5]); paint.strokeWidth = f(6)
                    canvas.drawCircle(f(1), f(2), f(3), paint)
                }
                "text", "centerText" -> {
                    val text = c[1] as String
                    val size = f(5)
                    paint.typeface = if (c[6] == true) semibold else regular; paint.textSize = size; paint.color = color(c[7])
                    var y = f(3)
                    if (c[0] == "centerText") { val bounds = Rect(); paint.getTextBounds(text, 0, text.length, bounds); y -= bounds.exactCenterY() }
                    text(canvas, paint, text, f(2), y, f(4), size)
                }
                "legend" -> {
                    @Suppress("UNCHECKED_CAST") val entries = c[1] as List<List<String>>
                    val x = f(2); val top = f(3); val width = f(4); val ref = c[5] == true
                    val cell = width / entries.size; val size = if (ref) 7.4f else 3.8f
                    for ((i, entry) in entries.withIndex()) {
                        val right = x + width - cell*i
                        val bx = right - if (ref) 7 else 10
                        paint.typeface = regular; paint.textSize = size; paint.style = Paint.Style.FILL
                        paint.color = color(entry[1]); val r = RectF(bx, top-2, bx+6, top+if(ref) 2 else 1)
                        canvas.drawRoundRect(r, 1f, 1f, paint)
                        paint.style = Paint.Style.STROKE; paint.strokeWidth = .55f
                        paint.color = if (entry[1] == "#ffffff" && ref) color("#5757c9") else color(entry[1])
                        canvas.drawRoundRect(r, 1f, 1f, paint); paint.style = Paint.Style.FILL
                        val labelWidth = minOf(cell-if(ref) 18 else 14, measure(paint, entry[0])+if(ref) 1f else .5f)
                        paint.color = color("#75829b")
                        text(canvas, paint, entry[0], bx-(if(ref) 4 else 3)-labelWidth/2, top+if(ref) 1.5f else 1f, labelWidth, size)
                    }
                }
                "clip" -> {
                    canvas.save(); clips++
                    val path = Path(); path.addRoundRect(RectF(f(1),f(2),f(1)+f(3),f(2)+f(4)), f(5),f(5),Path.Direction.CW); canvas.clipPath(path)
                }
                "restore" -> { require(clips>0); canvas.restore(); clips-- }
                else -> error("Unknown vector operation")
            }
        }
        require(clips==0)
    }
    private val runs = Regex("[A-Za-z0-9۰-۹٠-٩.+%/:-]+|[^A-Za-z0-9۰-۹٠-٩.+%/:-]+")
    private fun chunks(text: String): List<String> {
        val parts = runs.findAll(text).map { it.value }.toList()
        return if (text.any { it in '\u0600'..'\u06ff' && !it.isDigit() }) parts.reversed() else parts
    }
    private fun rtl(text: String) = text.any { it in '\u0600'..'\u06ff' && !it.isDigit() }
    private fun measure(paint: Paint, text: String): Float = chunks(text).sumOf { part ->
        paint.getRunAdvance(part.toCharArray(), 0, part.length, 0, part.length, rtl(part), part.length).toDouble()
    }.toFloat()
    private fun text(canvas: Canvas, paint: Paint, value: String, x: Float, y: Float, width: Float, size: Float) {
        require(value.length<=500 && width>0)
        val measured = measure(paint, value)
        paint.textSize = maxOf(3.8f, if (measured>width) size*width/measured else size)
        val parts = chunks(value); var cursor = x-measure(paint,value)/2
        canvas.save(); canvas.clipRect(x-width/2,y-size-3,x+width/2,y+5)
        try {
            for (part in parts) {
                canvas.drawTextRun(part,0,part.length,0,part.length,cursor,y,rtl(part),paint)
                cursor += paint.getRunAdvance(part.toCharArray(),0,part.length,0,part.length,rtl(part),part.length)
            }
        } finally { canvas.restore() }
    }
}
