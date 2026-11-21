package com.haseebullahjan.roshd.core

import java.io.File
import org.json.JSONObject

/** Binds derived colors to the exact saved answers and the measured image, never to a template guess. */
object GradedSheet {
    /** 2: wrong answers also show the key's option in green and a score footer is drawn. */
    const val COLOR_POLICY_VERSION=2
    fun answersHash(record:JSONObject)=sha256(canonical(record.a("answers")))
    fun current(record:JSONObject):JSONObject?=record.optJSONObject("annotation")?.takeIf {
        it.s("state")=="ready" && it.s("answersSha256")==answersHash(record) &&
            it.s("sourceImageSha256").isNotEmpty() && it.s("sourceImageSha256")==record.s("sourceImageSha256")
    }
    fun image(record:JSONObject):String?=current(record)?.s("path")?.takeIf { it.isNotBlank() && File(it).isFile }
    fun matchesKey(record:JSONObject,pack:OfflineExam):Boolean {
        val grade=current(record)?.o("grade") ?: return false
        val key=pack.manifest.o("offlineGrading")
        return key.s("releaseVersion").isNotBlank() && key.s("answerKeyVersion").isNotBlank() &&
            grade.s("releaseVersion")==key.s("releaseVersion") && grade.s("answerKeyVersion")==key.s("answerKeyVersion")
    }
    fun displayScore(record:JSONObject)=current(record)?.optJSONObject("grade") ?: record.o("offlineScore")

    /** Background colors cannot overwrite a later reread, edit, score or identity. */
    fun acceptsDerivedImage(current:JSONObject,snapshot:JSONObject):Boolean =
        current.s("state")=="ready" && GradedSheet.current(current)==null && current.optBoolean("annotationPending") &&
            listOf("id","qrText","studentPublicId","examId","schoolId","sourceImageSha256").all { current.s(it)==snapshot.s(it) } &&
            current.s("sourceImageSha256").isNotBlank() && answersHash(current)==answersHash(snapshot) &&
            canonical(current.o("offlineScore"))==canonical(snapshot.o("offlineScore"))

    fun request(record:JSONObject,score:JSONObject,sheet:JSONObject,outputPath:String,accepted:List<IntArray>?=null):JSONObject {
        val count=record.o("exam").optInt("questionCount")
        require(SheetFamilies.forCount(count).isNotEmpty() && sheet.optBoolean("success"))
        val answers=record.a("answers").objects();ScanReview.observations(record,count)
        require(score.optInt("questionCount")==count && score.s("answersSha256")==answersHash(record)) { "نمره با پاسخ‌های فعلی مطابق نیست." }
        val verdicts=score.a("questionVerdicts").strings()
        require(verdicts.size==count)
        require(accepted==null || (accepted.size==count && accepted.all { choices -> choices.size<=4 && choices.all { it in 1..4 } && choices.distinct().size==choices.size }))
        val geometry=sheet.o("annotationGeometry");val width=geometry.optInt("width");val height=geometry.optInt("height")
        require(width>=100 && height>=100 && width.toLong()*height<=24_000_000)
        val rows=geometry.a("questions").objects()
        require(rows.map { it.optInt("questionNumber") }.distinct().size==rows.size)
        val byQuestion=rows.associateBy { it.optInt("questionNumber") }
        val marks=mutableListOf<JSONObject>()
        for((index,answer) in answers.withIndex()) {
            val state=answer.s("state");val verdict=verdicts[index]
            require(when(state) {
                "selected"->verdict in setOf("correct","incorrect","voided")
                "multiple"->verdict in setOf("multiple","voided")
                "blank"->verdict=="blank"
                "ambiguous","unreadable"->verdict in setOf("review","voided")
                else->false
            }) { "وضعیت رنگ و پاسخ مطابق نیست." }
            val review=ScanReview.needsReview(answer)
            val chosen=if(review)emptyList() else when(verdict) {
                "correct","incorrect"->listOf(answer.optInt("selectedOption"))
                "multiple"->answer.a("markedOptions").let { array -> (0 until array.length()).map { array.getInt(it) } }.also { require(it.size>=2 && it.distinct().size==it.size) }
                else->emptyList()
            }
            // Key hints: yellow on a blank question, green beside a wrong answer.
            // Uncertain readings and voided questions get no hint.
            val hints=if(review || accepted==null || verdict !in setOf("blank","incorrect","multiple"))emptyList()
                else accepted[index].filter { it !in chosen }
            val row=byQuestion[index+1] ?: error("مختصات سؤال موجود نیست.")
            val options=row.a("options").objects()
            require(options.map { it.optInt("option") }.sorted()==listOf(1,2,3,4))
            for(option in chosen+hints) {
                require(option in 1..4)
                val point=options.single { it.optInt("option")==option }
                fun finite(key:String)=(point.opt(key) as? Number)?.toDouble()?.takeIf(Double::isFinite) ?: error("مختصات گزینه معتبر نیست.")
                val x=finite("x");val y=finite("y");val radius=finite("radius")
                require(radius in 2.0..100.0 && x-radius*1.5>=0 && x+radius*1.5<width && y-radius*1.5>=0 && y+radius*1.5<height)
                marks.add(json("questionNumber" to index+1,"option" to option,"x" to x,"y" to y,"radius" to radius,
                    "verdict" to when {
                        option in hints->if(verdict=="blank")"blank_correct" else "missed_correct"
                        verdict=="correct"->"correct"
                        else->"incorrect"
                    }))
            }
        }
        val input=sheet.s("normalizedImagePath")
        require(input.isNotBlank() && File(input).canonicalPath!=File(outputPath).canonicalPath &&
            File(record.s("sourcePath")).canonicalPath!=File(outputPath).canonicalPath)
        return json("imagePath" to input,"outputPath" to outputPath,"width" to width,"height" to height,"questionCount" to count,"marks" to marks)
            .apply { footerBox(width,height,rows)?.let { put("footerBox",it) } }
    }
    /** The score footer sits under the answer grid, between the bottom corner
     *  squares, over the printed "do not write here" strip; never over a bubble. */
    fun footerBox(width:Int,height:Int,rows:List<JSONObject>):JSONObject? {
        val bottomOfGrid=rows.flatMap { it.a("options").objects() }.maxOfOrNull { it.optDouble("y")+it.optDouble("radius")*1.5 } ?: return null
        val left=Math.round(width*.12).toInt();val right=Math.round(width*.88).toInt()
        val bottom=(height*.975).toInt()
        val top=maxOf(kotlin.math.ceil(bottomOfGrid+height*.006).toInt(),bottom-(height*.075).toInt())
        if(bottom-top<height*.035)return null
        return json("left" to left,"top" to top,"pixelWidth" to right-left,"pixelHeight" to bottom-top)
    }
    /** The verified key stays inside NativeHostActivity while it renders yellow hints. */
    fun nativeRequest(record:JSONObject,sheet:JSONObject,outputPath:String,showPoints:Boolean=false)=json(
        "record" to json("sourcePath" to record.s("sourcePath"),"sourceImageSha256" to record.s("sourceImageSha256"),
            "exam" to json("questionCount" to record.o("exam").optInt("questionCount")),"answers" to record.a("answers")),
        "sheet" to json("success" to sheet.optBoolean("success"),"normalizedImagePath" to sheet.s("normalizedImagePath"),"annotationGeometry" to sheet.o("annotationGeometry")),
        "outputPath" to outputPath,"showPoints" to showPoints)
    fun metadata(record:JSONObject,score:JSONObject,path:String,result:JSONObject)=json(
        "state" to "ready","path" to path,"answersSha256" to answersHash(record),"sourceImageSha256" to record.s("sourceImageSha256"),
        "createdAt" to now(),"colorPolicyVersion" to COLOR_POLICY_VERSION,"width" to result.optInt("width"),"height" to result.optInt("height"),"correctMarks" to result.optInt("correctMarks"),"incorrectMarks" to result.optInt("incorrectMarks"),"blankCorrectMarks" to result.optInt("blankCorrectMarks"),"missedCorrectMarks" to result.optInt("missedCorrectMarks"),"scoreFooter" to result.optBoolean("footer"),
        "grade" to score.copy().apply { remove("questionVerdicts") })
    fun invalidate(record:JSONObject) { record.remove("annotation");record.remove("annotationWarning");record.remove("annotationPending") }
}
