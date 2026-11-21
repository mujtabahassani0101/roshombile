package com.haseebullahjan.roshd.core

import org.junit.Assert.*
import org.junit.Test

class CameraNormalizationPolicyTest {
    private fun sheet(count:Int)=json("success" to true,"activeQuestionCount" to count,
        "sheetType" to json("code" to SheetFamilies.forCount(count),"detected" to true,"status" to "DETECTED"),
        "qrCode" to json("found" to true,"status" to "FOUND","text" to "verified-photo-identity"),
        "matchedRowsUsed" to if(count==40)20 else 25,
        "homographyStrategyUsed" to "all_${if(count==40)20 else 25}_registration_rows",
        "reprojectionError" to .4,"normalizedImagePath" to "normalized.jpg","annotationGeometry" to json("width" to 1750),
        "questions" to (1..count).map { json("question" to it,"status" to "BLANK") })
    @Test fun alignedCameraPhotosOfEachSupportedCountCanUseTheSourceNormalizer() {
        for(count in listOf(40,75,100))assertTrue(CameraNormalizationPolicy.complete(sheet(count),count))
    }
    @Test fun legacyHeaderRowsRequireAnIndependentlyVerifiedPrintedLayout() {
        for(count in listOf(40,75,100)) {
            val rows=if(count==40)24 else 29
            val old=sheet(count).put("matchedRowsUsed",rows).put("homographyStrategyUsed","all_${rows}_registration_rows")
            assertFalse(CameraNormalizationPolicy.complete(old,count))
            val id=if(count==40)"t3-header-20261020" else "t1-header-20261020"
            // A source variant proposal alone does not prove its circle positions.
            old.put("warnings",wrap(listOf(id,"sheet_geometry_variant:$id")))
            assertFalse(CameraNormalizationPolicy.complete(old,count))
            old.put("warnings",wrap(listOf("roshd_printed_layout_verified:$id")))
            assertTrue(CameraNormalizationPolicy.complete(old,count))
            old.put("warnings",wrap(listOf("roshd_printed_layout_verified:unknown")))
            assertFalse(CameraNormalizationPolicy.complete(old,count))
        }
    }
    @Test fun importsManualCornersAndExplicitRotationKeepTheOriginalDocumentPipeline() {
        val photo=json("inputSource" to "camera","captureQuestionCount" to 100)
        assertTrue(CameraNormalizationPolicy.eligible(photo))
        assertFalse(CameraNormalizationPolicy.eligible(photo.copy().put("documentTurns",1)))
        assertFalse(CameraNormalizationPolicy.eligible(photo.copy().put("documentCorners",wrap(listOf(0,0,1,0,1,1,0,1)))))
        assertFalse(CameraNormalizationPolicy.eligible(photo.copy().put("inputSource","import")))
    }
    @Test fun partialRowsFallbackGeometryAndPoorFitsStillNeedTheDocumentScanner() {
        val source=sheet(100)
        for(bad in listOf(source.copy().put("matchedRowsUsed",24),source.copy().put("homographyStrategyUsed","rail_extents"),
            source.copy().put("homographyStrategyUsed","answer_25_registration_rows"),source.copy().put("reprojectionError",2.0),
            source.copy().apply { remove("reprojectionError") }))assertFalse(CameraNormalizationPolicy.complete(bad,100))
    }
    @Test fun wrongFamilyMissingIdentityOrIncompleteAnswersCannotUseTheFastReturn() {
        val source=sheet(100)
        for(bad in listOf(source.copy().put("success",false),source.copy().put("sheetType",json("code" to "T3")),
            source.copy().put("qrCode",json()),source.copy().put("questions",wrap((1..99).map { json("question" to it) })),
            source.copy().put("activeQuestionCount",75),source.copy().put("annotationGeometry",json())))
            assertFalse(CameraNormalizationPolicy.complete(bad,100))
    }
    @Test fun uncertainFallbackGeometryPreservesNativeChoicesAndRequiresReview() {
        val source=sheet(75).put("matchedRowsUsed",24).put("reviewReasons",wrap(listOf("source_warning")))
        val choices=source.a("questions").objects().map { it.s("status") }
        CameraNormalizationPolicy.markGeometryForReview(source,75)
        CameraNormalizationPolicy.markGeometryForReview(source,75)
        assertTrue(source.optBoolean("success"));assertTrue(source.optBoolean("reviewRequired"))
        assertEquals(choices,source.a("questions").objects().map { it.s("status") })
        val answers=OmrResultParser.observations(source,75)
        assertTrue(answers.all { it.s("state")=="blank" && !ScanReview.needsReview(it) })
        assertTrue(ScanReview.engineNeedsReview(json("engine" to source,"answers" to answers)))
        assertEquals(2,source.a("reviewReasons").length())
    }
    @Test fun completeSourceGeometryKeepsTheNativeReviewState() {
        val source=sheet(100);val before=source.toString()
        CameraNormalizationPolicy.markGeometryForReview(source,100)
        assertEquals(before,source.toString())
    }
    @Test fun failedOrWeakerRetriesCannotDiscardReadableEvidence() {
        val partial=sheet(75).put("matchedRowsUsed",24)
        val failed=json("success" to false)
        assertFalse(CameraNormalizationPolicy.preferCandidate(partial,failed,75))
        assertFalse(CameraNormalizationPolicy.preferCandidate(sheet(75),partial,75))
        assertFalse(CameraNormalizationPolicy.preferCandidate(partial,partial.copy().put("matchedRowsUsed",10),75))
        assertTrue(CameraNormalizationPolicy.preferCandidate(partial,sheet(75),75))
        assertTrue(CameraNormalizationPolicy.preferCandidate(failed,partial,75))
        assertFalse(CameraNormalizationPolicy.preferCandidate(failed,sheet(40),75))
        val other=sheet(75).put("qrCode",json("found" to true,"status" to "FOUND","text" to "other-page"))
        assertFalse(CameraNormalizationPolicy.preferCandidate(partial,other,75))
    }
    @Test fun precisePoseRetryNeedsConfirmedSquaresAndAnIncompleteRead() {
        val failed=json("success" to false)
        for(reason in listOf("four_printed_squares","already_framed")) {
            val framing=json("reason" to reason,"resampled" to false)
            assertTrue(CameraNormalizationPolicy.shouldTryRectification(framing,failed,75))
            assertFalse(CameraNormalizationPolicy.shouldTryRectification(framing,sheet(75),75))
            assertFalse(CameraNormalizationPolicy.shouldTryRectification(framing.copy().put("resampled",true),failed,75))
        }
        for(reason in listOf("ambiguous_squares","square_geometry_unconfirmed","four_squares_unconfirmed"))
            assertFalse(CameraNormalizationPolicy.shouldTryRectification(json("reason" to reason),failed,75))
    }
}
