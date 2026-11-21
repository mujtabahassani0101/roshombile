package com.haseebullahjan.roshd.messenger

import com.haseebullahjan.roshd.core.*
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.GZIPInputStream
import javax.net.ssl.HttpsURLConnection

class ConnectionsManager internal constructor(private val open:(java.net.URL)->HttpsURLConnection = { it.openConnection() as HttpsURLConnection }) {
    private val active=java.util.concurrent.ConcurrentHashMap.newKeySet<HttpsURLConnection>()
    @Volatile private var revision=0
    @Volatile private var retryUntil=0L
    val diagnostics=java.util.concurrent.CopyOnWriteArrayList<JSONObject>()
    val cancellationVersion get()=revision
    fun cancel() { revision++;active.forEach { runCatching { it.disconnect() } } }
    fun awaitRetry(until:Long,version:Int) {
        do {
            if(revision!=version || Thread.currentThread().isInterrupted)throw java.io.InterruptedIOException("ارسال متوقف شد؛ بخش‌های تأییدشده محفوظ‌اند.")
            val remaining=until-System.currentTimeMillis()
            if(remaining<=0)return
            Thread.sleep(minOf(remaining,200))
        } while(true)
    }
    fun request(method:String,path:String,session:Session?=null,body:JSONObject?=null,school:String?=null,
                query:Map<String,String> = emptyMap(),apiOrigin:String=Roshd.API_ORIGIN,maxBytes:Int=12*1024*1024,
                rawBody:ByteArray?=null,headers:Map<String,String> = emptyMap(),readTimeoutMs:Int=180_000):JSONObject {
        require(path.startsWith("/api/v1/") && !path.contains(".."))
        require(readTimeoutMs in 1000..600_000)
        if(session!=null)require(session.valid) { "برای این عملیات با همان حساب دوباره وارد شوید." }
        if(System.currentTimeMillis()<retryUntil)throw ApiFailure("retry_after","سرور زمان انتظار تعیین کرده؛ کمی بعد دوباره تلاش کنید.",429,retryUntil)
        val requestRevision=revision
        val base=origin(session?.apiOrigin ?: apiOrigin)
        val suffix=if(query.isEmpty()) "" else query.entries.joinToString("&","?") { URLEncoder.encode(it.key,"UTF-8")+"="+URLEncoder.encode(it.value,"UTF-8") }
        val connection=open(java.net.URI(base+path+suffix).toURL())
        val started=System.nanoTime()
        var responseStatus=0;var responseBytes=0;var failureCode="";var failureTrace=""
        active.add(connection)
        try {
            connection.requestMethod=method;connection.instanceFollowRedirects=false;connection.connectTimeout=30_000;connection.readTimeout=readTimeoutMs
            connection.setRequestProperty("Accept","application/json");connection.setRequestProperty("Accept-Encoding","gzip")
            connection.setRequestProperty("Cache-Control","no-store");connection.useCaches=false
            if(session!=null) connection.setRequestProperty("Authorization","Bearer ${session.token}")
            if(school!=null)connection.setRequestProperty("X-ROSHD-Scanner-School",uuid(school))
            headers.forEach { (key,value) -> require(Regex("^[A-Za-z0-9-]+$").matches(key) && !value.contains('\r') && !value.contains('\n') && key.lowercase() !in setOf("authorization","host","x-roshd-scanner-school"));connection.setRequestProperty(key,value) }
            if(body!=null) { val bytes=body.toString().toByteArray(Charsets.UTF_8);require(bytes.size<=4*1024*1024);connection.doOutput=true;connection.setRequestProperty("Content-Type","application/json; charset=utf-8");connection.setFixedLengthStreamingMode(bytes.size);connection.outputStream.use { it.write(bytes) } }
            else if(rawBody!=null) { require(rawBody.size<=4*1024*1024);connection.doOutput=true;connection.setFixedLengthStreamingMode(rawBody.size);connection.outputStream.use { it.write(rawBody) } }
            val status=connection.responseCode
            responseStatus=status
            if(status in 300..399)throw ApiFailure("redirect_blocked","نشانی مستقیم سرور لازم است؛ درخواست هدایت‌شده ارسال نشد.",status)
            val raw=if(status in 200..299)connection.inputStream else connection.errorStream
            val source=if(connection.contentEncoding.equals("gzip",true) && raw!=null)GZIPInputStream(raw) else raw
            val bytes=ByteArrayOutputStream()
            source?.use { input -> val buffer=ByteArray(16*1024);while(true) { val n=input.read(buffer);if(n<0)break;require(bytes.size()+n<=maxBytes) { "حجم پاسخ سرور بیشتر از حد مجاز است." };bytes.write(buffer,0,n);check(revision==requestRevision) { "نشست تغییر کرده؛ پاسخ ذخیره نشد." } } }
            val response=if(bytes.size()==0)JSONObject() else runCatching { when(val value=org.json.JSONTokener(bytes.toString("UTF-8")).nextValue()) { is JSONObject -> value;is org.json.JSONArray -> json("items" to value);else -> error("Invalid JSON envelope") } }.getOrElse { if(status !in 200..299)JSONObject() else throw ApiFailure("invalid_response","پاسخ سرور ساختار معتبر ندارد.",status) }
            responseBytes=bytes.size()
            if(status !in 200..299) {
                val error=response.o("error").takeIf { it.length()>0 } ?: response
                val at=retryTime(connection)
                if(status==429 || status==503)retryUntil=at
                val code=error.s("code","http_$status")
                val trace=error.s("traceId",response.s("traceId",connection.getHeaderField("X-Trace-Id").orEmpty())).take(80)
                if(trace.isNotEmpty())failureTrace=trace
                val message=when(code) {
                    "authentication_required"->"اعتبار ورود پایان یافته؛ دوباره وارد شوید."
                    "annual_endpoint_required"->"سرور هنوز برای ساختار سالانه به‌روز نشده است؛ مشکل از رمز نیست. به پشتیبانی خبر دهید."
                    "information_period_changed"->"سال یا دور در سرور تغییر کرده است."
                    "image_processing_busy"->"سرور مصروف پردازش عکس است؛ ارسال از بخش‌های تأییدشده ادامه می‌یابد."
                    "school_scope_required"->"مکتب این عملیات مشخص نیست؛ معلومات امتحان را تازه کنید."
                    "permission_denied"->"حساب شما اجازهٔ انجام این عملیات را ندارد."
                    "mobile_scan_intake_retired"->"پذیرش پاسخ‌نامه در سرور غیرفعال است؛ عکس‌ها و پاسخ‌های شما محفوظ‌اند."
                    else->if(status==401)"اعتبار ورود پایان یافته؛ دوباره وارد شوید." else error.s("message",error.s("title","درخواست سرور کامل نشد ($status)."))
                }
                // 400/403/404/409 are never a wrong password: show code and trace id for support.
                val support=if(status in 400..499 && status!=401 && status!=429)" [کد: $code${if(trace.isNotEmpty())" • پیگیری: $trace" else ""}]" else ""
                throw ApiFailure(code,message+support,status,at)
            }
            check(revision==requestRevision) { "نشست تغییر کرده؛ پاسخ ذخیره نشد." }
            return response
        } catch(error:Exception) {
            failureCode=if(error is ApiFailure)error.code.takeIf { it.matches(Regex("[a-zA-Z0-9_]{1,80}")) } ?: "api_failure" else error.javaClass.simpleName
            throw error
        } finally {
            // Timings and status only; never store headers, queries, payloads or server messages.
            val diagnosticPath=path.replace(Regex("[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}"),"{id}").replace(Regex("/[0-9]+(?=/|$)"),"/{number}")
            diagnostics.add(json("method" to method,"path" to diagnosticPath,"status" to responseStatus,"bytes" to responseBytes,
                "elapsedMs" to (System.nanoTime()-started)/1_000_000,"errorCode" to failureCode,"traceId" to failureTrace,"appVersion" to com.haseebullahjan.roshd.BuildConfig.VERSION_NAME,"at" to now()))
            synchronized(diagnostics) { while(diagnostics.size>40)diagnostics.removeAt(0) }
            active.remove(connection);connection.disconnect()
        }
    }
    private fun retryTime(connection:HttpsURLConnection):Long {
        val value=connection.getHeaderField("Retry-After")
        return value?.toLongOrNull()?.let { System.currentTimeMillis()+it.coerceIn(0,86400)*1000 }
            ?: runCatching { ZonedDateTime.parse(value,DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli().coerceAtMost(System.currentTimeMillis()+86400_000) }.getOrDefault(0)
    }
    data class Download(val file:java.io.File,val headers:Map<String,String>)
    fun download(path:String,session:Session?,target:java.io.File,query:Map<String,String> = emptyMap(),maxBytes:Long=64L*1024*1024,expectedHash:String?=null,
                 method:String="GET",rawBody:ByteArray?=null,contentType:String?=null,acceptedMimeTypes:Set<String>?=null):Download {
        require(path.startsWith("/api/v1/") && !path.contains(".."))
        if(session!=null)require(session.valid) { "برای دریافت فایل دوباره وارد شوید." }
        if(System.currentTimeMillis()<retryUntil)throw ApiFailure("retry_after","سرور زمان انتظار تعیین کرده؛ کمی بعد دوباره تلاش کنید.",429,retryUntil)
        val requestRevision=revision
        val suffix=if(query.isEmpty())"" else query.entries.joinToString("&","?") { URLEncoder.encode(it.key,"UTF-8")+"="+URLEncoder.encode(it.value,"UTF-8") }
        val connection=open(java.net.URI(origin(session?.apiOrigin ?: Roshd.API_ORIGIN)+path+suffix).toURL())
        active.add(connection)
        val partial=java.io.File(target.path+".partial")
        try {
            connection.instanceFollowRedirects=false;connection.connectTimeout=30_000;connection.readTimeout=60_000;connection.useCaches=false
            connection.requestMethod=method
            if(session!=null) { connection.setRequestProperty("Authorization","Bearer ${session.token}");if(!session.province)connection.setRequestProperty("X-ROSHD-Scanner-School",session.schoolId) }
            connection.setRequestProperty("Accept","application/octet-stream,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet,text/csv,application/vnd.android.package-archive")
            if(rawBody!=null) { require(rawBody.size<=3*1024*1024);connection.doOutput=true;connection.setRequestProperty("Content-Type",contentType ?: "application/octet-stream");connection.setFixedLengthStreamingMode(rawBody.size);connection.outputStream.use { it.write(rawBody) } }
            val status=connection.responseCode
            if(status !in 200..299) {
                val at=retryTime(connection);if(status==429 || status==503)retryUntil=at
                throw ApiFailure(if(status in 300..399)"redirect_blocked" else "download_failed","دریافت فایل کامل نشد ($status).",status,at)
            }
            val mime=connection.contentType?.substringBefore(';')?.trim()?.lowercase()
            require(mime!=null && mime !in setOf("application/json","text/html") && (acceptedMimeTypes==null || mime in acceptedMimeTypes)) { "نوع فایل دریافتی با فایل درخواستی مطابقت ندارد." }
            target.parentFile!!.mkdirs();var size=0L;val digest=java.security.MessageDigest.getInstance("SHA-256")
            connection.inputStream.use { input -> partial.outputStream().use { output -> val buffer=ByteArray(65536);while(true) { val n=input.read(buffer);if(n<0)break;size+=n;require(size<=maxBytes);check(requestRevision==revision);digest.update(buffer,0,n);output.write(buffer,0,n) };output.fd.sync() } }
            require(size>0 && (connection.contentLengthLong<0 || size==connection.contentLengthLong)) { "فایل کامل دریافت نشده است." }
            val hash=digest.digest().joinToString("") { "%02x".format(it) };if(expectedHash!=null)require(hash==expectedHash) { "اثر انگشت فایل دانلودشده مطابقت ندارد." }
            check(requestRevision==revision) { "نشست تغییر کرده؛ فایل ذخیره نشد." }
            require(partial.renameTo(target))
            return Download(target,connection.headerFields.entries.filter { it.key!=null }.associate { it.key.lowercase() to it.value.joinToString(",") })
        } finally { partial.delete();active.remove(connection);connection.disconnect() }
    }
}
