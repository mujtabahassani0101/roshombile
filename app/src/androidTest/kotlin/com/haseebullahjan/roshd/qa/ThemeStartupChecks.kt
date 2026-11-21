package com.haseebullahjan.roshd.qa

import android.app.Instrumentation
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.haseebullahjan.roshd.MainActivity
import com.haseebullahjan.roshd.core.Session
import com.haseebullahjan.roshd.core.json
import com.haseebullahjan.roshd.messenger.*
import com.haseebullahjan.roshd.ui.actionbar.Route
import java.time.Instant

/** Real native first-use lifecycle checks, confined to the separate .nativeqa package. */
class ThemeStartupChecks(private val runner:Instrumentation) {
    private val context=runner.targetContext
    private val prefs=NativeSettings.open(context)
    private val app get()=(context.applicationContext as ApplicationLoader).controller
    private var activity:MainActivity?=null
    private var checks=0
    private val report=StringBuilder()
    private fun verify(value:Boolean,message:String) { check(value) { message };checks++;report.append("PASS: $message\n") }
    private fun main(block:()->Unit) {
        var failure:Throwable?=null
        runner.runOnMainSync { try { block() } catch(error:Throwable) { failure=error } }
        failure?.let { throw it }
    }
    private fun idle() { runner.waitForIdleSync();Thread.sleep(180);runner.waitForIdleSync() }
    private fun views(view:View):List<View> = listOf(view)+(if(view is ViewGroup)(0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList())
    private fun live()=views(activity!!.window.decorView)
    private fun setSession(value:Session?) { ExamsController::class.java.getDeclaredField("session").apply { isAccessible=true;set(app,value) } }
    private fun launch():MainActivity {
        val fresh=runner.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        activity=fresh
        repeat(100) { if(app.busy)Thread.sleep(50) };idle();return fresh
    }
    private fun close() { main { activity?.finish() };idle();activity=null }
    private fun recreate(action:()->Unit) {
        val monitor=runner.addMonitor(MainActivity::class.java.name,null,false)
        try {
            main(action)
            activity=runner.waitForMonitorWithTimeout(monitor,5000) as? MainActivity ?: error("Activity was not recreated")
            idle()
        } finally { runner.removeMonitor(monitor) }
    }
    private fun verifyStudy() {
        main {
            verify(activity!!.navigation.current?.name==if(app.session==null)"login" else "home","Startup directly opens the account destination")
            verify(live().any { it.tag=="header:study" },"Startup uses the Study header")
            verify(live().none { it.tag?.toString()?.let { tag -> tag.startsWith("theme:") || "organic" in tag }==true },"No removed theme choice or Organic view is present")
        }
    }
    fun run():String {
        val original=prefs.all.toMap()
        var originalSession:Session?=null
        var initialized=false
        try {
            launch();originalSession=app.session;initialized=true;close()
            main { setSession(null) }
            for(legacy in listOf(null,"study","organic","invalid-style")) {
                prefs.edit().apply { if(legacy==null)remove("visualStyle") else putString("visualStyle",legacy) }.putString("language","ps").putBoolean("dark",true).commit()
                launch();verifyStudy()
                verify(app.darkMode && app.language=="ps","Legacy $legacy keeps night mode and language")
                verify(live().filterIsInstance<TextView>().any { it.text=="ښه راغلاست" },"Pashto login is available without a theme question")
                recreate { activity!!.recreate() };verifyStudy()
                main { activity!!.navigation.presentFragment(Route("settings")) };idle()
                verify(live().none { it.tag?.toString()?.startsWith("theme:")==true },"Settings contains no theme picker")
                recreate { activity!!.recreate() }
                verify(activity!!.navigation.current?.name=="settings","Recreation retains the settings route")
                main { verify(activity!!.navigation.closeLastFragment() && activity!!.navigation.current?.name=="login","Back navigation still returns to login") }
                close()
            }
            main { setSession(Session(json("accountId" to "20000000-0000-0000-0000-000000000001","schoolId" to "10000000-0000-0000-0000-000000000001","role" to "ScannerOperator","displayName" to "آزمایشی","expiresAt" to Instant.EPOCH.toString()),"https://example.org")) }
            launch();verifyStudy()
            verify(live().any { it.tag=="dock-style:study" && it.isShown },"An existing account receives the Study dock")
            main { setSession(null);app.notifications.post(AppEvent.SESSION) };idle();verifyStudy()
            return "$report$checks single-theme startup checks passed.\n"
        } finally {
            close()
            if(initialized)main { setSession(originalSession) }
            prefs.edit().apply {
                clear()
                original.forEach { (key,value) -> when(value) {
                    is String->putString(key,value);is Boolean->putBoolean(key,value)
                    is Int->putInt(key,value);is Long->putLong(key,value);is Float->putFloat(key,value)
                    is Set<*>->putStringSet(key,value.filterIsInstance<String>().toSet())
                } }
            }.commit()
        }
    }
}
