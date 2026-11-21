package com.haseebullahjan.roshd.messenger

import com.haseebullahjan.roshd.BuildConfig
import com.haseebullahjan.roshd.core.*
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

fun ExamsController.downloadStudents(done:(Result<String>)->Unit)=run("دریافت فایل شاگردان…",done) {
    val owner=session ?: error("ابتدا وارد شوید.")
    require(owner.manager) { "این بخش برای مدیر مکتب است." }
    var cursor="";val seen=mutableSetOf<String>();var total=0;val files=mutableListOf<File>()
    do {
        var target=File(context.filesDir,"native-reports/students-${UUID.randomUUID()}.xlsx")
        val query=mutableMapOf("limit" to "2000");if(cursor.isNotEmpty())query["cursor"]=cursor
        val result=network.download("/api/v1/school-manager/registration-imports/students-file",owner,target,query,maxBytes=2L*1024*1024,acceptedMimeTypes=setOf("text/csv","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        val count=result.headers["x-export-row-count"]?.toIntOrNull() ?: error("تعداد شاگردان فایل تأیید نشد.")
        require(count in 0..2000)
        val type=result.headers["content-type"].orEmpty().substringBefore(';').trim().lowercase()
        if(type=="text/csv") { val csv=File(target.parentFile,target.nameWithoutExtension+".csv");check(target.renameTo(csv));target=csv }
        total+=count;files.add(target)
        cursor=result.headers["x-next-cursor"] ?: ""
        require(cursor.isEmpty() || seen.add(cursor)) { "سرور صفحهٔ تکراری برگرداند." }
    } while(cursor.isNotEmpty())
    val output=if(files.size==1)files.single() else File(context.filesDir,"native-reports/students-${UUID.randomUUID()}.zip").also { archive ->
        ZipOutputStream(archive.outputStream().buffered()).use { zip -> files.forEachIndexed { index,file ->
            zip.putNextEntry(ZipEntry("students-${index+1}.${file.extension}"));file.inputStream().use { it.copyTo(zip) };zip.closeEntry()
        } }
    }
    val mime=when(output.extension) { "zip" -> "application/zip";"csv" -> "text/csv";else -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" }
    native.call("shareFile",mapOf("path" to output.path,"mimeType" to mime)){}
    "$total شاگرد در ${files.size} فایل دریافت شد."
}

fun ExamsController.checkUpdate(done:(Result<String>)->Unit)=run("بررسی نسخهٔ جدید…",done) {
    val wire=network.request("GET","/api/v1/mobile/app-version",query=mapOf("platform" to "android","versionCode" to BuildConfig.VERSION_CODE.toString()),maxBytes=65536)
    val offer=AppRelease.parse(wire,BuildConfig.VERSION_CODE,context.packageName)
    storage.delete("global","update","available")
    if(offer==null)return@run if(wire.isNull("latest"))"هنوز نسخهٔ تازه‌ای منتشر نشده است." else "برنامه به‌روز است."
    val release=offer.metadata
    storage.put("global","update","available",release.put("updateRequired",offer.required))
    notifications.post(AppEvent.DATA)
    if(offer.required)"به‌روزرسانی به نسخهٔ ${release.s("versionName")} لازم است؛ از تنظیمات دانلود کنید." else "نسخهٔ ${release.s("versionName")} آماده است؛ از تنظیمات دانلود کنید."
}

fun ExamsController.installUpdate(done:(Result<String>)->Unit)=run("دریافت و بررسی نسخهٔ جدید…",done) {
    val release=storage.get("global","update","available") ?: error("ابتدا نسخهٔ تازه را بررسی کنید.")
    require(release.optInt("versionCode")>BuildConfig.VERSION_CODE && release.s("packageName")==context.packageName) { "نسخهٔ قدیمی یا مربوط به برنامهٔ دیگر نصب نمی‌شود." }
    val id=uuid(release.s("id"));val path=AppRelease.downloadPath(release)
    val info=wrap(native.blocking("appUpdate.info")) as org.json.JSONObject
    val file=File(info.s("updateDirectory"),"$id.apk")
    network.download(path,null,file,maxBytes=268435456,expectedHash=release.s("sha256"),acceptedMimeTypes=setOf("application/vnd.android.package-archive"))
    require(file.length()==release.optLong("sizeBytes"))
    if(native.blocking("appUpdate.canInstall")!=true) {
        native.call("appUpdate.settings"){}
        return@run "اجازهٔ نصب را فعال کنید و دوباره نصب را بزنید. فایل بررسی‌شده محفوظ است."
    }
    native.blocking("appUpdate.install",release.map()+mapOf("path" to file.path))
    "نصب نسخهٔ جدید در Android باز شد."
}

fun ExamsController.clearTemporaryFiles(done:(Result<String>)->Unit)=run("پاکسازی فایل‌های موقت…",done) {
    var bytes=0L
    for(name in listOf("native-share","native-crops","native-imports")) {
        val folder=File(context.cacheDir,name)
        folder.walkTopDown().filter { it.isFile }.forEach { bytes+=it.length() }
        if(folder.exists())check(folder.deleteRecursively())
    }
    "${bytes/1024} کیلوبایت فایل موقت پاک شد؛ اصل برگه‌ها و صف ارسال محفوظ است."
}

fun ExamsController.downloadStudentErrors(input:File,done:(Result<String>)->Unit)=run("بررسی فایل شاگردان…",done) {
    val owner=session ?: error("ابتدا وارد شوید.")
    require(owner.manager && input.length() in 1..2L*1024*1024)
    val boundary="roshd-${UUID.randomUUID()}"
    val name=input.name.replace(Regex("[^A-Za-z0-9._-]"),"_")
    val header="--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$name\"\r\nContent-Type: application/octet-stream\r\n\r\n".toByteArray()
    val body=header+input.readBytes()+"\r\n--$boundary--\r\n".toByteArray()
    val target=File(context.filesDir,"native-reports/student-errors-${UUID.randomUUID()}.xlsx")
    network.download("/api/v1/school-manager/registration-imports/errors",owner,target,method="POST",rawBody=body,contentType="multipart/form-data; boundary=$boundary",acceptedMimeTypes=setOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
    native.call("shareFile",mapOf("path" to target.path,"mimeType" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")){}
    "فایل خطاهای سرور دریافت شد."
}
