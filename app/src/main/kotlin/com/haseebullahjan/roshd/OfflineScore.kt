package com.haseebullahjan.roshd

import java.math.BigDecimal

/** Internal only: decrypted options must never leave the native layer. */
internal object OfflineScore {
    data class Policy(val version: Int, val correct: BigDecimal,
                      val incorrect: BigDecimal, val blank: BigDecimal)

    fun aggregate(options: IntArray, observations: List<Map<String, Any?>>, policy: Policy?): Map<String, Any?> {
        require(options.all { it in 1..4 })
        return aggregateChoices(options.map { intArrayOf(it) }, observations, policy, false)
    }

    fun aggregateAccepted(options: List<IntArray>, observations: List<Map<String, Any?>>, policy: Policy?): Map<String, Any?> =
        aggregateChoices(options, observations, policy, true)

    fun parseOptions(values: List<Any?>, questionCount: Int, v2: Boolean): List<IntArray> {
        require(questionCount in 1..120 && values.size == questionCount)
        fun option(value: Any?): Int {
            require(value is Number && value.toDouble() == value.toInt().toDouble() && value.toInt() in 1..4)
            return value.toInt()
        }
        return values.map { raw ->
            val choices = if (v2) {
                require(raw is List<*> && raw.size <= 4)
                raw.map { option(it) }.toIntArray()
            } else intArrayOf(option(raw))
            require(choices.toSet().size == choices.size)
            choices
        }
    }

    private fun aggregateChoices(options: List<IntArray>, observations: List<Map<String, Any?>>, policy: Policy?, v2: Boolean): Map<String, Any?> {
        require(options.size in 1..120 && observations.size == options.size)
        var count = 0
        var score = BigDecimal.ZERO
        var review = false
        val verdicts = ArrayList<String>(observations.size)
        options.forEachIndexed { index, correct ->
            require(correct.size <= 4 && correct.all { it in 1..4 } && correct.toSet().size == correct.size)
            val observation = observations[index]
            require(observation["state"] in setOf("selected", "multiple", "blank", "ambiguous", "unreadable"))
            if (observation["state"] == "selected") {
                val selected = observation["selectedOption"]
                require(selected is Number && selected.toDouble() == selected.toInt().toDouble() && selected.toInt() in 1..4)
            }
            // A blank observation always earns zero, including voided questions.
            // This is application grading policy; OMR observations stay intact.
            val points = if (observation["state"] == "blank") {
                verdicts.add("blank")
                BigDecimal.ZERO
            } else if (observation["state"] == "multiple") {
                // Several student marks are incorrect, even if the published
                // key accepts several alternatives or voids the question.
                verdicts.add("multiple")
                policy?.incorrect
            } else if (v2 && correct.isEmpty()) {
                verdicts.add("voided")
                count++
                policy?.correct
            } else when (observation["state"]) {
                "selected" -> {
                    val option = observation["selectedOption"] as? Number
                    require(option != null && option.toDouble() == option.toInt().toDouble() && option.toInt() in 1..4)
                    if (option.toInt() in correct) { verdicts.add("correct");count++; policy?.correct }
                    else { verdicts.add("incorrect");policy?.incorrect }
                }
                "ambiguous", "unreadable" -> { verdicts.add("review");review = true; BigDecimal.ZERO }
                else -> throw IllegalArgumentException("Invalid observation state")
            }
            if (points != null) score = score.add(points)
        }
        return mapOf(
            "correctCount" to count,
            "questionCount" to options.size,
            "rawScore" to policy?.let { score.stripTrailingZeros().toPlainString() },
            "scoringPolicyVersion" to policy?.version,
            "needsReview" to review,
            // Only correctness of the submitted observation leaves this calculation, never the key.
            "questionVerdicts" to verdicts.toList()
        )
    }
}
