package com.haseebullahjan.roshd.core

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class ServerTimeTest {
    private fun login(expiresAt: String) = json("data" to json(
        "accountId" to "20000000-0000-0000-0000-000000000001",
        "schoolId" to "10000000-0000-0000-0000-000000000001",
        "role" to "ScannerOperator", "accessScope" to "school",
        "accessToken" to "test-token", "tokenType" to "Bearer", "apiVersion" to "v1",
        "expiresIn" to 3600, "expiresAt" to expiresAt))

    @Test fun acceptsTheExactLoginTimestampFromAndroid11Failure() {
        assertEquals(Instant.parse("2026-11-08T11:08:24Z"), parseServerInstant("2026-11-08T11:08:24+00:00"))
    }

    @Test fun utcKabulAndNegativeOffsetsReferToTheSameInstant() {
        val expected=Instant.parse("2026-11-08T11:08:24Z")
        listOf("2026-11-08T11:08:24Z", "2026-11-08T15:38:24+04:30", "2026-11-08T06:08:24-05:00")
            .forEach { assertEquals(expected, parseServerInstant(it)) }
    }

    @Test fun preservesDotNetSevenDigitsAndNanosecondPrecision() {
        assertEquals(Instant.ofEpochSecond(1794136104,123456700), parseServerInstant("2026-11-08T11:08:24.1234567+00:00"))
        assertEquals(Instant.ofEpochSecond(1794136104,123456789), parseServerInstant("2026-11-08T15:38:24.123456789+04:30"))
    }

    @Test fun rejectsMissingZonesInvalidDatesAndTrailingData() {
        listOf("", "2026-11-08", "2026-11-08T11:08:24", "2026-02-30T11:08:24+00:00",
            "2026-11-08T11:08:24+25:00", "2026-11-08T11:08:24Zjunk").forEach { value ->
            val error=assertThrows(IllegalArgumentException::class.java) { parseServerInstant(value) }
            assertEquals("تاریخ پاسخ سرور معتبر نیست؛ دوباره تلاش کنید.",error.message)
        }
    }

    @Test fun loginAndRestoredSessionAcceptOffsetExpiryWithoutRewritingIt() {
        val wire=DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Instant.now().plusSeconds(3600).atOffset(ZoneOffset.ofHoursMinutes(4,30)))
        val response=login(wire);val before=response.toString()
        val owner=Session.login(response,"https://example.org")
        assertTrue(owner.valid)
        assertEquals(wire,owner.raw.s("expiresAt"))
        assertEquals(before,response.toString())
        assertTrue(Session(owner.raw.copy(),owner.apiOrigin).valid)
    }

    @Test fun expiredOffsetSessionAndTimezoneFreeSessionRemainInvalid() {
        val expired=DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Instant.now().minusSeconds(60).atOffset(ZoneOffset.ofHours(-5)))
        listOf(expired,"2099-11-08T11:08:24").forEach { wire ->
            assertThrows(IllegalArgumentException::class.java) { Session.login(login(wire),"https://example.org") }
            assertFalse(Session(login(wire).data(),"https://example.org").valid)
        }
    }
}
