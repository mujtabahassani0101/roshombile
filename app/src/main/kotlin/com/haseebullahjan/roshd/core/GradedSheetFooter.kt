package com.haseebullahjan.roshd.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import com.haseebullahjan.roshd.ui.components.ClayTheme
import java.io.File
import java.math.BigDecimal

/**
 * The "X out of Y" box printed at the bottom of the graded sheet. That same
 * image is shown, saved, shared and uploaded, so the score travels with it.
 * Only totals leave the grading call; the key itself is never written here.
 */
object GradedSheetFooter {
    data class Summary(val score:String,val outOf:String,val correct:Int,val incorrect:Int,val blank:Int,val review:Int)

    /** Points when the exam has a scoring policy and the server shows points; otherwise correct answers out of questions. */
    fun summary(score:Map<String,Any?>,maxPoints:BigDecimal?,showPoints:Boolean):Summary {
        val verdicts=(score["questionVerdicts"] as List<*>).map { it as String }
        val questions=(score["questionCount"] as Number).toInt()
        require(verdicts.size==questions)
        val raw=score["rawScore"] as? String
        val points=showPoints && raw!=null && maxPoints!=null && maxPoints.signum()>0
        return Summary(if(points)raw!! else (score["correctCount"] as Number).toInt().toString(),
            if(points)maxPoints!!.stripTrailingZeros().toPlainString() else questions.toString(),
            verdicts.count { it=="correct" || it=="voided" },verdicts.count { it=="incorrect" || it=="multiple" },
            verdicts.count { it=="blank" },verdicts.count { it=="review" })
    }

    /** Persian digits as printed on the sheet; numbers are isolated so a minus sign stays on the left. */
    fun number(value:String)=LRI+value.map { c -> when(c) { in '0'..'9'->'۰'+(c-'0');'.'->'٫';'-'->'−';else->c } }.joinToString("")+PDI
    fun headline(summary:Summary)="نمره: ${number(summary.score)} از ${number(summary.outOf)}"
    fun details(summary:Summary):List<Pair<String,Int>> = buildList {
        add("درست ${number(summary.correct.toString())}" to GREEN)
        add("نادرست ${number(summary.incorrect.toString())}" to RED)
        add("سفید ${number(summary.blank.toString())}" to AMBER)
        if(summary.review>0)add("نیاز به بازبینی ${number(summary.review.toString())}" to INK)
    }

    /** Premultiplied RGBA, row by row, exactly width*height*4 bytes; the native renderer blends it. */
    fun write(context:Context,summary:Summary,width:Int,height:Int,target:File) {
        require(width in 50..4000 && height in 20..1000)
        val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        try {
            draw(Canvas(bitmap),context,summary,width,height)
            // getPixels is ARGB on every platform; the raw buffer order is not.
            val colors=IntArray(width*height);bitmap.getPixels(colors,0,width,0,0,width,height)
            val bytes=ByteArray(colors.size*4)
            colors.forEachIndexed { index,color ->
                val alpha=color ushr 24
                fun premultiplied(shift:Int)=(((color shr shift) and 255)*alpha+127)/255
                bytes[index*4]=premultiplied(16).toByte();bytes[index*4+1]=premultiplied(8).toByte()
                bytes[index*4+2]=premultiplied(0).toByte();bytes[index*4+3]=alpha.toByte()
            }
            target.outputStream().use { it.write(bytes);it.fd.sync() }
        } finally { bitmap.recycle() }
    }

    internal fun draw(canvas:Canvas,context:Context,summary:Summary,width:Int,height:Int) {
        val stroke=maxOf(3f,height*.025f)
        val box=RectF(stroke/2,stroke/2,width-stroke/2,height-stroke/2)
        val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        paint.style=Paint.Style.FILL;paint.color=Color.WHITE;canvas.drawRoundRect(box,height*.16f,height*.16f,paint)
        paint.style=Paint.Style.STROKE;paint.strokeWidth=stroke;paint.color=INK;canvas.drawRoundRect(box,height*.16f,height*.16f,paint)
        val textWidth=(width-height*.5f).toInt()
        fun layout(text:CharSequence,size:Float,bold:Boolean)=StaticLayout.Builder.obtain(text,0,text.length,
            TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color=INK;textSize=size;typeface=if(bold)ClayTheme.bold(context) else ClayTheme.regular(context) },textWidth)
            .setTextDirection(TextDirectionHeuristics.RTL).setAlignment(Layout.Alignment.ALIGN_CENTER).setMaxLines(1).setIncludePad(false).build()
        val line=SpannableStringBuilder()
        details(summary).forEachIndexed { index,(text,color) ->
            if(index>0)line.append("   •   ")
            val start=line.length;line.append(text);line.setSpan(ForegroundColorSpan(color),start,line.length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val top=layout(headline(summary),height*.34f,true);val bottom=layout(line,height*.2f,true)
        val gap=height*.05f;val y=(height-top.height-gap-bottom.height)/2f
        canvas.save();canvas.translate(height*.25f,y);top.draw(canvas);canvas.translate(0f,top.height+gap);bottom.draw(canvas);canvas.restore()
    }

    /** Left-to-right isolate and its closing mark, written as code points (lint rejects literal bidi controls). */
    private val LRI=Char(0x2066).toString()
    private val PDI=Char(0x2069).toString()
    private val INK=Color.rgb(35,55,76)
    private val GREEN=Color.rgb(22,140,80)
    private val RED=Color.rgb(205,45,55)
    private val AMBER=Color.rgb(196,140,0)
}
