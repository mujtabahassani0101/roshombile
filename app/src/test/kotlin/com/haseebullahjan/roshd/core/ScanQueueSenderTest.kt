package com.haseebullahjan.roshd.core

import com.haseebullahjan.roshd.messenger.ConnectionsManager
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URL
import java.time.Instant
import javax.net.ssl.HttpsURLConnection

class ScanQueueSenderTest {
    private val school="10000000-0000-4000-8000-000000000001"
    private val otherSchool="10000000-0000-4000-8000-000000000002"
    private val owner=Session(json("accessToken" to "test-only-token","expiresAt" to Instant.now().plusSeconds(3600).toString(),
        "accountId" to "20000000-0000-4000-8000-000000000001","schoolId" to school),"https://example.org")
    private var now=System.currentTimeMillis()
    private fun packet(number:Int,schoolId:String=school):JSONObject {
        val id="30000000-0000-4000-8000-${number.toString().padStart(12,'0')}"
        val payload=json("identityMode" to "qr-lite-v1","examId" to "40000000-0000-4000-8000-000000000001",
            "clientSheetId" to id,"studentPublicId" to number.toString(),"qrText" to "T3:$number:ABCDEFGH","questionCount" to 2)
        val bytes=payload.toString().toByteArray()
        val request=json("schemaVersion" to "roshd.scan.v1","scanId" to id,"deviceUuid" to school,
            "payloadBase64" to ScannerCrypto.encode(bytes),"inputSha256" to sha256(bytes),"signatureBase64" to "test-signature")
        return json("id" to id,"kind" to "scan","status" to "pending","created" to number,"request" to request,
            "metadata" to json("schoolId" to schoolId))
    }
    private fun receipt(request:JSONObject):JSONObject=JSONObject(String(ScannerCrypto.decode(request.s("payloadBase64"))))
        .put("scanId",request.s("scanId")).put("inputSha256",request.s("inputSha256"))
        .put("status","received").put("receivedAt",Instant.now().toString())
    private fun success(body:JSONObject):JSONObject=json("schemaVersion" to "roshd.scan-batch-result.v1",
        "items" to body.a("scans").objects().mapIndexed { index,request ->
            json("index" to index,"scanId" to request.s("scanId"),"outcome" to "created","httpStatus" to 201,"result" to receipt(request))
        })
    private class Box(rows:List<JSONObject>):ScanOutbox {
        val rows=rows.associateBy { it.s("id") };val attempts=mutableMapOf<String,Int>()
        fun summaries()=rows.values.map { it.copy() }.reversed()
        override fun load(id:String)=rows[id]?.copy()
        override fun validate(item:JSONObject) { require(!item.optBoolean("broken")) { "Damaged local signature" } }
        override fun sending(id:String) { rows.getValue(id).put("status","sending");attempts[id]=(attempts[id] ?: 0)+1 }
        override fun delivered(id:String,receipt:JSONObject) { rows.getValue(id).put("status","delivered").put("receipt",receipt).put("retryAt",0) }
        override fun failed(id:String,failure:DeliveryFailure) { rows.getValue(id).put("status",if(failure.retry)"retry_wait" else "rejected").put("retryAt",failure.retryAt) }
        fun count(status:String)=rows.values.count { it.s("status")==status }
    }
    private data class Reply(val body:JSONObject,val status:Int=200)
    private class Connection(url:URL,private val reply:(Connection)->Reply):HttpsURLConnection(url) {
        val output=ByteArrayOutputStream();val headers=mutableMapOf<String,String>()
        val body get()=JSONObject(output.toString("UTF-8"))
        private val response by lazy { reply(this) }
        override fun setRequestProperty(key:String,value:String) { headers[key]=value }
        override fun getOutputStream()=output
        override fun getResponseCode()=response.status
        override fun getInputStream()=ByteArrayInputStream(response.body.toString().toByteArray())
        override fun getErrorStream()=inputStream
        override fun getCipherSuite()="TLS_AES_128_GCM_SHA256"
        override fun getLocalCertificates():Array<java.security.cert.Certificate>?=null
        override fun getServerCertificates():Array<java.security.cert.Certificate> = emptyArray()
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy()=false
    }
    private val calls=mutableListOf<Connection>()
    private fun network(reply:(Connection)->Reply={ Reply(success(it.body)) })=ConnectionsManager { url -> Connection(url,reply).also { calls.add(it) } }
    private fun send(box:Box,network:ConnectionsManager=network(),batch:Boolean=true,single:Boolean=true,session:Session=owner,
                     progress:(Int,Int)->Unit={_,_->})=ScanQueueSender(network) { now }.send(session,box.summaries(),batch,single,box,progress)

    @Test fun all120ScansAreSentInThreeBoundedRequestsWithProgress() {
        val box=Box((1..120).map(::packet));val progress=mutableListOf<Pair<Int,Int>>()
        val result=send(box,progress={ done,total -> progress.add(done to total) })
        assertEquals(listOf(50,50,20),calls.map { it.body.a("scans").length() })
        assertEquals((1..120).map { packet(it).s("id") },calls.flatMap { it.body.a("scans").objects().map { row -> row.s("scanId") } })
        assertTrue(calls.all { it.url.path=="/api/v1/scanner/scans/batch" && it.headers["X-ROSHD-Scanner-School"]==school })
        assertEquals(120,result.sent);assertEquals(0,result.remaining);assertEquals(120,box.count("delivered"))
        assertEquals(0 to 120,progress.first());assertEquals(120 to 120,progress.last())
    }
    @Test fun partialFailureKeepsBothNewAndAlreadyAcceptedReceipts() {
        val box=Box((1..5).map(::packet))
        val result=send(box,network { connection ->
            val body=success(connection.body);val rows=body.a("items")
            rows.getJSONObject(1).put("outcome","already_accepted").put("httpStatus",200)
            rows.getJSONObject(2).put("outcome","conflict").put("httpStatus",409).put("result",JSONObject.NULL).put("errorCode","scan_id_conflict")
            rows.getJSONObject(3).put("outcome","rejected").put("httpStatus",503).put("result",JSONObject.NULL).put("errorCode","unavailable")
            Reply(body)
        })
        assertEquals(3,result.sent);assertEquals(2,result.remaining);assertTrue(result.stopped)
        assertEquals(3,box.count("delivered"));assertEquals(1,box.count("rejected"));assertEquals(1,box.count("retry_wait"))
        assertTrue(box.rows.getValue(packet(4).s("id")).optLong("retryAt")>=now+30_000)
    }
    @Test fun missingOrForgedReceiptCannotMarkAScanDelivered() {
        val box=Box((1..3).map(::packet))
        val result=send(box,network { connection ->
            val body=success(connection.body)
            body.a("items").getJSONObject(0).o("result").put("inputSha256","wrong")
            body.a("items").remove(1)
            Reply(body)
        })
        assertEquals(1,result.sent);assertEquals(2,box.count("retry_wait"));assertEquals(1,box.count("delivered"))
    }
    @Test fun responseOrderDoesNotMatterButDuplicateMappingsAreUntrusted() {
        val box=Box((1..3).map(::packet))
        val result=send(box,network { connection ->
            val rows=success(connection.body).a("items").objects().reversed().toMutableList()
            rows.add(rows.last().copy())
            Reply(json("schemaVersion" to "roshd.scan-batch-result.v1","items" to rows))
        })
        assertEquals(2,result.sent);assertEquals("retry_wait",box.rows.getValue(packet(1).s("id")).s("status"))
    }
    @Test fun timeoutStopsFurtherBatchesAndRetryKeepsExactIdsAndPayloads() {
        val box=Box((1..70).map(::packet))
        val original=box.rows.mapValues { it.value.o("request").toString() }
        val first=send(box,network { throw IOException("timeout") })
        assertTrue(first.stopped);assertEquals(1,calls.size);assertEquals(50,box.count("retry_wait"));assertEquals(20,box.count("pending"))
        now+=60_000
        val retry=send(box)
        assertEquals(70,retry.sent);assertEquals(70,box.count("delivered"))
        assertEquals(original,box.rows.mapValues { it.value.o("request").toString() })
        assertEquals(2,box.attempts[packet(1).s("id")]);assertEquals(1,box.attempts[packet(70).s("id")])
        val count=calls.size
        assertEquals(0,send(box).sent);assertEquals(count,calls.size)
    }
    @Test fun provinceSchoolsNeverShareABatchOrChangeTheirBearer() {
        val province=owner.copy(raw=owner.raw.copy().put("schoolId",ZERO_UUID).put("accessScope","province").put("schoolIds",wrap(listOf(school,otherSchool))))
        val box=Box(listOf(packet(1),packet(2),packet(3,otherSchool),packet(4,otherSchool)))
        assertEquals(4,send(box,session=province).sent)
        assertEquals(listOf(school,otherSchool),calls.map { it.headers["X-ROSHD-Scanner-School"] })
        assertTrue(calls.all { it.headers["Authorization"]=="Bearer test-only-token" })
    }
    @Test fun singleSubmissionFallbackStillSendsTheWholeQueue() {
        val box=Box((1..3).map(::packet))
        assertEquals(3,send(box,network { Reply(receipt(it.body),201) },batch=false).sent)
        assertEquals(3,calls.size);assertTrue(calls.all { it.url.path=="/api/v1/scanner/scans" })
    }
    @Test fun deliveredDamagedAndNotYetDueRowsAreNotSent() {
        val box=Box(listOf(packet(1).put("status","delivered"),packet(2).put("status","storage_error"),
            packet(3).put("status","retry_wait").put("retryAt",now+60_000),packet(4)))
        val result=send(box)
        assertEquals(1,result.sent);assertEquals(1,result.deferred)
        assertEquals(listOf(packet(4).s("id")),calls.single().body.a("scans").objects().map { it.s("scanId") })
    }
    @Test fun invalidLocalPacketDoesNotHideOtherScans() {
        val huge=packet(3).apply { o("request").put("padding","x".repeat(100*1024)) }
        val box=Box(listOf(packet(1),packet(2).put("broken",true),huge,packet(4)))
        assertEquals(2,send(box).sent);assertEquals(2,box.count("rejected"));assertEquals(2,box.count("delivered"))
    }
    @Test fun byteBudgetSplitsABatchBeforeFourMiB() {
        val box=Box((1..50).map { packet(it).apply { o("request").put("padding","x".repeat(90*1024)) } })
        assertEquals(50,send(box).sent)
        assertEquals(2,calls.size)
        assertTrue(calls.all { it.output.size()<=ScanQueueSender.MAX_BYTES && it.body.a("scans").length() in 1..50 })
    }
    @Test fun invalidBatchSchemaKeepsEveryPacketForRetry() {
        val box=Box((1..3).map(::packet))
        val result=send(box,network { Reply(json("schemaVersion" to "unknown","items" to emptyList<JSONObject>())) })
        assertEquals(0,result.sent);assertEquals(3,box.count("retry_wait"));assertTrue(result.stopped)
    }
    @Test fun disabledIntakeMakesNoRequest() {
        val box=Box(listOf(packet(1)))
        assertThrows(IllegalArgumentException::class.java) { send(box,batch=false,single=false) }
        assertTrue(calls.isEmpty());assertEquals(1,box.count("pending"))
        assertEquals(0,send(Box(emptyList()),batch=false,single=false).sent)
        assertEquals(1,send(box,batch=true,single=false).sent)
    }
    @Test fun wholeRequestPermissionFailureDoesNotTryOtherGroupsOrFallback() {
        val box=Box((1..70).map(::packet))
        val result=send(box,network { Reply(json("code" to "permission_denied"),403) })
        assertTrue(result.stopped);assertEquals(1,calls.size);assertEquals(50,box.count("rejected"));assertEquals(20,box.count("pending"))
    }
}
