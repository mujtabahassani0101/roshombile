package com.haseebullahjan.roshd.core

import java.math.BigDecimal
import org.json.JSONObject

/** An immutable UI result for one saved photo, never a transport receipt or an official score. */
data class LiveScanFeedback(val id:String,val student:String,val pageIdentity:String,val state:String,val correct:Int?,val count:Int?,
                            val rawScore:String?,val needsReview:Boolean,val message:String,val elapsedMs:Long,val coloredPath:String?=null) {
    companion object {
        fun from(record:JSONObject):LiveScanFeedback {
            val score=GradedSheet.displayScore(record)
            val count=record.o("exam").optInt("questionCount")
            val number=score.opt("correctCount") as? Number
            val correct=number?.toInt()?.takeIf { number.toDouble()==it.toDouble() }
            val scored=record.s("state")=="ready" && SheetFamilies.forCount(count).isNotEmpty() && score.optInt("questionCount")==count && correct!=null && correct in 0..count
            val raw=score.opt("rawScore")?.takeUnless { it==JSONObject.NULL }?.toString()
                ?.takeIf { score.optInt("scoringPolicyVersion")>0 && runCatching { BigDecimal(it) }.isSuccess }
            val needsReview=record.optInt("reviewCount")>0 || ScanReview.pendingCount(record)>0 || score.optBoolean("needsReview") || ScanReview.engineNeedsReview(record)
            return LiveScanFeedback(record.s("id"),record.s("studentName").ifBlank { record.s("studentPublicId") },record.s("qrText"),record.s("state"),
                if(scored)correct else null,if(scored)count else null,if(scored)raw else null,needsReview,
                record.s("error").ifBlank { record.s("reviewWarning").ifBlank { record.s("scoreWarning").ifBlank { record.s("annotationWarning") } } },record.optLong("captureToResultMs").coerceAtLeast(0),GradedSheet.image(record))
        }
    }
}
