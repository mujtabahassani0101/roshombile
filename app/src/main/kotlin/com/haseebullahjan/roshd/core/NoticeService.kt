package com.haseebullahjan.roshd.core

import android.content.Context
import com.haseebullahjan.roshd.messenger.ConnectionsManager
import com.haseebullahjan.roshd.messenger.MessagesStorage
import com.haseebullahjan.roshd.platform.NativeGateway
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID

class NoticeService(private val context:Context,private val network:ConnectionsManager,private val native:NativeGateway,private val storage:MessagesStorage) {
    fun single(owner:Session,publicationId:String,color:String="blue"):File {
        require(color in setOf("blue","red"))
        require(!owner.province)
        val publication=uuid(publicationId)
        val previous=storage.get(owner.scope,"notice-single","$publication/$color")
        val requestId=previous?.s("requestId") ?: UUID.randomUUID().toString()
        storage.put(owner.scope,"notice-single","$publication/$color",json("requestId" to requestId))
        val keyScope=sha256(JSONArray(listOf("yse.mobile.notice.recipient.v1",owner.apiOrigin,owner.accountId.lowercase(),owner.schoolId.lowercase())).toString())
        val identity=wrap(native.blocking("mobileNoticeIdentity",mapOf("scope" to keyScope))) as JSONObject
        val bits=identity.optInt("bits");require(bits in setOf(2048,3072,4096))
        val kid=Base64.getUrlEncoder().withoutPadding().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(ScannerCrypto.decode(identity.s("publicKeySpki"))))
        val body=json("requestId" to requestId,"recipientPublicKeySpki" to identity.s("publicKeySpki"),"color" to color)
        val envelope=network.request("POST","/api/v1/school-manager/results/$publication/notice/mobile-payload",owner,body=body,maxBytes=6*1024*1024)
        val page=decrypt(envelope,keyScope,kid,bits)
        validateCommon(page,owner,requestId,color)
        require(page.has("afterPublicId") && page.isNull("afterPublicId") && page.has("nextAfterPublicId") && page.isNull("nextAfterPublicId") && page.has("source") && page.isNull("source"))
        val notices=page.getJSONArray("notices");require(notices.length()==1)
        val notice=notices.getJSONObject(0)
        require(notice.s("publicationId")==publication && Regex("^[0-9a-fA-F]{64}$").matches(notice.s("snapshotSha256")))
        val data=notice.getJSONObject("data");NoticeRenderer.validate(data);data.put("noticeColor",color)
        val output=File(context.filesDir,"manager-files/notices/$requestId.pdf")
        NoticeRenderer(context).render(output,listOf(data))
        storage.put(owner.scope,"notice-file",requestId,json("path" to output.path,"createdAt" to now(),"count" to 1,"round" to data.optInt("round"),"publicationId" to publication))
        return output
    }
    fun group(owner:Session,edition:String,round:Int,grade:Int?,reason:String,color:String="blue"):File {
        require(color in setOf("blue","red"))
        require(!owner.province && round in 1..ExamPeriod.MAX_ROUND && (grade==null || grade in 1..12) && reason.length in 3..500 && reason.none { it.code<32 })
        uuid(edition)
        val parameters=json("editionId" to edition,"round" to round,"grade" to grade,"reason" to reason,"color" to color)
        val previous=storage.get(owner.scope,"notice-intent","current")
        val intent=if(previous!=null && canonical(previous.o("parameters"))==canonical(parameters))previous else json("requestId" to UUID.randomUUID().toString(),"parameters" to parameters,"cursor" to null,"ids" to JSONArray())
        storage.put(owner.scope,"notice-intent","current",intent)
        val keyScope=sha256(JSONArray(listOf("yse.mobile.notice.recipient.v1",owner.apiOrigin,owner.accountId.lowercase(),owner.schoolId.lowercase())).toString())
        val identity=wrap(native.blocking("mobileNoticeIdentity",mapOf("scope" to keyScope))) as JSONObject
        val bits=identity.optInt("bits");require(bits in setOf(2048,3072,4096))
        val kid=Base64.getUrlEncoder().withoutPadding().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(ScannerCrypto.decode(identity.s("publicKeySpki"))))
        val requestId=intent.s("requestId");var cursor=intent.s("cursor").takeIf { it.isNotEmpty() }
        val ids=intent.a("ids").strings().toMutableList();val cursors=mutableSetOf<String>()
        var requests=0
        if(!intent.optBoolean("complete"))do {
            if(++requests>18)throw ApiFailure("notice_request_wait","بخش‌های دریافت‌شده محفوظ‌اند؛ یک دقیقه بعد همین درخواست را ادامه دهید.",429,System.currentTimeMillis()+60_000)
            val body=parameters.copy().put("requestId",requestId).put("recipientPublicKeySpki",identity.s("publicKeySpki")).put("afterPublicId",cursor ?: JSONObject.NULL).put("limit",20)
            val envelope=network.request("POST","/api/v1/school-manager/notice-exports/mobile-payload",owner,body=body,maxBytes=6*1024*1024)
            val page=decrypt(envelope,keyScope,kid,bits)
            validatePage(page,owner,requestId,cursor,intent)
            val notices=page.getJSONArray("notices").objects()
            for(notice in notices) {
                val id=uuid(notice.s("publicationId"));require(id !in ids)
                require(Regex("^[0-9a-fA-F]{64}$").matches(notice.s("snapshotSha256")) && notice.s("fileName").length<=255)
                val data=notice.getJSONObject("data");NoticeRenderer.validate(data);data.put("noticeColor",color)
                storage.put(owner.scope,"notice-data","$requestId/$id",data);ids.add(id)
            }
            cursor=page.s("nextAfterPublicId").takeIf { it.isNotEmpty() }
            require(cursor==null || (cursor==notices.last().o("data").s("studentPublicId") && cursors.add(cursor)))
            val source=page.o("source");if(!intent.has("source"))intent.put("source",source)
            intent.put("cursor",cursor ?: JSONObject.NULL).put("ids",JSONArray(ids)).put("complete",cursor==null)
            storage.put(owner.scope,"notice-intent","current",intent)
        } while(cursor!=null)
        require(ids.size==intent.o("source").optInt("noticeCount")) { "تمام اطلاع‌نامه‌های این درخواست دریافت نشده است؛ ادامهٔ درخواست محفوظ است." }
        val output=File(context.filesDir,"manager-files/notices/$requestId.pdf")
        NoticeRenderer(context).render(output,ids.map { id -> storage.get(owner.scope,"notice-data","$requestId/$id") ?: error("بخش اطلاع‌نامه پیدا نشد.") })
        storage.put(owner.scope,"notice-file",requestId,json("path" to output.path,"createdAt" to now(),"count" to ids.size,"round" to round))
        return output
    }
    private fun urlDecode(value:String,length:Int?=null,max:Int=6*1024*1024):ByteArray {
        require(value.isNotEmpty() && Regex("^[A-Za-z0-9_-]+$").matches(value))
        val bytes=Base64.getUrlDecoder().decode(value)
        require(bytes.size<=max && (length==null || bytes.size==length) && Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)==value)
        return bytes
    }
    private fun decrypt(envelope:JSONObject,scope:String,kid:String,bits:Int):JSONObject {
        require(envelope.keys().asSequence().toSet()==setOf("protected","encrypted_key","iv","ciphertext","tag"))
        val protected=envelope.s("protected");val header=JSONObject(String(urlDecode(protected,max=3072),Charsets.UTF_8))
        require(header.length()==6 && header.s("alg")=="RSA-OAEP-256" && header.s("enc")=="A256GCM" && header.s("zip")=="DEF" && header.s("typ")=="roshd.mobile-notice.v1" && header.s("cty")=="application/json" && header.s("kid")==kid)
        val bytes=native.blocking("mobileNoticeDecrypt",mapOf("scope" to scope,"kid" to kid,"protectedAscii" to protected.toByteArray(Charsets.US_ASCII),"encryptedKey" to urlDecode(envelope.s("encrypted_key"),bits/8),"iv" to urlDecode(envelope.s("iv"),12),"ciphertext" to urlDecode(envelope.s("ciphertext")),"tag" to urlDecode(envelope.s("tag"),16))) as ByteArray
        try { require(bytes.size in 1..4*1024*1024);return JSONObject(String(bytes,Charsets.UTF_8)) } finally { bytes.fill(0) }
    }
    private fun validateCommon(page:JSONObject,owner:Session,requestId:String,color:String) {
        require(page.s("schemaVersion")=="roshd.mobile-notice.v1" && page.s("requestId")==requestId && page.s("accountId")==owner.accountId && page.s("schoolId")==owner.schoolId && page.s("color")==color) { "محدودهٔ اطلاع‌نامه تأیید نشد." }
        val issued=parseServerInstant(page.s("issuedAt"));val expires=parseServerInstant(page.s("expiresAt"));val now=Instant.now()
        require(Duration.between(issued,expires).seconds==600L && !issued.isAfter(now.plusSeconds(30)) && now.isBefore(expires))
    }
    private fun validatePage(page:JSONObject,owner:Session,requestId:String,cursor:String?,intent:JSONObject) {
        validateCommon(page,owner,requestId,intent.o("parameters").s("color"))
        require(page.has("afterPublicId") && page.has("nextAfterPublicId") && page.s("afterPublicId").takeIf { it.isNotEmpty() }==cursor)
        val source=page.getJSONObject("source");require(source.s("id").isNotEmpty() && Regex("^[a-fA-F0-9]{64}$").matches(source.s("sha256")) && source.optInt("noticeCount") in 1..2000)
        val previous=intent.optJSONObject("source")
        if(previous!=null)require(source.s("id")==previous.s("id") && source.s("sha256")==previous.s("sha256") && source.optInt("noticeCount")==previous.optInt("noticeCount")) { "منبع گزارش در هنگام دریافت تغییر کرده است." }
        val parameters=intent.o("parameters")
        for(field in listOf("schoolId","editionId","round","grade"))if(source.has(field))require(canonical(source.opt(field))==canonical(if(field=="schoolId")owner.schoolId else parameters.opt(field)))
        val notices=page.getJSONArray("notices");require(notices.length() in 1..20)
    }
}
