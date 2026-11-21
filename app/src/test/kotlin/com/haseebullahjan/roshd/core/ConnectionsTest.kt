package com.haseebullahjan.roshd.core

import com.haseebullahjan.roshd.messenger.ConnectionsManager
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URL
import java.nio.file.Files
import java.time.Instant
import javax.net.ssl.HttpsURLConnection

class ConnectionsTest {
    private class Response(url:URL,private val code:Int,private val bytes:ByteArray,private val responseHeaders:Map<String,String> = emptyMap()):HttpsURLConnection(url) {
        val sent=mutableMapOf<String,String>();val output=ByteArrayOutputStream();var closed=false
        val announcedLength get()=fixedContentLength
        override fun setRequestProperty(key:String,value:String) { sent[key]=value }
        override fun getResponseCode()=code
        override fun getInputStream()=ByteArrayInputStream(bytes)
        override fun getErrorStream()=ByteArrayInputStream(bytes)
        override fun getOutputStream()=output
        override fun getHeaderField(name:String)=responseHeaders[name]
        override fun getHeaderFields()=responseHeaders.mapValues { listOf(it.value) }
        override fun getContentType()=responseHeaders["Content-Type"] ?: "application/json"
        override fun getContentLengthLong()=bytes.size.toLong()
        override fun getCipherSuite()="TLS_AES_128_GCM_SHA256"
        override fun getLocalCertificates():Array<java.security.cert.Certificate>?=null
        override fun getServerCertificates():Array<java.security.cert.Certificate> = emptyArray()
        override fun connect() {}
        override fun disconnect() { closed=true }
        override fun usingProxy()=false
    }
    private val owner=Session(json("accessToken" to "private-test-token","expiresAt" to Instant.now().plusSeconds(3600).toString(),"accountId" to "20000000-0000-0000-0000-000000000001","schoolId" to "10000000-0000-0000-0000-000000000001"),"https://example.org")
    @Test fun provinceOperationsSendTheRoshdSchoolHeaderWithoutChangingTheBearer() {
        lateinit var response:Response
        val manager=ConnectionsManager { url -> Response(url,200,"{}".toByteArray()).also { response=it } }
        val province=owner.copy(raw=owner.raw.copy().put("schoolId",ZERO_UUID).put("accessScope","province"))
        manager.request("POST","/api/v1/scanner/scans",province,body=json(),school=owner.schoolId)
        assertEquals(owner.schoolId,response.sent["X-ROSHD-Scanner-School"])
        assertEquals("Bearer private-test-token",response.sent["Authorization"])
        assertFalse(response.sent.containsKey("X-YSE-Scanner-School"))
        assertThrows(IllegalArgumentException::class.java) { manager.request("GET","/api/v1/scanner/configuration",province,headers=mapOf("X-ROSHD-Scanner-School" to owner.schoolId)) }
    }
    @Test fun rawImagePartsHaveTheExactAnnouncedLengthAndContentType() {
        lateinit var response:Response
        val manager=ConnectionsManager { url -> Response(url,200,"{}".toByteArray()).also { response=it } }
        val bytes=byteArrayOf(0,1,2,127,-1)
        manager.request("PUT","/api/v1/scanner/original-image-uploads/id/parts/1",owner,school=owner.schoolId,rawBody=bytes,headers=mapOf("Content-Type" to "application/octet-stream"))
        assertEquals(bytes.size,response.announcedLength);assertArrayEquals(bytes,response.output.toByteArray())
        assertEquals("application/octet-stream",response.sent["Content-Type"])
    }
    @Test fun permissionAndSchoolScopeFailuresKeepTheirCodesWithoutExpiringTheSession() {
        for((status,code) in listOf(403 to "permission_denied",401 to "school_scope_required")) {
            val manager=ConnectionsManager { url -> Response(url,status,json("title" to "Denied","code" to code).toString().toByteArray()) }
            val failure=assertThrows(ApiFailure::class.java) { manager.request("GET","/api/v1/scanner/configuration",owner) }
            assertEquals(code,failure.code);assertEquals(status,failure.status);assertTrue(owner.valid)
        }
    }
    @Test fun aProxyHtmlFailureKeepsItsStatusAndRetryAfterWithoutLeakingHtml() {
        val manager=ConnectionsManager { url -> Response(url,503,"<html>proxy details</html>".toByteArray(),mapOf("Retry-After" to "60")) }
        val failure=assertThrows(ApiFailure::class.java) { manager.request("GET","/api/v1/scanner/configuration",owner) }
        assertEquals(503,failure.status);assertTrue(failure.retryAt>System.currentTimeMillis());assertFalse(failure.message.contains("proxy details"))
    }
    @Test fun aPublicUpdateCheckCarriesNeitherBearerNorSchoolContext() {
        lateinit var response:Response
        val manager=ConnectionsManager { url -> Response(url,200,"{}".toByteArray()).also { response=it } }
        manager.request("GET","/api/v1/mobile/app-version",query=mapOf("platform" to "android","versionCode" to "4200"))
        assertFalse(response.sent.containsKey("Authorization"));assertFalse(response.sent.containsKey("X-ROSHD-Scanner-School"))
    }
    @Test fun aWrongFileMimeNeverCreatesTheRequestedWorkbook() {
        val folder=Files.createTempDirectory("roshd-mime").toFile()
        try {
            val target=java.io.File(folder,"students.xlsx")
            val manager=ConnectionsManager { url -> Response(url,200,byteArrayOf(1,2),mapOf("Content-Type" to "image/png")) }
            assertThrows(IllegalArgumentException::class.java) { manager.download("/api/v1/file",owner,target,acceptedMimeTypes=setOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) }
            assertFalse(target.exists());assertFalse(java.io.File(target.path+".partial").exists())
        } finally { folder.deleteRecursively() }
    }
    @Test fun diagnosticsRecordTemplatedPathsInsteadOfPrivateIdentifiers() {
        val manager=ConnectionsManager { url -> Response(url,200,"{}".toByteArray()) }
        manager.request("GET","/api/v1/school-manager/students/${owner.schoolId}/history",owner)
        assertEquals("/api/v1/school-manager/students/{id}/history",manager.diagnostics.single().s("path"))
        assertFalse(manager.diagnostics.toString().contains(owner.schoolId))
    }
    @Test fun unauthenticatedApiAndDownloadsUseTheRoshdOrigin() {
        val requested=mutableListOf<URL>()
        val manager=ConnectionsManager { url ->
            requested.add(url)
            if(url.path.endsWith("/file"))Response(url,200,"file".toByteArray(),mapOf("Content-Type" to "application/octet-stream"))
            else Response(url,200,"{}".toByteArray())
        }
        manager.request("GET","/api/v1/test")
        val folder=Files.createTempDirectory("roshd-origin").toFile()
        try { manager.download("/api/v1/file",null,java.io.File(folder,"file.bin")) }
        finally { folder.deleteRecursively() }
        assertEquals(2,requested.size)
        requested.forEach { assertEquals("https://roshd.azmoonic.com", "${it.protocol}://${it.authority}") }
    }
    @Test fun redirectsNeverForwardOrRetryCredentials() {
        var calls=0;lateinit var connection:Response
        val manager=ConnectionsManager { url -> calls++;Response(url,302,byteArrayOf(),mapOf("Location" to "https://other.example")).also { connection=it } }
        val failure=assertThrows(ApiFailure::class.java) { manager.request("GET","/api/v1/test",owner) }
        assertEquals("redirect_blocked",failure.code);assertEquals(1,calls);assertFalse(connection.instanceFollowRedirects);assertTrue(connection.closed)
    }
    @Test fun cooldownBlocksBothJsonAndFileWithoutAnotherRequest() {
        var calls=0
        val manager=ConnectionsManager { url -> calls++;Response(url,429,"{\"code\":\"slow_down\"}".toByteArray(),mapOf("Retry-After" to "60")) }
        assertThrows(ApiFailure::class.java) { manager.request("POST","/api/v1/test",owner,body=json("x" to 1)) }
        assertThrows(ApiFailure::class.java) { manager.request("GET","/api/v1/test",owner) }
        assertThrows(ApiFailure::class.java) { manager.download("/api/v1/file",owner,java.io.File("unused")) }
        assertEquals(1,calls)
    }
    @Test fun oversizedResponseIsClosedBeforeItCanEnterStorage() {
        lateinit var response:Response
        val manager=ConnectionsManager { url -> Response(url,200,"{\"long\":\"${"x".repeat(500)}\"}".toByteArray()).also { response=it } }
        assertThrows(IllegalArgumentException::class.java) { manager.request("GET","/api/v1/test",maxBytes=100) }
        assertTrue(response.closed);assertEquals("IllegalArgumentException",manager.diagnostics.single().s("errorCode"))
    }
    @Test fun diagnosticsExcludeBearerQueryAndRequestBody() {
        lateinit var response:Response
        val manager=ConnectionsManager { url -> Response(url,200,"[]".toByteArray()).also { response=it } }
        manager.request("POST","/api/v1/test",owner,body=json("password" to "secret"),query=mapOf("student" to "private-name"))
        assertEquals("Bearer private-test-token",response.sent["Authorization"])
        val trace=manager.diagnostics.toString()
        listOf("private-test-token","secret","private-name","password").forEach { assertFalse(trace.contains(it)) }
    }
    @Test fun failedFileHashPreservesExistingFileAndRemovesPartial() {
        val folder=Files.createTempDirectory("yse-transfer").toFile()
        try {
            val target=java.io.File(folder,"file.xlsx").apply { writeText("original") }
            val manager=ConnectionsManager { url -> Response(url,200,"changed".toByteArray(),mapOf("Content-Type" to "application/octet-stream")) }
            assertThrows(IllegalArgumentException::class.java) { manager.download("/api/v1/file",null,target,expectedHash=sha256("original")) }
            assertEquals("original",target.readText());assertFalse(java.io.File(target.path+".partial").exists())
        } finally { folder.deleteRecursively() }
    }
    @Test fun failedRequestsRecordSafeStatusAndDurationWithoutServerText() {
        val manager=ConnectionsManager { url -> Response(url,400,json("code" to "scan_unavailable","message" to "private-server-detail").toString().toByteArray()) }
        assertThrows(ApiFailure::class.java) { manager.request("PUT","/api/v1/scanner/original-image-uploads/${owner.schoolId}/parts/3",owner,query=mapOf("deviceUuid" to owner.accountId),rawBody=byteArrayOf(1,2,3)) }
        val row=manager.diagnostics.single()
        assertEquals(400,row.optInt("status"));assertEquals("scan_unavailable",row.s("errorCode"));assertTrue(row.optLong("elapsedMs")>=0)
        assertEquals("/api/v1/scanner/original-image-uploads/{id}/parts/{number}",row.s("path"))
        for(secret in listOf(owner.token,owner.accountId,owner.schoolId,"private-server-detail"))assertFalse(row.toString().contains(secret))
    }
    @Test fun cancellationStopsABackoffBeforeAnotherNetworkAttempt() {
        var calls=0
        val manager=ConnectionsManager { url -> calls++;Response(url,503,json("code" to "image_processing_busy","message" to "Scan request unavailable or invalid").toString().toByteArray()) }
        val version=manager.cancellationVersion
        val failure=assertThrows(ApiFailure::class.java) { manager.request("PUT","/api/v1/scanner/test",owner,rawBody=byteArrayOf(1)) }
        assertFalse(failure.message.contains("Scan request unavailable"));assertTrue(failure.message.contains("سرور"))
        manager.cancel()
        assertThrows(java.io.InterruptedIOException::class.java) { manager.awaitRetry(System.currentTimeMillis()+30000,version) }
        assertEquals(1,calls)
    }
}
