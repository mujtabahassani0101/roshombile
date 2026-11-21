package com.haseebullahjan.roshd.core

import com.haseebullahjan.roshd.messenger.ConnectionsManager
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.UUID

/** Explicit account test. Secrets stay in memory; only counts and protocol metadata are logged. */
class AuthenticatedRoshdApiTest {
    @Test fun loginConfigurationAndExamAccess() {
        assumeTrue("Authenticated live testing is opt-in.",System.getenv("ROSHD_AUTH_LIVE_TEST")=="1")
        val username=System.getenv("ROSHD_TEST_USERNAME") ?: error("Test username missing")
        val password=System.getenv("ROSHD_TEST_PASSWORD") ?: error("Test password missing")
        val network=ConnectionsManager()
        val wire=network.request("POST","/api/v1/scanner/login",
            body=json("username" to username.trim(),"password" to password),readTimeoutMs=30_000)
        record("login",json("status" to 200,"responseFields" to wire.data().keys().asSequence().sorted().toList()))
        val owner=Session.login(wire,Roshd.API_ORIGIN)
        record("session_parser",json("valid" to owner.valid,"role" to owner.role,
            "scope" to if(owner.province)"province" else "school","schoolCount" to owner.schools.size))
        try {
            val configuration=network.request("GET","/api/v1/scanner/configuration",owner,readTimeoutMs=30_000).data()
            val year=configuration.optJSONObject("currentAcademicYear")
            val rounds=ExamPeriod.openRounds(year)
            record("configuration",json("status" to 200,"hasCurrentYear" to (year!=null),
                "openRounds" to rounds,"features" to configuration.o("features")))
            if(year!=null)assertTrue(rounds.all { it in 1..12 })
            var cursor=""
            var pages=0
            var examCount=0
            val seenCursors=mutableSetOf<String>()
            do {
                check(++pages<=100 && seenCursors.add(cursor)) { "Exam pagination did not terminate" }
                val query=mutableMapOf("limit" to "100")
                if(cursor.isNotEmpty())query["cursor"]=cursor
                val exams=network.request("GET","/api/v1/scanner/exams",owner,query=query,readTimeoutMs=60_000).data()
                examCount+=exams.getJSONArray("items").length()
                cursor=if(exams.isNull("nextCursor"))"" else uuid(exams.getString("nextCursor"))
            } while(cursor.isNotEmpty())
            record("exams",json("status" to 200,"pages" to pages,"examCount" to examCount,"paginationComplete" to true))
            if(System.getenv("ROSHD_AUTH_BATCH_TEST")=="1") {
                val flag=configuration.o("features").opt("batchScan")
                assertTrue(flag==true || (flag is JSONObject && flag.optBoolean("enabled")))
                val failure=assertThrows(ApiFailure::class.java) {
                    network.request("POST","/api/v1/scanner/scans/batch",owner,
                        body=ScanQueueSender.envelope(emptyList()),school=owner.schools.single(),readTimeoutMs=30_000)
                }
                record("empty_batch_validation",json("status" to failure.status,"code" to failure.code,
                    "submittedScans" to 0))
                assertTrue("An empty batch must be rejected without writing scans",failure.status in setOf(400,422))
            }
            if(owner.province) {
                val schools=network.request("GET","/api/v1/scanner/schools",owner,readTimeoutMs=30_000).data()
                val rows=schools.optJSONArray("schools") ?: schools.optJSONArray("items")
                record("schools",json("status" to 200,"schoolCount" to rows?.length()))
            }
            if(System.getenv("ROSHD_AUTH_BUNDLE_TEST")=="1") {
                requireNotNull(year) { "Current academic year missing" }
                // An explicit test selection, independent of the user's saved round in the Android app.
                require(1 in rounds) { "Round 1 is not open for this test" }
                testInformationBundle(network,owner,year,1)
            }
        } finally {
            try {
                network.request("POST","/api/v1/scanner/logout",owner,body=JSONObject(),readTimeoutMs=30_000)
                record("logout",json("success" to true))
            } catch(failure:ApiFailure) {
                record("logout",json("success" to false,"status" to failure.status,"code" to failure.code))
            }
        }
    }

    private fun testInformationBundle(network:ConnectionsManager,owner:Session,year:JSONObject,round:Int) {
        require(owner.schools.size in 1..5) { "This probe is limited to five disposable device registrations" }
        val identities=owner.schools.associateWith { ephemeralIdentity() }
        val period=json("academicYearId" to year.s("academicYearId"),"editionId" to year.s("editionId"),
            "provinceId" to year.s("provinceId"),"round" to round,"currentYearVersion" to year.s("version"))
        val devices=identities.map { (school,pair) ->
            val identity=pair.first
            val signature=Signature.getInstance("SHA256withECDSAinP1363Format").run {
                initSign(pair.second.private)
                update(ScannerPacket.registrationLines(owner.scope(school),identity).joinToString("\n").toByteArray(Charsets.UTF_8))
                ScannerCrypto.encode(sign())
            }
            val registration=json("deviceUuid" to identity.deviceUuid,"deviceKeyId" to identity.keyId,
                "platform" to "android","deviceName" to "Roshd API verification (JVM; disposable)","appVersion" to "1.1.14",
                "signingPublicKeySpki" to identity.keys.s("signingPublicKeySpki"),
                "encryptionPublicKeySpki" to identity.keys.s("encryptionPublicKeySpki"),"proofOfPossession" to signature)
            json("schoolId" to school,"deviceUuid" to identity.deviceUuid,"deviceKeyId" to identity.keyId,
                "signingPublicKeySpki" to identity.keys.s("signingPublicKeySpki"),
                "encryptionPublicKeySpki" to identity.keys.s("encryptionPublicKeySpki"),"registration" to registration)
        }
        var cleanupFailed=false
        try {
            val bundle=network.request("POST","/api/v1/scanner/information-bundle",owner,
                body=json("schemaVersion" to "roshd.information-request.v2","format" to "qr-lite-v1",
                    "period" to period,"devices" to devices),readTimeoutMs=600_000).data()
            record("bundle_received",json("status" to 200,"schemaVersion" to bundle.s("schemaVersion"),
                "round" to round,"complete" to bundle.optBoolean("complete"),
                "studentCount" to bundle.a("students").length(),"examCount" to bundle.a("exams").length(),
                "packageCount" to bundle.a("packages").length()))
            assertEquals("roshd.information-bundle.v2",bundle.s("schemaVersion"))
            assertEquals("qr-lite-v1",bundle.s("format"))
            assertTrue(bundle.optBoolean("complete") && bundle.isNull("nextCursor"))
            assertEquals(canonical(period),canonical(bundle.o("period")))
            assertTrue(bundle.s("accountId")==owner.accountId && bundle.s("schoolId")==owner.schoolId)
            assertEquals(0,bundle.a("managerResponses").length())
            val incomingConfiguration=bundle.getJSONObject("configuration").data()
            val incomingYear=incomingConfiguration.getJSONObject("currentAcademicYear")
            assertTrue(listOf("id","academicYearId","editionId","provinceId","version").all { year.s(it)==incomingYear.s(it) })
            val registered=mutableSetOf<String>()
            for(row in bundle.getJSONArray("devices").objects()) {
                val school=uuid(row.s("schoolId"));val identity=identities[school]?.first ?: error("Unexpected registered school")
                assertTrue(registered.add(school))
                val receipt=row.o("registration").data()
                val device=receipt.optJSONObject("device") ?: receipt
                assertTrue(device.s("deviceUuid",device.s("uuid"))==identity.deviceUuid &&
                    device.s("accountId")==owner.accountId && device.s("schoolId")==school &&
                    device.s("signingPublicKeySha256",device.s("publicKeySha256"))==identity.signingHash &&
                    device.s("encryptionPublicKeySha256")==identity.encryptionHash && !device.optBoolean("isRevoked"))
            }
            assertEquals(identities.keys,registered)
            val verifier=OfflineVerifier()
            val names=verifier.names(bundle.getJSONArray("students"))
            val exams=bundle.getJSONArray("exams").objects().map(::Exam)
            assertTrue(exams.all { it.schoolId in identities && it.round==round && it.raw.s("editionId")==year.s("editionId") })
            var verified=0
            for(row in bundle.getJSONArray("packages").objects()) {
                val exam=exams.single { it.id==row.s("examId") && it.schoolId==row.s("schoolId") }
                try {
                    verifier.verify(exam,row.getJSONObject("package"),owner.scope(exam.schoolId),
                        identities.getValue(exam.schoolId).first,incomingConfiguration,names)
                } catch(failure:IllegalArgumentException) {
                    // Structural metadata only; never print student IDs, names, QR values or decrypted keys.
                    val payload=JSONObject(String(ScannerCrypto.decode(row.getJSONObject("package").s("payloadBase64")),Charsets.UTF_8))
                    val manifest=payload.optJSONObject("manifest") ?: payload
                    val shapes=(0 until manifest.a("sheets").length()).map { index ->
                        val sheet=manifest.a("sheets").getJSONArray(index)
                        val code=sheet.optString(0);val alias=sheet.optString(1);val qr=sheet.optString(2);val card=sheet.optString(3)
                        json("fields" to sheet.length(),"types" to (0 until sheet.length()).map { sheet.opt(it)?.javaClass?.simpleName },
                            "codeLength" to code.length,"codeDigits" to Regex("^[0-9]{1,32}$").matches(code),
                            "aliasLength" to alias.length,"aliasAsciiAlnum" to Regex("^[A-Za-z0-9]+$").matches(alias),
                            "aliasS2" to alias.startsWith("S2"),"aliasHasUnderscore" to alias.contains('_'),"aliasHasColon" to alias.contains(':'),
                            "qrLength" to qr.length,"qrColonCount" to qr.count { it==':' },"qrEqualsAlias" to (qr==alias),
                            "qrMatchesFamilyAndStudent" to (qr=="${exam.template}:$code:$alias"),
                            "qrT1" to qr.startsWith("T1:"),"qrT2" to qr.startsWith("T2:"),"qrT3" to qr.startsWith("T3:"),
                            "cardLength" to card.length,"cardDigits" to Regex("^[0-9]{1,32}$").matches(card),"cardC2" to card.startsWith("C2"),
                            "aliasCharacters" to alias.map { when { it.isDigit()->'0';it in 'a'..'z'->'a';it in 'A'..'Z'->'A';it in "-_:"->it;else->'?' } }.joinToString(""))
                    }.distinctBy { it.toString() }.take(10)
                    val studentKeys=(0 until manifest.a("sheets").length()).map { studentKey(manifest.a("sheets").getJSONArray(it).optString(0)) }
                    record("roster_shape_failure",json("questionCount" to exam.count,"supportedFamily" to exam.template,
                        "rows" to studentKeys.size,"uniqueStudentKeys" to studentKeys.distinct().size,"rowShapes" to shapes))
                    throw failure
                }
                verified++
            }
            record("bundle_verified",json("valid" to true,"verifiedPackageCount" to verified,
                "registeredDeviceCount" to registered.size))
        } finally {
            for((school,pair) in identities) {
                try {
                    network.request("POST","/api/v1/scanner/devices/${pair.first.deviceUuid}/revoke",owner,
                        school=school,body=json("reason" to "Disposable API verification completed; no student scans submitted"),readTimeoutMs=30_000)
                    record("test_device_revoked",json("success" to true))
                } catch(failure:ApiFailure) {
                    // A rejected atomic bundle may never have registered this identity.
                    if(failure.status!=404)cleanupFailed=true
                    record("test_device_revoked",json("success" to (failure.status==404),"status" to failure.status,
                        "code" to failure.code,"testDeviceUuid" to pair.first.deviceUuid))
                }
            }
            check(!cleanupFailed) { "Disposable device cleanup failed; inspect sanitized probe output" }
        }
    }

    private fun ephemeralIdentity():Pair<DeviceIdentity,KeyPair> {
        fun key()=KeyPairGenerator.getInstance("EC").run { initialize(ECGenParameterSpec("secp256r1"));generateKeyPair() }
        val signing=key();val encryption=key();val device=UUID.randomUUID().toString()
        val keys=json("encryptionSupported" to true,"signingPublicKeySpki" to ScannerCrypto.encode(signing.public.encoded),
            "signingPublicKeySha256" to sha256(signing.public.encoded),"encryptionPublicKeySpki" to ScannerCrypto.encode(encryption.public.encoded),
            "encryptionPublicKeySha256" to sha256(encryption.public.encoded))
        return DeviceIdentity("roshd.qa.transient.$device",device,"roshd.qa.$device.ecdsa-p256.v1",keys) to signing
    }

    private fun record(stage:String,data:JSONObject) {
        println("ROSHD_AUTH_PROBE "+data.put("stage",stage))
    }
}
