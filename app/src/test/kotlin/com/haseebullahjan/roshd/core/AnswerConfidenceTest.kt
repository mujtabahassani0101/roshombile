package com.haseebullahjan.roshd.core

import org.junit.Assert.*
import org.junit.Test

class AnswerConfidenceTest {
    @Test fun recoveredHighConfidenceSourceReadingStillNeedsExplicitConfirmation() {
        val sheet=json("questions" to listOf(json("question" to 1,"status" to "MARKED","selectedOptions" to listOf(2),
            "confidence" to .99,"needsReview" to true,"reviewReason" to "ink_visibility_recovered",
            "sourceOriginalStatus" to "BLANK","sourceOriginalOption" to null,"verificationPasses" to 2)))
        val answer=OmrResultParser.observations(sheet,1).single()
        val record=json("answers" to listOf(answer))
        assertEquals(2,answer.getInt("selectedOption"));assertEquals("ink_visibility_recovered",answer.s("reviewReason"))
        assertTrue(ScanReview.needsReview(answer))
        assertThrows(IllegalArgumentException::class.java) { ScanReview.requireReviewed(record) }
        assertEquals(setOf("state","selectedOption"),ScanReview.observations(record,1).single().keys().asSequence().toSet())
        ScanReview.confirmAnswer(answer,2);ScanReview.requireReviewed(record)
        assertFalse(ScanReview.needsReview(answer));assertEquals(2,answer.getInt("selectedOption"))
    }
    private fun read(status:String="MARKED",confidence:Any=0.7)=OmrResultParser.observations(json("questions" to listOf(
        json("question" to 1,"status" to status,"selectedOptions" to if(status=="MARKED")listOf(3) else emptyList<Int>(),"confidence" to confidence))),1).single()
    @Test fun uncertainCandidateSurvivesParsingAndCannotBeSubmittedUntilConfirmed() {
        val answer=read();val record=json("answers" to listOf(answer))
        assertEquals("selected",answer.s("state"));assertEquals(3,answer.getInt("selectedOption"))
        assertTrue(ScanReview.needsReview(answer));ScanReview.updateWarning(record)
        assertEquals(1,record.optInt("reviewCount"));assertTrue(record.s("reviewWarning").isNotEmpty())
        assertThrows(IllegalArgumentException::class.java) { ScanReview.requireReviewed(record) }
        // Tapping the existing candidate is an explicit confirmation too.
        ScanReview.confirmAnswer(answer,3);ScanReview.updateWarning(record)
        ScanReview.requireReviewed(record)
        assertEquals(0,record.optInt("reviewCount"));assertFalse(record.has("reviewWarning"))
        assertEquals(3,answer.getInt("selectedOption"));assertEquals(0.7,answer.getDouble("confidence"),0.0)
        assertTrue(answer.optBoolean("reviewed"))
    }
    @Test fun aBlankObservationNeedsNoConfirmationAtAnyConfidence() {
        val answer=read("BLANK",0.3)
        assertFalse(ScanReview.needsReview(answer));assertEquals("blank",answer.s("state"))
        assertTrue(answer.isNull("selectedOption"))
        ScanReview.requireReviewed(json("answers" to listOf(answer)))
    }
    @Test fun faintInkReviewEvidenceDoesNotAlterTheOriginalBlankDecisionOrConfidence() {
        val sheet=json("questions" to listOf(json("question" to 1,"status" to "BLANK","selectedOptions" to emptyList<Int>(),"confidence" to 0.99,"needsReview" to true)))
        val answer=OmrResultParser.observations(sheet,1).single()
        assertEquals("blank",answer.s("state"));assertEquals(0.99,answer.getDouble("confidence"),0.0)
        assertTrue(answer.isNull("selectedOption"));assertFalse(ScanReview.needsReview(answer))
        ScanReview.requireReviewed(json("answers" to listOf(answer)))
    }
    @Test fun definiteMultipleMarksAreIncorrectObservationsWithoutManualReview() {
        val sheet=json("questions" to listOf(json("question" to 1,"status" to "MULTI","selectedOptions" to listOf("A","C"),
            "confidence" to 0.2,"needsReview" to true,"reviewReason" to "multiple_visible_marks")))
        val answer=OmrResultParser.observations(sheet,1).single()
        assertEquals("multiple",answer.s("state"));assertTrue(answer.isNull("selectedOption"))
        assertEquals(listOf(1,3),(0 until answer.a("markedOptions").length()).map { answer.a("markedOptions").getInt(it) })
        assertFalse(ScanReview.needsReview(answer));ScanReview.requireReviewed(json("answers" to listOf(answer)))
    }
    @Test fun unknownReadingsCannotBePassedOffAsBlankOrDefiniteMultipleMarks() {
        for(status in listOf("AMBIGUOUS","UNREADABLE","MISSING","MULTI")) {
            val sheet=json("questions" to listOf(json("question" to 1,"status" to status,"selectedOptions" to emptyList<Int>(),"confidence" to 1.0)))
            val answer=OmrResultParser.observations(sheet,1).single()
            assertTrue(status,ScanReview.needsReview(answer));assertNotEquals("blank",answer.s("state"))
        }
    }
    @Test fun confidenceBoundaryAndLegacyRecordsStayCompatible() {
        assertTrue(ScanReview.needsReview(read(confidence=0.84999)))
        for(confidence in listOf(0.85,1.0))assertFalse(ScanReview.needsReview(read(confidence=confidence)))
        val legacy=json("questions" to listOf(json("question" to 1,"status" to "MARKED","selectedOptions" to listOf(2))))
        assertFalse(ScanReview.needsReview(OmrResultParser.observations(legacy,1).single()))
    }
    @Test fun invalidConfidenceNeverBecomesAutomaticApproval() {
        for(value in listOf(-0.1,1.1,"0.99",org.json.JSONObject.NULL))assertTrue(ScanReview.needsReview(read(confidence=value)))
    }
    @Test fun staleSummaryCannotBypassReviewAndEachQuestionNeedsItsOwnConfirmation() {
        val first=read();val second=read().put("questionNumber",2)
        val record=json("answers" to listOf(first,second),"reviewCount" to 0)
        assertEquals(2,ScanReview.pendingCount(record))
        ScanReview.confirmAnswer(first,4)
        assertEquals(1,ScanReview.pendingCount(record))
        assertThrows(IllegalArgumentException::class.java) { ScanReview.requireReviewed(record) }
        ScanReview.confirmAnswer(second,null);ScanReview.requireReviewed(record)
    }
    @Test fun localReviewMetadataIsExcludedFromOfflineGrading() {
        val result=ScanReview.observations(json("answers" to listOf(read())),1).single()
        assertEquals(setOf("state","selectedOption"),result.keys().asSequence().toSet())
        assertEquals(3,result.getInt("selectedOption"))
    }
    @Test fun confirmingAnswersResolvesAnswerWarningsButPreservesGeometryWarnings() {
        val answer=read();val record=json("answers" to listOf(answer),"engine" to json("reviewRequired" to true,"reviewReasons" to listOf("answer_confidence_low")))
        assertTrue(ScanReview.engineNeedsReview(record))
        ScanReview.confirmAnswer(answer,3);record.put("reviewedAt","now")
        assertFalse(ScanReview.engineNeedsReview(record))
        record.o("engine").put("reviewReasons",wrap(listOf("answer_confidence_low","answer_geometry_reprojection_high")))
        assertTrue(ScanReview.engineNeedsReview(record))
    }
}
