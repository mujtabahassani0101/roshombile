package com.haseebullahjan.roshd

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import com.haseebullahjan.roshd.core.*
import com.haseebullahjan.roshd.messenger.*
import com.haseebullahjan.roshd.ui.actionbar.Route
import com.haseebullahjan.roshd.ui.components.ClayPanel
import com.haseebullahjan.roshd.ui.components.ClayTheme
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Upgrade and navigation regressions caused by removing the second visual system. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class SingleThemeRegressionTest {
    private val context get()=RuntimeEnvironment.getApplication() as ApplicationLoader
    private val app get()=context.controller
    private fun set(name:String,value:Any?) { ExamsController::class.java.getDeclaredField(name).apply { isAccessible=true;set(app,value) } }
    private fun views(view:View):List<View> = listOf(view)+(if(view is ViewGroup)(0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList())
    private fun prepare(style:String?=null,dark:Boolean=false,language:String="fa",signedIn:Boolean=false) {
        NativeSettings.open(context).edit().clear().apply { if(style!=null)putString("visualStyle",style) }.putBoolean("dark",dark).putString("language",language).commit()
        set("initialized",true)
        set("session",if(signedIn)Session(json("accountId" to "qa","schoolId" to "qa","role" to "SchoolManager","displayName" to "کاربر آزمایشی"),"https://example.org") else null)
    }
    private fun assertStudy(activity:MainActivity,dark:Boolean) {
        val children=views(activity.window.decorView)
        assertTrue(children.any { it.tag=="header:study" })
        assertTrue(children.none { it.tag?.toString()?.let { tag -> "organic" in tag || tag.startsWith("theme:") }==true })
        val colors=activity.obtainStyledAttributes(intArrayOf(android.R.attr.colorAccent))
        try { assertEquals(ClayTheme(dark).accent,colors.getColor(0,0)) } finally { colors.recycle() }
        assertTrue(children.filterIsInstance<ClayPanel>().all { it.theme==ClayTheme(dark) })
    }
    @Test fun freshInstallOpensLoginAndSettingsWithoutAThemeQuestion() {
        prepare()
        Robolectric.buildActivity(MainActivity::class.java).use { lifecycle ->
            lifecycle.setup();val activity=lifecycle.get()
            assertEquals("login",activity.navigation.current?.name);assertStudy(activity,false)
            activity.navigation.presentFragment(Route("settings"))
            assertStudy(activity,false)
            assertEquals(1,views(activity.window.decorView).count { it.tag=="setting:dark" })
            assertTrue(activity.navigation.closeLastFragment())
            assertEquals("login",activity.navigation.current?.name)
            assertFalse(activity.navigation.closeLastFragment())
        }
    }
    @Test fun legacyPreferencesAlwaysUseStudyAndKeepLanguageNightModeAndAccountData() {
        for(style in listOf("organic","study","invalid-style"))for(dark in listOf(false,true)) {
            prepare(style,dark,"ps",signedIn=true)
            val owner=app.session
            val scan=json("id" to "retained","studentName" to "محفوظ","state" to "ready")
            set("scans",listOf(scan))
            Robolectric.buildActivity(MainActivity::class.java).use { lifecycle ->
                lifecycle.setup();val activity=lifecycle.get()
                assertEquals("home",activity.navigation.current?.name);assertStudy(activity,dark)
                assertSame(owner,app.session);assertEquals("retained",app.scans.single().s("id"))
                assertEquals("ps",app.language);assertEquals(dark,app.darkMode)
                val children=views(activity.window.decorView)
                assertTrue(children.any { it.tag=="dock-style:study" })
                assertEquals(4,children.count { it.tag?.toString()?.startsWith("home:section:")==true })
                activity.navigation.presentFragment(Route("history"))
                assertStudy(activity,dark)
                assertTrue(views(activity.window.decorView).any { it.tag=="scan-delete:retained" })
                activity.navigation.presentFragment(Route("archive"));assertStudy(activity,dark)
            }
        }
    }
    @Test fun savedThemeChooserStackResumesAtTheAccountDestination() {
        for(signedIn in listOf(false,true)) {
            prepare("organic",signedIn=signedIn)
            val state=Bundle().apply { putString("navigation",JSONArray(listOf(json("name" to "theme-choice","args" to json()))).toString()) }
            Robolectric.buildActivity(MainActivity::class.java).use { lifecycle ->
                lifecycle.create(state).start().resume().visible()
                assertEquals(if(signedIn)"home" else "login",lifecycle.get().navigation.current?.name)
                assertStudy(lifecycle.get(),false)
                assertFalse(lifecycle.get().navigation.closeLastFragment())
            }
        }
    }
    @Test fun existingBackStackStillRestoresAfterUpgrade() {
        prepare("organic",dark=true)
        val state=Bundle().apply { putString("navigation",JSONArray(listOf(json("name" to "login","args" to json()),json("name" to "settings","args" to json()))).toString()) }
        Robolectric.buildActivity(MainActivity::class.java).use { lifecycle ->
            lifecycle.create(state).start().resume().visible();val activity=lifecycle.get()
            assertEquals("settings",activity.navigation.current?.name);assertStudy(activity,true)
            assertTrue(activity.navigation.closeLastFragment())
            assertEquals("login",activity.navigation.current?.name);assertStudy(activity,true)
        }
    }
    @Test fun examSearchPaginationAndQuerySurviveNavigationInTheSingleTheme() {
        prepare("organic",signedIn=true);set("round",1)
        val exams=(1..85).map { Exam(json("id" to "exam-$it","name" to "امتحان علوم $it","round" to 1,"grade" to 6,"questionCount" to 40)) }
        set("exams",exams)
        Robolectric.buildActivity(MainActivity::class.java).use { lifecycle ->
            lifecycle.setup();val activity=lifecycle.get()
            activity.navigation.presentFragment(Route("exams"));assertStudy(activity,false)
            fun children()=views(activity.window.decorView)
            fun rows()=children().filterIsInstance<TextView>().filter { it.text.toString().startsWith("امتحان علوم ") }
            assertEquals(40,rows().size)
            children().single { it.tag=="exam-more" }.performClick();assertEquals(80,rows().size)
            val search=children().single { it.tag=="exam-search" } as EditText
            search.setText("وجودندارد");assertTrue(rows().isEmpty())
            search.setText("علوم 85");assertEquals(1,rows().size)
            assertTrue(children().none { it.tag=="exam-more" })
            activity.navigation.presentFragment(Route("settings"));assertTrue(activity.navigation.closeLastFragment())
            assertEquals("علوم 85",(children().single { it.tag=="exam-search" } as EditText).text.toString())
            assertEquals(1,rows().size)
        }
    }
}
