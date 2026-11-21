package com.haseebullahjan.roshd.core

import org.json.JSONObject

object OmrResultParser {
    private val visibleBlankReasons=setOf("faint_ink_candidate","multiple_visible_marks","ink_readings_disagree")
    private fun visibleInkInBlank(question:JSONObject)=question.s("status").uppercase()=="BLANK" &&
        question.optBoolean("needsReview") && question.s("reviewReason") in visibleBlankReasons
    fun qr(sheet:JSONObject):String {
        val value=sheet.o("qrCode")
        if(value.opt("found")!=true || value.s("status")!="FOUND")return ""
        return value.s("text")
    }
    fun option(value:Any?):Int = when(value) {
        is Number -> value.toInt()
        is String -> value.toIntOrNull() ?: when(value.trim().uppercase()) { "A"->1;"B"->2;"C"->3;"D"->4;else->0 }
        else -> 0
    }
    fun observations(sheet:JSONObject,count:Int):List<JSONObject> {
        require(count in 1..100)
        val raw=sheet.a("questions").objects()
        val numbers=raw.map { it.optInt("question",it.optInt("question_number")) }
        require(numbers.size==numbers.distinct().size && (1..count).all { it in numbers }) { "خوانش سؤال‌ها کامل و یکتا نیست." }
        return raw.filter { it.optInt("question",it.optInt("question_number")) in 1..count }.sortedBy { it.optInt("question",it.optInt("question_number")) }.map { question ->
            val options=question.a("selectedOptions")
            val confirmed=question.a("verifiedMultipleOptions")
            val verifiedMultiple=(0 until confirmed.length()).map { confirmed.opt(it) }.takeIf { values ->
                question.s("reviewReason")=="multiple_ink_confirmed" && question.optInt("verificationPasses")>=2 &&
                    values.size in 2..4 && values.all { it is Number && it.toDouble()==it.toInt().toDouble() && it.toInt() in 1..4 } &&
                    values.map { (it as Number).toInt() }.distinct().size==values.size
            }?.map { (it as Number).toInt() }?.sorted()
            val marked=verifiedMultiple ?: (0 until options.length()).map { option(options.opt(it)) }.filter { it in 1..4 }.distinct().sorted()
            val status=question.s("status").uppercase()
            val state=when { verifiedMultiple!=null -> "multiple";visibleInkInBlank(question) -> "ambiguous";status=="BLANK" -> "blank";status=="MARKED" && marked.size==1 -> "selected";status=="MULTI" && marked.size>=2 -> "multiple";status in setOf("AMBIGUOUS","MULTI") -> "ambiguous";else -> "unreadable" }
            json("questionNumber" to question.optInt("question",question.optInt("question_number")),"state" to state,"selectedOption" to if(state=="selected")marked.single() else null).apply {
                if(state=="multiple")put("markedOptions",wrap(marked))
                val automatic=state in setOf("blank","multiple")
                if(!automatic && question.optBoolean("needsReview")) {
                    put("needsReview",true)
                    question.s("reviewReason").takeIf { it in setOf("ink_visibility_recovered","ink_readings_disagree","multiple_visible_marks","faint_ink_candidate","faint_ink_in_blank_candidate") }
                        ?.let { put("reviewReason",it) }
                }
                // Keep the engine's candidate and its uncertainty together. These
                // fields are local review metadata, excluded from signed answers.
                if(question.has("confidence")) {
                    val confidence=(question.opt("confidence") as? Number)?.toDouble()?.takeIf { it.isFinite() && it in 0.0..1.0 }
                    if(confidence!=null)put("confidence",confidence)
                    if(!automatic && (confidence==null || confidence<0.85))put("needsReview",true)
                }
            }
        }
    }
}
