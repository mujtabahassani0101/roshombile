package com.haseebullahjan.roshd.ui

import android.app.AlertDialog
import android.content.Intent
import android.text.Editable
import android.text.TextWatcher
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.View
import android.widget.*
import com.haseebullahjan.roshd.BuildConfig
import com.haseebullahjan.roshd.MainActivity
import com.haseebullahjan.roshd.core.*
import com.haseebullahjan.roshd.messenger.*
import com.haseebullahjan.roshd.ui.actionbar.*
import com.haseebullahjan.roshd.ui.camera.ScannerActivity
import com.haseebullahjan.roshd.ui.components.*
import org.json.JSONObject
import java.io.File

object Screens {
    fun create(activity:MainActivity,route:Route):BaseFragment = when(route.name) {
        "login" -> LoginFragment(activity,route)
        "home" -> HomeFragment(activity,route)
        "exams" -> ExamsFragment(activity,route)
        "exam" -> ExamFragment(activity,route)
        "history" -> HistoryFragment(activity,route)
        "review" -> ReviewFragment(activity,route)
        "archive" -> ArchiveFragment(activity,route)
        "academic-archive" -> AcademicArchiveFragment(activity,route)
        "academic-archive-detail" -> AcademicArchiveDetailFragment(activity,route)
        "settings" -> SettingsFragment(activity,route)
        "manager" -> ManagerFragment(activity,route)
        "report" -> ReportFragment(activity,route)
        "editor" -> DocumentEditorFragment(activity,route)
        "notices" -> NoticesFragment(activity,route)
        "file" -> FilePreviewFragment(activity,route)
        "device" -> DeviceFragment(activity,route)
        else -> HomeFragment(activity,Route("home"))
    }
}

abstract class PageFragment(activity:MainActivity,route:Route,private val title:String):BaseFragment(activity,route) {
    protected lateinit var content:LinearLayout
    private var created=false
    private var renderScheduled=false
    private val pendingRender=Runnable { renderScheduled=false;rerender() }
    override fun createView():View {
        val root=LinearLayout(activity).apply {
            orientation=LinearLayout.VERTICAL;layoutDirection=View.LAYOUT_DIRECTION_RTL;clipChildren=false;clipToPadding=false
            if(route.name=="home")setBackgroundColor(theme.canvas)
        }
        val expanded=route.name=="login" && activity.resources.configuration.screenHeightDp>=500
        val heading=when(route.name) { "login"->app.tr("خوش آمدید","ښه راغلاست");"home"->app.tr("فضای کار آزمون","د ازموینې کاري ځای");else->title }
        val scroll=ScrollView(activity).apply {
            tag="page-scroll";isFillViewport=true;clipToPadding=false;isVerticalScrollBarEnabled=false
            if(app.session!=null)setPadding(0,0,0,activity.dp(92))
        }
        val body=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL;clipChildren=false;clipToPadding=false }
        if(route.name=="home") {
            body.full(row(activity).apply {
                tag="header:study";minimumHeight=activity.dp(64)
                setPadding(activity.dp(theme.pageGutter),activity.dp(12),activity.dp(theme.pageGutter),activity.dp(8))
                weight(text(activity,theme,"رشد",24f,true))
                addView(RoshdBrand.image(activity).apply { tag="brand:study" },LinearLayout.LayoutParams(activity.dp(40),activity.dp(40)))
            })
        } else body.full(orbitHeader(activity,theme,heading,expanded,if(route.name=="login")null else { { finishFragment() } }))
        content=LinearLayout(activity).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(activity.dp(theme.pageGutter),activity.dp(8),activity.dp(theme.pageGutter),activity.dp(28))
            clipChildren=false;clipToPadding=false
        }
        body.full(content);scroll.addView(body,FrameLayout.LayoutParams(-1,-2));root.addView(scroll,LinearLayout.LayoutParams(-1,-1))
        created=true;render()
        return root
    }
    protected abstract fun render()
    protected fun rerender() { if(created) { content.removeAllViews();render() } }
    private fun scheduleRender() {
        // A storage update publishes several related events. Draw their latest
        // state once per display frame instead of rebuilding the page for each.
        if(!created || renderScheduled)return
        renderScheduled=true;content.postOnAnimation(pendingRender)
    }
    override fun onResume() {
        super.onResume()
        if(route.name!="login" && route.name!="editor") {
            observe(AppEvent.DATA) { scheduleRender() };observe(AppEvent.QUEUE) { scheduleRender() };observe(AppEvent.SCANS) { scheduleRender() }
            rerender()
        }
    }
    override fun onPause() {
        if(created)content.removeCallbacks(pendingRender)
        renderScheduled=false;super.onPause()
    }
    protected fun label(value:String) { content.space(16);content.full(studyDisplay(activity,theme,value,19f,19f));content.space(8) }
    protected fun note(value:String,danger:Boolean=false) { content.full(text(activity,theme,value,13f,muted=!danger).apply { if(danger)setTextColor(theme.danger) });content.space(8) }
    protected fun action(label:String,primary:Boolean=false,click:()->Unit) { content.full(button(activity,theme,label,primary,click));content.space(4) }
    protected fun deleteScanAction(scan:JSONObject,closeOnDelete:Boolean=false) {
        val id=scan.s("id")
        content.full(button(activity,theme,app.tr("حذف برگهٔ اسکن‌شده","سکین شوې پاڼه ړنګه کړئ")) {
            AlertDialog.Builder(activity).setTitle(app.tr("حذف برگهٔ اسکن‌شده","سکین شوې پاڼه ړنګول"))
                .setMessage(app.tr("این برگه، پاسخ‌های خوانده‌شده و عکس‌های مربوط از گوشی حذف می‌شوند و از صف ارسال کنار می‌روند. نتیجه‌ای که قبلاً به سرور رسیده باقی می‌ماند. حذف شود؟",
                    "دا پاڼه، لوستل شوي ځوابونه او اړوند انځورونه له موبایل او د لېږلو له کتاره ړنګېږي. سرور ته مخکې لېږل شوې پایله پاتې کېږي. ړنګه شي؟"))
                .setNegativeButton(app.tr("انصراف","بېرته"),null)
                .setPositiveButton(app.tr("حذف برگه","پاڼه ړنګه کړئ")) { _,_ ->
                    app.deleteScan(id) { outcome ->
                        result(outcome)
                        if(outcome.isSuccess && closeOnDelete)finishFragment() else rerender()
                    }
                }.showThemed(theme)
        }.apply {
            tag="scan-delete:$id";setTextColor(theme.danger)
            contentDescription=app.tr("حذف برگهٔ ${scan.s("studentName").ifBlank { id.take(6) }}","د ${scan.s("studentName").ifBlank { id.take(6) }} پاڼه ړنګول")
            isEnabled=scan.s("state")!="capturing";alpha=if(isEnabled)1f else .55f
        });content.space(4)
    }
    protected fun uploadAllAction() {
        val expired=app.session?.valid==false
        if(expired) {
            note(app.tr("اعتبار ورود پایان یافته است. برای ادامهٔ ارسال با همان حساب وارد شوید؛ برگه‌ها در گوشی محفوظ‌اند.","د ننوتلو اعتبار پای ته رسېدلی. د لېږلو لپاره په هماغه حساب بیا ننوځئ؛ پاڼې په موبایل کې خوندي دي."),true)
            action(app.tr("ورود دوباره برای ارسال","د لېږلو لپاره بیا ننوتل"),true) { present("login") }
        } else action("ارسال همهٔ برگه‌ها",true) { app.sendAllScans(::result) }
        note(app.tr("پاسخ‌های آماده و برگه‌های صاف‌شده و تصحیح‌شده با یک دکمه ارسال می‌شوند.","چمتو ځوابونه او هوارې او تصحیح شوې پاڼې په یوه تڼۍ لېږل کېږي."))
        if(app.scans.isNotEmpty()) {
            note(app.tr("پاسخ‌ها: ${app.uploadedAnswerCount} از ${app.scans.size} تأیید سرور • برگه‌های تصحیح‌شده: ${app.uploadedImageCount} از ${app.scans.size} تأیید سرور\nارسال کامل: ${app.uploadedScanCount} برگه",
                "ځوابونه: ${app.uploadedAnswerCount} له ${app.scans.size} د سرور تایید • انځورونه: ${app.uploadedImageCount} له ${app.scans.size} د سرور تایید\nبشپړ لېږل شوي: ${app.uploadedScanCount} پاڼې"))
            val blocked=app.reviewBlockedScanCount
            if(blocked>0) {
                note(app.tr("پاسخ‌های $blocked برگه نیازمند بازبینی‌اند و هنوز ارسال نشده‌اند. عکس‌ها جداگانه ارسال می‌شوند.","د $blocked پاڼو ځوابونه کتنې ته اړتیا لري او لا نه دي لېږل شوي. انځورونه جلا لېږل کېږي."),true)
                if(route.name!="history")action(app.tr("بازبینی برگه‌های ارسال‌نشده","د نالېږل شوو پاڼو کتنه")) { present("history") }
            }
        }
    }
    protected fun item(title:String,subtitle:String="",glyph:Int=0xe23a,target:LinearLayout=content,click:(()->Unit)?=null):View {
        val tone=theme.studyColors[(target.childCount/2)%theme.studyColors.size]
        val panel=ClayPanel(activity,theme,tone=tone)
        val line=row(activity)
        line.addView(icon(activity,theme,glyph,26f).apply { setTextColor(theme.studyCardInk) },LinearLayout.LayoutParams(activity.dp(40),activity.dp(48)).apply { marginEnd=activity.dp(8) })
        val words=LinearLayout(activity).apply {
            orientation=LinearLayout.VERTICAL
            full(text(activity,theme,title,15f,true,localize=route.name !in setOf("exam","report","review","exams","history","archive","academic-archive","academic-archive-detail")).apply { setTextColor(theme.studyCardInk) })
            if(subtitle.isNotEmpty())full(text(activity,theme,subtitle,12f).apply { setTextColor(android.graphics.Color.rgb(52,47,42)) })
        }
        line.weight(words)
        if(click!=null)line.addView(icon(activity,theme,0xe058,20f).apply { setTextColor(theme.studyCardInk) },LinearLayout.LayoutParams(activity.dp(26),activity.dp(32)))
        panel.full(line)
        if(click!=null) { panel.isClickable=true;panel.isFocusable=true;panel.clayPress();panel.setOnClickListener { click() };panel.contentDescription="$title، $subtitle";panel.accessibilityDelegate=object:View.AccessibilityDelegate() { override fun onInitializeAccessibilityNodeInfo(host:View,info:android.view.accessibility.AccessibilityNodeInfo) { super.onInitializeAccessibilityNodeInfo(host,info);info.className=Button::class.java.name } } }
        target.full(panel);target.space(7);return panel
    }
    protected fun empty(title:String,detail:String) {
        val panel=ClayPanel(activity,theme,true)
        panel.full(icon(activity,theme,0xe00c,40f))
        panel.space(12)
        panel.full(text(activity,theme,title,16f,true).apply { gravity=Gravity.CENTER });panel.space(6)
        panel.full(text(activity,theme,detail,13f,muted=true).apply { gravity=Gravity.CENTER });content.full(panel)
    }
    protected fun scanSheet() { activity.startActivityForResult(Intent(activity,ScannerActivity::class.java),9501) }
    protected fun importDocuments() {
        val directory=File(activity.cacheDir,"native-imports").apply { mkdirs() }
        app.native.call("pickDocuments",mapOf("targetDirectory" to directory.path,"maxBytes" to 50L*1024*1024)) { response ->
            response.onSuccess { value ->
                val files=(value as? List<*>)?.mapNotNull { (it as? Map<*,*>)?.get("path")?.toString()?.let(::File) } ?: emptyList()
                if(files.isNotEmpty())app.processFiles(files,::result)
            }.onFailure { result(Result.failure(it)) }
        }
    }
    protected fun showDetails(value:JSONObject,title:String="جزئیات") {
        val lines=value.keys().asSequence().filter { it !in setOf("accessToken","token","payloadBase64","signatureBase64","encrypted_key","ciphertext") }.map { key -> "$key: ${value.opt(key)}" }.joinToString("\n")
        AlertDialog.Builder(activity).setTitle(title).setMessage(lines).setPositiveButton("بستن",null).showThemed(theme)
    }
}

private class LoginFragment(activity:MainActivity,route:Route):PageFragment(activity,route,"") {
    override fun render() {
        val top=row(activity)
        top.weight(text(activity,theme,app.tr("همراهِ مسیر یادگیری","د زده کړې د لارې ملګری"),14f,true))
        top.addView(button(activity,theme,app.tr("پښتو","دری")) { app.setLanguage(if(app.language=="fa")"ps" else "fa") });content.full(top)
        content.space(6)
        val username=field(activity,theme,app.tr("نام کاربری","د کارن نوم"))
        app.session?.raw?.s("loginUsername")?.takeIf { it.isNotBlank() }?.let(username::setText)
        val password=field(activity,theme,app.tr("رمز عبور","پټنوم"),true)
        username.id=View.generateViewId();password.id=View.generateViewId()
        content.space(16)
        content.full(text(activity,theme,app.tr("نام کاربری","د کارن نوم"),16f,true).apply { labelFor=username.id })
        content.space(6);content.full(username)
        content.space(16)
        content.full(text(activity,theme,app.tr("رمز عبور","پټنوم"),16f,true).apply { labelFor=password.id })
        content.space(6);content.full(password)
        val reveal=CheckBox(activity).apply { text=app.tr("نمایش رمز عبور","پټنوم ښکاره کړه");textSize=13f;typeface=ClayTheme.regular(activity);setTextColor(theme.muted);minHeight=activity.dp(48);buttonTintList=theme.controlsTint();setOnCheckedChangeListener { _,checked -> password.transformationMethod=if(checked)null else PasswordTransformationMethod.getInstance() } }
        styleStudyCheckBox(activity,theme,reveal)
        content.full(reveal);content.space(16)
        action(app.tr("ورود به حساب","حساب ته ننوتل"),true) {
            password.clearFocus()
            (activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(password.windowToken,0)
            app.login(username.text.toString(),password.text.toString()) { result(it);if(it.isSuccess)password.text.clear() }
        }
        content.space(18)
        note(app.tr("با حساب مدیر مکتب یا اپراتور اسکن وارد شوید.","د ښوونځي د مدیر یا د سکن د کارن حساب وکاروئ."))
        action(app.tr("ظاهر برنامه","د اپ بڼه")) { present("settings") }
        content.full(text(activity,theme,"نسخهٔ ${BuildConfig.VERSION_NAME}",12f,muted=true).apply { gravity=Gravity.CENTER })
    }
}

private class HomeFragment(activity:MainActivity,route:Route):PageFragment(activity,route,"رشد") {
    override fun render() {
        val owner=app.session ?: return
        val pending=app.queue.count { it.s("status")!="delivered" }
        content.full(button(activity,theme,app.tr("دریافت معلومات","معلومات ترلاسه کول"),true) {
            if(!owner.valid) { present("login");return@button }
            // Refresh first so this same top action also works when the server
            // has just opened a year or changed the available rounds.
            app.refreshConfiguration { outcome ->
                if(outcome.isFailure)result(outcome)
                else if(!activity.isFinishing && !activity.isDestroyed && activity.navigation.current?.name=="home")confirmInformation()
            }
        }.apply {
            tag="home:information"
            background=ClayDrawable(theme,fillColor=theme.ink,radius=18f,scale=resources.displayMetrics.density)
            setTextColor(theme.canvas)
        })
        content.space(8)
        val year=app.currentYear
        note(when {
            year==null -> app.tr("سال جاری از سرور دریافت نشده است.","اوسنی کال له سرور څخه نه دی ترلاسه شوی.")
            app.openRounds.isEmpty() -> app.tr("در حال حاضر دور باز وجود ندارد.","اوس مهال پرانیستی پړاو نشته.")
            app.round in app.openRounds -> year.s("label",year.s("academicYearKey"))+" • "+app.tr("دور ${app.round}","پړاو ${app.round}")
            else -> app.tr("برای انتخاب دور و دریافت، دکمهٔ بالا را بزنید.","د پړاو ټاکلو او معلوماتو لپاره پورته تڼۍ ووهئ.")
        })
        content.full(button(activity,theme,"اسکن پاسخ‌نامه") { scanSheet() }.apply { tag="home:scan" })
        content.space(18)
        homeRow(app.tr("امتحان‌ها","ازموینې"),app.tr("${app.packages.size} بستهٔ محفوظ","${app.packages.size} ساتل شوې بستې"),0xe23a,"section:exams") { present("exams") }
        homeRow(app.tr("نتیجه‌ها","پایلې"),app.tr("${app.scans.size} برگه • $pending در صف","${app.scans.size} پاڼې • $pending په کتار کې"),0xe158,"section:history") { present("history") }
        homeRow(app.tr("واردکردن","راوړل"),app.tr("عکس و PDF","انځور او PDF"),0xe836,"section:import") { importDocuments() }
        homeRow(app.tr("آرشیف","ارشیف"),app.tr("اصل عکس‌ها و فایل‌ها","اصلي انځورونه او فایلونه"),0xe00c,"section:archive") { present("archive") }
        action(app.tr("معلومات سال‌های گذشته","د تېرو کلونو معلومات")) { present("academic-archive") }
        val unreadable=app.queue.count { it.s("status")=="storage_error" }
        if(unreadable>0)note("$unreadable ثبت نیاز به بررسی دارد؛ اصل آن‌ها محفوظ است و ارسال نمی‌شود.",true)
        app.startupError?.let { note(it,true) }
        if(app.scans.isNotEmpty() || pending>0) {
            label(app.tr("آخرین فعالیت‌ها","وروستي فعالیتونه"))
            app.scans.take(3).forEach { scan ->
                homeRow(scan.s("studentName","پاسخ‌نامه"),scan.s("createdAt").take(16).replace('T',' '),0xe23a,"recent:${scan.s("id")}") { present("review",json("id" to scan.s("id"))) }
            }
            uploadAllAction()
        }
        if(owner.manager)homeRow(app.tr("بخش مدیر مکتب","د ښوونځي د مدیر برخه"),app.tr("شاگردان، گزارش‌ها و اطلاع‌نامه‌ها","زده کوونکي، راپورونه او خبرتیاوې"),0xe158,"manager") { present("manager") }
        content.space(8)
        note(owner.name+" • "+if(owner.valid)app.tr("حساب فعال","فعال حساب") else app.tr("آفلاین • معلومات شما محفوظ است","آفلاین • ستاسو معلومات خوندي دي"))
    }

    private fun homeRow(title:String,subtitle:String,glyph:Int,key:String,click:()->Unit) {
        val panel=ClayPanel(activity,theme).apply {
            tag="home:$key";setPadding(activity.dp(14),activity.dp(10),activity.dp(14),activity.dp(10))
            minimumHeight=activity.dp(68)
            isClickable=true;isFocusable=true;contentDescription="$title، $subtitle"
            setOnClickListener { click() };clayPress()
            accessibilityDelegate=object:View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host:View,info:android.view.accessibility.AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host,info);info.className=Button::class.java.name
                }
            }
        }
        val line=row(activity)
        line.addView(icon(activity,theme,glyph,24f),LinearLayout.LayoutParams(activity.dp(36),activity.dp(40)).apply { marginEnd=activity.dp(10) })
        line.weight(LinearLayout(activity).apply {
            orientation=LinearLayout.VERTICAL
            full(text(activity,theme,title,15f,true,localize=false))
            full(text(activity,theme,subtitle,12f,muted=true,localize=false))
        })
        line.addView(icon(activity,theme,0xe058,18f),LinearLayout.LayoutParams(activity.dp(24),activity.dp(32)))
        panel.full(line);content.full(panel);content.space(8)
    }

    private fun confirmInformation() {
        val year=app.currentYear
        val open=app.openRounds
        if(year==null || open.isEmpty()) {
            AlertDialog.Builder(activity).setTitle(app.tr("دریافت معلومات","معلومات ترلاسه کول"))
                .setMessage(if(year==null)app.tr("سال جاری هنوز در سرور تعیین نشده است.","اوسنی کال لا په سرور کې نه دی ټاکل شوی.") else app.tr("در حال حاضر دور باز وجود ندارد.","اوس مهال پرانیستی پړاو نشته."))
                .setPositiveButton(app.tr("بستن","تړل"),null).showThemed(theme)
            return
        }
        if(app.round !in open) { chooseInformationRound();return }
        val selected=app.round
        val period=ExamPeriod.key(year)
        val dialog=AlertDialog.Builder(activity).setTitle(app.tr("دریافت معلومات دور $selected","د پړاو $selected معلومات ترلاسه کړئ"))
            .setMessage(year.s("label",year.s("academicYearKey")))
            .setNegativeButton(app.tr("بازگشت","بېرته"),null)
            .setPositiveButton(app.tr("دریافت","ترلاسه کول")) { _,_ ->
                if(app.round==selected && selected in app.openRounds && ExamPeriod.key(app.currentYear)==period)app.downloadInformation(::result)
            }
        if(open.size>1)dialog.setNeutralButton(app.tr("تغییر دور","پړاو بدلول")) { _,_ -> chooseInformationRound() }
        dialog.showThemed(theme)
    }

    private fun chooseInformationRound() {
        val open=app.openRounds
        AlertDialog.Builder(activity).setTitle(app.tr("انتخاب دور امتحان","د ازموینې پړاو وټاکئ"))
            .setSingleChoiceItems(open.map { app.tr("دور $it","پړاو $it") }.toTypedArray(),open.indexOf(app.round)) { dialog,index ->
                dialog.dismiss()
                runCatching { app.selectRound(open[index]) }.onSuccess { confirmInformation() }.onFailure { result(Result.failure(it)) }
            }.setNegativeButton(app.tr("بازگشت","بېرته"),null).showThemed(theme)
    }
}

private class ExamsFragment(activity:MainActivity,route:Route):PageFragment(activity,route,"امتحان‌ها") {
    private var query=route.args.s("examQuery")
    private var limit=route.args.optInt("examLimit",40)
    override fun render() {
        val list=app.exams.filter { app.round==0 || it.round==app.round }
        note(if(app.round==0)"دور باز از سرور دریافت نشده است؛ تنظیمات را در خانه تازه کنید." else "امتحان‌های دور ${app.round}")
        if(list.isEmpty()) {
            empty("امتحانی موجود نیست","دور را انتخاب و معلومات آن را از صفحهٔ خانه دریافت کنید.")
            return
        }
        val search=field(activity,theme,"جست‌وجوی امتحان‌ها").apply { tag="exam-search";setText(query) }
        content.full(search);content.space(12)
        val results=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL };content.full(results)
        fun fill(value:String) {
            if(value!=query)limit=40
            query=value;route.args.put("examQuery",value);route.args.put("examLimit",limit)
            results.removeAllViews()
            val matching=list.filter { it.name.contains(value,true) }
            matching.take(limit).forEach { exam ->
                item(exam.name,"صنف ${exam.grade} • ${exam.count} سؤال • ${if(app.packages[exam.key]?.valid==true)"آمادهٔ آفلاین" else "نیاز به دریافت بسته"}",0xe23a,target=results) { present("exam",json("key" to exam.key)) }
            }
            if(matching.isEmpty())results.full(text(activity,theme,"امتحانی موجود نیست",15f,muted=true))
            if(matching.size>limit)results.full(button(activity,theme,"نمایش بیشتر") { limit+=40;fill(query) }.apply { tag="exam-more" })
        }
        search.watch(::fill);fill(query)
    }
}
private class ExamFragment(activity:MainActivity,route:Route):PageFragment(activity,route,"امتحان") {
    private var query=route.args.s("studentQuery")
    private var studentLimit=route.args.optInt("studentLimit",100)
    override fun render() {
        val exam=app.exams.singleOrNull { it.key==route.args.s("key") } ?: return empty("امتحان موجود نیست","فهرست را تازه کنید.")
        val pack=app.packages[exam.key]
        item(exam.name,"صنف ${exam.grade} • دور ${exam.round} • ${exam.count} سؤال\n${pack?.roster?.size ?: 0} شاگرد")
        if(pack==null) { note("بستهٔ معتبر این امتحان موجود نیست. از صفحهٔ خانه معلومات این دور را دریافت کنید.",true);return }
        action("اسکن پاسخ‌نامه",true) { scanSheet() }
        label("شاگردان")
        val search=field(activity,theme,"جست‌وجوی نام یا آی‌دی").apply { tag="student-search";setText(query) };content.full(search)
        val list=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL };content.full(list)
        fun fill(value:String) {
            if(value!=query)studentLimit=100
            query=value;route.args.put("studentQuery",value);route.args.put("studentLimit",studentLimit);list.removeAllViews()
            val rows=pack.roster.filter { it.name.contains(value,true) || it.code.contains(value) }
            rows.take(studentLimit).forEach { student ->
                val title=student.name.ifBlank { student.code }
                val view=ClayPanel(activity,theme).apply {
                    full(text(activity,theme,title,15f,true,localize=false));full(text(activity,theme,student.code,13f,muted=true))
                }
                list.full(view.apply { tag="student:${student.code}" })
            }
            if(rows.isEmpty())list.full(text(activity,theme,app.tr("شاگردی در این بخش پیدا نشد.","په دې برخه کې زده کوونکی ونه موندل شو."),14f,muted=true))
            if(rows.size>studentLimit)list.full(button(activity,theme,"نمایش بیشتر") { studentLimit+=100;route.args.put("studentLimit",studentLimit);fill(query) }.apply { tag="student-more" })
        }
        search.watch(::fill);fill(query)
        if(!pack.valid)note("بستهٔ این امتحان منقضی شده است؛ معلومات آن را دوباره دریافت کنید.",true)
    }
}

private fun statusLabel(status:String)=when(status) { "delivered"->"ارسال‌شده؛ رسید معتبر سرور";"sending"->"در حال ارسال";"rejected"->"ردشده؛ نیاز به بررسی";"storage_error"->"نیاز به بررسی فایل محفوظ";"retry_wait"->"در انتظار تلاش دوباره";else->"در صف محفوظ" }

private class HistoryFragment(activity:MainActivity,route:Route):PageFragment(activity,route,"نتیجه‌های اسکن") {
    private var limit=100
    override fun render() {
        note("${app.scans.size} برگهٔ محفوظ • نتیجهٔ موقت آفلاین و نمرهٔ رسمی جدا نمایش داده می‌شوند.")
        uploadAllAction()
        action("ارسال فقط پاسخ‌ها") { app.submitPending(::result) }
        action(app.tr("ارسال برگه‌های تصحیح‌شده","تصحیح شوې پاڼې ولېږئ")) { app.sendImages(::result) }
        action("تازه‌سازی رسیدهای ارسال") { app.refreshReceipts(::result) }
        if(app.scans.isEmpty())empty("هنوز برگه‌ای اسکن نشده","با دوربین یا واردکردن فایل، نخستین برگه را بخوانید.")
        val delivered=app.queue.filter { it.s("status")=="delivered" }.map { it.s("id") }.toSet()
        app.scans.take(limit).forEach { scan ->
            val title=scan.s("studentName").ifEmpty { "برگهٔ ${scan.s("id").take(6)}" }
            val status=when {
                scan.s("scanId") in delivered && app.hasUploadedImage(scan) -> "ارسال کامل شد"
                scan.s("scanId") in delivered -> "پاسخ‌ها ارسال شد؛ برگهٔ تصحیح‌شده در انتظار ارسال"
                app.hasUploadedImage(scan) -> "برگهٔ تصحیح‌شده ارسال شد؛ پاسخ‌ها در انتظار ارسال"
                else -> when(scan.s("state")) { "capturing"->"در حال ذخیرهٔ عکس";"processing"->"موتور در حال بررسی برگه";"capture_failed"->"گرفتن عکس کامل نشد";"ready"->if(scan.optBoolean("sealed"))"درخواست امضا شده" else "آمادهٔ ارسال";"unsupported_sheet"->"نوع برگه پشتیبانی نمی‌شود";"identity_required"->"نیاز به شناسایی QR";"read_failed"->"خوانش کامل نشد";else->"در انتظار خوانش" }
            }
            val score=scan.o("offlineScore").let { if(it.has("correctCount"))" • ${it.optInt("correctCount")} پاسخ درست موقت" else "" }
            val pending=if(scan.optBoolean("sealed"))0 else ScanReview.pendingCount(scan)
            val review=if(pending>0)app.tr("$pending پاسخ نیازمند بازبینی؛ پاسخ‌ها ارسال نشده‌اند","$pending ځوابونه کتنې ته اړتیا لري؛ ځوابونه نه دي لېږل شوي") else ""
            item(title,listOf(if(pending>0 && status=="آمادهٔ ارسال")"" else status,review).filter { it.isNotBlank() }.joinToString(" • ")+score,0xe23a) {
                present("review",json("id" to scan.s("id"),"needsReviewOnly" to (pending>0)))
            }
            app.imageErrors[scan.s("scanId")]?.let { note(app.tr("ارسال عکس کامل نشد: $it","د انځور لېږل بشپړ نه شول: $it"),true) }
            deleteScanAction(scan)
        }
        if(app.scans.size>limit)action("نمایش بیشتر") { limit+=100;rerender() }
        content.space(12);action("خروجی نتیجه‌ها") { app.exportHistory(::result) }
    }
}
private class ReviewFragment(activity:MainActivity,route:Route):PageFragment(activity,route,"بازبینی برگه") {
    private var showOriginal=route.args.optBoolean("original")
    private var onlyNeedsReview=route.args.optBoolean("needsReviewOnly")
    override fun render() {
        val scan=app.scans.singleOrNull { it.s("id")==route.args.s("id") } ?: return empty("برگه پیدا نشد","به فهرست نتیجه‌ها برگردید.")
        item(scan.s("studentName","پاسخ‌نامه"),scan.s("studentPublicId"),0xe23a)
        deleteScanAction(scan,closeOnDelete=true)
        scan.s("submissionError").takeIf { it.isNotBlank() }?.let { note(it,true) }
        val colored=GradedSheet.image(scan)
        val preview=SheetPreviewView(activity)
        content.addView(preview,LinearLayout.LayoutParams(-1,activity.dp(410)));Thumbs.load(preview,if(showOriginal || colored==null)scan.s("sourcePath") else colored,2200)
        if(colored!=null) {
            note(if(showOriginal)"عکس اصلی محفوظ است." else app.tr("سبز با تیک: پاسخ درست. سرخ: پاسخ غلط یا چندعلامتی؛ گزینهٔ درستِ آن سؤال حلقهٔ سبز دارد. زرد: گزینهٔ درستِ سؤال سفید. نمره پایین برگه نوشته شده است. خوانش نامشخص نیاز به بازبینی دارد.","شین له نښې سره: سم ځواب. سور: ناسم یا څو نښې لرونکی ځواب؛ د هغې پوښتنې سم غوراوی شین کړۍ لري. ژېړ: د تشې پوښتنې سم غوراوی. نمره د پاڼې لاندې لیکل شوې. ناڅرګنده لوستنه کتنې ته اړتیا لري."))
            action(if(showOriginal)"دیدن برگهٔ رنگی" else "دیدن عکس اصلی") { showOriginal=!showOriginal;rerender() }
            action("ذخیرهٔ برگهٔ رنگی در گوشی") { app.native.call("saveFile",mapOf("path" to colored,"mimeType" to "image/jpeg","fileName" to "roshd-graded-${scan.s("id")}.jpg")) { result(it.map { saved -> if(saved==true)"برگهٔ رنگی ذخیره شد." else "" }) } }
            action("اشتراک‌گذاری برگهٔ رنگی") { app.native.call("shareFile",mapOf("path" to colored,"mimeType" to "image/jpeg")) { result(it.map { "" }) } }
        }
        if(scan.s("state")=="ready")action(if(colored==null)"ساخت برگهٔ رنگی" else "ساخت دوبارهٔ برگهٔ رنگی") { app.createColoredSheet(scan.s("id"),::result) }
        scan.s("annotationWarning").takeIf { it.isNotEmpty() }?.let { note(it,true) }
        scan.s("error").takeIf { it.isNotEmpty() }?.let { note(it,true) }
        val score=GradedSheet.displayScore(scan)
        LiveScanFeedback.from(scan).rawScore?.let { item(app.tr("نمرهٔ موقت: $it","لنډمهاله نمره: $it"),app.tr("محاسبهٔ آفلاین از کلید معتبر امتحان","د ازموینې له باوري کیلي څخه افلاین محاسبه"),0xe158) }
        if(score.has("correctCount"))item("${score.optInt("correctCount")} پاسخ درست از ${score.optInt("questionCount")}","محاسبهٔ موقت آفلاین؛ نمرهٔ رسمی سرور جدا است.",0xe158)
        scan.s("scoreWarning").takeIf { it.isNotEmpty() }?.let { note(it,true) }
        scan.s("reviewWarning").takeIf { it.isNotEmpty() }?.let { note(it,true) }
        val delivered=app.queue.singleOrNull { it.s("id")==scan.s("scanId") }
        if(delivered!=null)item(statusLabel(delivered.s("status")),delivered.s("error"),0xe186)
        if(app.hasUploadedImage(scan))note(app.tr("نسخهٔ صاف‌شده و تصحیح‌شده تأیید سرور دارد.","هواره او تصحیح شوې نسخه د سرور تایید لري."))
        app.imageErrors[scan.s("scanId")]?.let { note(it,true) }
        if(scan.s("examId").isNotEmpty() && !app.hasUploadedImage(scan)) {
            action(app.tr("ارسال نسخهٔ صاف‌شده و تصحیح‌شده","هواره او تصحیح شوې نسخه ولېږئ")) {
                if(app.session?.valid==false)present("login") else app.sendImage(scan.s("id"),::result)
            }
        }
        if(!scan.optBoolean("sealed") && scan.s("state") !in setOf("capturing","processing")) {
            action("خوانش دوباره") { app.retryScan(scan.s("id"),::result) }
            action("تنظیم گوشه‌ها و صاف‌سازی برگه") { present("editor",json("scanId" to scan.s("id"))) }
            if(scan.s("state")=="ready")action("محاسبهٔ نمرهٔ موقت") { app.recalculateScore(scan.s("id"),::result) }
        }
        if(scan.optBoolean("sealed"))note("این نتیجه امضا شده و برای حفظ سابقه قابل تغییر نیست.")
        label("پاسخ‌های خوانده‌شده")
        note(app.tr("پاسخ سفید نیاز به بازبینی ندارد. چند گزینهٔ علامت‌خورده، پاسخ غلط محسوب می‌شود.","تش ځواب کتنې ته اړتیا نه لري. څو نښه شوي غوراوي ناسم ځواب ګڼل کېږي."))
        val pending=ScanReview.pendingCount(scan)
        if(pending>0 || onlyNeedsReview)action(if(onlyNeedsReview)"نمایش همهٔ سؤال‌ها" else "فقط سؤال‌های نیازمند بازبینی ($pending)") { onlyNeedsReview=!onlyNeedsReview;rerender() }
        if(pending>0)note("عکس را با پاسخ مقایسه کنید و برای تأیید، روی گزینهٔ درست یا «سفید» بزنید. نمره تا پایان بازبینی موقت است.")
        scan.a("answers").objects().filter { !onlyNeedsReview || ScanReview.needsReview(it) }.forEach { answer ->
            val number=answer.optInt("questionNumber");val line=row(activity)
            if(answer.s("state")=="multiple")note(app.tr("سؤال $number: چندعلامتی، پاسخ غلط","پوښتنه $number: څو نښې، ناسم ځواب"),true)
            if(ScanReview.needsReview(answer)) {
                val reason=when(answer.s("reviewReason")) {
                    "ink_visibility_recovered"->app.tr("علامت کم‌رنگ یا رنگی بازیابی شده است.","کم رنګه یا رنګین نښان بېرته لوستل شوی دی.")
                    "ink_readings_disagree"->app.tr("خوانش‌های رنگ و کنتراست یکسان نیستند.","د رنګ او روښانتیا لوستنې یو شان نه دي.")
                    "multiple_visible_marks"->app.tr("بیش از یک علامت دیده می‌شود.","له یوه ډېر نښانونه لیدل کېږي.")
                    "faint_ink_candidate","faint_ink_in_blank_candidate"->app.tr("اثر کم‌رنگی در گزینه‌ها دیده می‌شود.","په غوراویو کې کم رنګه نښان لیدل کېږي.")
                    else->app.tr("خوانش کم‌اطمینان است.","لوستنه پوره باوري نه ده.")
                }
                note(app.tr("سؤال $number: $reason پاسخ را از روی عکس تأیید کنید.","پوښتنه $number: $reason ځواب له انځور سره پرتله او تایید کړئ."),true)
            }
            line.addView(text(activity,theme,number.toString(),14f,true).apply { gravity=Gravity.CENTER },LinearLayout.LayoutParams(activity.dp(32),activity.dp(48)))
            for(option in 1..4)line.weight(button(activity,theme,option.toString(),answer.optInt("selectedOption")==option) {
                if(!scan.optBoolean("sealed"))app.editAnswer(scan.s("id"),number,option,::result)
            }.apply { minHeight=activity.dp(48);setPadding(activity.dp(8),activity.dp(10),activity.dp(8),activity.dp(10));isEnabled=!scan.optBoolean("sealed") })
            content.full(line)
            if(!scan.optBoolean("sealed")) {
                val blank=text(activity,theme,"سؤال $number: سفید / پاک‌کردن پاسخ",12f,muted=true).apply { minHeight=activity.dp(44);gravity=Gravity.CENTER_VERTICAL;isClickable=true;isFocusable=true;setOnClickListener { app.editAnswer(scan.s("id"),number,null,::result) } }
                content.full(blank)
            }
        }
    }
}

private class ArchiveFragment(activity:MainActivity,route:Route):PageFragment(activity,route,"آرشیف برگه‌ها") {
    private var limit=100
    override fun render() {
        note("عکس‌های آرشیف را جداگانه مرور یا حذف کنید. سابقهٔ نتیجه و صف ارسال محفوظ می‌ماند.")
        action(app.tr("سال‌های گذشته","تېر کلونه")) { present("academic-archive") }
        action("واردکردن عکس یا PDF") { importDocuments() }
        val archivedScans=app.archiveScans
        val extras=app.archive.filter { file -> app.scans.none { it.s("sourcePath")==file.s("sourcePath") } }
        if(archivedScans.isEmpty() && extras.isEmpty())empty("آرشیف خالی است","برگه‌های اسکن‌شده در اینجا نگهداری می‌شوند.")
        extras.take(limit).forEach { file ->
            item(file.s("name","فایل آرشیف"),file.s("mimeType"),if(file.s("mimeType")=="application/pdf")0xe702 else 0xe2ca) { present("file",json("id" to file.s("id"))) }
            deleteButton(file.s("id"),file.s("name","فایل آرشیف"))
        }
        archivedScans.take(limit).forEach { scan ->
            val panel=ClayPanel(activity,theme);val line=row(activity)
            val image=ImageView(activity).apply { scaleType=ImageView.ScaleType.CENTER_CROP;contentDescription="پیش‌نمایش پاسخ‌نامه" }
            line.addView(image,LinearLayout.LayoutParams(activity.dp(62),activity.dp(82)));Thumbs.load(image,scan.s("sourcePath"),220)
            val words=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL;setPadding(activity.dp(14),0,activity.dp(14),0);full(text(activity,theme,scan.s("studentName","پاسخ‌نامه"),15f,true));full(text(activity,theme,"عکس اصلی • ${scan.s("createdAt").take(16).replace('T',' ')}",12f,muted=true)) }
            line.weight(words);panel.full(line);panel.setOnClickListener { present("review",json("id" to scan.s("id"),"original" to true)) };panel.isFocusable=true;content.full(panel)
            deleteButton(scan.s("id"),scan.s("studentName","پاسخ‌نامه"))
        }
        if(archivedScans.size>limit || extras.size>limit)action("نمایش بیشتر") { limit+=100;rerender() }
    }
    private fun deleteButton(id:String,name:String) {
        content.full(button(activity,theme,app.tr("حذف از آرشیف","له ارشیف څخه ړنګول")) {
            AlertDialog.Builder(activity).setTitle(app.tr("حذف از آرشیف","له ارشیف څخه ړنګول"))
                .setMessage(app.tr("«$name» از آرشیف حذف شود؟ سابقهٔ نتیجه و فایل لازم برای بازبینی و ارسال باقی می‌ماند.","«$name» له ارشیف څخه ړنګ شي؟ د پایلې سابقه او د کتنې او لېږلو لپاره اړین فایل پاتې کېږي."))
                .setNegativeButton(app.tr("بازگشت","بېرته"),null)
                .setPositiveButton(app.tr("حذف","ړنګول")) { _,_ -> app.deleteArchivePhoto(id,::result) }.showThemed(theme)
        }.apply { tag="archive-delete:$id";contentDescription=app.tr("حذف $name از آرشیف","له ارشیف څخه $name ړنګول");setTextColor(theme.danger) })
        content.space(8)
    }
}

private class SettingsFragment(activity:MainActivity,route:Route):PageFragment(activity,route,"تنظیمات") {
    override fun render() {
        label("نمایش و زبان")
        val dark=Switch(activity).apply {
            tag="setting:dark";text=I18n.phrase(activity,"حالت تاریک");isChecked=app.darkMode
            typeface=ClayTheme.regular(activity);textSize=16f;setTextColor(theme.ink);minHeight=activity.dp(56)
            thumbTintList=theme.controlsTint();trackTintList=android.content.res.ColorStateList.valueOf(theme.border)
            setPadding(activity.dp(8),0,activity.dp(8),0);setOnCheckedChangeListener { _,checked -> app.setDark(checked) }
        };styleStudySwitch(activity,theme,dark);content.full(dark)
        action(if(app.language=="fa")"زبان: دری • تغییر به پښتو" else "ژبه: پښتو • دری ته بدلول") { app.setLanguage(if(app.language=="fa")"ps" else "fa") }
        if(app.session==null) {
            action(app.tr("بازگشت به ورود","ننوتلو ته ستنېدل")) { finishFragment() }
            return
        }
        item(app.session?.name ?: "حساب کاربری",if(app.session?.manager==true)"مدیر مکتب" else "اپراتور اسکن",0xe4d0)
        label("حساب و معلومات")
        action(app.tr("ورود دوباره به همین حساب","همدې حساب ته بیا ننوتل")) { present("login") }
        action("تازه‌سازی سال و تنظیمات") { app.refreshConfiguration(::result) }
        action("ثبت و وضعیت دستگاه") { present("device") }
        action("خروجی نتیجه‌ها") { app.exportHistory(::result) }
        if(app.session?.manager==true)action("بخش مدیر مکتب") { present("manager") }
        action("بررسی نسخهٔ جدید") { app.checkUpdate(::result) }
        action("دانلود و نصب نسخهٔ جدید") { app.installUpdate(::result) }
        action("پاکسازی فایل‌های موقت") { app.clearTemporaryFiles(::result) }
        item("رشد","نسخهٔ ${BuildConfig.VERSION_NAME}",0xe2ce)
        action("خروج از حساب") {
            AlertDialog.Builder(activity).setTitle("خروج از حساب").setMessage("نتیجه‌ها و صف‌های محفوظ در گوشی باقی می‌مانند.").setNegativeButton("بازگشت",null).setPositiveButton("خروج") { _,_ -> app.logout(::result) }.showThemed(theme)
        }
    }
}

private class ManagerFragment(activity:MainActivity,route:Route):PageFragment(activity,route,"بخش مدیر مکتب") {
    override fun render() {
        item("فهرست شاگردان","دریافت فایل ثبت‌نام شاگردان",0xe4d0) { app.downloadStudents(::result) }
        item("بررسی فایل شاگردان","انتخاب فایل و دریافت خطاهای سرور",0xe23a) {
            val directory=File(activity.cacheDir,"native-imports").apply { mkdirs() }
            app.native.call("pickDocuments",mapOf("targetDirectory" to directory.path,"maxBytes" to 2L*1024*1024,"studentFile" to true)) { value ->
                value.onSuccess { rows -> val path=((rows as? List<*>)?.firstOrNull() as? Map<*,*>)?.get("path")?.toString();if(path!=null)app.downloadStudentErrors(File(path),::result) }.onFailure { result(Result.failure(it)) }
            }
        }
        item("نتیجه‌های رسمی","نمره‌ها و تاریخچهٔ شاگردان",0xe158) { present("report",json("kind" to "results")) }
        item("اطلاع‌نامه‌ها","دریافت امن و ساخت فایل در گوشی",0xe702) { present("notices") }
    }
}
private fun EditText.watch(callback:(String)->Unit) { addTextChangedListener(object:TextWatcher {
    override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int) {}
    override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) { callback(s?.toString() ?: "") }
    override fun afterTextChanged(value:Editable?) {}
}) }

