package com.haseebullahjan.roshd.core

import com.haseebullahjan.roshd.messenger.ConnectionsManager
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.zip.Deflater

class OriginalImageUploader internal constructor(private val network:ConnectionsManager,private val sign:(DeviceIdentity,List<String>)->String) {
    constructor(network:ConnectionsManager,crypto:ScannerCrypto):this(network,crypto::sign)
    fun upload(owner:Session,identity:DeviceIdentity,scanId:String,source:JSONObject,file:File,state:JSONObject,zip:Boolean,
               onPart:(Int,Int)->Unit={_,_->},save:(JSONObject)->Unit):JSONObject {
        val snapshot=ImageUploadSnapshot.verify(file,source.s("sourceImageSha256"))
        val mime=snapshot.mime
        fun persist(phase:String) { state.put("phase",phase);save(state) }
        fun prepare():JSONObject {
            if(!state.has("request")) {
                val requestId=UUID.randomUUID().toString()
                val signature=sign(identity,listOf("roshd.image-upload.v1",owner.schoolId,owner.accountId,identity.deviceUuid,requestId,scanId,mime,snapshot.size.toString(),source.s("sourceImageSha256")))
                state.put("request",json("requestId" to requestId,"deviceUuid" to identity.deviceUuid,"contentType" to mime,"sizeBytes" to snapshot.size,"sha256" to source.s("sourceImageSha256"),"signatureBase64" to signature));persist("prepare")
            }
            return network.request("POST","/api/v1/scanner/scans/$scanId/original-image/uploads",owner,state.o("request"),owner.schoolId,readTimeoutMs=30_000).data()
        }
        var upload=try {
            if(state.s("uploadId").isEmpty())prepare() else network.request("GET","/api/v1/scanner/original-image-uploads/${uuid(state.s("uploadId"))}",owner,school=owner.schoolId,query=mapOf("deviceUuid" to identity.deviceUuid),readTimeoutMs=30_000).data()
        } catch(error:ApiFailure) {
            if(error.code!="original_image_complete")throw error
            persist("reconcile")
            val receipt=network.request("GET","/api/v1/scanner/scans/$scanId/original-image",owner,school=owner.schoolId,query=mapOf("deviceUuid" to identity.deviceUuid)).data()
            return checkReceipt(receipt,scanId,source,mime,snapshot.size).also { state.put("receipt",it);persist("verified") }
        }
        fun checkUpload(value:JSONObject) {
            uuid(value.s("uploadId"))
            require(value.s("scanId")==scanId && value.s("deviceUuid")==identity.deviceUuid && value.s("sha256")==source.s("sourceImageSha256") && value.optInt("sizeBytes")==snapshot.size && value.s("contentType")==mime && value.optInt("partSizeBytes")==1024*1024 && value.optInt("partCount")==((snapshot.size+1024*1024-1)/(1024*1024)) && value.optJSONArray("parts")!=null) { "قصد ارسال عکس با اصل محفوظ مطابقت ندارد." }
        }
        checkUpload(upload)
        if(upload.s("status")=="expired") { state.remove("request");state.remove("uploadId");persist("prepare");upload=prepare();checkUpload(upload) }
        require(upload.s("status") in setOf("uploading","verified")) { "آپلود عکس توسط سرور متوقف شده است؛ عکس محفوظ است." }
        val uploadId=upload.s("uploadId");state.put("uploadId",uploadId);persist("uploading")
        if(upload.s("status")=="verified")return checkReceipt(upload.o("originalImage"),scanId,source,mime,snapshot.size).also { state.put("receipt",it);persist("verified") }
        val acknowledged=upload.a("parts").objects().associateBy { it.optInt("partNumber") }
        require(acknowledged.size==upload.a("parts").length() && acknowledged.keys.all { it in 1..upload.optInt("partCount") })
        for(number in 1..snapshot.partCount) {
            val hash=snapshot.partHash(number);val partSize=snapshot.partSize(number)
            val existing=acknowledged[number]
            val receipt=existing ?: run {
                val part=snapshot.readPart(file,number)
                val signature=sign(identity,listOf("roshd.image-part.v1",owner.schoolId,owner.accountId,identity.deviceUuid,uploadId,number.toString(),hash))
                val body=partBody(part,number,zip)
                persist("part_$number")
                network.request("PUT","/api/v1/scanner/original-image-uploads/$uploadId/parts/$number${if(body.zipped)"/zip" else ""}",owner,school=owner.schoolId,rawBody=body.bytes,
                    headers=mapOf("Content-Type" to if(body.zipped)"application/zip" else "application/octet-stream","X-Device-UUID" to identity.deviceUuid,"X-Part-SHA256" to hash,"X-Device-Signature" to signature),readTimeoutMs=55_000).data()
            }
            require(receipt.optInt("partNumber")==number && receipt.s("sha256")==hash && receipt.optInt("sizeBytes")==partSize) { "رسید بخش عکس با بایت‌های اصل مطابق نیست." }
            state.put("acknowledgedParts",number);persist("uploading")
            onPart(number,upload.optInt("partCount"))
        }
        persist("complete")
        val receipt=try {
            network.request("POST","/api/v1/scanner/original-image-uploads/$uploadId/complete",owner,json("deviceUuid" to identity.deviceUuid),owner.schoolId,readTimeoutMs=100_000).data()
        } catch(e:Exception) {
            if(e is ApiFailure && e.status in 400..499 && e.code!="original_image_complete")throw e
            persist("reconcile")
            network.request("GET","/api/v1/scanner/scans/$scanId/original-image",owner,school=owner.schoolId,query=mapOf("deviceUuid" to identity.deviceUuid)).data()
        }
        return checkReceipt(receipt,scanId,source,mime,snapshot.size).also { state.put("receipt",it);persist("verified") }
    }
    internal data class PartBody(val bytes:ByteArray,val zipped:Boolean)
    companion object {
        /** Transport compression never changes the evidence bytes or the part signature. */
        internal fun partBody(part:ByteArray,number:Int,zip:Boolean):PartBody {
            if(!zip)return PartBody(part,false)
            val compressed=ByteArrayOutputStream().also { output -> ZipOutputStream(output).use { archive ->
                archive.setLevel(Deflater.BEST_SPEED)
                archive.putNextEntry(ZipEntry("image.part${number.toString().padStart(3,'0')}"))
                archive.write(part);archive.closeEntry()
            } }.toByteArray()
            return if(compressed.size.toLong()*100<part.size.toLong()*98)PartBody(compressed,true) else PartBody(part,false)
        }
    }
    private fun checkReceipt(receipt:JSONObject,id:String,source:JSONObject,mime:String,length:Int):JSONObject {
        val identityMatches=if(source.s("identityMode")=="qr-lite-v1")receipt.s("clientSheetId")==source.s("clientSheetId") && receipt.s("qrText")==source.s("qrText") && receipt.s("studentPublicId")==source.s("studentPublicId") else receipt.s("sheetInstanceId")==source.s("sheetInstanceId")
        require(receipt.s("status")=="verified" && receipt.s("scanId")==id && receipt.s("examId")==source.s("examId") && receipt.s("sha256")==source.s("sourceImageSha256") && receipt.optInt("sizeBytes")==length && receipt.s("contentType")==mime
            && identityMatches && receipt.optInt("width") in 1..10000 && receipt.optInt("height") in 1..10000 && receipt.optLong("width")*receipt.optLong("height")<=24_000_000)
            { "رسید نهایی اصل عکس با این برگه مطابقت ندارد." }
        parseServerInstant(receipt.s("verifiedAt"))
        return receipt
    }
}
