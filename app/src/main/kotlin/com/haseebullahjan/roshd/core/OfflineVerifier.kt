package com.haseebullahjan.roshd.core

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** Trust is established from the exact signed bytes, never a derived roster. */
class OfflineVerifier {
    fun verify(exam:Exam,envelope:JSONObject,session:Session,identity:DeviceIdentity,configuration:JSONObject,names:Map<String,String>):OfflineExam {
        val bytes=ScannerCrypto.decode(envelope.s("payloadBase64"))
        require(bytes.isNotEmpty() && sha256(bytes)==envelope.s("payloadSha256").lowercase()) { "اثر انگشت بستهٔ آفلاین مطابقت ندارد." }
        val keyId=envelope.s("signingKeyId",envelope.o("signature").s("keyId"))
        val now=Instant.now()
        val trust=configuration.optJSONArray("offlineTrustKeys") ?: configuration.o("trust").optJSONArray("offlineTrustKeys") ?: configuration.optJSONArray("verificationKeys") ?: JSONArray()
        val key=trust.objects().singleOrNull { it.s("keyId",it.s("key_id",it.s("signingKeyId",it.s("id"))))==keyId && it.s("status") in setOf("","active","retired")
            && (it.s("notBefore",it.s("not_before")).isEmpty() || !now.isBefore(parseServerInstant(it.s("notBefore",it.s("not_before")))))
            && (it.s("notAfter",it.s("not_after")).isEmpty() || now.isBefore(parseServerInstant(it.s("notAfter",it.s("not_after"))))) }
            ?: error("کلید معتبر امضای سرور پیدا نشد.")
        require(key.s("algorithm","ECDSA-P256-SHA256").uppercase()=="ECDSA-P256-SHA256") { "الگوریتم امضای بسته معتبر نیست." }
        require(ScannerCrypto.verify(key.s("publicKeySpki",key.s("publicKey",key.s("public_key"))),bytes,envelope.s("signatureBase64",envelope.o("signature").s("value")))) { "امضای بستهٔ آفلاین معتبر نیست." }
        val payload=JSONObject(String(bytes,Charsets.UTF_8))
        require(payload.s("schemaVersion")=="roshd.offline-lite.v1") { "نسخهٔ بستهٔ آفلاین پشتیبانی نمی‌شود." }
        val manifest=payload.optJSONObject("manifest") ?: payload
        if(envelope.has("manifest")) require(canonical(envelope.opt("manifest"))==canonical(manifest)) { "محتوای بسته با امضا مطابقت ندارد." }
        fun same(field:String,expected:String) { require(if(field=="deviceKeyId")manifest.s(field)==expected else manifest.s(field).equals(expected,true)) { "بسته به محدودهٔ جاری تعلق ندارد ($field)." } }
        same("scannerAccountId",session.accountId);same("schoolId",session.schoolId);same("deviceUuid",identity.deviceUuid);same("deviceKeyId",identity.keyId);same("examId",exam.id)
        for((field,value) in listOf("signingPublicKeySha256" to identity.signingHash,"encryptionPublicKeySha256" to identity.encryptionHash,"editionId" to exam.raw.s("editionId"),"accessVersion" to exam.raw.s("accessVersion"))) {
            if(value.isNotEmpty() && (manifest.has(field) || field in setOf("editionId","accessVersion")))same(field,value)
        }
        require(exam.count in 1..100 && manifest.optInt("questionCount")==exam.count && manifest.optInt("round")==exam.round && manifest.optInt("grade")==exam.grade) { "دور، صنف یا تعداد سؤال بسته مطابقت ندارد." }
        if(manifest.has("provinceId")) require(manifest.s("provinceId")==exam.raw.s("provinceId"))
        if(exam.raw.has("roundGeneration"))require(manifest.optInt("roundGeneration",-1)==exam.raw.optInt("roundGeneration"))
        require(manifest.s("packageId").isNotEmpty() && manifest.s("packageVersion").isNotEmpty())
        val issued=parseServerInstant(manifest.s("issuedAt"));val expires=parseServerInstant(manifest.s("expiresAt"))
        require(expires.isAfter(now) && expires.isAfter(issued)) { "اعتبار بستهٔ آفلاین پایان یافته است؛ معلومات را تازه کنید." }
        fun containsKey(value:Any?):Boolean = when(value) {
            is JSONObject -> value.keys().asSequence().any { it.lowercase() in setOf("correctoptions","acceptedoptions","correctoption","answerkey","correctanswers") || containsKey(value.opt(it)) }
            is JSONArray -> (0 until value.length()).any { containsKey(value.opt(it)) }
            else -> false
        }
        require(!containsKey(payload)) { "کلید پاسخ آشکار در بسته مجاز نیست." }
        return OfflineExam(exam,envelope,manifest,roster(exam,manifest,names))
    }
    fun names(source:JSONArray):Map<String,String> {
        val result=linkedMapOf<String,String>()
        for(i in 0 until source.length()) {
            val row=source.getJSONArray(i)
            require(row.length()==3 && (0..2).all { row.opt(it) is String })
            val code=row.getString(0);val key=studentKey(code)
            require(Regex("^[0-9]{1,32}$").matches(code) && !result.containsKey(key) && row.getString(1).length<=256 && row.getString(2).length<=256)
            result[key]="${row.getString(1)} ${row.getString(2)}".trim()
        }
        return result
    }
    fun roster(exam:Exam,manifest:JSONObject,names:Map<String,String>):List<RosterRow> {
        require(!manifest.has("roster") && !manifest.has("answerSheets"))
        val sheets=manifest.getJSONArray("sheets");require(sheets.length()<=100000)
        val seen=mutableSetOf<String>()
        return (0 until sheets.length()).map {
            val row=sheets.getJSONArray(it);require(row.length() in 2..4 && (0 until row.length()).all { n -> row.opt(n) is String })
            val code=row.getString(0);val alias=row.getString(1)
            require(Regex("^[0-9]{1,32}$").matches(code) && seen.add(studentKey(code))) { "هویت شاگرد در فهرست برگه‌ها معتبر یا یکتا نیست." }
            val qr=if(row.length()>=3)row.getString(2) else "${exam.template}:$code:$alias"
            require(if(row.length()==2)SheetQrIdentity.currentAlias(alias) && SheetQrIdentity.isPrinted(qr)
                else SheetQrIdentity.historical(qr,alias,code,exam.template)) { "QR برگه با فهرست امضاشدهٔ امتحان مطابق نیست." }
            val card=if(row.length()==4)row.getString(3) else code
            require((Regex("^[0-9]{1,32}$").matches(card) && studentKey(card)==studentKey(code)) || Regex("^C2[0123456789ABCDEFGHJKMNPQRSTVWXYZ]{18}$").matches(card))
            RosterRow(code,alias,qr,card,names[studentKey(code)] ?: error("نام شاگرد در بسته موجود نیست."),exam.id)
        }
    }
}
