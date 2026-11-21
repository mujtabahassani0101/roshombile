package com.haseebullahjan.roshd.qa

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import com.haseebullahjan.roshd.MainActivity
import com.haseebullahjan.roshd.core.*
import com.haseebullahjan.roshd.messenger.*
import com.haseebullahjan.roshd.ui.actionbar.Route
import com.haseebullahjan.roshd.ui.components.*
import java.io.File
import java.time.Instant

/** Offline, isolated QA-package visual checks. No production credentials or server calls. */
class ThemeUiChecks(private val runner:Instrumentation) {
    private val context=runner.targetContext
    private val prefs=context.getSharedPreferences("roshd_native_settings",0)
    private val app get()=(context.applicationContext as ApplicationLoader).controller
    private val report=StringBuilder()
    private var checks=0
    private var activity:MainActivity?=null
    private fun verify(value:Boolean,message:String) { check(value) { message };checks++;report.append("PASS: $message\n") }
    private fun main(block:()->Unit) {
        var failure:Throwable?=null
        runner.runOnMainSync { try { block() } catch(error:Throwable) { failure=error } }
        failure?.let { throw it }
    }
    private fun idle() { runner.waitForIdleSync();Thread.sleep(180);runner.waitForIdleSync() }
    private fun views(v:View):List<View> = listOf(v)+(if(v is ViewGroup)(0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList())
    private fun set(name:String,value:Any?) { ExamsController::class.java.getDeclaredField(name).apply { isAccessible=true;set(app,value) } }
    private fun launch():MainActivity {
        val fresh=runner.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        activity=fresh
        repeat(100) { if(app.busy)Thread.sleep(50) }
        idle();return fresh
    }
    private fun capture(name:String) {
        idle()
        val image=runner.uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
        val out=File(context.filesDir,"qa/themes/$name.png");out.parentFile!!.mkdirs()
        out.outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) };image.recycle()
        main {
            views(activity!!.window.decorView).firstOrNull { it.tag?.toString()?.startsWith("dock-style:")==true && it.isShown }?.let { dock ->
                val position=IntArray(2);dock.getLocationOnScreen(position)
                File(out.parentFile,"$name-dock.json").writeText(json("left" to position[0],"top" to position[1],"width" to dock.width,"height" to dock.height).toString())
            }
        }
    }
    private fun toggleNight():MainActivity {
        val monitor=runner.addMonitor(MainActivity::class.java.name,null,false)
        main { views(activity!!.window.decorView).single { it.tag=="setting:dark" }.performClick() }
        val fresh=runner.waitForMonitorWithTimeout(monitor,5000) as? MainActivity ?: error("Night switch did not recreate activity")
        runner.removeMonitor(monitor);activity=fresh;idle();return fresh
    }
    private fun fixtureOwner() {
        val owner=Session(json("accountId" to "20000000-0000-0000-0000-000000000001","schoolId" to "10000000-0000-0000-0000-000000000001","role" to "SchoolManager","displayName" to "کاربر آزمایشی","expiresAt" to Instant.EPOCH.toString()),"https://example.org")
        val year=json("academicYearKey" to "۱۴۰۵","label" to "سال تعلیمی ۱۴۰۵","openRounds" to listOf(1,2,3))
        set("session",owner);set("configuration",json("currentAcademicYear" to year));set("round",1)
        set("exams",listOf(Exam(json("id" to "50000000-0000-0000-0000-000000000001","schoolId" to owner.schoolId,"name" to "امتحان علوم صنف ششم","round" to 1,"grade" to 6,"questionCount" to 40))))
    }
    private fun dockChecks(fresh:MainActivity,mode:String) {
        for(route in listOf("history","archive","settings","home")) {
            main { views(fresh.window.decorView).single { it.tag=="dock:$route" }.performClick() };idle()
            capture("$mode-dock-$route")
            main {
                val tabs=views(fresh.window.decorView).filter { it.tag in setOf("dock:home","dock:history","dock:archive","dock:settings") }
                verify(fresh.navigation.current?.name==route && tabs.count { it.isSelected }==1 && tabs.single { it.isSelected }.tag=="dock:$route","$mode $route tap opens its real route with exactly one selected destination")
                val selected=tabs.single { it.isSelected }
                val node=selected.createAccessibilityNodeInfo()
                verify(node.isSelected && node.className==android.widget.Button::class.java.name && !node.contentDescription.isNullOrBlank(),"$mode $route exposes its selected state and name to accessibility")
                verify(selected is android.widget.TextView && selected.background!=null,"$mode $route uses the compact selected pill")
                verify(tabs.all { it.width>=context.dp(48) && it.height>=context.dp(48) },"$mode $route retains four 48dp touch targets")
            }
        }
    }
    fun run(dockOnly:Boolean=false):String {
        val original=mapOf("style" to prefs.getString("visualStyle",null),"dark" to prefs.getBoolean("dark",false),"language" to prefs.getString("language","fa"))
        var saved=emptyMap<String,Any?>()
        try {
            prefs.edit().putString("visualStyle","organic").putBoolean("dark",false).putString("language","fa").commit()
            var fresh=launch()
            saved=mapOf("session" to app.session,"configuration" to app.configuration,"exams" to app.exams,"round" to app.round)
            main { set("session",null);fresh.navigation.reset(Route("login")) };idle()
            verify(fresh.navigation.current?.name=="login","An upgraded installation opens login without a theme picker")
            main {
                val checkbox=views(fresh.window.decorView).filterIsInstance<android.widget.CheckBox>().single()
                val password=views(fresh.window.decorView).filterIsInstance<android.widget.EditText>().single { !it.isSaveEnabled }
                checkbox.performClick();verify(password.transformationMethod==null,"The reveal control shows the password")
                checkbox.performClick();verify(password.transformationMethod is android.text.method.PasswordTransformationMethod,"The reveal control restores password masking")
            }
            capture("study-light-login")
            var dialog:android.app.AlertDialog?=null
            main { dialog=android.app.AlertDialog.Builder(fresh).setTitle("معلومات محفوظ").setMessage("عکس‌ها و نتیجه‌ها در گوشی محفوظ است.").setPositiveButton("بستن",null).showThemed(ClayTheme(false)) };idle()
            main {
                verify(dialog!!.getButton(android.app.AlertDialog.BUTTON_POSITIVE).currentTextColor==ClayTheme(false).onAccent,"Dialog actions use readable Study colors")
                dialog!!.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
                fresh.navigation.presentFragment(Route("settings"))
            };idle()
            verify(views(fresh.window.decorView).none { it.tag?.toString()?.startsWith("theme:")==true },"Settings exposes only appearance and language controls")
            fresh=toggleNight();verify(app.darkMode && fresh.navigation.current?.name=="settings","Night mode retains settings navigation")
            fresh=toggleNight();verify(!app.darkMode && fresh.navigation.current?.name=="settings","Light mode retains settings navigation")
            for(dark in listOf(false,true)) {
                main { activity?.finish() };idle()
                prefs.edit().putBoolean("dark",dark).putString("language","fa").commit();fresh=launch()
                main { fixtureOwner();fresh.navigation.reset(Route("home")) };idle()
                val mode="study-${if(dark)"dark" else "light"}"
                verify(views(fresh.window.decorView).any { it.tag=="brand:study" },"$mode retains the original app identity")
                verify(views(fresh.window.decorView).none { it.tag?.toString()?.let { tag -> "organic" in tag || tag.startsWith("theme:") }==true },"$mode has no obsolete visual branch")
                if(!dockOnly) {
                    Thread.sleep(250);idle()
                    val glass=views(fresh.window.decorView).filterIsInstance<StudyGlassDock>().single()
                    val before=glass.renderedFrames;val signature=glass.sourceFingerprint
                    verify(before>0,"$mode renders the real page through AndroidLiquidGlass")
                    main { views(fresh.window.decorView).filterIsInstance<ScrollView>().single().scrollTo(0,context.dp(260)) }
                    Thread.sleep(250);idle()
                    verify(glass.renderedFrames>before && glass.sourceFingerprint!=signature,"$mode scrolling updates the refracted backdrop")
                    val settled=glass.renderedFrames;Thread.sleep(250);idle()
                    verify(glass.renderedFrames==settled,"$mode stops rendering when stationary")
                    main { views(fresh.window.decorView).filterIsInstance<ScrollView>().single().scrollTo(0,0) };idle()
                }
                capture("$mode-home");dockChecks(fresh,mode)
                val expected=ClayTheme(dark)
                val pairs=listOf(expected.ink to expected.surface,expected.muted to expected.surface,expected.muted to expected.canvas,expected.onAccent to expected.primary,expected.dockMuted to expected.dock,expected.dockSelectedInk to expected.dockSelected)
                verify(pairs.all { androidx.core.graphics.ColorUtils.calculateContrast(it.first,it.second)>=4.5 },"$mode text contrast meets 4.5:1")
                if(!dockOnly)for(route in listOf("exams","exam","history","review","archive","file","manager","report","notices","device","settings")) {
                    main { fresh.navigation.presentFragment(Route(route)) };idle()
                    verify(views(fresh.window.decorView).filterIsInstance<ClayPanel>().all { it.theme==expected },"$mode $route shares the same theme")
                    if(route=="exams")main {
                        val search=views(fresh.window.decorView).single { it.tag=="exam-search" } as android.widget.EditText
                        search.setText("وجودندارد")
                        verify(views(fresh.window.decorView).filterIsInstance<android.widget.TextView>().none { it.text=="امتحان علوم صنف ششم" },"$mode exam search filters rows")
                        search.setText("علوم")
                        verify(views(fresh.window.decorView).filterIsInstance<android.widget.TextView>().any { it.text=="امتحان علوم صنف ششم" },"$mode exam search restores matching rows")
                    }
                    if(route in setOf("settings","history","exams","archive"))capture("$mode-$route")
                }
                main {
                    fresh.navigation.reset(Route("home"))
                    val bounds=Rect();val screen=fresh.resources.displayMetrics.widthPixels
                    verify(views(fresh.window.decorView).all { !it.getGlobalVisibleRect(bounds) || (bounds.left>=0 && bounds.right<=screen) },"$mode content stays within the screen")
                    val controls=views(fresh.window.decorView).filter { it.isClickable && it.visibility==View.VISIBLE && it.height>0 }
                    verify(controls.all { it.height>=context.dp(48) },"$mode home controls retain 48dp touch targets")
                }
            }
            main { activity?.finish();set("session",null) };idle()
            prefs.edit().putBoolean("dark",true).putString("language","ps").commit();fresh=launch()
            verify(app.darkMode && app.language=="ps","Language and night mode survive relaunch")
            verify(views(fresh.window.decorView).filterIsInstance<android.widget.TextView>().any { it.text=="ښه راغلاست" },"Pashto login renders in the unified theme")
            capture("study-dark-pashto-login")
            main { fixtureOwner();fresh.navigation.reset(Route("home")) };idle();dockChecks(fresh,"study-dark-pashto")
            return "$report$checks single-theme checks passed.\n"
        } finally {
            main { activity?.finish();saved.forEach { (key,value) -> set(key,value) } }
            prefs.edit().apply {
                val style=original["style"] as? String;if(style==null)remove("visualStyle") else putString("visualStyle",style)
                putBoolean("dark",original["dark"] as Boolean);putString("language",original["language"] as String)
            }.commit()
        }
    }
}
