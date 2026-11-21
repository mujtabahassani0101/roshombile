package com.haseebullahjan.roshd.core

import com.haseebullahjan.roshd.messenger.ConnectionsManager
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

/** Opt-in, read-only probes using the app's actual HTTPS client. No account or device is created. */
class LiveRoshdApiTest {
    @Before fun requireExplicitLiveRun() {
        assumeTrue("Set ROSHD_LIVE_API_TEST=1 to contact the live Roshd server.",
            System.getenv("ROSHD_LIVE_API_TEST") == "1")
    }

    @Test fun publicUpdateResponseIsAcceptedByTheApp() {
        val wire=ConnectionsManager().request("GET","/api/v1/mobile/app-version",
            query=mapOf("platform" to "android","versionCode" to "4200"),readTimeoutMs=30_000)
        assertEquals("roshd.mobile-app-version.v1",wire.s("schemaVersion"))
        val release=AppRelease.parse(wire,4200,"com.haseebullahjan.roshd")
        assertEquals(wire.getBoolean("updateAvailable"),release!=null)
        println("ROSHD_LIVE_PROBE "+json("case" to "update","status" to 200,
            "schemaVersion" to wire.s("schemaVersion"),"updateAvailable" to (release!=null)))
    }

    @Test fun updateRejectsAnOmittedInstalledVersion() {
        val failure=assertThrows(ApiFailure::class.java) {
            ConnectionsManager().request("GET","/api/v1/mobile/app-version",
                query=mapOf("platform" to "android"),readTimeoutMs=30_000)
        }
        record("update_missing_version",failure)
        assertEquals(400,failure.status)
    }

    @Test fun scannerConfigurationRequiresLogin() {
        requireAuthentication("configuration","/api/v1/scanner/configuration")
    }

    @Test fun receiptsRequireLogin() {
        requireAuthentication("receipt","/api/v1/scanner/scans/00000000-0000-4000-8000-000000000001/receipt",
            query=mapOf("deviceUuid" to "00000000-0000-4000-8000-000000000002"))
    }

    @Test fun schoolPortalRequiresLogin() {
        // This policy returns ProblemDetails without the scanner-specific error code.
        requireAuthentication("portal","/api/v1/school-manager/portal",requireScannerCode=false)
    }

    @Test fun anInvalidBearerIsRejected() {
        val fake=Session(json("accessToken" to "roshd-read-only-smoke-invalid-token",
            "expiresAt" to Instant.now().plusSeconds(300).toString(),
            "accountId" to "00000000-0000-4000-8000-000000000001",
            "schoolId" to "00000000-0000-4000-8000-000000000002"),Roshd.API_ORIGIN)
        requireAuthentication("invalid_bearer","/api/v1/scanner/configuration",session=fake)
    }

    private fun requireAuthentication(name:String,path:String,query:Map<String,String> = emptyMap(),session:Session?=null,requireScannerCode:Boolean=true) {
        val failure=assertThrows(ApiFailure::class.java) {
            ConnectionsManager().request("GET",path,session=session,query=query,readTimeoutMs=30_000)
        }
        record(name,failure)
        assertEquals(401,failure.status)
        if(requireScannerCode)assertEquals("authentication_required",failure.code)
    }

    private fun record(name:String,failure:ApiFailure) {
        println("ROSHD_LIVE_PROBE "+json("case" to name,"status" to failure.status,"code" to failure.code))
    }
}
