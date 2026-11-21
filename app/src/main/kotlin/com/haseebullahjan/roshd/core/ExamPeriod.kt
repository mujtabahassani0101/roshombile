package com.haseebullahjan.roshd.core

import org.json.JSONObject

object ExamPeriod {
    const val MAX_ROUND=12
    fun openRounds(year:JSONObject?)=year?.a("openRounds")?.let { a ->
        val maximum=year.optInt("roundCount",MAX_ROUND).coerceIn(1,MAX_ROUND)
        (0 until a.length()).map { a.optInt(it) }.filter { it in 1..maximum }.distinct().sorted()
    } ?: emptyList()
    fun currentRound(year:JSONObject?):Int {
        val open=openRounds(year)
        return open.singleOrNull() ?: 0
    }
    fun key(year:JSONObject?)=year?.let { canonical(json("id" to it.opt("id"),"academicYearId" to it.opt("academicYearId"),"editionId" to it.opt("editionId"),"provinceId" to it.opt("provinceId"),"version" to it.opt("version"))) }.orEmpty()
}

object ScanQueuePolicy {
    fun canSend(packet:JSONObject,now:Long):Boolean=packet.s("kind")=="scan" && when(packet.s("status")) {
        "rejected"->true
        "pending","sending","retry_wait"->packet.optLong("retryAt")<=now
        else->false
    }
}
