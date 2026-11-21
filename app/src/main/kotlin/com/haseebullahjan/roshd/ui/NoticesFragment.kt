package com.haseebullahjan.roshd.ui

import android.widget.LinearLayout
import android.app.AlertDialog
import android.text.Editable
import android.text.TextWatcher
import com.haseebullahjan.roshd.MainActivity
import com.haseebullahjan.roshd.core.*
import com.haseebullahjan.roshd.ui.actionbar.Route
import com.haseebullahjan.roshd.ui.components.*

class NoticesFragment(activity:MainActivity,route:Route):PageFragment(activity,route,"اطلاع‌نامه‌ها") {
    private var reasonValue=""
    private var gradeValue=""
    private var selectedRound=app.round.coerceIn(1,ExamPeriod.MAX_ROUND)
    private var color="blue"
    private var edition=app.currentYear?.s("editionId") ?: ""
    private var editionName=app.currentYear?.s("label") ?: "انتخاب دوره"
    override fun render() {
        note("اطلاع‌نامه‌های رسمی پس از دریافت امن، در گوشی به PDF تبدیل می‌شوند.")
        action("دوره: $editionName") {
            app.managerRequest("/api/v1/school-manager/results/editions",emptyMap()) { response ->
                response.onSuccess { page ->
                    val choices=page.a("items").objects()
                    AlertDialog.Builder(activity).setTitle("دورهٔ نشر نتیجه‌ها").setItems(choices.map { it.s("label",it.s("name",it.s("academicYearKey",it.s("editionId",it.s("id"))))) }.toTypedArray()) { _,which ->
                        val chosen=choices[which];edition=chosen.s("editionId",chosen.s("id"));editionName=chosen.s("label",chosen.s("name",chosen.s("academicYearKey",edition)));rerender()
                    }.showThemed(theme)
                }.onFailure { result(Result.failure(it)) }
            }
        }
        action(app.tr("دور $selectedRound","پړاو $selectedRound")) {
            AlertDialog.Builder(activity).setTitle(app.tr("دور امتحان","د ازموینې پړاو"))
                .setSingleChoiceItems((1..ExamPeriod.MAX_ROUND).map { app.tr("دور $it","پړاو $it") }.toTypedArray(),selectedRound-1) { dialog,which ->
                    selectedRound=which+1;dialog.dismiss();rerender()
                }.setNegativeButton(app.tr("بازگشت","بېرته"),null).showThemed(theme)
        }
        val colors=row(activity);colors.weight(button(activity,theme,app.tr("آبی","آبي"),color=="blue") { color="blue";rerender() });colors.weight(button(activity,theme,app.tr("سرخ","سور"),color=="red") { color="red";rerender() });content.full(colors)
        val grade=field(activity,theme,"صنف ۱ تا ۱۲؛ خالی برای همه").apply { inputType=android.text.InputType.TYPE_CLASS_NUMBER;setText(gradeValue);changes { gradeValue=it } }
        label("صنف");content.full(grade)
        val reason=field(activity,theme,"دلیل دریافت گروهی")
        reason.setText(reasonValue);reason.changes { reasonValue=it }
        label("دلیل دریافت");content.full(reason)
        action("دریافت اطلاع‌نامه‌های این دور",true) {
            val owner=app.session ?: return@action
            val selected=selectedRound
            val reasonText=reasonValue.trim()
            val classNumber=gradeValue.toIntOrNull()
            if(gradeValue.isNotBlank() && classNumber !in 1..12) { result(Result.failure(IllegalArgumentException("صنف باید ۱ تا ۱۲ باشد.")));return@action }
            val selectedEdition=edition;val selectedColor=color
            app.run("دریافت و ساخت اطلاع‌نامه‌ها…",{ result(it);if(it.isSuccess)rerender() }) {
                val file=NoticeService(activity,app.network,app.native,app.storage).group(owner,selectedEdition,selected,classNumber,reasonText,selectedColor)
                app.native.call("openPdf",mapOf("path" to file.path)){}
                "اطلاع‌نامه‌ها آماده شد."
            }
        }
        label("اطلاع‌نامه‌های محفوظ")
        // Read storage on the stage queue; Keystore and disk never run in UI layout.
        val list=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL };content.full(list)
        val owner=app.session ?: return
        java.util.concurrent.Executors.newSingleThreadExecutor().also { executor -> executor.execute {
            val files=runCatching { app.storage.list(owner.scope,"notice-file") }.getOrDefault(emptyList())
            activity.runOnUiThread {
                files.forEach { file -> list.full(button(activity,theme,"دور ${file.optInt("round")} • ${file.optInt("count")} اطلاع‌نامه") { app.native.call("openPdf",mapOf("path" to file.s("path"))){} }) }
                if(files.isEmpty())list.full(text(activity,theme,"هنوز اطلاع‌نامه‌ای دریافت نشده است.",13f,muted=true))
            };executor.shutdown()
        } }
    }
    private fun android.widget.EditText.changes(changed:(String)->Unit) {
        addTextChangedListener(object:TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int) {}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) { changed(s?.toString().orEmpty()) }
            override fun afterTextChanged(s:Editable?) {}
        })
    }
}
