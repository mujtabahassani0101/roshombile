package com.haseebullahjan.roshd

import java.math.BigDecimal
import org.junit.Assert.*
import org.junit.Test

class OfflineScoreTest {
    private val policy = OfflineScore.Policy(1, BigDecimal("3"), BigDecimal("-1"), BigDecimal.ZERO)
    private fun observation(state: String, option: Int? = null): Map<String, Any?> =
        mapOf("state" to state, "selectedOption" to option)

    @Test fun countsAndWeightedMarksDoNotCalculatePercentage() {
        for (correct in listOf(0, 7, 10)) {
            val value = OfflineScore.aggregate(IntArray(10) { 1 },
                List(10) { observation("selected", if (it < correct) 1 else 2) }, policy)
            assertEquals(correct, value["correctCount"])
            assertFalse(value.containsKey("percentage"))
            assertEquals((correct * 3 - (10 - correct)).toString(), value["rawScore"])
            assertFalse(value.containsKey("correctOptions"))
        }
    }

    @Test fun decimalMultipleBlankAndAmbiguousUseExactPoints() {
        val decimal = OfflineScore.Policy(1, BigDecimal("2.5"), BigDecimal("-0.25"), BigDecimal("0.1"))
        val value = OfflineScore.aggregate(IntArray(6) { 1 }, listOf(
            observation("selected", 1), observation("selected", 1),
            observation("multiple"), observation("blank"),
            observation("ambiguous"), observation("unreadable")), decimal)
        assertEquals("4.75", value["rawScore"])
        assertFalse(value.containsKey("percentage"))
        assertEquals(true, value["needsReview"])
    }

    @Test fun oldCapsuleHasCountButNoInventedRawScore() {
        val value = OfflineScore.aggregate(intArrayOf(1, 2, 3),
            listOf(observation("selected", 1), observation("blank"), observation("multiple")), null)
        assertEquals(1, value["correctCount"])
        assertNull(value["rawScore"])
        assertNull(value["scoringPolicyVersion"])
        assertFalse(value.containsKey("percentage"))
    }

    @Test fun verdictsDescribeOnlySubmittedChoicesWithNeutralUncertainAndVoidedQuestions() {
        val observations=listOf(observation("selected",3),observation("selected",2),observation("multiple"),observation("blank"),observation("ambiguous"),observation("unreadable"))
        val v1=OfflineScore.aggregate(IntArray(6) { 3 },observations,policy)
        assertEquals(listOf("correct","incorrect","multiple","blank","review","review"),v1["questionVerdicts"])
        val v2=OfflineScore.aggregateAccepted(List(6) { intArrayOf() },observations,policy)
        assertEquals(listOf("voided","voided","multiple","blank","voided","voided"),v2["questionVerdicts"])
        for(value in listOf(v1,v2))for(field in listOf("correctOptions","acceptedOptions","answerKey"))assertFalse(value.containsKey(field))
    }

    @Test(expected = IllegalArgumentException::class) fun missingObservationFailsClosed() {
        OfflineScore.aggregate(intArrayOf(1, 2), listOf(observation("blank")), policy)
    }

    @Test(expected = IllegalArgumentException::class) fun invalidOptionFailsClosed() {
        OfflineScore.aggregate(intArrayOf(1), listOf(observation("selected", 9)), policy)
    }

    @Test fun amendedKeyAcceptsOneOfSeveralButNeverMultipleStudentMarks() {
        val options = listOf(intArrayOf(1, 3), intArrayOf(1, 3), intArrayOf(1, 2, 3, 4), intArrayOf())
        val value = OfflineScore.aggregateAccepted(options, listOf(
            observation("selected", 3), observation("multiple"), observation("selected", 2), observation("unreadable")), policy)
        assertEquals(3, value["correctCount"])
        assertEquals("8", value["rawScore"])
        assertEquals(false, value["needsReview"])
        assertFalse(value.containsKey("acceptedOptions"))
    }

    @Test fun anEmptyAcceptedSetStillLeavesBlankObservationsAtZero() {
        val observations = listOf(observation("selected", 1), observation("selected", 4),
            observation("blank"), observation("multiple"), observation("ambiguous"), observation("unreadable"))
        val value = OfflineScore.aggregateAccepted(List(observations.size) { intArrayOf() }, observations, policy)
        assertEquals(4, value["correctCount"])
        assertEquals("11", value["rawScore"])
        assertFalse(value.containsKey("percentage"))
        assertEquals(false, value["needsReview"])
    }

    @Test fun v2KeepsIncorrectPointsButBlankIsZeroAndUncertainNeedsReview() {
        val decimal = OfflineScore.Policy(1, BigDecimal("2.5"), BigDecimal("-0.25"), BigDecimal("0.1"))
        val value = OfflineScore.aggregateAccepted(List(5) { intArrayOf(1, 3) }, listOf(
            observation("selected", 3), observation("selected", 2), observation("blank"),
            observation("multiple"), observation("unreadable")), decimal)
        assertEquals(1, value["correctCount"])
        assertEquals("2", value["rawScore"])
        assertEquals(true, value["needsReview"])
    }

    @Test fun blankNeverUsesPublishedPositiveOrNegativePointsAndNeedsNoDecision() {
        for (blankPoints in listOf("-2", "0", "4")) {
            val changed = policy.copy(blank = BigDecimal(blankPoints))
            for (accepted in listOf(listOf(intArrayOf(1)), listOf(intArrayOf()))) {
                val value = OfflineScore.aggregateAccepted(accepted, listOf(observation("blank")), changed)
                assertEquals(0, value["correctCount"])
                assertEquals("0", value["rawScore"])
                assertEquals(false, value["needsReview"])
            }
        }
    }
    @Test fun multipleStudentMarksAlwaysUseIncorrectPointsAndNeverEarnCorrectCredit() {
        for(accepted in listOf(intArrayOf(1),intArrayOf(1,3),intArrayOf(1,2,3,4),intArrayOf())) {
            val value=OfflineScore.aggregateAccepted(listOf(accepted),listOf(observation("multiple")),policy)
            assertEquals(0,value["correctCount"]);assertEquals("-1",value["rawScore"])
            assertEquals(false,value["needsReview"]);assertEquals(listOf("multiple"),value["questionVerdicts"])
        }
        val blank=OfflineScore.aggregateAccepted(List(75) { intArrayOf(1) },List(75) { observation("blank") },policy)
        assertEquals(0,blank["correctCount"]);assertEquals("0",blank["rawScore"]);assertEquals(false,blank["needsReview"])
    }

    @Test fun parserKeepsLegacyAndAmendedSemanticsSeparate() {
        assertArrayEquals(intArrayOf(3), OfflineScore.parseOptions(listOf(3), 1, false).single())
        val choices = OfflineScore.parseOptions(listOf(listOf(1, 3), emptyList<Int>(), listOf(2), listOf(1, 2, 3, 4)), 4, true)
        assertArrayEquals(intArrayOf(1, 3), choices[0])
        assertTrue(choices[1].isEmpty())
        assertArrayEquals(intArrayOf(2), choices[2])
    }

    @Test fun malformedAcceptedSetsAndCanonicalCodesCannotBecomeChoices() {
        for (values in listOf(listOf(listOf(1, 1)), listOf(listOf(0)), listOf(listOf(5)),
                listOf(listOf(1.5)), listOf(21), listOf(listOf("1")))) {
            try { OfflineScore.parseOptions(values, 1, true); fail("Malformed choices accepted") }
            catch (expected: IllegalArgumentException) { }
        }
    }

    @Test(expected = IllegalArgumentException::class) fun legacyZeroNeverBecomesAnEmptyAmendedKey() {
        OfflineScore.parseOptions(listOf(0), 1, false)
    }

    @Test(expected = IllegalArgumentException::class) fun invalidObservationStillFailsForAnEmptyAcceptedSet() {
        OfflineScore.aggregateAccepted(listOf(intArrayOf()), listOf(observation("selected", 9)), policy)
    }
}
