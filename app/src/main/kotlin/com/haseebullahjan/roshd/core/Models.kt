package com.haseebullahjan.roshd.core

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

fun json(vararg fields: Pair<String, Any?>): JSONObject = JSONObject().apply { fields.forEach { put(it.first, wrap(it.second)) } }
fun wrap(value: Any?): Any = when (value) {
    null -> JSONObject.NULL
    is Map<*, *> -> JSONObject().apply { value.forEach { (k,v) -> put(k.toString(),wrap(v)) } }
    is Iterable<*> -> JSONArray().apply { value.forEach { put(wrap(it)) } }
    else -> value
}
fun JSONObject.s(key: String, fallback: String = ""): String = if (isNull(key)) fallback else optString(key,fallback)
fun JSONObject.o(key: String): JSONObject = optJSONObject(key) ?: JSONObject()
fun JSONObject.a(key: String): JSONArray = optJSONArray(key) ?: JSONArray()
fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
fun JSONArray.strings(): List<String> = (0 until length()).mapNotNull { opt(it) as? String }
fun JSONObject.copy(): JSONObject = JSONObject(toString())
fun JSONObject.data(): JSONObject = optJSONObject("data") ?: this
fun JSONObject.map(): Map<String,Any?> = keys().asSequence().associateWith { k -> unwrap(opt(k)) }
fun unwrap(value: Any?): Any? = when(value) {
    is JSONObject -> value.map()
    is JSONArray -> (0 until value.length()).map { unwrap(value.opt(it)) }
    JSONObject.NULL -> null
    else -> value
}
fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
fun sha256(text: String): String = sha256(text.toByteArray(Charsets.UTF_8))
fun uuid(value: String): String {
    val text=value.lowercase()
    require(Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$").matches(text) && text!=ZERO_UUID) { "هویت محدوده معتبر نیست." }
    return text
}
const val ZERO_UUID = "00000000-0000-0000-0000-000000000000"
fun origin(value: String): String {
    val uri=URI(value)
    require(uri.scheme=="https" && !uri.host.isNullOrEmpty() && uri.rawUserInfo==null && uri.query==null && uri.fragment==null && uri.path in listOf("","/")) { "نشانی سرور باید مبدأ مستقیم HTTPS باشد." }
    return "https://${uri.host.lowercase()}${if(uri.port>0 && uri.port!=443) ":${uri.port}" else ""}"
}
fun studentKey(code: String): String = if (Regex("^[1-9][0-9]{5}$").matches(code)) "${code}0000" else code
fun uuidV5(namespace: String, name: String): String {
    val u=UUID.fromString(namespace)
    val bytes=java.nio.ByteBuffer.allocate(16).putLong(u.mostSignificantBits).putLong(u.leastSignificantBits).array()
    val digest=MessageDigest.getInstance("SHA-1").digest(bytes+name.toByteArray(Charsets.UTF_8)).copyOf(16)
    digest[6]=((digest[6].toInt() and 15) or 80).toByte(); digest[8]=((digest[8].toInt() and 63) or 128).toByte()
    val buffer=java.nio.ByteBuffer.wrap(digest)
    return UUID(buffer.long,buffer.long).toString()
}
fun canonical(value: Any?): String = when(value) {
    is JSONObject -> value.keys().asSequence().sorted().joinToString(",","{","}") { JSONObject.quote(it)+":"+canonical(value.opt(it)) }
    is JSONArray -> (0 until value.length()).joinToString(",","[","]") { canonical(value.opt(it)) }
    is String -> JSONObject.quote(value)
    null,JSONObject.NULL -> "null"
    else -> value.toString()
}
fun now(): String = Instant.now().toString()

data class Session(val raw: JSONObject, val apiOrigin: String) {
    val accountId get()=raw.s("accountId")
    val schoolId get()=raw.s("schoolId")
    val token get()=raw.s("accessToken",raw.s("token"))
    val name get()=raw.s("displayName",raw.s("userName","کاربر مکتب"))
    val role get()=raw.s("role")
    val province get()=raw.s("accessScope")=="province"
    val manager get()=role.equals("SchoolManager",true)
    val schools get()=if(province) raw.a("schoolIds").strings().map(::uuid) else listOf(uuid(schoolId))
    val valid get()=token.isNotEmpty() && runCatching { parseServerInstant(raw.s("expiresAt")).isAfter(Instant.now()) }.getOrDefault(false)
    val scope get()="$apiOrigin\n$accountId\n$schoolId"
    fun scope(school: String)=copy(raw=raw.copy().put("schoolId",school))
    companion object {
        fun login(response: JSONObject, apiOrigin: String): Session {
            val data=response.data().copy()
            data.put("accountId",uuid(data.s("accountId")))
            val role=data.s("role").lowercase()
            require(role in setOf("schoolmanager","scanneroperator")) { "حساب باید مدیر مکتب یا اپراتور اسکن باشد." }
            require(data.s("tokenType")=="Bearer" && data.s("apiVersion")=="v1" && data.s("accessToken").isNotEmpty() && !Regex("\\s").containsMatchIn(data.s("accessToken"))) { "پاسخ ورود معتبر نیست." }
            require(data.optInt("expiresIn") in 1..86400 && parseServerInstant(data.s("expiresAt")).isAfter(Instant.now())) { "اعتبار زمانی ورود معتبر نیست." }
            if(data.s("schoolId")==ZERO_UUID) {
                require(data.s("accessScope")=="province" && data.opt("schoolSelectionRequired")==false && role=="scanneroperator") { "محدودهٔ ولایتی معتبر نیست." }
                uuid(data.s("provinceId"))
                data.put("schoolIds",JSONArray(data.a("schoolIds").strings().map(::uuid)))
            } else { uuid(data.s("schoolId")); require(data.s("accessScope") in setOf("","school")) }
            return Session(data,origin(apiOrigin))
        }
    }
}

data class Exam(val raw: JSONObject) {
    val id get()=raw.s("id",raw.s("examId"))
    val schoolId get()=raw.s("schoolId")
    val name get()=raw.s("name",raw.s("title",raw.s("examCode","امتحان")))
    val grade get()=raw.optInt("grade",raw.optInt("gradeLevel"))
    val round get()=raw.optInt("round")
    val count get()=raw.optInt("questionCount")
    val key get()="$schoolId/$id"
    val template get()=SheetFamilies.forCount(count)
}
data class DeviceIdentity(val alias: String,val deviceUuid: String,val keyId: String,val keys: JSONObject) {
    val signingHash get()=keys.s("signingPublicKeySha256")
    val encryptionHash get()=keys.s("encryptionPublicKeySha256")
    fun json()=json("alias" to alias,"deviceUuid" to deviceUuid,"keyId" to keyId,"keys" to keys)
}
data class RosterRow(val code: String,val alias: String,val sheetQr: String,val cardQr: String,val name: String,val examId: String) {
    val sheetId get()=uuidV5(examId,"qr-sheet:$sheetQr")
}
data class OfflineExam(val exam: Exam,val envelope: JSONObject,val manifest: JSONObject,val roster: List<RosterRow>) {
    val expires get()=parseServerInstant(manifest.s("expiresAt"))
    val valid get()=expires.isAfter(Instant.now())
}
data class ApiFailure(val code: String,override val message: String,val status: Int=0,val retryAt: Long=0): Exception(message)
