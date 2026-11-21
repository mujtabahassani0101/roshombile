package com.haseebullahjan.roshd.core

import org.junit.Assert.*
import org.junit.Test

class ScanReviewTest {
    private val exam=Exam(json("id" to "10000000-0000-0000-0000-000000000001","schoolId" to "20000000-0000-0000-0000-000000000001","questionCount" to 2))
    private val row=RosterRow("001234","A","exact-QR","card","نام",exam.id)
    private val pack=OfflineExam(exam,json(),json(),listOf(row))
    private fun frozen()=json("identityMode" to "qr-lite-v1","examId" to exam.id,"clientSheetId" to row.sheetId,"studentPublicId" to row.code,"qrText" to row.sheetQr)
    @Test fun rereadRemovesPriorIdentityAnswersAndScoresWithoutChangingEvidence() {
        val record=json("id" to "original-id","sourcePath" to "/private/legacy-original-id.jpg","sourceImageSha256" to "original-hash","createdAt" to "original-time","scanId" to "capture-id","sourceSnapshot" to frozen(),
            "schoolId" to exam.schoolId,"examId" to exam.id,"studentName" to "old-name","studentPublicId" to row.code,"qrText" to row.sheetQr,"answers" to listOf(json("questionNumber" to 1)),"offlineScore" to json("correctCount" to 1),"serverScore" to json("rawScore" to 99),"reviewedAt" to "old-review","scoreWarning" to "old-warning","error" to "old-error","state" to "ready")
        val saved=record.toString();val next=ScanReview.freshRead(record)
        assertEquals(saved,record.toString())
        listOf("id","sourcePath","sourceImageSha256","createdAt","scanId","sourceSnapshot").forEach { assertEquals(record.opt(it).toString(),next.opt(it).toString()) }
        listOf("schoolId","examId","studentName","studentPublicId","qrText","answers","offlineScore","serverScore","reviewedAt","scoreWarning","error").forEach { assertFalse(it,next.has(it)) }
        assertEquals("pending",next.s("state"))
        val annotated=record.copy().put("annotation",json("state" to "ready")).put("annotationWarning","old")
        assertFalse(ScanReview.freshRead(annotated).has("annotation"));assertFalse(ScanReview.freshRead(annotated).has("annotationWarning"))
    }
    @Test fun sealedRereadIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { ScanReview.freshRead(json("sealed" to true)) }
    }
    @Test fun capturedIdentityCannotBeReassignedByRereading() {
        assertTrue(ScanReview.matchesFrozenSource(json(),pack,row))
        val record=json("sourceSnapshot" to frozen())
        assertTrue(ScanReview.matchesFrozenSource(record,pack,row))
        assertFalse(ScanReview.matchesFrozenSource(record,pack,row.copy(sheetQr="EXACT-QR")))
        assertFalse(ScanReview.matchesFrozenSource(record,pack,row.copy(code="1234")))
        record.o("sourceSnapshot").put("examId","different-exam")
        assertFalse(ScanReview.matchesFrozenSource(record,pack,row))
    }
    @Test fun legacyFrozenCaptureIsNotSilentlyConvertedToAnotherIdentityMode() {
        assertFalse(ScanReview.matchesFrozenSource(json("sourceSnapshot" to frozen().put("identityMode","sheet-instance-v1")),pack,row))
    }
    @Test fun gradingUsesCurrentOrderedObservationsAndDoesNotExposeOtherFields() {
        val record=json("answers" to listOf(json("questionNumber" to 1,"state" to "selected","selectedOption" to 4,"studentName" to "private"),json("questionNumber" to 2,"state" to "blank","selectedOption" to 2)))
        val observations=ScanReview.observations(record,2)
        assertEquals(4,observations[0].getInt("selectedOption"));assertFalse(observations[0].has("studentName"))
        assertTrue(observations[1].isNull("selectedOption"));assertEquals(2,record.a("answers").getJSONObject(1).getInt("selectedOption"))
    }
    @Test fun incompleteDuplicateAndOutOfOrderQuestionsCannotBeGraded() {
        for(numbers in listOf(listOf(1),listOf(1,1),listOf(2,1),listOf(1,3))) {
            val record=json("answers" to numbers.map { json("questionNumber" to it,"state" to "blank") })
            assertThrows(IllegalArgumentException::class.java) { ScanReview.observations(record,2) }
        }
    }
    @Test fun malformedSelectionsAndUnknownStatesCannotBeGraded() {
        for(answer in listOf(json("state" to "selected","selectedOption" to 1.5),json("state" to "selected","selectedOption" to 5),json("state" to "selected"),json("state" to "unknown"))) {
            assertThrows(IllegalArgumentException::class.java) { ScanReview.observations(json("answers" to listOf(answer.put("questionNumber",1))),1) }
        }
    }
    @Test fun existingBlankAndMultipleFlagsAreMigratedWithoutAlteringAnyObservationOrManualEdit() {
        val answers=listOf(json("questionNumber" to 1,"state" to "blank","selectedOption" to null,"needsReview" to true,"reviewReason" to "faint_ink_candidate"),
            json("questionNumber" to 2,"state" to "multiple","selectedOption" to null,"markedOptions" to listOf(1,4),"needsReview" to true),
            json("questionNumber" to 3,"state" to "selected","selectedOption" to 2,"needsReview" to true),
            json("questionNumber" to 4,"state" to "selected","selectedOption" to 3,"reviewed" to true,"needsReview" to false))
        val record=json("id" to "photo","state" to "ready","sourcePath" to "/original.jpg","sourceImageSha256" to "exact-hash","scanId" to "capture-id", "sourceSnapshot" to frozen(),
            "answers" to answers,"submissionError" to "old review blocker","reviewedAt" to "existing manual review")
        val observations=canonical(ScanReview.observations(record,4));val evidence=record.o("sourceSnapshot").toString()
        assertTrue(ScanReview.applyAnswerPolicy(record));assertEquals(1,ScanReview.pendingCount(record))
        assertEquals(observations,canonical(ScanReview.observations(record,4)))
        assertEquals(listOf(1,4),record.a("answers").getJSONObject(1).a("markedOptions").let { a -> (0 until a.length()).map(a::getInt) })
        assertEquals("existing manual review",record.s("reviewedAt"));assertEquals(evidence,record.o("sourceSnapshot").toString())
        assertEquals("exact-hash",record.s("sourceImageSha256"));assertEquals("capture-id",record.s("scanId"))
        assertTrue(record.a("answers").getJSONObject(3).optBoolean("reviewed"));assertFalse(record.has("submissionError"))
        val after=record.toString();assertFalse(ScanReview.applyAnswerPolicy(record));assertEquals(after,record.toString())
    }
    @Test fun policyMigrationNeverChangesSignedResultsAndDoesNotInventManualConfirmation() {
        val sealed=json("sealed" to true,"state" to "ready","answers" to listOf(json("state" to "blank","needsReview" to true)))
        val original=sealed.toString();assertFalse(ScanReview.applyAnswerPolicy(sealed));assertEquals(original,sealed.toString())
        val blank=json("state" to "ready","answers" to listOf(json("state" to "blank","needsReview" to true)),
            "engine" to json("reviewRequired" to true,"reviewReasons" to listOf("answer_confidence_low")))
        ScanReview.applyAnswerPolicy(blank);assertEquals(0,ScanReview.pendingCount(blank));assertFalse(ScanReview.engineNeedsReview(blank))
        assertFalse(blank.has("reviewedAt"));assertFalse(blank.a("answers").getJSONObject(0).optBoolean("reviewed"))
        blank.o("engine").put("reviewReasons",wrap(listOf("answer_geometry_reprojection_high")))
        assertTrue(ScanReview.engineNeedsReview(blank))
    }
    @Test fun malformedMultipleMetadataCannotBypassReview() {
        for(options in listOf(emptyList<Any>(),listOf(1),listOf(1,1),listOf(1,5),listOf(1,2.5),listOf("1","2")))
            assertTrue(ScanReview.needsReview(json("state" to "multiple","markedOptions" to options)))
    }
}
