package com.haseebullahjan.roshd.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A frozen, rectified and graded upload artifact; camera evidence is kept separately. */
object UploadSheet {
    const val VARIANT="rectified-graded-v1"
    fun bind(record:JSONObject):JSONObject {
        require(!record.optBoolean("sealed")) { "عکس درخواست امضاشده قابل جایگزینی نیست." }
        val annotation=GradedSheet.current(record) ?: error("نسخهٔ تصحیح‌شدهٔ این پاسخ‌ها آماده نیست.")
        require(annotation.optInt("colorPolicyVersion")==GradedSheet.COLOR_POLICY_VERSION)
        val width=annotation.optInt("width");val height=annotation.optInt("height")
        require(width>=100 && height>=100 && width.toLong()*height<=24_000_000 &&
            width==record.o("engine").o("annotationGeometry").optInt("width") && height==record.o("engine").o("annotationGeometry").optInt("height"))
        val file=File(annotation.s("path"));val hash=annotation.s("sha256")
        require(file.isFile && file.length() in 1..16*1024*1024 && hash==EvidenceArchive.hash(file)) { "فایل تصحیح‌شده با نسخهٔ محفوظ مطابقت ندارد." }
        val current=record.optJSONObject("uploadEvidence")
        if(current!=null && current.s("sha256")==hash && current.s("answersSha256")==GradedSheet.answersHash(record) && current.s("path")==file.path)return current
        val frozen=record.optJSONObject("sourceSnapshot")
        if(record.s("scanId").isNotBlank() && (frozen==null || frozen.s("sourceImageSha256")!=hash)) {
            val history=record.optJSONArray("previousImageCaptures") ?: JSONArray().also { record.put("previousImageCaptures",it) }
            history.put(json("scanId" to record.s("scanId"),"sourceSnapshot" to frozen?.copy(),"uploadEvidence" to current?.copy()))
            record.remove("scanId");record.remove("sourceSnapshot")
        }
        return json("variant" to VARIANT,"path" to file.path,"sha256" to hash,"sizeBytes" to file.length(),
            "sourceImageSha256" to record.s("sourceImageSha256"),"answersSha256" to GradedSheet.answersHash(record),
            "width" to annotation.optInt("width"),"height" to annotation.optInt("height"),"colorPolicyVersion" to GradedSheet.COLOR_POLICY_VERSION)
            .also { record.put("uploadEvidence",it) }
    }
    fun sourcePath(record:JSONObject,source:JSONObject):String {
        val upload=record.optJSONObject("uploadEvidence")
        if(upload!=null && upload.s("sha256")==source.s("sourceImageSha256"))return upload.s("path")
        require(record.optBoolean("sealed") && record.s("sourceImageSha256")==source.s("sourceImageSha256")) { "نسخهٔ صاف‌شده و تصحیح‌شدهٔ برگه آماده نیست." }
        return record.s("sourcePath")
    }
    fun transferPath(record:JSONObject,source:JSONObject):String {
        val upload=record.o("uploadEvidence")
        require(upload.s("variant")==VARIANT && upload.s("sha256")==source.s("sourceImageSha256")) {
            "این ثبت قدیمی به عکس قبلی متصل است؛ برای ارسال نسخهٔ صاف‌شده و رنگی، برگه را دوباره اسکن کنید. ثبت قبلی محفوظ است."
        }
        return sourcePath(record,source)
    }
}
