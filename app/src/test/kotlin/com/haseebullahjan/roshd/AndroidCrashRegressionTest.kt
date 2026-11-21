package com.haseebullahjan.roshd

import android.graphics.Bitmap
import android.os.Looper
import android.view.View
import com.haseebullahjan.roshd.ui.components.SheetPreviewView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** Runs Android constructors and lifecycle on the JVM without an emulator or a device. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class AndroidCrashRegressionTest {
    @Test fun scannerSwitchesFortySeventyFiveAndHundredInPlaceWithOneSelection() {
        val application=RuntimeEnvironment.getApplication() as com.haseebullahjan.roshd.messenger.ApplicationLoader
        val app=application.controller
        val prefs=com.haseebullahjan.roshd.messenger.NativeSettings.open(application)
        val oldSelection=prefs.all["scannerQuestionCount"]
        val session=app.session;val round=app.round;val configuration=app.configuration;val packages=app.packages
        val type=com.haseebullahjan.roshd.messenger.ExamsController::class.java
        fun set(name:String,value:Any?) { type.getDeclaredField(name).apply { isAccessible=true }.set(app,value) }
        fun views(view:View):List<View> = listOf(view)+(if(view is android.view.ViewGroup)
            (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList())
        set("session",com.haseebullahjan.roshd.core.Session(org.json.JSONObject().put("accountId","qa").put("schoolId","qa"),"https://example.org"))
        set("round",1)
        set("configuration",org.json.JSONObject().put("currentAcademicYear",org.json.JSONObject().put("editionId","edition").put("openRounds",org.json.JSONArray(listOf(1)))))
        val pack=com.haseebullahjan.roshd.core.OfflineExam(
            com.haseebullahjan.roshd.core.Exam(org.json.JSONObject().put("id","exam").put("round",1).put("editionId","edition")),
            org.json.JSONObject(),org.json.JSONObject().put("expiresAt",java.time.Instant.now().plusSeconds(3600).toString()),
            listOf(com.haseebullahjan.roshd.core.RosterRow("123456","alias","sheet","card","Student","exam")))
        set("packages",mapOf("exam" to pack))
        try {
            Robolectric.buildActivity(com.haseebullahjan.roshd.ui.camera.ScannerActivity::class.java).use { lifecycle ->
                lifecycle.setup()
                val children=views(lifecycle.get().window.decorView)
                for(count in listOf(40,75,100))assertEquals(1,children.count { it.tag=="scanner-choose-$count" })
                val preview=children.filterIsInstance<androidx.camera.view.PreviewView>().single()
                assertEquals(1,children.count { it.tag=="scanner-sheet-segments" })
                val segments=children.filterIsInstance<android.widget.RadioButton>()
                for(count in listOf(75,100,40)) {
                    segments.single { it.tag=="scanner-choose-$count" }.performClick()
                    assertEquals(1,segments.count { it.isChecked })
                    assertEquals("scanner-choose-$count",segments.single { it.isChecked }.tag)
                    assertEquals(count,prefs.getInt("scannerQuestionCount",0))
                    assertSame(preview,views(lifecycle.get().window.decorView).filterIsInstance<androidx.camera.view.PreviewView>().single())
                }
                assertTrue((children.single { it.tag=="scanner-choose-75" } as android.widget.TextView).text.contains("۷۵"))
                application.assets.open("images/omr-75-preview.png").use {
                    val bitmap=android.graphics.BitmapFactory.decodeStream(it)
                    assertNotNull(bitmap);assertTrue(bitmap.width>100 && bitmap.height>100);bitmap.recycle()
                }
            }
        } finally {
            set("session",session);set("round",round);set("configuration",configuration);set("packages",packages)
            prefs.edit().apply { if(oldSelection is Int)putInt("scannerQuestionCount",oldSelection) else remove("scannerQuestionCount") }.commit()
        }
    }
    @Test fun bitmapPreviewSurvivesConstructionLayoutReplacementAndClearing() {
        val view=SheetPreviewView(RuntimeEnvironment.getApplication())
        view.setImageBitmap(Bitmap.createBitmap(800,1200,Bitmap.Config.ARGB_8888))
        view.measure(View.MeasureSpec.makeMeasureSpec(400,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(600,View.MeasureSpec.EXACTLY))
        view.layout(0,0,400,600)
        assertNotNull(view.drawable)
        view.setImageBitmap(null)
        view.setImageBitmap(Bitmap.createBitmap(1200,800,Bitmap.Config.ARGB_8888))
        assertNotNull(view.drawable)
    }
    @Test fun studyStartupAndLifecycleDoNotCrash()=startup("study")
    @Test fun legacyOrganicPreferenceDoesNotBreakStartup()=startup("organic")
    private fun startup(style:String) {
        val app=RuntimeEnvironment.getApplication()
        app.getSharedPreferences("roshd_native_settings",0).edit().putString("visualStyle",style).commit()
        Robolectric.buildActivity(MainActivity::class.java).use { controller ->
            controller.setup()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
            assertNotNull(controller.get().window.decorView)
            controller.pause().stop().start().resume().visible()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
        }
    }
}
