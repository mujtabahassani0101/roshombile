package com.haseebullahjan.roshd.core

import org.json.JSONObject

/** Skip a redundant outer-paper crop only after the source OMR proves every rail row. */
object CameraNormalizationPolicy {
    fun shouldTryRectification(framing:JSONObject,sheet:JSONObject,count:Int)=
        !complete(sheet,count) && !framing.optBoolean("resampled") &&
            framing.s("reason") in setOf("four_printed_squares","already_framed")

    /** A failed retry must not discard readable evidence from an earlier path.
     * Partial geometry stays reviewable; only complete geometry supersedes it.
     */
    fun preferCandidate(current:JSONObject,candidate:JSONObject,count:Int):Boolean {
        if(complete(current,count))return false
        val identity=OmrResultParser.qr(current)
        if(identity.isNotBlank() && OmrResultParser.qr(candidate).let { it.isNotBlank() && it!=identity })return false
        if(complete(candidate,count))return true
        return !current.optBoolean("success") && candidate.optBoolean("success") &&
            candidate.optInt("activeQuestionCount")==count && OmrResultParser.qr(candidate).isNotBlank() &&
            SheetFamilies.matchesSelection(count,candidate.o("sheetType").s("code"))
    }

    fun eligible(record:JSONObject)=record.s("inputSource")=="camera" &&
        record.optInt("captureQuestionCount") in SheetFamilies.scanCounts &&
        !record.has("documentCorners") && record.optInt("documentTurns")==0

    fun complete(sheet:JSONObject,count:Int):Boolean {
        if(!sheet.optBoolean("success") || count !in SheetFamilies.scanCounts || OmrResultParser.qr(sheet).isBlank() ||
            !sheet.o("sheetType").optBoolean("detected") || sheet.o("sheetType").s("status")!="DETECTED" ||
            !SheetFamilies.matchesSelection(count,sheet.o("sheetType").s("code")) ||
            sheet.optInt("activeQuestionCount")!=count)return false
        val modernRows=if(count==40)20 else 25
        val rows=sheet.optInt("matchedRowsUsed")
        if(rows!=modernRows) {
            if(rows!=modernRows+4)return false
            val legacyIds=if(count==40)setOf("t3-header-20261020","t3-legacy-header","t3-2x20-2026")
                else setOf("t1-header-20261020","t1-legacy-header")
            val warnings=sheet.a("warnings")
            if((0 until warnings.length()).none { warnings.optString(it) in legacyIds.map { id -> "roshd_printed_layout_verified:$id" } })return false
        }
        val error=sheet.optDouble("reprojectionError",Double.NaN)
        if(sheet.s("homographyStrategyUsed")!="all_${rows}_registration_rows" ||
            sheet.optInt("matchedRowsUsed")!=rows || !error.isFinite() || error !in 0.0..1.5)return false
        if(sheet.s("normalizedImagePath").isBlank() || sheet.o("annotationGeometry").optInt("width")<=0)return false
        return runCatching { OmrResultParser.observations(sheet,count).map { it.optInt("questionNumber") }==(1..count).toList() }.getOrDefault(false)
    }

    /** Preserve native choices, but do not sign uncertain camera geometry without review. */
    fun markGeometryForReview(sheet:JSONObject,count:Int) {
        if(!sheet.optBoolean("success") || complete(sheet,count))return
        sheet.put("reviewRequired",true)
        val reasons=sheet.a("reviewReasons")
        if((0 until reasons.length()).none { reasons.optString(it)=="camera_registration_incomplete" })
            reasons.put("camera_registration_incomplete")
        sheet.put("reviewReasons",reasons)
        sheet.a("questions").objects().forEach { it.put("needsReview",true) }
    }
}
