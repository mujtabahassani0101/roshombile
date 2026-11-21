package com.haseebullahjan.roshd.ui.components

import com.haseebullahjan.roshd.ui.camera.PreviewPageIdentity
import com.haseebullahjan.roshd.ui.camera.PageFingerprint
import org.junit.Assert.*
import org.junit.Test

class PreviewPageIdentityTest {
    @Test fun twoFreshIndependentReadsAreRequired() {
        val gate=PreviewPageIdentity();val qr="T3:123456:AbCd1234"
        gate.update(qr,1000);assertNull(gate.current(1100))
        gate.update(qr,1200);assertEquals(qr,gate.current(1250))
        assertNull(gate.current(3001))
    }
    @Test fun staleOrReorderedCallbacksCannotRearmTheCamera() {
        val gate=PreviewPageIdentity();val qr="T1:123456:AbCd1234"
        gate.update(qr,1200);gate.update(qr,1200);gate.update(qr,1000)
        assertNull(gate.current(1300));gate.update(qr,3000);assertNull(gate.current(3050))
    }
    @Test fun changedQrRequiresItsOwnConfirmation() {
        val gate=PreviewPageIdentity();val one="T1:1:AbCd1234";val two="T1:2:AbCd1234"
        gate.update(one,1000);gate.update(one,1200);gate.update(two,1400)
        assertNull(gate.current(1450));gate.update(two,1600);assertEquals(two,gate.current(1700))
    }
    @Test fun retiredMalformedOrAmbiguousPreviewQrCannotUnlockASheet() {
        for(qr in listOf(null,"T2:1:AbCd1234","T3:1:invalid","T3::AbCd1234"," T1:1:AbCd1234")) {
            val gate=PreviewPageIdentity();gate.update(qr,1000);gate.update(qr,1200);assertNull(gate.current(1250))
        }
    }
    @Test fun historicalPreviewNeedsTheVerifiedRosterAndTwoFreshExactReads() {
        val qr="1:1234567:123456789"
        val unknown=PreviewPageIdentity();unknown.update(qr,1000);unknown.update(qr,1200);assertNull(unknown.current(1250))
        val known=PreviewPageIdentity(setOf(qr));known.update(qr,1000);assertNull(known.current(1100))
        known.update(qr,1200);assertEquals(qr,known.current(1250))
        known.update("1:1234567:123456780",1400);assertNull(known.current(1450))
    }
    @Test fun delayedDecodeAndAnIntermittentMissDoNotPermanentlyBlockCapture() {
        val gate=PreviewPageIdentity();val qr="T1:1:AbCd1234"
        gate.update(qr,1000,1792)
        assertEquals(qr,gate.current(1800,aligned=true))
        gate.update(null,1810,2353)
        assertEquals(qr,gate.current(2400,aligned=true))
        gate.update(qr,2360,2903)
        assertEquals(qr,gate.current(3000))
        assertNull(gate.current(4161))
    }
    @Test fun anOldResultCannotBeMadeFreshByUsingItsCompletionTime() {
        val gate=PreviewPageIdentity();val qr="T1:1:AbCd1234"
        gate.update(qr,1000,4000)
        assertNull(gate.current(4010,aligned=true))
    }
    @Test fun aChangedPageDescriptorInvalidatesThePreviewIdentity() {
        val gate=PreviewPageIdentity();val qr="T1:1:AbCd1234"
        val first=PageFingerprint(ByteArray(PageFingerprint.SIZE) { if(it%2==0)30 else 220.toByte() })
        val next=PageFingerprint(ByteArray(PageFingerprint.SIZE) { if(it%2==0)220.toByte() else 30 })
        gate.update(qr,1000,1500,first)
        assertEquals(qr,gate.current(1600,first,true));assertNull(gate.current(1600,next,true))
        gate.clear();assertNull(gate.current(1700,first,true))
    }
}
