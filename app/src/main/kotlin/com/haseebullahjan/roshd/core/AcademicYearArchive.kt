package com.haseebullahjan.roshd.core

import com.haseebullahjan.roshd.messenger.ConnectionsManager
import org.json.JSONArray
import org.json.JSONObject

/** Read-only snapshots have no access to operational packages, settings or the outbox. */
class AcademicYearArchive(
    private val network:ConnectionsManager,
    private val read:(String,String,String)->JSONObject?,
    private val write:(String,String,String,JSONObject)->Unit
) {
    private val kind="academic-year-archive"
    private fun rows(array:JSONArray)=(0 until array.length()).map { array.getJSONObject(it) }
    fun cachedYears(owner:Session):List<JSONObject> = read(owner.scope,kind,"years")?.let(::years).orEmpty()
    fun refreshYears(owner:Session):List<JSONObject> {
        val response=network.request("GET","/api/v1/scanner/academic-years",owner).data()
        val items=years(response)
        write(owner.scope,kind,"years",response)
        return items
    }
    private fun years(response:JSONObject):List<JSONObject> {
        val rows=rows(response.getJSONArray("items"))
        rows.forEach(::validateYear)
        require(rows.map { it.s("id") }.distinct().size==rows.size) { "فهرست سال‌ها تکراری است." }
        return rows // Inactive years remain selectable; preserve the server's order.
    }
    private fun validateYear(year:JSONObject) {
        for(key in listOf("id","academicYearId","provinceId","editionId"))uuid(year.getString(key))
        require(year.getInt("roundCount")>0 && year.getString("label").isNotBlank()
            && year.getString("academicYearKey").isNotBlank() && year.getString("version").isNotBlank()) { "مشخصات سال تعلیمی معتبر نیست." }
        year.getBoolean("isCurrent");year.getBoolean("isActive")
    }
    private fun key(year:JSONObject,round:Int):String {
        validateYear(year)
        require(round in 1..year.getInt("roundCount")) { "دور را از گزینه‌های همین سال انتخاب کنید." }
        return "${year.s("id")}:$round"
    }
    fun cached(owner:Session,year:JSONObject,round:Int):JSONObject? = read(owner.scope,kind,key(year,round))?.also {
        validate(it.getJSONObject("snapshot"),owner,year,round)
    }
    fun download(owner:Session,year:JSONObject,round:Int):JSONObject {
        val id=key(year,round)
        val response=network.request("GET","/api/v1/scanner/academic-years/${uuid(year.s("id"))}/rounds/$round/information",
            owner,maxBytes=12*1024*1024,readTimeoutMs=600_000).data()
        validate(response,owner,year,round)
        val cached=json("snapshot" to response,"downloadedAt" to now())
        write(owner.scope,kind,id,cached) // Replace only this complete, validated selection.
        return cached
    }
    private fun validate(data:JSONObject,owner:Session,year:JSONObject,round:Int) {
        require(data.getString("schemaVersion")=="roshd.information-archive.v1" && data.getString("accountId")==owner.accountId
            && data.has("schoolId") && (if(owner.province)data.isNull("schoolId") else data.s("schoolId")==owner.schoolId)
            && data.getInt("round")==round) { "معلومات دریافتی مربوط به حساب، مکتب یا دور انتخاب‌شده نیست؛ معلومات قبلی محفوظ است." }
        val incoming=data.getJSONObject("year");validateYear(incoming)
        require(listOf("id","academicYearId","editionId","provinceId").all { incoming.s(it)==year.s(it) }
            && round in 1..incoming.getInt("roundCount")) { "سال معلومات دریافتی با انتخاب شما مطابقت ندارد." }
        rows(data.getJSONArray("exams")).forEach { exam ->
            uuid(exam.getString("examId"));uuid(exam.getString("schoolId"))
            require(owner.province || exam.s("schoolId")==owner.schoolId) { "مکتب امتحان خارج از محدودهٔ حساب است." }
            rows(exam.getJSONArray("students"))
        }
        rows(data.getJSONArray("attendance")).forEach { rows(it.getJSONArray("items")) }
        require(data.has("publishedResults"))
        if(!data.isNull("publishedResults")) {
            val results=data.getJSONObject("publishedResults")
            for(name in listOf("editions","students","history"))rows(results.getJSONArray(name))
        }
    }
}
