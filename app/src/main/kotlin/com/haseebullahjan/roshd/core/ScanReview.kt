package com.haseebullahjan.roshd.core

import org.json.JSONObject

/** A reread replaces derived observations, while captured evidence keeps its identity. */
object ScanReview {
    const val ANSWER_POLICY_VERSION=2
    private fun validMultiple(answer:JSONObject):Boolean {
        val options=answer.a("markedOptions")
        val values=(0 until options.length()).map { options.opt(it) }
        return values.size in 2..4 && values.all { it is Number && it.toDouble()==it.toInt().toDouble() && it.toInt() in 1..4 } &&
            values.map { (it as Number).toInt() }.distinct().size==values.size
    }
    fun needsReview(answer:JSONObject)=when(answer.s("state")) {
        "blank"->false
        "multiple"->!validMultiple(answer)
        "selected"->answer.optBoolean("needsReview")
        else->true
    }
    /** Update local review policy, preserving observations, manual edits and signed history. */
    fun applyAnswerPolicy(record:JSONObject):Boolean {
        if(record.optBoolean("sealed") || record.s("state")!="ready" || record.optInt("answerPolicyVersion")>=ANSWER_POLICY_VERSION)return false
        val saved=record.a("answers").objects()
        // Earlier versions removed native ink warnings when a question was
        // labelled blank. Restore only positive evidence from its saved read;
        // preserve explicit human confirmations and all signed history.
        val parsed=runCatching { OmrResultParser.observations(record.o("engine"),saved.size).associateBy { it.optInt("questionNumber") } }.getOrDefault(emptyMap())
        saved.forEach { answer ->
            val original=parsed[answer.optInt("questionNumber")]
            if(answer.s("state")=="blank" && !answer.optBoolean("reviewed") && original?.s("state")=="ambiguous" && original.optBoolean("needsReview")) {
                answer.put("state","ambiguous").put("needsReview",true).put("reviewReason",original.s("reviewReason"))
            }
            if(answer.s("state")=="blank" || (answer.s("state")=="multiple" && validMultiple(answer))) {
                answer.remove("needsReview");answer.remove("reviewReason")
            }
        }
        record.put("answerPolicyVersion",ANSWER_POLICY_VERSION).remove("submissionError")
        updateWarning(record)
        return true
    }
    fun pendingCount(record:JSONObject)=record.a("answers").objects().count(::needsReview)
    fun updateWarning(record:JSONObject) {
        val count=pendingCount(record)
        record.put("reviewCount",count).remove("reviewWarning")
        if(count>0)record.put("reviewWarning","$count پاسخ نیاز به بازبینی دارد؛ پیش از ارسال، گزینه یا سفیدبودن هر سؤال را تأیید کنید.")
    }
    fun confirmAnswer(answer:JSONObject,option:Int?) {
        require(option==null || option in 1..4)
        answer.put("state",if(option==null)"blank" else "selected").put("selectedOption",option ?: JSONObject.NULL)
            .put("markedOptions",wrap(if(option==null)emptyList<Int>() else listOf(option)))
            .put("needsReview",false).put("reviewed",true)
    }
    fun requireReviewed(record:JSONObject) {
        require(pendingCount(record)==0) { "${pendingCount(record)} پاسخ نیاز به بازبینی دارد؛ در صفحهٔ بازبینی، پاسخ این سؤال‌ها را تأیید کنید." }
    }
    fun engineNeedsReview(record:JSONObject):Boolean {
        val engine=record.o("engine")
        if(!engine.optBoolean("reviewRequired"))return false
        val reasons=engine.a("reviewReasons").strings()
        // Manual confirmation resolves answer uncertainty, while image and
        // geometry warnings continue to describe the original photo.
        return pendingCount(record)>0 || reasons.isEmpty() ||
            reasons.any { it !in setOf("answer_confidence_low","answers_ambiguous") }
    }
    fun freshRead(record:JSONObject):JSONObject {
        require(!record.optBoolean("sealed")) { "درخواست امضاشده قابل تغییر نیست." }
        return record.copy().apply {
            listOf("engine","qrText","schoolId","examId","exam","studentPublicId","studentName","clientSheetId",
                "answers","offlineScore","serverScore","scoreWarning","reviewedAt","reviewCount","reviewWarning","submissionError","error","annotation","annotationWarning","annotationPending").forEach { remove(it) }
            put("state","pending")
        }
    }
    fun matchesFrozenSource(record:JSONObject,pack:OfflineExam,row:RosterRow):Boolean {
        val source=record.optJSONObject("sourceSnapshot") ?: return true
        return source.s("identityMode")=="qr-lite-v1" && source.s("examId")==pack.exam.id && source.s("clientSheetId")==row.sheetId &&
            source.s("studentPublicId")==row.code && source.s("qrText")==row.sheetQr
    }
    fun observations(record:JSONObject,count:Int):List<JSONObject> {
        val answers=record.a("answers").objects()
        require(count in 1..100 && answers.map { it.optInt("questionNumber") }==(1..count).toList()) { "پاسخ تمام سؤال‌ها باید به ترتیب موجود باشد." }
        return answers.map { answer ->
            val state=answer.s("state");val option=answer.opt("selectedOption")
            require(state in setOf("selected","multiple","blank","ambiguous","unreadable"))
            require(state!="selected" || (option is Number && option.toDouble()==option.toInt().toDouble() && option.toInt() in 1..4))
            json("state" to state,"selectedOption" to if(state=="selected")option else null)
        }
    }
}
