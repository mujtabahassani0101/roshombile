package com.haseebullahjan.roshd.core

import org.junit.Assert.*
import org.junit.Test

class OmrEvidencePolicyTest {
    private fun parse(question:org.json.JSONObject)=OmrResultParser.observations(json("questions" to listOf(question.put("question",1))),1).single()
    @Test fun positiveInkEvidenceCannotBeSilentlyConvertedToAnAutomaticBlank() {
        for(reason in listOf("faint_ink_candidate","multiple_visible_marks","ink_readings_disagree")) {
            val answer=parse(json("status" to "BLANK","selectedOptions" to emptyList<String>(),"confidence" to 1,"needsReview" to true,"reviewReason" to reason))
            assertEquals("ambiguous",answer.s("state"));assertTrue(ScanReview.needsReview(answer));assertTrue(answer.isNull("selectedOption"))
            assertEquals(reason,answer.s("reviewReason"))
        }
    }
    @Test fun emptyBubblesAndAnUnconfirmedDarknessHeuristicStillNeedNoReview() {
        for(reason in listOf("","faint_ink_in_blank_candidate")) {
            val answer=parse(json("status" to "BLANK","selectedOptions" to emptyList<String>(),"confidence" to .4,"needsReview" to true,"reviewReason" to reason))
            assertEquals("blank",answer.s("state"));assertFalse(ScanReview.needsReview(answer))
        }
        assertEquals("blank",parse(json("status" to "BLANK","needsReview" to false,"reviewReason" to "faint_ink_candidate")).s("state"))
    }
    @Test fun independentlyConfirmedMultipleMarksReplaceTheSingleCandidateWithoutReview() {
        val answer=parse(json("status" to "MARKED","selectedOptions" to listOf("1"),"confidence" to .7,
            "verifiedMultipleOptions" to listOf(4,1),"verificationPasses" to 2,"reviewReason" to "multiple_ink_confirmed"))
        assertEquals("multiple",answer.s("state"));assertTrue(answer.isNull("selectedOption"))
        assertEquals("[1,4]",answer.a("markedOptions").toString());assertFalse(ScanReview.needsReview(answer))
        val packet=ScanReview.observations(json("answers" to listOf(answer)),1).single()
        assertEquals("multiple",packet.s("state"));assertTrue(packet.isNull("selectedOption"))
    }
    @Test fun malformedOrSinglePassMultipleEvidenceCannotInventAMultiple() {
        for(options in listOf(listOf<Any>(1),listOf(1,1),listOf(1,5),listOf(1,2.5),listOf("1","2"))) {
            val answer=parse(json("status" to "MARKED","selectedOptions" to listOf("2"),"confidence" to 1,
                "verifiedMultipleOptions" to options,"verificationPasses" to 3,"reviewReason" to "multiple_ink_confirmed"))
            assertEquals("selected",answer.s("state"));assertEquals(2,answer.optInt("selectedOption"))
        }
        for(passes in listOf(0,1))assertEquals("selected",parse(json("status" to "MARKED","selectedOptions" to listOf("2"),"confidence" to 1,
            "verifiedMultipleOptions" to listOf(1,2),"verificationPasses" to passes,"reviewReason" to "multiple_ink_confirmed")).s("state"))
    }
    @Test fun migrationRestoresSavedInkWarningsAndPreservesHumanBlankConfirmations() {
        val raw=listOf(1,2,3).map { n -> json("question" to n,"status" to "BLANK","selectedOptions" to emptyList<String>(),"needsReview" to true,
            "reviewReason" to if(n==3)"faint_ink_in_blank_candidate" else "faint_ink_candidate") }
        val answers=listOf(1,2,3).map { n -> json("questionNumber" to n,"state" to "blank","selectedOption" to null,"reviewed" to (n==2)) }
        val record=json("state" to "ready","answerPolicyVersion" to 1,"sourceImageSha256" to "unchanged","answers" to answers,"engine" to json("questions" to raw))
        assertTrue(ScanReview.applyAnswerPolicy(record));assertEquals(1,ScanReview.pendingCount(record))
        assertEquals(listOf("ambiguous","blank","blank"),record.a("answers").objects().map { it.s("state") })
        assertTrue(record.a("answers").getJSONObject(1).optBoolean("reviewed"));assertEquals("unchanged",record.s("sourceImageSha256"))
        val saved=record.toString();assertFalse(ScanReview.applyAnswerPolicy(record));assertEquals(saved,record.toString())
    }
}
