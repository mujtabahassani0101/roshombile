package com.haseebullahjan.roshd.core

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class SheetFamiliesTest {
    @Test fun scannerSelectionRestrictsPhysicalFamilyAndSurvivesReread() {
        assertEquals(listOf(40,75,100),SheetFamilies.scanCounts)
        assertEquals(SheetFamilies.templates[1],SheetFamilies.templateForSelection(40))
        assertEquals(SheetFamilies.templates[0],SheetFamilies.templateForSelection(100))
        assertEquals(SheetFamilies.templates[2],SheetFamilies.templateForSelection(75))
        assertEquals(listOf(SheetFamilies.templates[2],SheetFamilies.templates[0],SheetFamilies.templates[4]),SheetFamilies.templatesForSelection(75))
        assertEquals(listOf(SheetFamilies.templates[0],SheetFamilies.templates[4]),SheetFamilies.templatesForSelection(100))
        assertEquals(listOf(SheetFamilies.templates[1],SheetFamilies.templates[3]),SheetFamilies.templatesForSelection(40))
        assertEquals(SheetFamilies.templates,SheetFamilies.templatesForSelection(0))
        assertEquals("omr-75-preview.png",SheetFamilies.previewForSelection(75))
        assertTrue(SheetFamilies.matchesSelection(75,"T1"));assertFalse(SheetFamilies.matchesSelection(75,"T3"))
        assertTrue(SheetFamilies.matchesExamSelection(75,75))
        assertFalse(SheetFamilies.matchesExamSelection(75,100));assertFalse(SheetFamilies.matchesExamSelection(100,75))
        assertTrue(SheetFamilies.matchesExamSelection(100,100));assertTrue(SheetFamilies.matchesExamSelection(0,75))
        for(count in listOf(0,60,80))assertThrows(IllegalArgumentException::class.java) { SheetFamilies.templateForSelection(count) }
        assertTrue(SheetFamilies.matchesSelection(40,"T3"));assertFalse(SheetFamilies.matchesSelection(40,"T1"))
        assertTrue(SheetFamilies.matchesSelection(100,"T1"));assertFalse(SheetFamilies.matchesSelection(100,"T3"))
        assertTrue(SheetFamilies.matchesSelection(0,"T3"));assertFalse(SheetFamilies.matchesSelection(60,"T1"))
        val original=json("captureQuestionCount" to 40,"sourceImageSha256" to "retained","state" to "ready","offlineScore" to json("correctCount" to 32))
        val reread=ScanReview.freshRead(original)
        assertEquals(40,reread.optInt("captureQuestionCount"));assertEquals("retained",reread.s("sourceImageSha256"))
        assertFalse(reread.has("offlineScore"));assertEquals("ready",original.s("state"))
    }

    @Test fun modernAndLegacyPrintsRetainSeparatePhysicalLayouts() {
        assertEquals(listOf("yse_omr_a5_t1_100q_v2.json","yse_omr_a5_t3_40q_v1.json","yse_omr_a5_t1_75q_20261115.json",
            "yse_omr_a5_t3_40q_legacy_v1.json","yse_omr_a5_t1_100q_legacy_v2.json"),SheetFamilies.templates)
    }
    @Test fun retiredCapacityCannotSilentlyBecomeA100QuestionSheet() {
        assertEquals("T3",SheetFamilies.forCount(40));assertEquals("T1",SheetFamilies.forCount(100))
        for(count in 41..60)assertEquals("",SheetFamilies.forCount(count))
        for(count in listOf(-1,0,101,120))assertEquals("",SheetFamilies.forCount(count))
    }
    @Test fun retiredOpaqueQrCannotCreateANativeFamilyBinding() {
        val qr="S2"+"0".repeat(18)
        val pack=OfflineExam(Exam(json("round" to 1,"questionCount" to 60)),json(),json("expiresAt" to Instant.now().plusSeconds(3600).toString()),
            listOf(RosterRow("1","",qr,"","", "exam")))
        assertTrue(OmrRosterIndex(listOf(pack),1).historicalBindings.isEmpty())
    }
}
