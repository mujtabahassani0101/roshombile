package com.haseebullahjan.roshd.core

import java.util.Base64
import com.haseebullahjan.roshd.platform.NativeGateway
import org.json.JSONObject
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.util.UUID

class ScannerCrypto(private val native: NativeGateway) {
    fun secureRead(key:String):String? = native.blocking("secureRead",mapOf("key" to key)) as? String
    fun secureWrite(key:String,value:String?) { native.blocking("secureWrite",mapOf("key" to key,"value" to value)) }
    fun identity(session:Session):DeviceIdentity {
        val scope=sha256("${session.schoolId}\n${session.accountId}").take(32)
        val alias="roshd.scanner.$scope"
        val upgraded=secureRead("$alias.agreement-identity.v2")?.let(::JSONObject)
        if(upgraded!=null)require(upgraded.s("alias")=="$alias.agreement-v2" && upgraded.o("previous").s("alias")==alias && uuid(upgraded.s("deviceUuid"))!=uuid(upgraded.o("previous").s("deviceUuid"))) { "هویت ارتقایافتهٔ دستگاه معتبر نیست؛ کلیدهای قبلی محفوظ‌اند." }
        val active=upgraded?.s("alias") ?: alias
        val device=upgraded?.s("deviceUuid") ?: secureRead("$alias.device-uuid.v1") ?: UUID.randomUUID().toString().also { secureWrite("$alias.device-uuid.v1",it) }
        val keyId=upgraded?.s("keyId") ?: secureRead("$alias.device-key-id.v1") ?: "roshd.scanner.$device.ecdsa-p256.v1".also { secureWrite("$alias.device-key-id.v1",it) }
        val keys=wrap(native.blocking("p256Identity",mapOf("alias" to active))) as JSONObject
        require(sha256(decode(keys.s("signingPublicKeySpki")))==keys.s("signingPublicKeySha256")) { "اثر انگشت کلید امضا مطابقت ندارد." }
        if(keys.optBoolean("encryptionSupported"))require(sha256(decode(keys.s("encryptionPublicKeySpki")))==keys.s("encryptionPublicKeySha256") && keys.s("signingPublicKeySpki")!=keys.s("encryptionPublicKeySpki"))
        return DeviceIdentity(active,uuid(device),keyId,keys)
    }
    /** The controller must drain immutable legacy requests before committing this identity. */
    fun upgrade(session:Session):DeviceIdentity {
        val previous=identity(session)
        if(!previous.keys.optBoolean("encryptionUpgradeRequired"))return previous
        val alias="${previous.alias}.agreement-v2"
        val keys=wrap(native.blocking("p256Identity",mapOf("alias" to alias))) as JSONObject
        require(keys.optBoolean("encryptionSupported") && !keys.optBoolean("encryptionUpgradeRequired")) { "کلید سازگار ساخته نشد؛ هویت قبلی محفوظ است." }
        val device=UUID.randomUUID().toString()
        val saved=json("alias" to alias,"deviceUuid" to device,"keyId" to "roshd.scanner.$device.ecdsa-p256.v1","previous" to json("alias" to previous.alias,"deviceUuid" to previous.deviceUuid,"keyId" to previous.keyId,"signingPublicKeySpki" to previous.keys.s("signingPublicKeySpki"),"signingPublicKeySha256" to previous.signingHash))
        secureWrite("${previous.alias}.agreement-identity.v2",saved.toString())
        return identity(session)
    }
    fun sign(identity:DeviceIdentity,lines:List<String>):String {
        require(lines.none { it.contains('\r') || it.contains('\n') })
        return native.blocking("p256Sign",mapOf("alias" to identity.alias,"dataBase64" to encode(lines.joinToString("\n").toByteArray(Charsets.UTF_8)))) as String
    }
    fun registration(session:Session,identity:DeviceIdentity):JSONObject {
        val transcript=ScannerPacket.registrationLines(session,identity)
        val metadata=wrap(native.blocking("registrationMetadata")) as JSONObject
        return json("deviceUuid" to identity.deviceUuid,"deviceKeyId" to identity.keyId,"platform" to "android",
            "deviceName" to metadata.s("deviceName"),"appVersion" to metadata.s("appVersion"),"signingPublicKeySpki" to identity.keys.s("signingPublicKeySpki"),
            "proofOfPossession" to sign(identity,transcript),"encryptionPublicKeySpki" to identity.keys.opt("encryptionPublicKeySpki"))
    }
    fun packet(session:Session,identity:DeviceIdentity,kind:String,payload:JSONObject,id:String=UUID.randomUUID().toString()):JSONObject {
        require(kind=="scan")
        return ScannerPacket.create(kind,session,identity.deviceUuid,id,payload) { sign(identity,it) }
    }
    fun offlineCount(session:Session,identity:DeviceIdentity,offline:OfflineExam,observations:List<JSONObject>,annotation:JSONObject?=null):JSONObject? {
        val manifest=offline.manifest;val capsule=manifest.o("offlineGrading")
        if(capsule.length()==0)return null
        val schema=capsule.s("schemaVersion")
        require(schema in setOf("roshd.offline-correct-count.v1","roshd.offline-correct-count.v2")) { "نسخهٔ کلید نمره‌دهی پشتیبانی نمی‌شود." }
        val expires=parseServerInstant(manifest.s("expiresAt")).epochSecond
        require(expires>Instant.now().epochSecond && capsule.s("recipientKeySha256")==identity.encryptionHash)
        require(observations.size==offline.exam.count && offline.valid)
        val aad=listOf(schema,session.schoolId,session.accountId,identity.deviceUuid,offline.exam.id,manifest.s("packageId"),manifest.s("packageVersion"),identity.encryptionHash,capsule.s("releaseVersion"),capsule.s("answerKeyVersion"),offline.exam.count.toString(),expires.toString()).joinToString("\n")
        val envelope=capsule.optJSONObject("encryption") ?: capsule
        return wrap(native.blocking("offlineCorrectCount",envelope.map()+mapOf("alias" to identity.alias,"schemaVersion" to schema,"aad" to aad,"recipientKeySha256" to identity.encryptionHash,
            "releaseVersion" to capsule.s("releaseVersion"),"answerKeyVersion" to capsule.s("answerKeyVersion"),"questionCount" to offline.exam.count,"observations" to observations.map { it.map() },"annotation" to annotation?.map()))) as? JSONObject
    }
    companion object {
        fun encode(bytes:ByteArray):String = Base64.getEncoder().encodeToString(bytes)
        fun decode(text:String):ByteArray {
            val bytes=Base64.getDecoder().decode(text)
            require(encode(bytes)==text) { "کدگذاری Base64 معتبر نیست." }
            return bytes
        }
        fun verify(publicKey:String,bytes:ByteArray,signature:String):Boolean {
            val raw=decode(signature);if(raw.size!=64)return false
            val key=KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(decode(publicKey)))
            val verifier=Signature.getInstance("SHA256withECDSA");verifier.initVerify(key);verifier.update(bytes)
            return verifier.verify(p1363ToDer(raw))
        }
        fun p1363ToDer(raw:ByteArray):ByteArray {
            require(raw.size==64)
            fun integer(bytes:ByteArray):ByteArray {
                val stripped=bytes.dropWhile { it==0.toByte() }.toByteArray().let { if(it.isEmpty())byteArrayOf(0) else it }
                val value=if(stripped[0].toInt()<0)byteArrayOf(0)+stripped else stripped
                return byteArrayOf(2,value.size.toByte())+value
            }
            val pair=integer(raw.copyOfRange(0,32))+integer(raw.copyOfRange(32,64))
            return byteArrayOf(48,pair.size.toByte())+pair
        }
    }
}
