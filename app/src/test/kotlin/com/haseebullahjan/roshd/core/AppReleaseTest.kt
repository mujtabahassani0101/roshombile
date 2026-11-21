package com.haseebullahjan.roshd.core

import org.junit.Assert.*
import org.junit.Test

class AppReleaseTest {
    private val id="10000000-0000-0000-0000-000000000001"
    private val packageName="com.haseebullahjan.roshd"
    private fun release()=json("id" to id,"packageName" to packageName,"versionCode" to 100,"minSupportedVersionCode" to 90,"versionName" to "2.0","sha256" to "a".repeat(64),"sizeBytes" to 12345,"publishedAt" to "2026-11-11T12:00:00Z","deletedAt" to null,"downloadUrl" to "/api/v1/mobile/app-releases/$id/apk")
    private fun wire(latest:org.json.JSONObject?=release(),available:Boolean=true,required:Boolean=true)=json("platform" to "android","updateAvailable" to available,"updateRequired" to required,"latest" to latest)
    @Test fun updateFlagsAndMinimumSupportedVersionFollowTheContract() {
        assertTrue(AppRelease.parse(wire(),80,packageName)!!.required)
        assertFalse(AppRelease.parse(wire(required=false),95,packageName)!!.required)
        assertNull(AppRelease.parse(wire(available=false,required=false),100,packageName))
        assertNull(AppRelease.parse(wire(available=false,required=false),101,packageName))
        assertNull(AppRelease.parse(wire(latest=null,available=false,required=false),80,packageName))
    }
    @Test fun anotherPackageOrInconsistentAvailabilityIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { AppRelease.parse(wire(release().put("packageName","another.app")),80,packageName) }
        assertThrows(IllegalArgumentException::class.java) { AppRelease.parse(wire(),101,packageName) }
        assertThrows(IllegalArgumentException::class.java) { AppRelease.parse(wire(latest=null),80,packageName) }
    }
    @Test fun onlyAnExactImmutableDownloadOnTheSameOriginIsAccepted() {
        val expected="/api/v1/mobile/app-releases/$id/apk"
        assertEquals(expected,AppRelease.downloadPath(release().put("downloadUrl",Roshd.API_ORIGIN+expected)))
        for(url in listOf("https://other.example$expected","//$id.example$expected","$expected?token=secret","$expected#fragment",expected.replace("/apk","/%61pk"))) {
            assertThrows(IllegalArgumentException::class.java) { AppRelease.downloadPath(release().put("downloadUrl",url)) }
        }
    }
    @Test fun MissingHashOrInvalidFileSizeCannotBecomeAnInstallOffer() {
        for(bad in listOf(release().put("sha256",""),release().put("sizeBytes",0),release().put("sizeBytes",268435457))) {
            assertThrows(IllegalArgumentException::class.java) { AppRelease.parse(wire(bad),80,packageName) }
        }
    }
}
