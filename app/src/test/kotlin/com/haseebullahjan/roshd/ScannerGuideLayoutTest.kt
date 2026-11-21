package com.haseebullahjan.roshd

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.camera.view.PreviewView
import com.haseebullahjan.roshd.core.*
import com.haseebullahjan.roshd.messenger.*
import com.haseebullahjan.roshd.ui.camera.ScannerActivity
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],qualifiers="mdpi")
class ScannerGuideLayoutTest {
    private fun descendants(view:View):List<View> = listOf(view)+(if(view is ViewGroup)(0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList())
    @Test fun longResultsAndProgressCannotCollapseTheCameraOrSeparateItsCompactControls() {
        val application=RuntimeEnvironment.getApplication() as ApplicationLoader;val app=application.controller
        fun set(name:String,value:Any?)=ExamsController::class.java.getDeclaredField(name).apply { isAccessible=true;set(app,value) }
        set("initialized",true)
        set("session",Session(json("accountId" to "qa","schoolId" to "qa"),"https://example.org"))
        set("configuration",json("currentAcademicYear" to json("id" to "year","editionId" to "edition","label" to "1405","openRounds" to listOf(1))))
        set("round",1)
        set("packages",mapOf("exam" to OfflineExam(Exam(json("id" to "exam","round" to 1,"editionId" to "edition")),json(),
            json("expiresAt" to Instant.now().plusSeconds(3600).toString()),listOf(RosterRow("123456","alias","sheet","card","Student","exam")))))
        val intent=Intent(application,ScannerActivity::class.java).putExtra("questionCount",100)
        Robolectric.buildActivity(ScannerActivity::class.java,intent).use { lifecycle ->
            lifecycle.setup();val activity=lifecycle.get();val views=descendants(activity.window.decorView)
            val preview=views.filterIsInstance<PreviewView>().single()
            val auto=views.single { it.tag=="scanner-auto" }
            val controls=auto.parent as ViewGroup
            assertEquals(3,controls.childCount)
            val result=views.filterIsInstance<TextView>().single { it.tag=="scanner-live-result" }
            assertEquals(View.GONE,result.visibility)
            val queue=views.filterIsInstance<TextView>().single { it.contentDescription=="وضعیت صف خوانش برگه‌ها" }
            for((w,h) in listOf(320 to 640,360 to 760)) {
                fun layout() { activity.window.decorView.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));activity.window.decorView.layout(0,0,w,h) }
                result.visibility=View.GONE;layout();val before=preview.height
                result.text="نتیجه و نام بسیار طولانی برای بازبینی ".repeat(80);result.visibility=View.VISIBLE
                queue.text="وضعیت بسیار طولانی صف و خوانش ".repeat(80);layout()
                assertTrue("${w}x$h before=$before after=${preview.height}",preview.height>=h*.4)
                assertTrue(preview.height>=before*.65)
                assertTrue(controls.height>0)
                val selector=views.single { it.tag=="scanner-sheet-segments" }
                val shutter=views.single { it.tag=="scanner-shutter" }
                val selectorParent=selector.parent as ViewGroup
                assertSame(selectorParent,shutter.parent)
                assertEquals(selectorParent.indexOfChild(shutter)-1,selectorParent.indexOfChild(selector))
                val position=IntArray(2);selector.getLocationOnScreen(position)
                val cameraPosition=IntArray(2);preview.getLocationOnScreen(cameraPosition)
                assertTrue(position[1]>=cameraPosition[1]+preview.height)
            }
        }
    }
}
