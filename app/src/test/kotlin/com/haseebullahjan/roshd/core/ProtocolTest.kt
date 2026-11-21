package com.haseebullahjan.roshd.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.time.Instant

class ProtocolTest {
    private val school="10000000-0000-0000-0000-000000000001"
    private val account="20000000-0000-0000-0000-000000000001"
    private val examId="30000000-0000-0000-0000-000000000001"
    private val deviceId="40000000-0000-0000-0000-000000000001"
    private val pair=KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private val identity=DeviceIdentity("test",deviceId,"test-key",json("signingPublicKeySha256" to "abc","encryptionPublicKeySha256" to "def"))
    private val owner=Session(json("accountId" to account,"schoolId" to school),"https://example.org")
    private val exam=Exam(json("id" to examId,"schoolId" to school,"round" to 1,"grade" to 6,"questionCount" to 2))
    private val config=json("offlineTrustKeys" to listOf(json("keyId" to "trusted","algorithm" to "ECDSA-P256-SHA256","publicKeySpki" to ScannerCrypto.encode(pair.public.encoded),"status" to "active")))
    private fun manifest()=json("schemaVersion" to "roshd.offline-lite.v1","scannerAccountId" to account,"schoolId" to school,"deviceUuid" to deviceId,"deviceKeyId" to "test-key","examId" to examId,"round" to 1,"grade" to 6,"questionCount" to 2,
        "packageId" to "50000000-0000-0000-0000-000000000001","packageVersion" to "1","issuedAt" to Instant.now().minusSeconds(60).toString(),"expiresAt" to Instant.now().plusSeconds(3600).toString(),"sheets" to listOf(listOf("1234560000","ABCDEFGH")))
    private fun signed(value:JSONObject):JSONObject {
        val bytes=value.toString().toByteArray()
        val sign=Signature.getInstance("SHA256withECDSAinP1363Format").apply { initSign(pair.private);update(bytes) }.sign()
        return json("payloadBase64" to ScannerCrypto.encode(bytes),"payloadSha256" to sha256(bytes),"signingKeyId" to "trusted","signatureBase64" to ScannerCrypto.encode(sign))
    }
    private fun verify(envelope:JSONObject,session:Session=owner)=OfflineVerifier().verify(exam,envelope,session,identity,config,mapOf("1234560000" to "نام شاگرد"))
    @Test fun signedRosterKeepsExactPrintedIdentity() {
        val result=verify(signed(manifest()))
        assertEquals("T3:1234560000:ABCDEFGH",result.roster.single().sheetQr)
        assertEquals(uuidV5(examId,"qr-sheet:T3:1234560000:ABCDEFGH"),result.roster.single().sheetId)
    }
    @Test fun signedHistoricalFourFieldRowsKeepNineDigitAliasAndExactOpaqueQr() {
        for(qr in listOf("1:1234560000:123456789","T3:1234560000:123456789")) {
            val data=manifest().put("sheets",JSONArray(listOf(listOf("1234560000","123456789",qr,"1234560000"))))
            val envelope=signed(data);val before=envelope.toString();val result=verify(envelope)
            assertEquals(qr,result.roster.single().sheetQr);assertEquals("123456789",result.roster.single().alias)
            assertEquals(uuidV5(examId,"qr-sheet:$qr"),result.roster.single().sheetId)
            assertEquals(before,result.envelope.toString())
            assertEquals(qr,OmrRosterIndex(listOf(result),1).historicalBindings.single().s("qrText"))
        }
    }
    @Test fun compactRowsDoNotInventQrForUnknownHistoricalAlias() {
        assertThrows(IllegalArgumentException::class.java) { verify(signed(manifest().put("sheets",JSONArray(listOf(listOf("1234560000","123456789")))))) }
    }
    @Test fun historicalQrStillRequiresItsExactAliasFamilyBoundsAndCardIdentity() {
        for(row in listOf(
            listOf("1234560000","123456789","1:1234560000:987654321","1234560000"),
            listOf("1234560000","123456789","T1:1234560000:123456789","1234560000"),
            listOf("1234560000","123456789","T2:1234560000:123456789","1234560000"),
            listOf("1234560000","123456789","1:1234560000:123456789 ","1234560000"),
            listOf("1234560000","123456789","x".repeat(129)+":123456789","1234560000"),
            listOf("1234560000","123456789","1:1234560000:123456789","9999999"))) {
            assertThrows(IllegalArgumentException::class.java) { verify(signed(manifest().put("sheets",JSONArray(listOf(row))))) }
        }
    }
    @Test fun alteredHistoricalQrCannotUseAnEarlierSignatureOrCrossStudentIdentity() {
        val data=manifest().put("sheets",JSONArray(listOf(listOf("1234560000","123456789","1:1234560000:123456789","1234560000"))))
        val envelope=signed(data);val modified=data.copy().put("sheets",JSONArray(listOf(listOf("1234560000","123456789","2:1234560000:123456789","1234560000"))))
        envelope.put("payloadBase64",ScannerCrypto.encode(modified.toString().toByteArray()))
        assertThrows(IllegalArgumentException::class.java) { verify(envelope) }
    }
    @Test fun alteredBytesAreRejectedBeforeRosterUse() {
        val envelope=signed(manifest());envelope.put("payloadBase64",ScannerCrypto.encode(manifest().put("grade",7).toString().toByteArray()))
        assertThrows(IllegalArgumentException::class.java) { verify(envelope) }
    }
    @Test fun signedOffsetDatesAndTrustWindowsKeepOriginalSignedBytes() {
        fun offset(time:Instant)=java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(time.atOffset(java.time.ZoneOffset.ofHoursMinutes(4,30)))
        val data=manifest().put("issuedAt",offset(Instant.now().minusSeconds(60))).put("expiresAt",offset(Instant.now().plusSeconds(3600)))
        val envelope=signed(data);val original=envelope.toString()
        val trust=config.copy().apply { a("offlineTrustKeys").getJSONObject(0)
            .put("notBefore",offset(Instant.now().minusSeconds(3600))).put("notAfter",offset(Instant.now().plusSeconds(7200))) }
        val result=OfflineVerifier().verify(exam,envelope,owner,identity,trust,mapOf("1234560000" to "نام شاگرد"))
        assertTrue(result.valid)
        assertEquals(data.s("expiresAt"),result.manifest.s("expiresAt"))
        assertEquals(original,result.envelope.toString())
        assertEquals(sha256(ScannerCrypto.decode(envelope.s("payloadBase64"))),envelope.s("payloadSha256"))
        trust.a("offlineTrustKeys").getJSONObject(0).put("notAfter",offset(Instant.now().minusSeconds(1)))
        assertThrows(IllegalStateException::class.java) { OfflineVerifier().verify(exam,envelope,owner,identity,trust,emptyMap()) }
    }
    @Test fun validSignatureCannotCrossAccountScope() {
        assertThrows(IllegalArgumentException::class.java) { verify(signed(manifest()),owner.copy(raw=owner.raw.copy().put("accountId",school))) }
    }
    @Test fun expiredSignedPackageCannotAuthorizeOfflineWork() {
        assertThrows(IllegalArgumentException::class.java) { verify(signed(manifest().put("expiresAt",Instant.now().minusSeconds(10).toString()))) }
    }
    @Test fun unsignedManifestCannotReplaceSignedBytes() {
        val envelope=signed(manifest()).put("manifest",manifest().put("grade",7))
        assertThrows(IllegalArgumentException::class.java) { verify(envelope) }
    }
    @Test fun plainCorrectAnswersAreForbiddenEvenInSignedEnvelope() {
        assertThrows(IllegalArgumentException::class.java) { verify(signed(manifest().put("correctOptions",JSONArray(listOf(1,2))))) }
    }
    @Test fun duplicateMembershipsAreRejected() {
        val data=manifest().put("sheets",JSONArray(listOf(listOf("1234560000","ABCDEFGH"),listOf("123456","ABCDEFGH"))))
        assertThrows(IllegalArgumentException::class.java) { verify(signed(data)) }
    }
    @Test fun uuidV5IsCompatibleWithRfc4122() {
        assertEquals("21f7f8de-8051-5b89-8680-0195ef798b6a",uuidV5("6ba7b810-9dad-11d1-80b4-00c04fd430c8","www.widgets.com"))
    }
    @Test fun numericCardCompatibilityNeverStripsLeadingZeros() {
        assertEquals("1234560000",studentKey("123456"));assertEquals("012345",studentKey("012345"))
    }
    @Test fun qrMustHaveNativeFoundStatus() {
        assertEquals("",OmrResultParser.qr(json("qrCode" to json("text" to "T3:123456:ABCDEFGH","found" to false,"status" to "MISSING"))))
    }
    @Test fun observationParsingPreservesBlankAndMultiple() {
        val raw=json("questions" to listOf(json("question" to 1,"status" to "BLANK","selectedOptions" to emptyList<String>()),json("question" to 2,"status" to "MULTI","selectedOptions" to listOf("A","C"))))
        val result=OmrResultParser.observations(raw,2)
        assertTrue(result[0].isNull("selectedOption"));assertFalse(result[0].has("markedOptions"));assertEquals(listOf(1,3),(0..1).map { result[1].a("markedOptions").getInt(it) })
    }
    @Test fun missingQuestionCannotBecomeBlankAnswer() {
        assertThrows(IllegalArgumentException::class.java) { OmrResultParser.observations(json("questions" to listOf(json("question" to 1,"status" to "MARKED","selectedOptions" to listOf("1")))),2) }
    }
    @Test fun duplicateQuestionNumbersAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { OmrResultParser.observations(json("questions" to listOf(json("question" to 1),json("question" to 1))),1) }
    }
    @Test fun apiOriginRejectsCredentialRedirectTargets() {
        listOf("http://example.org","https://u:p@example.org","https://example.org/path","https://example.org?x=1").forEach { assertThrows(IllegalArgumentException::class.java) { origin(it) } }
        assertEquals("https://example.org",origin("https://EXAMPLE.org:443/"))
    }
    @Test fun currentLiteRowsKeepNumericSheetQrAndHistoricalTypedRows() {
        // scanner-api-contract: new sheets carry a signed numeric sheetQr with a
        // seven-digit student ID and a nine-digit alias, plus the card QR; two
        // columns remain the historical T1/T2/T3 encoding.
        val names=mapOf("1234567" to "نام شاگرد","7654321" to "شاگرد دوم")
        val data=manifest().put("sheets",JSONArray(listOf(listOf("1234567","044497381","3:1234567:044497381","1234567"),listOf("7654321","pRYFedRB"))))
        val result=OfflineVerifier().verify(exam,signed(data),owner,identity,config,names)
        val numeric=result.roster.first { it.code=="1234567" };val typed=result.roster.first { it.code=="7654321" }
        assertEquals("3:1234567:044497381",numeric.sheetQr);assertEquals("044497381",numeric.alias);assertEquals("1234567",numeric.cardQr)
        assertEquals("T3:7654321:pRYFedRB",typed.sheetQr);assertEquals("7654321",typed.cardQr)
        val binding=OmrRosterIndex(listOf(result),1).historicalBindings.single()
        assertEquals("3:1234567:044497381",binding.s("qrText"));assertEquals("T3",binding.s("sheetType"))
    }
    private fun notice(version:Int=4)=JSONObject(javaClass.classLoader!!.getResourceAsStream("notices/renderer-v$version-blue.json")!!.bufferedReader().use { it.readText() }).getJSONObject("data")
    @Test fun noticePayloadWithProvinceRanksPopulationsAndSectionIsRendered() {
        val data=notice().put("section","الف").put("hasResult",true)
        data.getJSONObject("ranks").put("province",3).put("nationalPopulation",1200).put("provincePopulation",300).put("schoolPopulation",30).put("classPopulation",15)
        data.getJSONArray("groupRanks").getJSONObject(0).put("province",2).put("class",1)
        NoticeRenderer.validate(data)
        assertEquals("رتبه — کشور 4 از 1200 • ولایت 3 از 300 • مکتب 2 از 30 • شعبه 1 از 15",NoticeRenderer.rankLine(data.getJSONObject("ranks")))
        assertEquals("رتبهٔ گروه‌ها: ساینسیات (کشور 4، ولایت 2، مکتب 2، شعبه 1) • اجتماعیات (کشور 5، مکتب 2)",NoticeRenderer.groupRankLine(data))
        assertEquals("الف",NoticeRenderer.section(data))
    }
    @Test fun absentNoticeRanksAndPopulationsAreNeverPrintedAsZero() {
        val data=notice().put("section",JSONObject.NULL)
        data.getJSONObject("ranks").put("province",JSONObject.NULL).put("nationalPopulation",JSONObject.NULL).put("classPopulation",JSONObject.NULL)
        NoticeRenderer.validate(data)
        assertEquals("رتبه — کشور 4 • مکتب 2 • شعبه 1",NoticeRenderer.rankLine(data.getJSONObject("ranks")))
        assertNull(NoticeRenderer.section(data))
        assertNull(NoticeRenderer.rankLine(JSONObject()))
        assertTrue(NoticeRenderer.hasResult(notice()))
    }
    @Test fun noticeWithoutResultNeedsIdentityButNoScores() {
        val data=notice().put("hasResult",false).put("publicationVersion",0).put("subjects",JSONArray()).put("questions",JSONArray()).put("history",JSONArray())
        data.remove("totalScore");data.remove("ranks")
        NoticeRenderer.validate(data)
        assertFalse(NoticeRenderer.hasResult(data))
        assertThrows(IllegalArgumentException::class.java) { NoticeRenderer.validate(data.copy().put("studentPublicId","12")) }
        assertThrows(IllegalStateException::class.java) { NoticeRenderer.validate(data.copy().put("hasResult",true).put("publicationVersion",1)) }
    }
    @Test fun malformedNewNoticeFieldsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { NoticeRenderer.validate(notice().put("hasResult","yes")) }
        assertThrows(IllegalArgumentException::class.java) { NoticeRenderer.validate(notice().put("section",7)) }
        assertThrows(IllegalArgumentException::class.java) { NoticeRenderer.validate(notice().apply { getJSONObject("ranks").put("provincePopulation",-1) }) }
        assertThrows(IllegalArgumentException::class.java) { NoticeRenderer.validate(notice().apply { getJSONObject("ranks").put("province",2.5) }) }
        assertThrows(IllegalStateException::class.java) { NoticeRenderer.validate(notice().apply { getJSONObject("ranks").put("province","2") }) }
    }
    @Test fun existingBackendNoticeVersionsRemainAccepted() {
        (1..4).forEach { version ->
            val text=javaClass.classLoader!!.getResourceAsStream("notices/renderer-v$version-blue.json")!!.bufferedReader().use { it.readText() }
            NoticeRenderer.validate(JSONObject(text).getJSONObject("data"))
        }
    }
    @Test fun scanReceiptRequiresDeliveryMetadataAndExactQr() {
        val payload=json("questionCount" to 2,"identityMode" to "qr-lite-v1","examId" to examId,"clientSheetId" to school,"studentPublicId" to "1234560000","qrText" to "T3:1234560000:ABCDEFGH")
        val request=json("scanId" to deviceId,"payloadBase64" to ScannerCrypto.encode(payload.toString().toByteArray()),"inputSha256" to sha256(payload.toString()))
        val receipt=payload.copy().put("scanId",deviceId).put("inputSha256",request.s("inputSha256")).put("questionCount",2).put("status","received").put("receivedAt",Instant.now().toString())
        ReceiptVerifier.validate("scan",request,receipt)
        val offsetReceipt=receipt.copy().put("receivedAt","2026-11-08T11:08:24.1234567+00:00")
        val original=offsetReceipt.toString()
        ReceiptVerifier.validate("scan",request,offsetReceipt)
        assertEquals(original,offsetReceipt.toString())
        assertThrows(IllegalArgumentException::class.java) { ReceiptVerifier.validate("scan",request,receipt.copy().put("qrText","T3:1234560000:abcdefgh")) }
        assertThrows(IllegalArgumentException::class.java) { ReceiptVerifier.validate("scan",request,receipt.copy().put("status","unknown")) }
    }
}
