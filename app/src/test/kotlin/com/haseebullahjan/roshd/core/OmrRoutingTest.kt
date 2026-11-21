package com.haseebullahjan.roshd.core

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class OmrRoutingTest {
    private val qr="S2"+"0".repeat(18)
    private fun pack(round:Int=2,count:Int=40,expires:Instant=Instant.now().plusSeconds(3600))=OfflineExam(Exam(json("round" to round,"questionCount" to count)),json(),json("expiresAt" to expires.toString()),listOf(RosterRow("1234567890",qr,qr,"","Preview","exam")))
    @Test fun anOpaqueQrUsesOnlyItsUniqueCurrentVerifiedRosterFamily() {
        assertEquals("T3",OmrRouting.historicalFamily(qr,listOf(pack()),2))
        assertNull(OmrRouting.historicalFamily(qr,listOf(pack()),1))
        assertNull(OmrRouting.historicalFamily(qr,listOf(pack(),pack(count=100)),2))
        assertNull(OmrRouting.historicalFamily(qr,listOf(pack(expires=Instant.EPOCH)),2))
        assertNull(OmrRouting.historicalFamily("T1:1234567890:ABCDEFGH",listOf(pack()),2))
    }
}
