package com.haseebullahjan.roshd.core

import com.haseebullahjan.roshd.messenger.ConnectionsManager
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URL
import java.time.Instant
import java.util.zip.GZIPOutputStream
import javax.net.ssl.HttpsURLConnection

class AcademicYearArchiveTest {
    private fun id(n:Int)="10000000-0000-4000-8000-${n.toString().padStart(12,'0')}"
    private val owner=Session(json("accountId" to id(1),"schoolId" to id(2),"accessToken" to "test-only",
        "expiresAt" to Instant.now().plusSeconds(3600).toString()),"https://example.org")
    private fun year(n:Int=3,count:Int=12)=json("id" to id(n),"academicYearId" to id(n+100),"academicYearKey" to "1404",
        "label" to "سال تعلیمی ۱۴۰۴","provinceId" to id(4),"editionId" to id(n+200),"version" to "v1",
        "roundCount" to count,"isCurrent" to false,"isActive" to false)
    private fun snapshot(year:JSONObject=year(),round:Int=1,session:Session=owner)=json(
        "schemaVersion" to "roshd.information-archive.v1","accountId" to session.accountId,
        "schoolId" to if(session.province)null else session.schoolId,"year" to year,"round" to round,
        "exams" to emptyList<Any>(),"publishedResults" to null,"attendance" to emptyList<Any>())
    private class Response(url:URL,private val bytes:ByteArray,private val gzip:Boolean=false,private val status:Int=200):HttpsURLConnection(url) {
        val headers=mutableMapOf<String,String>();var closed=false
        override fun setRequestProperty(key:String,value:String) { headers[key]=value }
        override fun getResponseCode()=status
        override fun getInputStream()=ByteArrayInputStream(bytes)
        override fun getErrorStream()=ByteArrayInputStream(bytes)
        override fun getContentEncoding()=if(gzip)"gzip" else null
        override fun getHeaderField(name:String):String?=null
        override fun getCipherSuite()="TLS_AES_128_GCM_SHA256"
        override fun getLocalCertificates():Array<java.security.cert.Certificate>?=null
        override fun getServerCertificates():Array<java.security.cert.Certificate> = emptyArray()
        override fun connect() {}
        override fun disconnect() { closed=true }
        override fun usingProxy()=false
    }
    private class Fixture {
        val documents=mutableMapOf<Triple<String,String,String>,JSONObject>()
        var response=JSONObject();var gzip=false;var status=200
        val requests=mutableListOf<Response>()
        val network=ConnectionsManager { url ->
            val raw=response.toString().toByteArray()
            val bytes=if(gzip)ByteArrayOutputStream().also { output -> GZIPOutputStream(output).use { it.write(raw) } }.toByteArray() else raw
            Response(url,bytes,gzip,status).also { requests.add(it) }
        }
        val archive=AcademicYearArchive(network,{ scope,kind,id -> documents[Triple(scope,kind,id)]?.copy() },
            { scope,kind,id,value -> documents[Triple(scope,kind,id)]=value.copy() })
    }
    @Test fun inactiveYearsAndTheirClosedRoundsRemainSelectableWithoutDeviceRegistration() {
        val f=Fixture();val old=year(count=6)
        f.response=json("items" to listOf(year(10).put("isCurrent",true),old))
        assertEquals(listOf(id(10),old.s("id")),f.archive.refreshYears(owner).map { it.s("id") })
        assertFalse(f.archive.cachedYears(owner).last().getBoolean("isActive"))
        f.response=snapshot(old,6);f.gzip=true
        val cached=f.archive.download(owner,old,6)
        assertEquals(6,cached.o("snapshot").getInt("round"))
        assertTrue(cached.s("downloadedAt").isNotEmpty())
        val request=f.requests.last()
        assertEquals("/api/v1/scanner/academic-years/${old.s("id")}/rounds/6/information",request.url.path)
        assertEquals("GET",request.requestMethod);assertFalse(request.doOutput)
        assertEquals("Bearer test-only",request.headers["Authorization"])
        assertEquals("gzip",request.headers["Accept-Encoding"])
        assertFalse(request.headers.containsKey("X-ROSHD-Scanner-School"));assertTrue(request.closed)
        val count=f.requests.size
        for(round in listOf(0,7,13))assertThrows(IllegalArgumentException::class.java) { f.archive.download(owner,old,round) }
        assertEquals(count,f.requests.size)
        val extended=year(count=15);f.response=snapshot(extended,15)
        assertEquals(15,f.archive.download(owner,extended,15).o("snapshot").getInt("round"))
    }
    @Test fun eachAccountServerSchoolYearAndRoundHasAnIndependentSnapshot() {
        val f=Fixture()
        val otherSchool=owner.copy(raw=owner.raw.copy().put("schoolId",id(20)))
        val otherAccount=owner.copy(raw=owner.raw.copy().put("accountId",id(21)))
        val otherServer=owner.copy(apiOrigin="https://another.example.org")
        for(session in listOf(owner,otherSchool,otherAccount,otherServer)) {
            for(year in listOf(year(),year(10)))for(round in listOf(1,2)) {
                assertNull(f.archive.cached(session,year,round))
                f.response=snapshot(year,round,session)
                f.archive.download(session,year,round)
            }
        }
        assertEquals(16,f.documents.size)
        assertEquals(owner.accountId,f.archive.cached(owner,year(),1)!!.o("snapshot").s("accountId"))
        assertEquals(otherSchool.schoolId,f.archive.cached(otherSchool,year(),1)!!.o("snapshot").s("schoolId"))
        assertTrue(f.documents.keys.all { it.second=="academic-year-archive" })
    }
    @Test fun mismatchedOrIncompleteSnapshotsCannotReplaceTheLastGoodCopy() {
        val f=Fixture();f.response=snapshot();f.archive.download(owner,year(),1)
        val saved=canonical(f.archive.cached(owner,year(),1))
        val invalid=listOf(
            snapshot().put("schemaVersion","roshd.information-bundle.v2"),snapshot().put("accountId",id(30)),
            snapshot().put("schoolId",id(31)),snapshot().put("schoolId",JSONObject.NULL),snapshot().put("round",2),
            snapshot(year(10)),snapshot().apply { remove("attendance") },snapshot().apply { remove("publishedResults") },
            snapshot().put("exams",org.json.JSONArray().put("not an exam")),
            snapshot().put("publishedResults",json("editions" to emptyList<Any>()))
        )
        for(value in invalid) {
            f.response=value
            assertThrows(Exception::class.java) { f.archive.download(owner,year(),1) }
            assertEquals(saved,canonical(f.archive.cached(owner,year(),1)))
        }
        f.status=413;f.response=json("code" to "information_bundle_too_large")
        val error=assertThrows(ApiFailure::class.java) { f.archive.download(owner,year(),1) }
        assertEquals(413,error.status);assertEquals(saved,canonical(f.archive.cached(owner,year(),1)))
    }
    @Test fun provincialSnapshotUsesNullSchoolAndDoesNotSendASchoolHeader() {
        val f=Fixture();val provincial=owner.copy(raw=owner.raw.copy().put("schoolId",ZERO_UUID).put("accessScope","province"))
        f.response=snapshot(session=provincial)
        assertTrue(f.archive.download(provincial,year(),1).o("snapshot").isNull("schoolId"))
        assertFalse(f.requests.single().headers.containsKey("X-ROSHD-Scanner-School"))
        f.response=snapshot(session=provincial).put("schoolId",owner.schoolId)
        assertThrows(IllegalArgumentException::class.java) { f.archive.download(provincial,year(),1) }
    }
    @Test fun emptyYearListsAndEmptyValidRoundsAreSuccessfulAndInvalidListsKeepTheCache() {
        val f=Fixture();f.response=json("items" to emptyList<Any>())
        assertTrue(f.archive.refreshYears(owner).isEmpty())
        f.response=json("items" to listOf(year()))
        f.archive.refreshYears(owner)
        f.response=json("items" to listOf(year(),year()))
        assertThrows(IllegalArgumentException::class.java) { f.archive.refreshYears(owner) }
        assertEquals(1,f.archive.cachedYears(owner).size)
        f.response=snapshot();f.archive.download(owner,year(),1)
        assertEquals(0,f.archive.cached(owner,year(),1)!!.o("snapshot").a("exams").length())
    }
}
