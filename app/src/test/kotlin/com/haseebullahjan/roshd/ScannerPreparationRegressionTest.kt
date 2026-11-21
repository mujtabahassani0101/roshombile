package com.haseebullahjan.roshd

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.camera.view.PreviewView
import com.haseebullahjan.roshd.core.*
import com.haseebullahjan.roshd.messenger.ApplicationLoader
import com.haseebullahjan.roshd.messenger.ExamsController
import com.haseebullahjan.roshd.ui.camera.ScannerActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class ScannerPreparationRegressionTest {
    private fun set(app:ExamsController,name:String,value:Any?) {
        ExamsController::class.java.getDeclaredField(name).apply { isAccessible=true }.set(app,value)
    }
    private fun views(view:View):List<View> = listOf(view)+(if(view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList())
    private fun fixture(round:Int=0,open:List<Int> = listOf(1,2),yearPresent:Boolean=true,work:(ExamsController,ScannerActivity)->Unit) {
        val app=(RuntimeEnvironment.getApplication() as ApplicationLoader).controller
        val previous=listOf(app.session,app.configuration,app.round,app.packages)
        set(app,"session",Session(json("accountId" to "10000000-0000-4000-8000-000000000001",
            "schoolId" to "10000000-0000-4000-8000-000000000002"),"https://example.org"))
        set(app,"configuration",if(yearPresent)json("currentAcademicYear" to json("id" to "year","editionId" to "edition","label" to "1405","openRounds" to open)) else json())
        set(app,"round",round);set(app,"packages",emptyMap<String,OfflineExam>())
        try {
            Robolectric.buildActivity(ScannerActivity::class.java).use { lifecycle ->
                lifecycle.setup();work(app,lifecycle.get())
            }
        } finally {
            listOf("session","configuration","round","packages").forEachIndexed { i,name -> set(app,name,previous[i]) }
        }
    }
    @Test fun missingRoundKeepsScannerOpenAndLetsTheOperatorChooseBeforeDownloading() = fixture { app,activity ->
        assertFalse(activity.isFinishing)
        val initial=views(activity.window.decorView)
        assertEquals(1,initial.count { it.tag=="scanner-preparation" })
        assertTrue(initial.none { it is PreviewView || it.tag=="scanner-download-information" })
        initial.single { it.tag=="scanner-select-round" }.performClick()
        val dialog=ShadowAlertDialog.getLatestAlertDialog()
        dialog.listView.performItemClick(null,1,dialog.listView.adapter.getItemId(1))
        assertEquals(2,app.round)
        val selected=views(activity.window.decorView)
        assertEquals(1,selected.count { it.tag=="scanner-download-information" })
        assertTrue(selected.none { it is PreviewView })
        assertFalse(activity.isFinishing)
    }
    @Test fun configurationAloneDoesNotOpenACameraWhosePhotosWouldWaitForeverForPackages() = fixture(round=1) { _,activity ->
        val children=views(activity.window.decorView)
        assertFalse(activity.isFinishing)
        assertEquals(1,children.count { it.tag=="scanner-download-information" })
        assertTrue(children.none { it is PreviewView || it.tag=="scanner-sheet-choice" })
        assertTrue(children.filterIsInstance<TextView>().any { it.text.contains("بستهٔ معتبر پاسخ‌نامه") })
    }
    @Test fun expiredOtherRoundOtherYearAndEmptyRostersCannotMakeTheScannerReady() = fixture(round=1) { app,_ ->
        fun pack(round:Int=1,edition:String="edition",expires:Instant=Instant.now().plusSeconds(3600),roster:Boolean=true)=OfflineExam(
            Exam(json("id" to "exam","round" to round,"editionId" to edition)),json(),json("expiresAt" to expires.toString()),
            if(roster)listOf(RosterRow("123456","alias","sheet","card","Student","exam")) else emptyList())
        for(value in listOf(pack(round=2),pack(edition="previous-edition"),pack(expires=Instant.now().minusSeconds(1)),pack(roster=false))) {
            set(app,"packages",mapOf("exam" to value));assertEquals(0,app.scannerPackageCount)
        }
        set(app,"packages",mapOf("exam" to pack()));assertEquals(1,app.scannerPackageCount)
    }
    @Test fun closedRoundsShowAnExplanationWithoutOfferingAnInvalidDownload() = fixture(round=1,open=emptyList()) { _,activity ->
        val children=views(activity.window.decorView)
        assertFalse(activity.isFinishing)
        assertEquals(1,children.count { it.tag=="scanner-refresh-year" })
        assertTrue(children.none { it.tag=="scanner-select-round" || it.tag=="scanner-download-information" || it is PreviewView })
    }
    @Test fun missingYearOffersConfigurationInsteadOfSilentlyClosing() = fixture(yearPresent=false) { _,activity ->
        val children=views(activity.window.decorView)
        assertFalse(activity.isFinishing)
        assertEquals(1,children.count { it.tag=="scanner-refresh-year" })
        assertTrue(children.none { it is PreviewView })
    }
}
