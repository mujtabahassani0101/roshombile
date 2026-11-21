package com.haseebullahjan.roshd

import android.content.DialogInterface
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import com.haseebullahjan.roshd.core.*
import com.haseebullahjan.roshd.messenger.*
import com.haseebullahjan.roshd.ui.Screens
import com.haseebullahjan.roshd.ui.actionbar.Route
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.spec.SecretKeySpec

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk=[28])
class ScanDeletionRegressionTest {
    private fun set(target:Any,name:String,value:Any?) { target.javaClass.getDeclaredField(name).apply { isAccessible=true;set(target,value) } }
    private fun get(app:ExamsController,name:String):Any=requireNotNull(app.javaClass.getDeclaredField(name).apply { isAccessible=true }.get(app))
    private fun fixture():ExamsController {
        val app=(RuntimeEnvironment.getApplication() as ApplicationLoader).controller
        set(app.storage,"secret\$delegate",lazyOf(SecretKeySpec(ByteArray(32) { 9 },"AES")))
        set(app,"initialized",true)
        set(app,"session",Session(json("accountId" to "10000000-0000-4000-8000-000000000001",
            "schoolId" to "10000000-0000-4000-8000-000000000002"),"https://example.org"))
        return app
    }
    private fun await(condition:()->Boolean) {
        for(i in 0 until 600) { shadowOf(Looper.getMainLooper()).idle();if(condition())return;Thread.sleep(10) }
        fail("Asynchronous local deletion did not finish")
    }
    private fun delete(app:ExamsController,id:String):Result<String> {
        var result:Result<String>?=null;app.deleteScan(id) { result=it };await { result!=null };return result!!
    }
    private fun scan(app:ExamsController,id:String,state:String="ready"):JSONObject {
        val source=app.storage.mediaFile(app.session!!.scope,"$id.jpg").apply { writeText("test photo") }
        return json("id" to id,"sourcePath" to source.path,"studentName" to "شاگرد آزمایشی","state" to state,"createdAt" to now())
            .also { app.storage.put(app.session!!.scope,"scan",id,it) }
    }
    @Test fun deletionRemovesTheLocalSheetPhotosOutboxAndCameraJobWithoutTouchingOtherAccounts() {
        val app=fixture();val scope=app.session!!.scope;val one=scan(app,"one");val other=scan(app,"other")
        val normalized=File(app.storage.mediaFile(scope,"engine-one").apply { mkdirs() },"normalized.jpg").apply { writeText("normalized") }
        val graded=app.storage.mediaFile(scope,"graded-one.jpg").apply { writeText("colored") }
        val oldGraded=app.storage.mediaFile(scope,"graded-one-10000000-0000-4000-8000-000000000099.jpg").apply { writeText("previous color") }
        one.put("scanId","packet-one").put("sealed",true).put("engine",json("normalizedImagePath" to normalized.path)).put("annotation",json("path" to graded.path))
        app.storage.put(scope,"scan","one",one)
        for((id,file) in listOf("one" to File(one.s("sourcePath")),"graded:one" to graded))
            app.storage.put(scope,"archive",id,json("id" to id,"sourcePath" to file.path))
        app.storage.put(scope,"image","packet-one",json("phase" to "pending"))
        app.storage.enqueue(scope,"scan","packet-one","one",json(),one)
        app.storage.addCameraWork(scope,"one",1,"year");app.storage.cameraWorkStatus(scope,"one","pending")
        val foreign=scope+"-other-account";val foreignFile=app.storage.mediaFile(foreign,"one.jpg").apply { writeText("foreign") }
        app.storage.put(foreign,"scan","one",one.copy().put("sourcePath",foreignFile.path))
        app.storage.enqueue(foreign,"scan","packet-foreign","one",json(),one)
        set(app,"scans",listOf(one,other))
        assertTrue(delete(app,"one").isSuccess)
        assertNull(app.storage.get(scope,"scan","one"));assertNull(app.storage.get(scope,"archive","one"))
        assertNull(app.storage.get(scope,"archive","graded:one"));assertNull(app.storage.get(scope,"image","packet-one"))
        assertTrue(app.storage.queue(scope,"scan").isEmpty());assertEquals(0,app.storage.cameraWorkCount(scope))
        assertTrue(app.scans.none { it.s("id")=="one" })
        await { !File(one.s("sourcePath")).exists() && !normalized.exists() && !graded.exists() && !oldGraded.exists() && app.storage.get(scope,"scan-trash","one")==null }
        assertTrue(foreignFile.isFile);assertNotNull(app.storage.get(foreign,"scan","one"))
        assertEquals(1,app.storage.queue(foreign,"scan").size);assertTrue(File(other.s("sourcePath")).isFile)
    }
    @Test fun aLateCameraResultCannotRecreateADeletedSheetAndItsFilesAreCleanedAfterTheWorker() {
        val app=fixture();val owner=app.session!!;val record=scan(app,"active","processing");val source=File(record.s("sourcePath"))
        @Suppress("UNCHECKED_CAST") val active=get(app,"cameraActive") as MutableSet<String>
        active.add("active");set(app,"cameraProcessing",true);(get(app,"running") as AtomicBoolean).set(true)
        app.storage.addCameraWork(owner.scope,"active",1,"year");app.storage.cameraWorkStatus(owner.scope,"active","processing")
        set(app,"scans",listOf(record))
        assertTrue(delete(app,"active").isSuccess);assertTrue(source.isFile)
        val output=app.storage.mediaFile(owner.scope,"engine-active").apply { mkdirs() }
        File(output,"late-native.jpg").writeText("late result")
        val inputType=app.javaClass.declaredClasses.single { it.simpleName=="ScanInput" }
        val input=inputType.declaredConstructors.single().apply { isAccessible=true }.newInstance("active",source)
        val readType=app.javaClass.declaredClasses.single { it.simpleName=="PreparedRead" }
        val read=readType.declaredConstructors.single().apply { isAccessible=true }.newInstance(owner,listOf(input),listOf(record),OmrRosterIndex(emptyList(),1),json("outputDirectory" to output.path),"test")
        val stage=get(app,"stageQueue") as DispatchQueue
        val finished=CountDownLatch(1)
        stage.post {
            try {
                app.javaClass.getDeclaredMethod("persistRead",readType,JSONObject::class.java).apply { isAccessible=true }
                    .invoke(app,read,json("sheets" to listOf(json("page" to 1))))
                app.javaClass.getDeclaredMethod("finishCameraRead",String::class.java,String::class.java,Throwable::class.java)
                    .apply { isAccessible=true }.invoke(app,owner.scope,"active",null)
            } finally { finished.countDown() }
        }
        assertTrue(finished.await(5,TimeUnit.SECONDS))
        await { !source.exists() && !output.exists() }
        assertNull(app.storage.get(owner.scope,"scan","active"));assertTrue(app.scans.isEmpty())
        assertNull(app.storage.get(owner.scope,"archive","active"))
    }
    @Test fun cleanupWaitsForColorWorkAndRetainsSharedAndExternalFiles() {
        val app=fixture();val scope=app.session!!.scope;val one=scan(app,"one");val shared=File(one.s("sourcePath"))
        val other=scan(app,"other").put("sourcePath",shared.path);app.storage.put(scope,"scan","other",other)
        val outside=File.createTempFile("roshd-external-",".jpg").apply { writeText("external") }
        val graded=app.storage.mediaFile(scope,"graded-one.jpg").apply { writeText("color") }
        one.put("annotation",json("path" to graded.path)).put("engine",json("normalizedImagePath" to outside.path))
        app.storage.put(scope,"scan","one",one)
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        (get(app,"cameraAnnotations") as ExecutorService).execute { entered.countDown();check(release.await(5,TimeUnit.SECONDS));check(shared.isFile);graded.writeText("completed color") }
        try {
            assertTrue(entered.await(3,TimeUnit.SECONDS));assertTrue(delete(app,"one").isSuccess)
            assertTrue(graded.isFile);assertNotNull(app.storage.get(scope,"scan-trash","one"))
        } finally { release.countDown() }
        await { !graded.exists() && app.storage.get(scope,"scan-trash","one")==null }
        assertTrue(shared.isFile);assertTrue(outside.isFile);assertNotNull(app.storage.get(scope,"scan","other"));outside.delete()
    }
    @Test fun activeCaptureOrUploadCannotBePartiallyDeleted() {
        val app=fixture();val scope=app.session!!.scope;val record=scan(app,"one","capturing")
        assertTrue(delete(app,"one").isFailure);assertNotNull(app.storage.get(scope,"scan","one"))
        record.put("state","ready");app.storage.put(scope,"scan","one",record)
        (get(app,"running") as AtomicBoolean).set(true)
        try { assertTrue(delete(app,"one").isFailure);assertTrue(File(record.s("sourcePath")).isFile) }
        finally { (get(app,"running") as AtomicBoolean).set(false) }
    }
    @Test fun lateColorFilesAreDiscardedWithoutRecreatingTheDeletedArchive() {
        val app=fixture();val owner=app.session!!;val record=scan(app,"one")
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        (get(app,"cameraAnnotations") as ExecutorService).execute { entered.countDown();check(release.await(5,TimeUnit.SECONDS)) }
        try {
            assertTrue(entered.await(3,TimeUnit.SECONDS));assertTrue(delete(app,"one").isSuccess)
            val color=app.storage.mediaFile(owner.scope,"late-colored.jpg").apply { writeText("late color") }
            val base=app.storage.mediaFile(owner.scope,"late-color-base.jpg").apply { writeText("late normalized base") }
            val snapshot=record.copy().put("annotation",json("path" to color.path)).put("annotationBasePath",base.path)
            val finished=CountDownLatch(1)
            (get(app,"stageQueue") as DispatchQueue).post {
                try {
                    app.javaClass.getDeclaredMethod("persistCameraAnnotation",Session::class.java,JSONObject::class.java)
                        .apply { isAccessible=true }.invoke(app,owner,snapshot)
                } finally { finished.countDown() }
            }
            assertTrue(finished.await(3,TimeUnit.SECONDS));assertNull(app.storage.get(owner.scope,"scan","one"))
            assertNull(app.storage.get(owner.scope,"archive","graded:one"))
            release.countDown();await { !color.exists() && !base.exists() && !File(record.s("sourcePath")).exists() }
        } finally { release.countDown() }
    }
    @Test fun unfinishedFileCleanupResumesAfterRestartRecovery() {
        val app=fixture();val scope=app.session!!.scope;val file=app.storage.mediaFile(scope,"orphan.jpg").apply { writeText("photo") }
        app.storage.put(scope,"scan-trash","gone",json("id" to "gone","paths" to listOf(file.path),"deletedAt" to now()))
        app.javaClass.getDeclaredMethod("recoverCameraWork").apply { isAccessible=true }.invoke(app)
        await { !file.exists() && app.storage.get(scope,"scan-trash","gone")==null }
    }
    private fun views(view:View):List<View> = listOf(view)+(if(view is ViewGroup)(0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList())
    @Test fun historyAndReviewOfferDeletionAndCancelKeepsTheSheet() {
        val app=fixture();val record=scan(app,"one");set(app,"scans",listOf(record))
        Robolectric.buildActivity(MainActivity::class.java).use { lifecycle ->
            lifecycle.setup();val activity=lifecycle.get();val history=Screens.create(activity,Route("history"))
            val delete=views(history.view()).single { it.tag=="scan-delete:one" }
            delete.performClick();ShadowAlertDialog.getLatestAlertDialog().getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
            assertNotNull(app.storage.get(app.session!!.scope,"scan","one"))
            val review=Screens.create(activity,Route("review",json("id" to "one")))
            assertEquals(1,views(review.view()).count { it.tag=="scan-delete:one" })
            delete.performClick();ShadowAlertDialog.getLatestAlertDialog().getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            await { app.scans.isEmpty() }
            val restored=Screens.create(activity,Route("history"));assertTrue(views(restored.view()).none { it.tag=="scan-delete:one" })
            history.onFragmentDestroy();review.onFragmentDestroy();restored.onFragmentDestroy()
        }
    }
}
