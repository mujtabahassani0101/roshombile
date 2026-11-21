package com.haseebullahjan.roshd.core

import org.json.JSONObject
import java.net.URI

data class AppRelease(val metadata:JSONObject,val required:Boolean) {
    companion object {
        fun parse(wire:JSONObject,installedVersion:Int,packageName:String):AppRelease? {
            require(wire.s("platform")=="android" && wire.opt("updateAvailable") is Boolean && wire.opt("updateRequired") is Boolean) { "پاسخ بررسی نسخه معتبر نیست." }
            if(wire.has("schemaVersion"))require(wire.s("schemaVersion")=="roshd.mobile-app-version.v1")
            val latest=wire.optJSONObject("latest")
            if(latest==null) {
                require(wire.has("latest") && wire.isNull("latest") && !wire.getBoolean("updateAvailable") && !wire.getBoolean("updateRequired"))
                return null
            }
            val version=latest.optInt("versionCode",-1)
            val minimum=latest.optInt("minSupportedVersionCode",-1)
            require(version in 1..2_100_000_000 && minimum in 0..version && latest.s("versionName").isNotBlank())
            require(latest.s("packageName")==packageName && Regex("^[0-9a-f]{64}$").matches(latest.s("sha256")) && latest.optLong("sizeBytes") in 1..268_435_456L && latest.isNull("deletedAt")) { "نسخهٔ منتشرشده متعلق به این برنامه نیست." }
            uuid(latest.s("id"));parseServerInstant(latest.s("publishedAt"))
            downloadPath(latest)
            val available=version>installedVersion
            require(wire.getBoolean("updateAvailable")==available && wire.getBoolean("updateRequired")== (available && installedVersion<minimum))
            return if(available)AppRelease(latest.copy(),wire.getBoolean("updateRequired")) else null
        }
        fun downloadPath(release:JSONObject):String {
            val expected="/api/v1/mobile/app-releases/${uuid(release.s("id"))}/apk"
            val url=URI(release.s("downloadUrl"))
            require(!url.isOpaque && url.rawQuery==null && url.rawFragment==null && url.rawPath==expected)
            if(url.isAbsolute)require(origin("${url.scheme}://${url.rawAuthority}")==Roshd.API_ORIGIN)
            else require(url.rawAuthority==null)
            return expected
        }
    }
}
