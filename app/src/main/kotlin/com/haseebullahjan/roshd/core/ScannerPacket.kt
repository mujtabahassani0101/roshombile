package com.haseebullahjan.roshd.core

import org.json.JSONObject

/** Exact-byte envelopes for the two independent Roshd intake operations. */
object ScannerPacket {
    fun create(kind:String,owner:Session,deviceUuid:String,id:String,payload:JSONObject,sign:(List<String>)->String):JSONObject {
        require(kind in setOf("scan","image-capture"))
        require(payload.s("schemaVersion") in setOf("roshd.$kind-payload.v1","roshd.$kind-payload.v2"))
        if(kind=="image-capture")require(!payload.has("answers"))
        val bytes=payload.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..if(kind=="scan")48*1024 else 8*1024) { "حجم معلومات بیشتر از حد مجاز است." }
        val hash=sha256(bytes);val schema="roshd.$kind.v1"
        val lines=listOf(schema,uuid(owner.schoolId),uuid(owner.accountId),uuid(deviceUuid),uuid(id),hash)
        return json("schemaVersion" to schema,"${if(kind=="scan")"scan" else "capture"}Id" to id,"deviceUuid" to deviceUuid,
            "payloadBase64" to ScannerCrypto.encode(bytes),"inputSha256" to hash,"signatureBase64" to sign(lines))
    }
    fun registrationLines(owner:Session,identity:DeviceIdentity):List<String> {
        val encryption=identity.keys.optBoolean("encryptionSupported")
        return listOf(if(encryption)"roshd.device-registration.v2" else "roshd.device-registration.v1",owner.schoolId,owner.accountId,identity.deviceUuid,identity.keyId,identity.signingHash)+
            if(encryption)listOf(identity.encryptionHash) else emptyList()
    }
}
