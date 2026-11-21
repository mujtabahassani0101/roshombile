package com.haseebullahjan.roshd.ui

import android.app.AlertDialog
import android.widget.LinearLayout
import com.haseebullahjan.roshd.MainActivity
import com.haseebullahjan.roshd.core.*
import com.haseebullahjan.roshd.ui.actionbar.Route
import com.haseebullahjan.roshd.ui.components.*
import org.json.JSONObject

/** Reports retain their own server cursors; a local scan is never an official grade. */
class ReportFragment(activity:MainActivity,route:Route):PageFragment(activity,route,"گزارش رسمی") {
    private val studentId=route.args.s("studentId")
    private var edition=route.args.s("editionId",app.currentYear?.s("editionId") ?: "")
    private var selectedRound=route.args.optInt("round",app.round).coerceIn(1,ExamPeriod.MAX_ROUND)
    private var grade:Int?=null
    private var search=""
    private var beforeVersion:Int?=null
    private var rows=emptyList<JSONObject>()
    private var editions=emptyList<JSONObject>()
    private var cursor=""
    private var moreHistory=false
    private var issue=""
    private var started=false
    private var loading=false
    private val seenCursors=mutableSetOf<String>()

    override fun render() {
        if(!started) { started=true;activity.window.decorView.post { if(studentId.isNotEmpty())load() else loadEditions() } }
        note(if(studentId.isNotEmpty())"تاریخچهٔ رسمی ${route.args.s("name")}" else "نتیجه‌های رسمی منتشرشده")
        if(studentId.isEmpty()) {
            val label=editions.firstOrNull { it.s("editionId",it.s("id"))==edition }?.let { it.s("label",it.s("name",it.s("academicYearKey"))) } ?: "انتخاب دوره"
            action("دوره: $label") {
                if(editions.isEmpty())loadEditions() else AlertDialog.Builder(activity).setTitle("دورهٔ نشر نتیجه‌ها").setItems(editions.map { it.s("label",it.s("name",it.s("academicYearKey",it.s("editionId",it.s("id"))))) }.toTypedArray()) { _,which ->
                    edition=editions[which].s("editionId",editions[which].s("id"));reset()
                }.showThemed(theme)
            }
            action("دور $selectedRound • ${grade?.let { "صنف $it" } ?: "همهٔ صنف‌ها"}${if(search.isEmpty())"" else " • $search"}") { filters() }
        }
        if(issue.isNotEmpty())note(issue,true)
        action(if(loading)"در حال دریافت…" else "تازه‌سازی گزارش") { if(!loading)reset() }
        if(rows.isEmpty() && !loading)empty("گزارشی در دسترس نیست","دوره، دور یا فیلتر گزارش را بررسی کنید.")
        rows.forEach { value ->
            val name=value.s("studentName",listOf(value.s("firstName"),value.s("fatherName"),value.s("lastName")).filter { it.isNotEmpty() }.joinToString(" • ")).ifEmpty { value.s("name",value.s("examName","گزارش امتحان")) }
            val detail="${value.s("studentPublicId")} • نمره: ${value.s("rawScore",value.s("score",value.s("percentage","هنوز موجود نیست")))}${if(studentId.isNotEmpty())" • نسخهٔ ${value.optInt("publicationVersion")}" else ""}"
            item(name,detail,0xe158) {
                when {
                    studentId.isEmpty() && value.s("studentId").isNotEmpty() -> present("report",json("kind" to "results","studentId" to value.s("studentId"),"name" to name,"editionId" to edition,"round" to value.optInt("round",selectedRound)))
                    else -> showDetails(value,"گزارش رسمی")
                }
            }
            if(value.optBoolean("managerMayReadNotice") && value.s("publicationId").isNotEmpty())action("اطلاع‌نامهٔ $name") {
                val owner=app.session ?: return@action;val publication=value.s("publicationId")
                AlertDialog.Builder(activity).setTitle("رنگ اطلاع‌نامه").setItems(arrayOf("آبی","سرخ")) { _,choice ->
                    app.run("دریافت اطلاع‌نامه…",::result) {
                        val file=NoticeService(activity,app.network,app.native,app.storage).single(owner,publication,if(choice==0)"blue" else "red")
                        app.native.call("openPdf",mapOf("path" to file.path)){};"اطلاع‌نامه آماده شد."
                    }
                }.showThemed(theme)
            }
        }
        if(cursor.isNotEmpty() || moreHistory)action("صفحهٔ بعد") { if(!loading)load() }
    }
    private fun filters() {
        val panel=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL;setPadding(activity.dp(16),activity.dp(8),activity.dp(16),0) }
        val phrase=field(activity,theme,"نام یا شناسهٔ شاگرد").apply { setText(search) }
        val classNumber=field(activity,theme,"صنف ۱ تا ۱۲؛ خالی برای همه").apply { inputType=android.text.InputType.TYPE_CLASS_NUMBER;setText(grade?.toString() ?: "") }
        panel.full(phrase);panel.full(classNumber)
        val picker=android.widget.NumberPicker(activity).apply { minValue=1;maxValue=ExamPeriod.MAX_ROUND;value=selectedRound;contentDescription="دور امتحان" };panel.full(picker)
        AlertDialog.Builder(activity).setTitle("فیلتر نتیجه‌ها").setView(panel).setNegativeButton("بازگشت",null).setPositiveButton("اعمال") { _,_ ->
            val parsed=classNumber.text.toString().toIntOrNull()
            if((classNumber.text.isNotBlank() && parsed !in 1..12) || phrase.text.length>80)result(Result.failure(IllegalArgumentException("صنف ۱ تا ۱۲ و جستجو حداکثر ۸۰ حرف باشد.")))
            else { grade=parsed;search=phrase.text.toString().trim();selectedRound=picker.value;reset() }
        }.showThemed(theme)
    }
    private fun reset() {
        if(loading)return
        rows=emptyList();cursor="";beforeVersion=null;moreHistory=false;seenCursors.clear();load()
    }
    private fun loadEditions() {
        if(loading)return;loading=true
        app.managerRequest("/api/v1/school-manager/results/editions",emptyMap()) { response ->
            loading=false
            response.onSuccess { page ->
                editions=page.a("items").objects()
                if(editions.none { it.s("editionId",it.s("id"))==edition })edition=editions.firstOrNull()?.let { it.s("editionId",it.s("id")) } ?: ""
                issue="";if(edition.isNotEmpty())activity.window.decorView.post { load() }
            }.onFailure { issue=it.message ?: "دوره‌ها دریافت نشد." }
            rerender()
        }
    }
    private fun load() {
        if(loading)return
        if(edition.isEmpty()) { issue="ابتدا دورهٔ نشر نتیجه را انتخاب کنید.";rerender();return }
        val path=when {
            studentId.isNotEmpty() -> "/api/v1/school-manager/results/students/${uuid(studentId)}/history"
            else -> "/api/v1/school-manager/results"
        }
        val query=mutableMapOf("limit" to if(studentId.isEmpty())"100" else "20")
        query["editionId"]=edition;query["round"]=selectedRound.toString()
        if(studentId.isEmpty()) { grade?.let { query["grade"]=it.toString() };if(search.isNotEmpty())query["search"]=search }
        if(cursor.isNotEmpty())query["cursor"]=cursor
        beforeVersion?.let { query["beforeVersion"]=it.toString() }
        loading=true;rerender()
        app.managerRequest(path,query) { response ->
            loading=false
            response.onSuccess { page -> runCatching {
                val next=page.optJSONArray("items") ?: page.optJSONArray("results") ?: error("ساختار گزارش معتبر نیست.")
                val values=next.objects()
                if(studentId.isNotEmpty()) {
                    val versions=values.map { it.optInt("publicationVersion") }
                    require(versions.all { it>0 && (beforeVersion==null || it<beforeVersion!!) } && versions.toSet().size==versions.size) { "ترتیب نسخه‌های تاریخچه معتبر نیست." }
                    if(versions.isNotEmpty())beforeVersion=versions.min();moreHistory=values.size==20
                }
                val nextCursor=page.s("nextCursor")
                require(nextCursor.isEmpty() || seenCursors.add(nextCursor)) { "سرور صفحهٔ تکراری برگرداند." }
                rows=rows+values;cursor=nextCursor;issue=""
            }.onFailure { issue=it.message ?: "گزارش معتبر نیست." } }.onFailure { issue=it.message ?: "گزارش دریافت نشد." }
            rerender()
        }
    }
}
