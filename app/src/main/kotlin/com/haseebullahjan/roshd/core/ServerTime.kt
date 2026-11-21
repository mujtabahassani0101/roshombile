package com.haseebullahjan.roshd.core

import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** Parse server ISO timestamps with an explicit zone on every supported Android version.
 * Keep the original wire string intact: signed payloads and receipt correlation use exact bytes.
 */
fun parseServerInstant(value: String): Instant = try {
    OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant()
} catch (error: DateTimeParseException) {
    throw IllegalArgumentException("تاریخ پاسخ سرور معتبر نیست؛ دوباره تلاش کنید.", error)
}
