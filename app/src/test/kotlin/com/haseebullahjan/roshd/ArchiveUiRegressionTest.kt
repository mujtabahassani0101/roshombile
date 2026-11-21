package com.haseebullahjan.roshd

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.haseebullahjan.roshd.core.*
import com.haseebullahjan.roshd.messenger.*
import com.haseebullahjan.roshd.ui.AcademicArchiveFragment
import com.haseebullahjan.roshd.ui.Screens
import com.haseebullahjan.roshd.ui.actionbar.Route
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import javax.crypto.spec.SecretKeySpec
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class ArchiveUiRegressionTest {
    private fun set(target:Any,name:String,value:Any?) { target.javaClass.getDeclaredField(name).apply { isAccessible=true;set(target,value) } }
    private fun views(view:View):List<View> = listOf(view)+(if(view is ViewGroup)(0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList())
    private fun fixture():ExamsController {
        val context=RuntimeEnvironment.getApplication() as ApplicationLoader
        context.getSharedPreferences("roshd_native_settings",0).edit().putString("visualStyle","organic").commit()
        val app=context.controller
        // Test-only key: JVM tests do not emulate AndroidKeyStore.
        set(app.storage,"secret\$delegate",lazyOf(SecretKeySpec(ByteArray(32) { 7 },"AES")))
        set(app,"initialized",true)
        set(app,"session",Session(json("accountId" to "10000000-0000-4000-8000-000000000001",
            "schoolId" to "10000000-0000-4000-8000-000000000002"),"https://example.org"))
        return app
    }
    @Test fun eachOriginalAndColoredPhotoHasItsOwnDeleteControlAndDeletionSurvivesPageRecreation() {
        val app=fixture();val scope=app.session!!.scope
        val scan=json("id" to "one","studentName" to "شاگرد آزمایشی","sourcePath" to "/evidence/one.jpg","state" to "ready")
        val graded=json("id" to "graded:one","name" to "برگهٔ رنگی","sourcePath" to "/evidence/graded-one.jpg","mimeType" to "image/jpeg")
        app.storage.put(scope,"scan","one",scan)
        app.storage.put(scope,"archive","one",json("id" to "one","sourcePath" to "/evidence/one.jpg"))
        app.storage.put(scope,"archive","graded:one",graded)
        set(app,"scans",listOf(scan));set(app,"archive",listOf(graded))
        Robolectric.buildActivity(MainActivity::class.java).use { lifecycle ->
            lifecycle.setup();val activity=lifecycle.get()
            val page=Screens.create(activity,Route("archive"))
            val children=views(page.view())
            assertEquals(1,children.count { it.tag=="archive-delete:one" })
            assertEquals(1,children.count { it.tag=="archive-delete:graded:one" })
            var completed:Result<String>?=null
            app.deleteArchivePhoto("one") { completed=it }
            for(i in 1..300) { shadowOf(Looper.getMainLooper()).idle();if(completed!=null)break;Thread.sleep(10) }
            assertNotNull(completed);assertTrue(completed!!.isSuccess)
            val recreated=Screens.create(activity,Route("archive"))
            val remaining=views(recreated.view())
            assertTrue(remaining.none { it.tag=="archive-delete:one" })
            assertEquals(1,remaining.count { it.tag=="archive-delete:graded:one" })
            assertNotNull(app.storage.get(scope,"scan","one"))
            assertNotNull(app.storage.get(scope,"archive-removed","one"))
            assertEquals(1,app.scans.size)
            page.onFragmentDestroy();recreated.onFragmentDestroy()
        }
    }
    @Test fun pastYearsScreenShowsInactiveYearItsOwnRoundAndEmptyCachedSnapshot() {
        val app=fixture();val old=json("id" to "old-year","label" to "سال تعلیمی ۱۴۰۴","roundCount" to 6,"isActive" to false,"isCurrent" to false)
        val operational=json("id" to "current-year","label" to "سال تعلیمی ۱۴۰۵","openRounds" to listOf(2))
        set(app,"configuration",json("currentAcademicYear" to operational));set(app,"round",2)
        Robolectric.buildActivity(MainActivity::class.java).use { lifecycle ->
            lifecycle.setup()
            val fragment=AcademicArchiveFragment(lifecycle.get(),Route("academic-archive"))
            set(fragment,"started",true);set(fragment,"years",listOf(old));set(fragment,"yearId","old-year");set(fragment,"selectedRound",6)
            set(fragment,"cached",json("downloadedAt" to "2026-11-14T12:00:00Z","snapshot" to json("exams" to emptyList<Any>(),"attendance" to emptyList<Any>())))
            val text=views(fragment.view()).filterIsInstance<TextView>().joinToString("\n") { it.text.toString() }
            assertTrue(text.contains("سال تعلیمی ۱۴۰۴"));assertTrue(text.contains("دور انتخاب‌شده: 6"))
            assertTrue(text.contains("امتحانی ثبت نشده"));assertTrue(text.contains("محفوظ در گوشی"))
            assertEquals(2,app.round);assertEquals("current-year",app.currentYear!!.s("id"))
            fragment.onFragmentDestroy()
        }
    }
    @Test fun aCompletionFromThePreviousAccountIsReturnedAsAFailureWithoutCrashingTheUi() {
        val app=fixture();val entered=CountDownLatch(1);val release=CountDownLatch(1)
        var completed:Result<JSONObject>?=null
        app.readAcademicArchive("archive test",{ completed=it }) {
            entered.countDown();check(release.await(3,TimeUnit.SECONDS));json("oldAccount" to true)
        }
        try {
            assertTrue(entered.await(3,TimeUnit.SECONDS))
            set(app,"session",app.session!!.copy(raw=app.session!!.raw.copy().put("accountId","10000000-0000-4000-8000-000000000099")))
        } finally { release.countDown() }
        for(i in 1..300) { shadowOf(Looper.getMainLooper()).idle();if(completed!=null)break;Thread.sleep(10) }
        assertNotNull(completed);assertTrue(completed!!.isFailure)
    }
    @Test fun savedYearsAndSnapshotsOpenWithoutANetworkRequestEvenWithAValidSession() {
        val app=fixture()
        val owner=app.session!!.copy(raw=app.session!!.raw.copy().put("accessToken","test-only").put("expiresAt",Instant.now().plusSeconds(3600).toString()))
        set(app,"session",owner)
        val year=json("id" to "10000000-0000-4000-8000-000000000003","academicYearId" to "10000000-0000-4000-8000-000000000004",
            "provinceId" to "10000000-0000-4000-8000-000000000005","editionId" to "10000000-0000-4000-8000-000000000006",
            "academicYearKey" to "1404","label" to "سال تعلیمی ۱۴۰۴","version" to "v1","roundCount" to 6,"isCurrent" to false,"isActive" to false)
        val snapshot=json("schemaVersion" to "roshd.information-archive.v1","accountId" to owner.accountId,"schoolId" to owner.schoolId,
            "year" to year,"round" to 1,"exams" to emptyList<Any>(),"attendance" to emptyList<Any>(),"publishedResults" to null)
        app.storage.put(owner.scope,"academic-year-archive","years",json("items" to listOf(year)))
        app.storage.put(owner.scope,"academic-year-archive","${year.s("id")}:1",json("snapshot" to snapshot,"downloadedAt" to now()))
        val calls=AtomicInteger()
        set(app,"academicArchive",AcademicYearArchive(ConnectionsManager { calls.incrementAndGet();error("Unexpected network request") },app.storage::get,app.storage::put))
        Robolectric.buildActivity(MainActivity::class.java).use { lifecycle ->
            lifecycle.setup()
            val fragment=AcademicArchiveFragment(lifecycle.get(),Route("academic-archive"))
            val root=fragment.view()
            for(i in 1..300) {
                shadowOf(Looper.getMainLooper()).idle()
                if(views(root).filterIsInstance<TextView>().any { it.text.contains("محفوظ در گوشی") })break
                Thread.sleep(10)
            }
            assertTrue(views(root).filterIsInstance<TextView>().any { it.text.contains("محفوظ در گوشی") })
            assertEquals(0,calls.get());fragment.onFragmentDestroy()
        }
    }
}
