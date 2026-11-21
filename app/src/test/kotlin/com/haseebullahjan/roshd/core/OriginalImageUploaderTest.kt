package com.haseebullahjan.roshd.core

import com.haseebullahjan.roshd.messenger.ConnectionsManager
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URL
import java.nio.file.Files
import java.time.Instant
import java.util.zip.ZipInputStream
import javax.net.ssl.HttpsURLConnection

class OriginalImageUploaderTest {
    private val school="10000000-0000-4000-8000-000000000001"
    private val scan="20000000-0000-4000-8000-000000000001"
    private val upload="30000000-0000-4000-8000-000000000001"
    private val device=DeviceIdentity("test","40000000-0000-4000-8000-000000000001","test-key",json())
    private val owner=Session(json("schoolId" to school,"accountId" to "50000000-0000-4000-8000-000000000001","accessToken" to "test-token","expiresAt" to Instant.now().plusSeconds(3600).toString()),"https://example.org")
    private class Response(url:URL,val reply:(Response)->Pair<Int,JSONObject>):HttpsURLConnection(url) {
        val body=ByteArrayOutputStream();val headers=mutableMapOf<String,String>()
        private val result by lazy { reply(this) }
        override fun setRequestProperty(key:String,value:String) { headers[key]=value }
        override fun getResponseCode()=result.first
        override fun getInputStream()=ByteArrayInputStream(result.second.toString().toByteArray())
        override fun getErrorStream()=inputStream
        override fun getOutputStream()=body
        override fun getCipherSuite()="test"
        override fun getLocalCertificates():Array<java.security.cert.Certificate>?=null
        override fun getServerCertificates():Array<java.security.cert.Certificate> = emptyArray()
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy()=false
    }
    @Test fun transportCompressionKeepsExactBytesAndSkipsIncompressibleJpegParts() {
        val random=ByteArray(1024*1024).also { java.util.Random(7).nextBytes(it) }
        val raw=OriginalImageUploader.partBody(random,1,true)
        assertFalse(raw.zipped);assertArrayEquals(random,raw.bytes)
        val compressible=ByteArray(1024*1024) { (it%7).toByte() }
        val zipped=OriginalImageUploader.partBody(compressible,2,true)
        assertTrue(zipped.zipped);assertTrue(zipped.bytes.size<compressible.size/10)
        ZipInputStream(ByteArrayInputStream(zipped.bytes)).use {
            assertEquals("image.part002",it.nextEntry.name);assertArrayEquals(compressible,it.readBytes());assertNull(it.nextEntry)
        }
        assertArrayEquals(compressible,OriginalImageUploader.partBody(compressible,2,false).bytes)
    }
    private fun withSource(work:(File,ByteArray,JSONObject,JSONObject)->Unit) {
        val folder=Files.createTempDirectory("image-resume").toFile()
        try {
            val bytes=ByteArray(1024*1024+751).also { java.util.Random(8).nextBytes(it);it[0]=255.toByte();it[1]=216.toByte();it[2]=255.toByte() }
            val file=File(folder,"source.jpg").apply { writeBytes(bytes) }
            val source=json("sourceImageSha256" to sha256(bytes),"identityMode" to "qr-lite-v1","clientSheetId" to "sheet","qrText" to "printed-qr","studentPublicId" to "student","examId" to "exam")
            val receipt=json("status" to "verified","scanId" to scan,"examId" to "exam","clientSheetId" to "sheet","qrText" to "printed-qr","studentPublicId" to "student",
                "sha256" to sha256(bytes),"sizeBytes" to bytes.size,"contentType" to "image/jpeg","width" to 3000,"height" to 4000,"verifiedAt" to Instant.now().toString())
            work(file,bytes,source,receipt)
            assertArrayEquals(bytes,file.readBytes())
        } finally { folder.deleteRecursively() }
    }
    private fun intent(bytes:ByteArray,parts:List<JSONObject>)=json("uploadId" to upload,"scanId" to scan,"deviceUuid" to device.deviceUuid,"sha256" to sha256(bytes),
        "sizeBytes" to bytes.size,"contentType" to "image/jpeg","partSizeBytes" to 1024*1024,"partCount" to 2,"parts" to parts,"status" to "uploading")
    @Test fun resumeSkipsAcknowledgedPartAndReconcilesACompletionTimeoutWithTheExactReceipt()=withSource { file,bytes,source,receipt ->
        val sent=mutableListOf<String>();val signatures=mutableListOf<List<String>>()
        val state=json("uploadId" to upload,"request" to json("requestId" to "immutable-original-request"))
        val first=bytes.copyOfRange(0,1024*1024);val second=bytes.copyOfRange(1024*1024,bytes.size)
        val manager=ConnectionsManager { url -> Response(url) { connection ->
            sent.add(connection.requestMethod+" "+url.path)
            when {
                url.path.endsWith("/parts/2") -> {
                    assertArrayEquals(second,connection.body.toByteArray());assertEquals(sha256(second),connection.headers["X-Part-SHA256"])
                    200 to json("partNumber" to 2,"sha256" to sha256(second),"sizeBytes" to second.size)
                }
                url.path.endsWith("/complete") -> 503 to json("code" to "image_processing_busy")
                url.path.endsWith("/original-image") -> 200 to receipt
                else -> 200 to intent(bytes,listOf(json("partNumber" to 1,"sha256" to sha256(first),"sizeBytes" to first.size)))
            }
        } }
        val result=OriginalImageUploader(manager) { _,lines -> signatures.add(lines);"test-signature" }.upload(owner,device,scan,source,file,state,true,save={})
        assertEquals(receipt.toString(),result.toString());assertEquals("verified",state.s("phase"))
        assertEquals("immutable-original-request",state.o("request").s("requestId"))
        assertEquals(1,signatures.size);assertEquals(sha256(second),signatures.single().last())
        assertTrue(sent.none { it.endsWith("/parts/1") });assertEquals(4,sent.size)
    }
    @Test fun aWrongAcknowledgedHashStopsBeforeSendingOrCompletingAnything()=withSource { file,bytes,source,_ ->
        var calls=0
        val manager=ConnectionsManager { url -> Response(url) { calls++;200 to intent(bytes,listOf(json("partNumber" to 1,"sha256" to "wrong","sizeBytes" to 1024*1024))) } }
        assertThrows(IllegalArgumentException::class.java) {
            OriginalImageUploader(manager) { _,_ -> error("No signing expected") }.upload(owner,device,scan,source,file,json("uploadId" to upload),false,save={})
        }
        assertEquals(1,calls)
    }
    @Test fun aReceiptForDifferentEvidenceIsNeverMarkedVerified()=withSource { file,bytes,source,receipt ->
        receipt.put("sha256","wrong")
        val manager=ConnectionsManager { url -> Response(url) { 200 to intent(bytes,emptyList()).put("status","verified").put("originalImage",receipt) } }
        val state=json("uploadId" to upload)
        assertThrows(IllegalArgumentException::class.java) { OriginalImageUploader(manager) { _,_->"unused" }.upload(owner,device,scan,source,file,state,false,save={}) }
        assertNotEquals("verified",state.s("phase"));assertFalse(state.has("receipt"))
    }
    @Test fun oversizedFilesAreRejectedBeforeAllocatingTheFileOrContactingTheServer() {
        val file=Files.createTempFile("oversized-image",".jpg").toFile()
        try {
            java.io.RandomAccessFile(file,"rw").use { it.setLength(2L*1024*1024*1024) }
            val manager=ConnectionsManager { error("An oversized file must not reach the network") }
            assertThrows(IllegalArgumentException::class.java) {
                OriginalImageUploader(manager) { _,_->error("No signing expected") }
                    .upload(owner,device,scan,json("sourceImageSha256" to "unused"),file,json(),false,save={})
            }
        } finally { file.delete() }
    }
    @Test fun aWholeImageHashMismatchStopsBeforeAnyNetworkCall()=withSource { file,_,source,_ ->
        source.put("sourceImageSha256","wrong")
        val manager=ConnectionsManager { error("Unauthenticated bytes must not reach the network") }
        assertThrows(IllegalArgumentException::class.java) {
            OriginalImageUploader(manager) { _,_->error("No signing expected") }.upload(owner,device,scan,source,file,json(),false,save={})
        }
    }
    @Test fun changingAnUnsentPartAfterPreflightStopsBeforeItsSignatureOrUpload()=withSource { file,bytes,source,_ ->
        var calls=0
        val manager=ConnectionsManager { url -> Response(url) {
            calls++
            java.io.RandomAccessFile(file,"rw").use { it.seek(1024L*1024);it.write((bytes[1024*1024].toInt() xor 1) and 255) }
            val first=bytes.copyOfRange(0,1024*1024)
            200 to intent(bytes,listOf(json("partNumber" to 1,"sha256" to sha256(first),"sizeBytes" to first.size)))
        } }
        try {
            assertThrows(IllegalArgumentException::class.java) {
                OriginalImageUploader(manager) { _,_->error("Changed bytes must not be signed") }
                    .upload(owner,device,scan,source,file,json("uploadId" to upload),false,save={})
            }
            assertEquals(1,calls)
        } finally { file.writeBytes(bytes) }
    }
    @Test fun exactSixteenMegabyteLimitKeepsTheLastPartAndAllEvidenceBytes() {
        val file=Files.createTempFile("bounded-image",".jpg").toFile()
        try {
            java.io.RandomAccessFile(file,"rw").use { it.setLength(16L*1024*1024);it.write(byteArrayOf(255.toByte(),216.toByte(),255.toByte())) }
            val hash=EvidenceArchive.hash(file);val snapshot=ImageUploadSnapshot.verify(file,hash)
            assertEquals(16,snapshot.partCount);assertEquals("image/jpeg",snapshot.mime)
            assertEquals(1024*1024,snapshot.partSize(16))
            val digest=java.security.MessageDigest.getInstance("SHA-256")
            for(number in 1..snapshot.partCount)digest.update(snapshot.readPart(file,number))
            assertEquals(hash,digest.digest().joinToString("") { "%02x".format(it) })
            assertEquals(hash,EvidenceArchive.hash(file))
        } finally { file.delete() }
    }
    @Test fun shortPngHeaderRemainsAValidSingleExactPart() {
        val file=Files.createTempFile("bounded-image",".png").toFile()
        try {
            val bytes=byteArrayOf(137.toByte(),80,78,71,13,10,26,10);file.writeBytes(bytes)
            val snapshot=ImageUploadSnapshot.verify(file,sha256(bytes))
            assertEquals("image/png",snapshot.mime);assertEquals(1,snapshot.partCount)
            assertArrayEquals(bytes,snapshot.readPart(file,1))
        } finally { file.delete() }
    }
}
