package com.haseebullahjan.roshd.qa

import android.Manifest
import android.app.Instrumentation
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.camera.view.PreviewView
import com.haseebullahjan.roshd.MainActivity
import com.haseebullahjan.roshd.core.json
import com.haseebullahjan.roshd.messenger.ApplicationLoader
import com.haseebullahjan.roshd.ui.actionbar.Route
import com.haseebullahjan.roshd.ui.camera.ScannerActivity
import org.json.JSONArray
import org.json.JSONObject

/** Uses the signed-in phone's real home button and camera; never presses the shutter. */
class PhoneCameraChecks(private val runner:Instrumentation) {
    private fun main(work:()->Unit) {
        var failure:Throwable?=null
        runner.runOnMainSync { try { work() } catch(error:Throwable) { failure=error } }
        failure?.let { throw it }
    }
    private fun views(view:View):List<View> = listOf(view)+(if(view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList())
    private fun until(label:String,work:()->Boolean) {
        val started=SystemClock.elapsedRealtime()
        while(true) {
            var ready=false
            main { ready=work() }
            if(ready)return
            check(SystemClock.elapsedRealtime()-started<20_000) { "$label timed out" }
            Thread.sleep(100)
        }
    }
    fun run():JSONObject {
        val context=runner.targetContext
        check(context.checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED) {
            "Grant camera permission through Android before this check"
        }
        val activity=runner.startActivitySync(Intent(context,MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        val app=(context.applicationContext as ApplicationLoader).controller
        until("Application initialization") { !app.busy && app.session!=null }
        val owner=checkNotNull(app.session)
        check(owner.valid && app.scannerPackageCount>0) { "Download valid information first" }
        val scanCount=app.storage.list(owner.scope,"scan").size
        val queueCount=app.storage.queue(owner.scope).size
        val rows=JSONArray()
        var scanner:ScannerActivity?=null
        val preferences=com.haseebullahjan.roshd.messenger.NativeSettings.open(context)
        val oldSelection=preferences.all["scannerQuestionCount"]
        try {
            main { activity.navigation.reset(Route("home")) }
            val monitor=runner.addMonitor(ScannerActivity::class.java.name,null,false)
            try {
                main { check(views(activity.window.decorView).single { it.tag=="home:scan" }.performClick()) }
                scanner=runner.waitForMonitorWithTimeout(monitor,10_000) as? ScannerActivity
                    ?: error("Home scan button did not launch the scanner")
            } finally { runner.removeMonitor(monitor) }
            val live=checkNotNull(scanner)
            until("Sheet segments") { views(live.window.decorView).any { it.tag=="scanner-sheet-segments" } }
            main { check(views(live.window.decorView).single { it.tag=="scanner-auto" }.performClick()) }
            var originalPreview:PreviewView?=null
            for(count in listOf(40,75,100)) {
                runner.sendStatus(1,android.os.Bundle().apply { putString("stream","Checking live camera segment for $count questions…\n") })
                main {
                    val buttons=views(live.window.decorView).filterIsInstance<android.widget.RadioButton>()
                    check(buttons.size==3)
                    buttons.single { it.tag=="scanner-choose-$count" }.performClick()
                    check(buttons.count { it.isChecked }==1 && buttons.single { it.isChecked }.tag=="scanner-choose-$count")
                }
                until("Camera streaming for $count questions") {
                    views(live.window.decorView).filterIsInstance<PreviewView>().singleOrNull()
                        ?.previewStreamState?.value==PreviewView.StreamState.STREAMING
                }
                main {
                    val preview=views(live.window.decorView).filterIsInstance<PreviewView>().single()
                    if(originalPreview==null)originalPreview=preview else check(originalPreview===preview) { "Switching segments recreated the camera" }
                    val all=views(live.window.decorView)
                    val selector=all.single { it.tag=="scanner-sheet-segments" }
                    val shutter=all.single { it.tag=="scanner-shutter" }
                    check(shutter.isEnabled)
                    fun top(view:View)=IntArray(2).also { view.getLocationOnScreen(it) }[1]
                    check(top(selector)>=top(preview)+preview.height) { "Sheet selector must be below the camera" }
                    check(top(selector)+selector.height<=top(shutter)) { "Sheet selector must be above the shutter" }
                    check(selector.parent===shutter.parent) { "Sheet selection must remain within thumb reach of the shutter" }
                }
                Thread.sleep(2_000) // Exercise live analysis for every selection.
                rows.put(json("questionCount" to count,"cameraStreaming" to true,"shutterEnabled" to true,
                    "selectorBelowPreview" to true,"selectorAboveShutter" to true,"singleSelection" to true,"cameraReused" to true,"automaticCaptureDisabled" to true))
            }
            val screenshot=runner.uiAutomation.takeScreenshot()
            val folder=java.io.File(context.getExternalFilesDir(null),"qa").apply { mkdirs() }
            java.io.File(folder,"phone-camera-1.1.49.png").outputStream().use {
                check(screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it))
            }
            screenshot.recycle()
            main { live.finish() };scanner=null
            until("Return to home") { activity.hasWindowFocus() }
            check(scanCount==app.storage.list(owner.scope,"scan").size) { "Scan count changed" }
            check(queueCount==app.storage.queue(owner.scope).size) { "Queue count changed" }
            return json("version" to com.haseebullahjan.roshd.BuildConfig.VERSION_NAME,
                "cameraPermissionGranted" to true,"cases" to rows,"scanCountUnchanged" to true,
                "queueCountUnchanged" to true,"cameraChecksPassed" to true)
        } finally {
            main { scanner?.finish() }
            preferences.edit().apply {
                if(oldSelection is Int)putInt("scannerQuestionCount",oldSelection) else remove("scannerQuestionCount")
            }.commit()
        }
    }
}
