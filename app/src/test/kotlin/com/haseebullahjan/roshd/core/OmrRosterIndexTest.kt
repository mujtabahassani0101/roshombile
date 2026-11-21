package com.haseebullahjan.roshd.core

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class OmrRosterIndexTest {
    private val qr="S2"+"0".repeat(18)
    private fun pack(id:String="exam",round:Int=1,expires:Instant=Instant.now().plusSeconds(3600),rows:Int=1)=OfflineExam(
        Exam(json("id" to id,"round" to round,"questionCount" to 40)),json(),json("expiresAt" to expires.toString()),
        (1..rows).map { n -> RosterRow(n.toString(),qr,if(rows==1)qr else "T3:$n:ABCDEFGH","", "Test",id) })
    @Test fun exactQrLookupKeepsTheVerifiedStudentAndExam() {
        val index=OmrRosterIndex(listOf(pack()),1)
        assertEquals("exam",index.matches(qr,json()).single().pack.exam.id)
        assertTrue(index.matches(qr.lowercase(),json()).isEmpty())
    }
    @Test fun duplicateQrStillRequiresHumanReviewAndHasNoNativeBinding() {
        val index=OmrRosterIndex(listOf(pack("one"),pack("two")),1)
        assertEquals(2,index.matches(qr,json()).size);assertTrue(index.historicalBindings.isEmpty())
    }
    @Test fun roundAndExpiryAreStillAuthoritative() {
        val index=OmrRosterIndex(listOf(pack(round=2),pack(expires=Instant.EPOCH)),1)
        assertTrue(index.matches(qr,json()).isEmpty());assertTrue(index.historicalBindings.isEmpty())
    }
    @Test fun opaqueHistoricalBindingsRequireAnExactUnambiguousActiveFamily() {
        val opaque="1:1234567:123456789"
        val original=pack().copy(roster=listOf(RosterRow("1234567","123456789",opaque,"1234567","Test","exam")))
        val index=OmrRosterIndex(listOf(original),1)
        assertEquals(opaque,index.historicalBindings.single().s("qrText"));assertEquals("T3",index.historicalBindings.single().s("sheetType"))
        assertTrue(index.matches("1:1234567:123456780",json()).isEmpty())
        assertTrue(OmrRosterIndex(listOf(original,original.copy(exam=Exam(json("id" to "other","round" to 1,"questionCount" to 40)))),1).historicalBindings.isEmpty())
        assertTrue(OmrRosterIndex(listOf(original.copy(manifest=json("expiresAt" to Instant.EPOCH.toString()))),1).historicalBindings.isEmpty())
        assertTrue(OmrRosterIndex(listOf(original),2).historicalBindings.isEmpty())
        assertTrue(OmrRosterIndex(listOf(original.copy(exam=Exam(json("round" to 1,"questionCount" to 60)))),1).historicalBindings.isEmpty())
    }
    @Test fun frozenSourceCannotBeRemappedToAnotherStudentOrExam() {
        val source=json("identityMode" to "qr-lite-v1","examId" to "different","clientSheetId" to "other","studentPublicId" to "1","qrText" to qr)
        assertTrue(OmrRosterIndex(listOf(pack()),1).matches(qr,json("sourceSnapshot" to source)).isEmpty())
    }
    @Test fun seventyFiveQuestionBindingRequiresAUniqueVerifiedRosterAndKeepsT1Identity() {
        val printed="T1:123456:ABCDEFGH"
        val original=pack().copy(exam=Exam(json("id" to "exam","round" to 1,"questionCount" to 75)),
            roster=listOf(RosterRow("123456","ABCDEFGH",printed,"123456","Test","exam")))
        val index=OmrRosterIndex(listOf(original),1)
        assertTrue(index.historicalBindings.isEmpty())
        assertEquals(75,index.recognitionBindings.single().optInt("activeQuestionCount"))
        assertEquals("T1",index.recognitionBindings.single().s("sheetType"))
        assertEquals(printed,index.recognitionBindings.single().s("qrText"))
        assertTrue(OmrRosterIndex(listOf(original,original),1).recognitionBindings.isEmpty())
        assertTrue(OmrRosterIndex(listOf(original),2).recognitionBindings.isEmpty())
        assertTrue(OmrRosterIndex(listOf(original.copy(manifest=json("expiresAt" to Instant.EPOCH.toString()))),1).recognitionBindings.isEmpty())
        val opaque=original.copy(roster=listOf(RosterRow("1","",qr,"","Test","exam")))
        assertEquals(75,OmrRosterIndex(listOf(opaque),1).recognitionBindings.single().optInt("activeQuestionCount"))
    }
    @Test fun indexedLookupBenchmarkUsesTheSameMatchesAsRosterTraversal() {
        val packages=listOf(pack(rows=50000))
        val start=System.nanoTime();val index=OmrRosterIndex(packages,1);val buildMs=(System.nanoTime()-start)/1e6
        val probes=(1..100).map { "T3:${it*499}:ABCDEFGH" }
        val oldStart=System.nanoTime()
        val expected=probes.map { query -> packages.flatMap { p -> p.roster.filter { it.sheetQr==query }.map { it.code } } }
        val oldMs=(System.nanoTime()-oldStart)/1e6
        val newStart=System.nanoTime();val actual=probes.map { query -> index.matches(query,json()).map { it.row.code } }
        val indexedMs=(System.nanoTime()-newStart)/1e6
        assertEquals(expected,actual)
        println("ROSHD_ROSTER_BENCH rows=50000 probes=100 buildMs=$buildMs traversalMs=$oldMs indexedMs=$indexedMs")
    }
}
