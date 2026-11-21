package com.haseebullahjan.roshd.core

import org.junit.Assert.*
import org.junit.Test

class ScannerPacketTest {
    private val school="10000000-0000-0000-0000-000000000001"
    private val account="20000000-0000-0000-0000-000000000001"
    private val device="30000000-0000-0000-0000-000000000001"
    private val id="40000000-0000-0000-0000-000000000001"
    private val owner=Session(json("schoolId" to school,"accountId" to account),Roshd.API_ORIGIN)
    @Test fun scanUsesExactUtf8BytesAndDocumentedSignatureOrder() {
        val payload=json("schemaVersion" to "roshd.scan-payload.v2","note" to "برگهٔ رشد","questionCount" to 1)
        val exact=payload.toString().toByteArray(Charsets.UTF_8)
        var lines=emptyList<String>()
        val packet=ScannerPacket.create("scan",owner,device,id,payload) { lines=it;"test-signature" }
        assertEquals("roshd.scan.v1",packet.s("schemaVersion"));assertEquals(id,packet.s("scanId"))
        assertArrayEquals(exact,ScannerCrypto.decode(packet.s("payloadBase64")))
        assertEquals(listOf("roshd.scan.v1",school,account,device,id,sha256(exact)),lines)
        assertFalse(lines.joinToString("\n").endsWith("\n"))
        assertEquals("test-signature",packet.s("signatureBase64"))
    }
    @Test fun imageFirstHasItsOwnEnvelopeAndNeverCarriesAnswers() {
        val payload=json("schemaVersion" to "roshd.image-capture-payload.v2","qrText" to "T3:1234567890:ABCDEFGH")
        val packet=ScannerPacket.create("image-capture",owner,device,id,payload) { assertEquals("roshd.image-capture.v1",it.first());"signature" }
        assertEquals(id,packet.s("captureId"));assertFalse(packet.has("scanId"))
        assertThrows(IllegalArgumentException::class.java) { ScannerPacket.create("image-capture",owner,device,id,payload.copy().put("answers",json())) { "signature" } }
    }
    @Test fun packetRejectsAnUnsupportedKindOrOversizedCapture() {
        assertThrows(IllegalArgumentException::class.java) { ScannerPacket.create("unsupported",owner,device,id,json()) { "signature" } }
        val tooLarge=json("schemaVersion" to "roshd.image-capture-payload.v2","padding" to "x".repeat(8192))
        assertThrows(IllegalArgumentException::class.java) { ScannerPacket.create("image-capture",owner,device,id,tooLarge) { "signature" } }
    }
    @Test fun deviceV2ProofIncludesTheIndependentEncryptionFingerprintLast() {
        val identity=DeviceIdentity("alias",device,"key-id",json("encryptionSupported" to true,"signingPublicKeySha256" to "sign-hash","encryptionPublicKeySha256" to "ecdh-hash"))
        assertEquals(listOf("roshd.device-registration.v2",school,account,device,"key-id","sign-hash","ecdh-hash"),ScannerPacket.registrationLines(owner,identity))
        assertEquals(listOf("roshd.device-registration.v1",school,account,device,"key-id","sign-hash"),ScannerPacket.registrationLines(owner,identity.copy(keys=identity.keys.copy().put("encryptionSupported",false))))
    }
}
