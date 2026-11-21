package com.haseebullahjan.roshd.ui

import android.app.AlertDialog
import com.haseebullahjan.roshd.MainActivity
import com.haseebullahjan.roshd.core.*
import com.haseebullahjan.roshd.ui.actionbar.Route
import com.haseebullahjan.roshd.ui.components.showThemed
import org.json.JSONObject

class AcademicArchiveFragment(activity:MainActivity,route:Route):PageFragment(activity,route,"سال‌های گذشته") {
    private var years=emptyList<JSONObject>()
    private var yearId=route.args.s("yearId")
    private var selectedRound=route.args.optInt("round",1)
    private var cached:JSONObject?=null
    private var issue=""
    private var unauthorized=false
    private var started=false
    private var loading=false
    private var revision=0
    private var alive=true
    private var limit=100
    private val year get()=years.firstOrNull { it.s("id")==yearId }

    override fun onFragmentDestroy() { alive=false;revision++;super.onFragmentDestroy() }
    override fun render() {
        if(!started) { started=true;activity.window.decorView.post { loadYears(false) } }
        note(app.tr("معلومات سال و دور دلخواه را دریافت و آفلاین مرور کنید.","د خپلې خوښې کال او پړاو معلومات ترلاسه او افلاین وګورئ."))
        action(app.tr("تازه‌سازی فهرست سال‌ها","د کلونو لړ تازه کول")) { if(!loading)loadYears(true) }
        if(issue.isNotBlank())note(issue,true)
        if(unauthorized)action(app.tr("ورود دوباره","بیا ننوتل")) { present("login") }
        if(loading)note(app.tr("در حال دریافت…","د ترلاسه کولو په حال کې…"))
        if(years.isEmpty()) {
            if(!loading)empty("سالی در دسترس نیست","برای این حساب برنامهٔ سالانه‌ای دریافت نشده است؛ فهرست سال‌ها را تازه کنید.")
            return
        }
        val selected=year ?: return
        action(app.tr("سال: ${selected.s("label")}","کال: ${selected.s("label")}")) {
            AlertDialog.Builder(activity).setTitle(app.tr("انتخاب سال تعلیمی","تعلیمي کال وټاکئ"))
                .setSingleChoiceItems(years.map { it.s("label") }.toTypedArray(),years.indexOf(selected)) { dialog,index ->
                    dialog.dismiss()
                    if(yearId!=years[index].s("id")) { yearId=years[index].s("id");selectedRound=1;select() }
                }.setNegativeButton(app.tr("بازگشت","بېرته"),null).showThemed(theme)
        }
        action(app.tr("دور انتخاب‌شده: $selectedRound","ټاکل شوی پړاو: $selectedRound")) {
            AlertDialog.Builder(activity).setTitle(app.tr("انتخاب دور امتحان","د ازموینې پړاو وټاکئ"))
                .setSingleChoiceItems((1..selected.getInt("roundCount")).map { app.tr("دور $it","پړاو $it") }.toTypedArray(),selectedRound-1) { dialog,index ->
                    dialog.dismiss();if(selectedRound!=index+1) { selectedRound=index+1;select() }
                }.setNegativeButton(app.tr("بازگشت","بېرته"),null).showThemed(theme)
        }
        action(app.tr("دریافت معلومات این سال و دور","د دې کال او پړاو معلومات ترلاسه کول"),true) { if(!loading)loadSelection(true) }
        val saved=cached ?: return note(app.tr("معلومات این انتخاب هنوز در گوشی دریافت نشده است.","د دې ټاکنې معلومات لا په موبایل کې نه دي ترلاسه شوي."))
        note(app.tr("محفوظ در گوشی • ${saved.s("downloadedAt").take(16).replace('T',' ')}","په موبایل کې خوندي • ${saved.s("downloadedAt").take(16).replace('T',' ')}"))
        val data=saved.o("snapshot")
        label(app.tr("امتحان‌ها","ازموینې"))
        val exams=data.a("exams").objects()
        if(exams.isEmpty())note(app.tr("در این سال و دور امتحانی ثبت نشده است.","په دې کال او پړاو کې ازموینه نه ده ثبت شوې."))
        exams.take(limit).forEach { exam ->
            item("${exam.s("schoolName")} • صنف ${exam.optInt("grade")}",
                "${exam.optInt("questionCount")} سوال • ${exam.optInt("issuedSheetCount")} برگه • ${exam.optInt("registeredRosterMemberCount")} تخصیص",0xe23a) {
                detail("exam",json("examId" to exam.s("examId"),"schoolId" to exam.s("schoolId")))
            }
        }
        if(exams.size>limit)action("نمایش بیشتر") { limit+=100;rerender() }
        data.optJSONObject("publishedResults")?.let { results ->
            label(app.tr("نتیجه‌های رسمی منتشرشده","خپرې شوې رسمي پایلې"))
            action("نتیجه‌های شاگردان (${results.a("students").length()})") { detail("results") }
            action("تاریخچهٔ نشر نتیجه‌ها (${results.a("history").length()})") { detail("history") }
        }
        if(data.a("attendance").length()>0) {
            label(app.tr("حاضری","حاضري"))
            action("گزارش حاضری (${data.a("attendance").length()})") { detail("attendance") }
        }
    }
    private fun detail(kind:String,extra:JSONObject=JSONObject()) {
        val selected=year ?: return
        present("academic-archive-detail",extra.put("year",selected.copy()).put("round",selectedRound).put("kind",kind))
    }
    private fun select() {
        revision++;cached=null;issue="";unauthorized=false;limit=100
        route.args.put("yearId",yearId).put("round",selectedRound)
        // A response for the previous selection can populate its own cache only.
        loading=false;rerender()
        loadCachedWhenIdle(revision)
    }
    private fun loadCachedWhenIdle(ticket:Int) {
        if(!alive || revision!=ticket)return
        if(app.busy)activity.window.decorView.postDelayed({ loadCachedWhenIdle(ticket) },200)
        else loadSelection(false)
    }
    private fun loadYears(refresh:Boolean) {
        if(!alive)return
        val owner=app.session ?: return
        val ticket=++revision;loading=true;rerender()
        app.readAcademicArchive("دریافت فهرست سال‌ها…",{ outcome:Result<List<JSONObject>> ->
            if(!alive || app.session!==owner || ticket!=revision)return@readAcademicArchive
            loading=false
            outcome.onSuccess { incoming ->
                years=incoming;issue="";unauthorized=false
                if(years.none { it.s("id")==yearId }) {
                    yearId=(years.firstOrNull { !it.optBoolean("isCurrent") } ?: years.firstOrNull())?.s("id").orEmpty()
                    selectedRound=1;cached=null
                }
                year?.let {
                    val bounded=selectedRound.coerceIn(1,it.getInt("roundCount"))
                    if(bounded!=selectedRound)cached=null
                    selectedRound=bounded
                }
                route.args.put("yearId",yearId).put("round",selectedRound)
            }.onFailure { failure(it) }
            rerender()
            // Saved selections open immediately, including with an unexpired token
            // while offline. Refreshing the year list remains an explicit action.
            if(!refresh && years.isEmpty() && owner.valid)loadYears(true)
            else if(year!=null)loadSelection(false)
        }) { session -> if(refresh)app.academicArchive.refreshYears(session) else app.academicArchive.cachedYears(session) }
    }
    private fun loadSelection(download:Boolean) {
        if(!alive)return
        val selected=year?.copy() ?: return
        val owner=app.session ?: return
        val round=selectedRound;val ticket=++revision;loading=true
        if(download) { issue="";unauthorized=false }
        rerender()
        app.readAcademicArchive(if(download)"دریافت معلومات سال‌های گذشته…" else "خواندن معلومات محفوظ…",{ outcome:Result<JSONObject> ->
            if(!alive || app.session!==owner || ticket!=revision)return@readAcademicArchive
            loading=false
            outcome.onSuccess { cached=it.takeIf { value -> value.length()>0 } }.onFailure { failure(it) }
            rerender()
            if(outcome.exceptionOrNull().let { it is ApiFailure && it.status==404 })loadYears(true)
        }) { session ->
            if(download)app.academicArchive.download(session,selected,round)
            else app.academicArchive.cached(session,selected,round) ?: JSONObject()
        }
    }
    private fun failure(error:Throwable) {
        unauthorized=error is ApiFailure && error.status==401
        issue=when {
            unauthorized -> "اعتبار ورود پایان یافته است؛ با همین حساب دوباره وارد شوید."
            error is ApiFailure -> when(error.status) {
                403 -> "این حساب اجازهٔ دریافت معلومات انتخاب‌شده را ندارد."
                404 -> "این سال دیگر در دسترس نیست؛ فهرست سال‌ها تازه می‌شود."
                400 -> "شناسهٔ سال یا شمارهٔ دور معتبر نیست؛ انتخاب را بررسی کنید."
                409 -> "معلومات این دور در دسترس نیست؛ دور دیگری از همین سال انتخاب کنید."
                413 -> "حجم معلومات این انتخاب بیش از حد مجاز است؛ معلومات ناقص ذخیره نشد."
                429,503 -> "سرور موقتاً آماده نیست؛ پس از زمان انتظار دوباره تلاش کنید."
                else -> error.message
            }
            else -> error.message ?: "معلومات دریافت نشد؛ معلومات قبلی محفوظ است."
        }
    }
}

class AcademicArchiveDetailFragment(activity:MainActivity,route:Route):PageFragment(activity,route,"معلومات سال‌های گذشته") {
    private var started=false
    private var data:JSONObject?=null
    private var issue=""
    private var limit=100
    private var alive=true
    override fun onFragmentDestroy() { alive=false;super.onFragmentDestroy() }
    override fun render() {
        if(!started) {
            started=true;val owner=app.session
            activity.window.decorView.post {
                app.readAcademicArchive("خواندن معلومات محفوظ…",{ outcome:Result<JSONObject> ->
                    if(alive && app.session===owner) {
                        outcome.onSuccess { data=it.optJSONObject("snapshot");if(data==null)issue="معلومات این انتخاب در گوشی موجود نیست؛ آن را دوباره دریافت کنید." }
                            .onFailure { issue=it.message.orEmpty() }
                        rerender()
                    }
                }) { app.academicArchive.cached(it,route.args.o("year"),route.args.getInt("round")) ?: JSONObject() }
            }
        }
        note("${route.args.o("year").s("label")} • دور ${route.args.optInt("round")}")
        if(issue.isNotBlank())note(issue,true)
        val snapshot=data ?: return
        when(route.args.s("kind")) {
            "exam" -> {
                val exam=snapshot.a("exams").objects().firstOrNull { it.s("examId")==route.args.s("examId") && it.s("schoolId")==route.args.s("schoolId") } ?: return
                label("${exam.s("schoolName")} • صنف ${exam.optInt("grade")}")
                note("${exam.optInt("questionCount")} سوال • مرحلهٔ ${exam.optInt("workflowStage")} • ${if(exam.optBoolean("roundFinalized"))"دور نهایی شده" else "دور نهایی نشده"}")
                note("${exam.optInt("issuedSheetCount")} برگهٔ صادرشده • ${exam.optInt("registeredRosterMemberCount")} تخصیص")
                label("شاگردان دارای برگه")
                val students=exam.a("students").objects()
                students.take(limit).forEach { item(studentName(it),it.s("studentPublicId"),0xe4d0) }
                more(students.size)
            }
            "results","history" -> {
                val published=snapshot.o("publishedResults")
                val rows=if(route.args.s("kind")=="history")published.a("history").objects() else
                    published.a("students").objects().flatMap { it.a("results").objects() }
                label(if(route.args.s("kind")=="history")"تاریخچهٔ نشر نتیجه‌ها" else "نتیجه‌های رسمی شاگردان")
                rows.take(limit).forEach { value ->
                    item(studentName(value),"${value.s("studentPublicId")} • نمره: ${value.s("totalScore")} • نسخهٔ ${value.optInt("publicationVersion")}",0xe158) {
                        val subjects=value.a("subjects").objects().joinToString("\n") { "${it.s("subject",it.s("subjectName"))}: ${it.s("score",it.s("rawScore"))}" }
                        AlertDialog.Builder(activity).setTitle(studentName(value))
                            .setMessage("${value.s("schoolName")} • صنف ${value.optInt("grade")}\nنمره: ${value.s("totalScore")}\nتراز: ${value.s("totalTaraz","—")}\nتاریخ نشر: ${value.s("publishedAt").take(10)}\n$subjects")
                            .setPositiveButton("بستن",null).showThemed(theme)
                    }
                }
                more(rows.size)
            }
            "attendance" -> {
                val reports=snapshot.a("attendance").objects()
                reports.take(limit).forEach { report ->
                    val exam=snapshot.a("exams").objects().firstOrNull { it.s("examId")==report.s("examId") }
                    item("${exam?.s("schoolName").orEmpty()} • صنف ${exam?.optInt("grade") ?: ""}",
                        "حاضر: ${report.optInt("presentCount")} • غیرحاضر: ${report.optInt("absentCount")} • ثبت‌نشده: ${report.optInt("unrecordedCount")}",0xe23a) {
                        present("academic-archive-detail",route.args.copy().put("kind","attendance-items").put("examId",report.s("examId")))
                    }
                }
                more(reports.size)
            }
            "attendance-items" -> {
                val report=snapshot.a("attendance").objects().firstOrNull { it.s("examId")==route.args.s("examId") } ?: return
                note(if(report.optBoolean("closed"))"حاضری بسته شده است." else "حاضری باز است.")
                val rows=report.a("items").objects()
                rows.take(limit).forEach { value -> item(studentName(value),"${value.s("studentPublicId")} • ${when(value.s("status").lowercase()) { "present"->"حاضر";"absent"->"غیرحاضر";else->"ثبت‌نشده" }}",0xe4d0) }
                more(rows.size)
            }
        }
    }
    private fun studentName(value:JSONObject)=listOf(value.s("firstName"),value.s("lastName")).filter { it.isNotBlank() }.joinToString(" ").ifEmpty { value.s("studentPublicId","شاگرد") }
    private fun more(count:Int) {
        if(count==0)note("در این انتخاب معلوماتی ثبت نشده است.")
        if(count>limit)action("نمایش بیشتر") { limit+=100;rerender() }
    }
}
