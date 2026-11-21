package com.haseebullahjan.roshd.core

import com.haseebullahjan.roshd.OfflineScore
import java.math.BigDecimal
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LiveScanFeedbackTest {
    private fun record(count:Int=40)=json("id" to "photo-one","state" to "ready","studentName" to "Student One",
        "exam" to json("questionCount" to count),"captureToResultMs" to 780,
        "offlineScore" to json("correctCount" to 0,"questionCount" to count,"rawScore" to "0","scoringPolicyVersion" to 1))
    @Test fun fullPhysicalSheetsProduceExactDecimalMarksAndCounts() {
        for(count in listOf(40,75,100)) {
            val observations=List(count) { index->mapOf<String,Any?>("state" to "selected","selectedOption" to if(index<count-5)1 else 2) }
            val aggregate=OfflineScore.aggregate(IntArray(count) { 1 },observations,
                OfflineScore.Policy(1,BigDecimal("2.5"),BigDecimal("-0.25"),BigDecimal.ZERO))
            val value=LiveScanFeedback.from(record(count).put("offlineScore",JSONObject(aggregate)))
            assertEquals(count-5,value.correct);assertEquals(count,value.count)
            assertEquals(when(count) { 40->"86.25";75->"173.75";else->"236.25" },value.rawScore)
            assertFalse(value.needsReview);assertEquals(780,value.elapsedMs)
        }
    }
    @Test fun zeroIsARealScoreAndMustRemainVisible() {
        val value=LiveScanFeedback.from(record())
        assertEquals(0,value.correct);assertEquals("0",value.rawScore)
    }
    @Test fun missingPolicyNeverTurnsCorrectCountIntoInventedMarks() {
        val input=record();input.o("offlineScore").remove("scoringPolicyVersion")
        val value=LiveScanFeedback.from(input)
        assertEquals(0,value.correct);assertNull(value.rawScore)
    }
    @Test fun missingKeyOrIncompleteReadCannotReuseAStaleGrade() {
        val input=record().put("state","read_failed").put("error","Unreadable")
        assertNull(LiveScanFeedback.from(input).rawScore)
        input.put("state","ready").remove("offlineScore")
        assertNull(LiveScanFeedback.from(input).correct)
    }
    @Test fun mismatchedInvalidAndRetiredCountsNeverDisplayAScore() {
        val mismatch=record();mismatch.o("offlineScore").put("questionCount",100)
        assertNull(LiveScanFeedback.from(mismatch).correct)
        for(count in listOf(0,60,120))assertNull(LiveScanFeedback.from(record(count)).rawScore)
        for(correct in listOf(-1,41,0.5)) {
            val input=record();input.o("offlineScore").put("correctCount",correct)
            assertNull(LiveScanFeedback.from(input).correct)
        }
    }
    @Test fun ambiguousAnswersAndEngineQualityRemainVisibleNextToTheEstimate() {
        assertTrue(LiveScanFeedback.from(record().put("reviewCount",2)).needsReview)
        assertTrue(LiveScanFeedback.from(record().put("engine",json("reviewRequired" to true))).needsReview)
        val input=record();input.o("offlineScore").put("needsReview",true)
        assertTrue(LiveScanFeedback.from(input).needsReview)
    }
    @Test fun everyCompletionKeepsItsOwnIdentityEvenWhenWorkersFinishInReverseOrder() {
        val first=record();val second=record(100).put("id","photo-two").put("studentName","Student Two")
        val results=listOf(second,first).map(LiveScanFeedback::from)
        first.put("studentName","Changed later")
        assertEquals(listOf("photo-two","photo-one"),results.map { it.id })
        assertEquals(listOf("Student Two","Student One"),results.map { it.student })
        assertEquals(listOf(100,40),results.map { it.count })
    }
}
