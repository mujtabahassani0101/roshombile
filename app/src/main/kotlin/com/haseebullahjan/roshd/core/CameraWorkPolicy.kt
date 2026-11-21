package com.haseebullahjan.roshd.core

import org.json.JSONObject

object CameraWorkPolicy {
    const val BATCH_SIZE=2
    const val MAX_PENDING=120
    /** Metadata versions may change while the academic year/edition stays the same. */
    fun period(year:JSONObject?):String=year?.let { value -> canonical(json(
        "id" to value.opt("id"),"academicYearId" to value.opt("academicYearId"),
        "editionId" to value.opt("editionId"),"provinceId" to value.opt("provinceId"))) }.orEmpty()
    /** Never combine captures from different periods or silently switch their selected round. */
    fun select(rows:List<JSONObject>,period:String,availableRounds:Set<Int>,selectedRound:Int,maximum:Int=BATCH_SIZE):List<JSONObject> {
        require(maximum in 1..BATCH_SIZE)
        val eligible=rows.filter { it.s("status")=="pending" && it.s("period")==period &&
            it.optInt("round").let { round -> (if(round==0)selectedRound else round) in availableRounds } }.sortedBy { it.optLong("created") }
        val first=eligible.firstOrNull() ?: return emptyList()
        val round=first.optInt("round").takeIf { it>0 } ?: selectedRound
        return eligible.filter { (it.optInt("round").takeIf { n -> n>0 } ?: selectedRound)==round }.take(maximum)
    }
}
