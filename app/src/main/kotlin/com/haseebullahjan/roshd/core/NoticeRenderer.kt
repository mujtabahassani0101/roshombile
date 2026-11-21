package com.haseebullahjan.roshd.core

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.haseebullahjan.roshd.ui.components.ClayTheme
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Bounded vector rendering of authenticated notice data; official scores are never inferred. */
class NoticeRenderer(private val context:Context) {
    fun render(target:File,notices:List<JSONObject>) {
        require(notices.size in 1..2000)
        val document=PdfDocument();val partial=File(target.path+".partial")
        try {
            notices.forEachIndexed { index,data ->
                validate(data)
                val page=document.startPage(PdfDocument.PageInfo.Builder(595,842,index+1).create())
                draw(page.canvas,data,index+1,notices.size);document.finishPage(page)
            }
            target.parentFile!!.mkdirs();partial.outputStream().use { document.writeTo(it);it.fd.sync() };require(partial.renameTo(target))
        } finally { document.close();partial.delete() }
    }
    internal fun draw(canvas:Canvas,data:JSONObject,page:Int,total:Int) {
        val paint=Paint(Paint.ANTI_ALIAS_FLAG);val ink=Color.rgb(35,55,76)
        val red=data.s("noticeColor")=="red"
        canvas.drawColor(Color.WHITE)
        fun line(y:Float) { paint.color=if(red)Color.rgb(231,211,215) else Color.rgb(211,223,234);paint.strokeWidth=.6f;canvas.drawLine(24f,y,571f,y,paint) }
        fun txt(value:String,x:Float,y:Float,width:Float=547f,size:Float=10f,bold:Boolean=false) {
            val textPaint=TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color=ink;textSize=size;typeface=if(bold)ClayTheme.bold(context) else ClayTheme.regular(context) }
            val layout=StaticLayout.Builder.obtain(value,0,value.length,textPaint,width.toInt()).setTextDirection(android.text.TextDirectionHeuristics.RTL).setAlignment(Layout.Alignment.ALIGN_NORMAL).setMaxLines(2).setIncludePad(false).build()
            canvas.save();canvas.translate(x,y);layout.draw(canvas);canvas.restore()
        }
        txt("رشد",24f,22f,size=20f,bold=true)
        txt(data.s("title"),24f,55f,size=12f,bold=true);line(78f)
        txt("${data.s("firstName")} ${data.s("lastName")} • نام پدر: ${data.s("fatherName")}",24f,91f,bold=true)
        val section=section(data)?.let { " • شعبه $it" }.orEmpty()
        txt("${data.s("schoolName")} • صنف ${data.optInt("grade")}$section • دور ${data.optInt("round")}",24f,114f)
        txt("آی‌دی ${data.s("studentPublicId")} • تاریخ ${data.s("examDate").take(10)}",24f,135f,size=9f)
        paint.color=if(red)Color.rgb(251,236,238) else Color.rgb(234,242,248);canvas.drawRoundRect(RectF(24f,160f,571f,211f),9f,9f,paint)
        if(!hasResult(data)) {
            // The server marks a notice without an official result. Its
            // identity is printed; no score, rank or answer is invented.
            txt("نتیجهٔ رسمی برای این شاگرد در این دور ثبت نشده است.",30f,177f,535f,12f,true)
            line(807f);txt("${if(data.optInt("publicationVersion")>0)"نسخهٔ نشر ${data.optInt("publicationVersion")} • " else ""}$page / $total",24f,816f,547f,8f)
            return
        }
        val ranks=data.optJSONObject("ranks") ?: JSONObject()
        txt("نمرهٔ کل: ${data.opt("totalScore")}",305f,173f,250f,14f,true)
        txt("تراز: ${if(data.isNull("totalTaraz"))"—" else data.opt("totalTaraz")}",130f,177f,160f,11f,true)
        txt("رتبهٔ کشور: ${rank(ranks,"overall") ?: "—"}",30f,177f,100f,11f,true)
        val subjects=data.a("subjects").objects()
        var y=231f
        rankLine(ranks)?.let { txt(it,24f,217f,547f,9f);y=243f }
        txt("مضمون",400f,y,150f,9f,true);txt("درست / نادرست / سفید",218f,y,180f,9f,true);txt("درصد",110f,y,100f,9f,true);txt("نمره",30f,y,80f,9f,true)
        y+=20;line(y)
        subjects.forEach { subject ->
            txt(subject.s("name"),400f,y+5,150f,9f)
            txt("${subject.optInt("correct")} / ${subject.optInt("incorrect")} / ${subject.optInt("blank")}",218f,y+5,180f,9f)
            txt(subject.opt("percentage").toString(),110f,y+5,100f,9f);txt(subject.opt("score").toString(),30f,y+5,80f,9f)
            y+=23;line(y)
        }
        groupRankLine(data)?.let { txt(it,24f,y+6,547f,8.5f);y+=28 }
        y+=14;txt("پاسخ‌ها • سبز: گزینهٔ درست • توپر: پاسخ شاگرد",24f,y,547f,9f,true);y+=25
        val questions=data.a("questions").objects();val columns=6;val cell=91f
        val history=data.a("history").objects()
        val questionRows=(questions.size+columns-1)/columns
        val height=minOf(16f,((if(history.isNotEmpty())760f else 790f)-y)/questionRows)
        questions.forEachIndexed { index,question ->
            val column=index%columns;val row=index/columns;val left=24f+(columns-1-column)*cell;val top=y+row*height
            txt(question.optInt("number").toString(),left+68,top-5,18f,7f)
            for(option in 1..4) {
                val x=left+56-(option-1)*13
                val accepted=question.optInt("correctOption").let { if(it<=4)it==option else ((it-16) and (1 shl(option-1)))!=0 }
                paint.color=if(accepted)Color.rgb(34,131,93) else Color.rgb(153,169,185)
                paint.style=Paint.Style.STROKE;paint.strokeWidth=if(accepted)1.1f else .5f;canvas.drawCircle(x,top,4.5f,paint)
                if(question.optInt("selectedOption")==option) { paint.style=Paint.Style.FILL;paint.color=if(question.s("status")=="Correct")Color.rgb(34,131,93) else Color.rgb(190,66,77);canvas.drawCircle(x,top,3f,paint) }
                paint.style=Paint.Style.FILL
            }
        }
        if(history.isNotEmpty()) {
            val top=y+questionRows*height+12
            txt("دورهای قبلی: "+history.joinToString("  •  ") { "دور ${it.optInt("round")}: ${it.opt("totalScore")}" },24f,top,547f,9f)
        }
        line(807f);txt("نسخهٔ نشر ${data.optInt("publicationVersion")} • $page / $total",24f,816f,547f,8f)
    }
    companion object {
        private val rankScopes=listOf("overall" to "nationalPopulation","province" to "provincePopulation","school" to "schoolPopulation","class" to "classPopulation")
        private val scopeNames=mapOf("overall" to "کشور","province" to "ولایت","school" to "مکتب","class" to "شعبه")
        /** Older payloads omit hasResult; they always carried an official result. */
        fun hasResult(data:JSONObject)=!data.has("hasResult") || data.optBoolean("hasResult",true)
        fun section(data:JSONObject):String? = if(data.isNull("section"))null else data.s("section").trim().takeIf { it.isNotEmpty() }
        /** A rank or population is shown only as the server sent it; null or absent is never printed as zero. */
        private fun rank(source:JSONObject,key:String):Int? = if(source.isNull(key))null else (source.opt(key) as? Number)?.toInt()?.takeIf { it>0 }
        fun rankLine(ranks:JSONObject):String? = rankScopes.mapNotNull { (key,population) ->
            val value=rank(ranks,key) ?: return@mapNotNull null
            "${scopeNames.getValue(key)} $value${rank(ranks,population)?.let { " از $it" }.orEmpty()}"
        }.takeIf { it.isNotEmpty() }?.joinToString(" • ",prefix="رتبه — ")
        fun groupRankLine(data:JSONObject):String? = (data.optJSONArray("groupRanks") ?: JSONArray()).objects().mapNotNull { group ->
            val scopes=rankScopes.mapNotNull { (key,_) -> rank(group,key)?.let { "${scopeNames.getValue(key)} $it" } }
            if(scopes.isEmpty())null else "${group.s("name")} (${scopes.joinToString("، ")})"
        }.takeIf { it.isNotEmpty() }?.joinToString(" • ",prefix="رتبهٔ گروه‌ها: ")
        private fun optionalCount(source:JSONObject,key:String) {
            if(!source.has(key) || source.isNull(key))return
            val value=source.opt(key) as? Number ?: error("رتبه یا جمعیت اطلاع‌نامه معتبر نیست.")
            require(value.toDouble().let { it.isFinite() && it>=0 && it==Math.floor(it) && it<=Int.MAX_VALUE }) { "رتبه یا جمعیت اطلاع‌نامه معتبر نیست." }
        }
        fun validate(data:JSONObject) {
            require(data.s("rendererVersion") in (1..4).map { "roshd.notice-a4-120.v$it" }) { "نسخهٔ رسم اطلاع‌نامه پشتیبانی نمی‌شود." }
            for(field in listOf("title","studentPublicId","firstName","fatherName","lastName","schoolName","examDate"))require(data.opt(field) is String && data.s(field).length<=500)
            require(!data.has("hasResult") || data.opt("hasResult") is Boolean)
            require(!data.has("section") || data.isNull("section") || (data.opt("section") is String && data.s("section").length<=100))
            require(data.optInt("grade") in 1..12 && data.optInt("round") in 1..ExamPeriod.MAX_ROUND)
            require(Regex("^[0-9]{6}([0-9]|[0-9]{4})?$").matches(data.s("studentPublicId")))
            require(runCatching { java.time.OffsetDateTime.parse(data.s("examDate")) }.isSuccess || runCatching { java.time.LocalDate.parse(data.s("examDate")) }.isSuccess)
            if(!hasResult(data))return
            require(data.optInt("publicationVersion")>0)
            data.optJSONObject("ranks")?.let { ranks -> rankScopes.forEach { (rank,population) -> optionalCount(ranks,rank);optionalCount(ranks,population) } }
            if(data.has("groupRanks") && !data.isNull("groupRanks")) data.getJSONArray("groupRanks").objects().forEach { group ->
                require(group.opt("name") is String && group.s("name").length<=500);rankScopes.forEach { (rank,_) -> optionalCount(group,rank) }
            }
            val total=data.opt("totalScore") as? Number ?: error("نمرهٔ رسمی موجود نیست.");require(total.toDouble().isFinite())
            val subjects=data.getJSONArray("subjects").objects();val questions=data.getJSONArray("questions").objects()
            require(subjects.size in 1..8 && questions.size in 1..120 && subjects.map { it.s("name") }.distinct().size==subjects.size)
            require(subjects.sumOf { it.optInt("count") }==questions.size)
            subjects.forEach { require(it.s("name").length<=500 && it.optInt("count") in 1..120 && it.optInt("correct")>=0 && it.optInt("incorrect")>=0 && it.optInt("blank")>=0 && it.optInt("correct")+it.optInt("incorrect")+it.optInt("blank")==it.optInt("count"));for(key in listOf("score","percentage"))require((it.opt(key) as? Number)?.toDouble()?.isFinite()==true) }
            val valid=setOf(0,1,2,3,4,19,21,22,23,25,26,27,28,29,30,31)
            questions.forEachIndexed { index,q -> require(q.optInt("number")==index+1 && q.optInt("correctOption") in valid && q.s("status") in setOf("Correct","Incorrect","Blank") && q.s("originalState") in setOf("selected","blank","multiple","ambiguous","unreadable") && q.opt("corrected") is Boolean && (q.isNull("selectedOption") || q.optInt("selectedOption") in 1..4)) }
        }
    }
}
