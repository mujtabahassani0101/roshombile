package com.haseebullahjan.roshd.qa

import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import com.haseebullahjan.roshd.MainActivity
import com.haseebullahjan.roshd.core.*
import com.haseebullahjan.roshd.messenger.ApplicationLoader
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Explicit live-account check. Uses production login/download methods and records counts only. */
class PhoneInformationChecks(private val runner:Instrumentation) {
    fun run(arguments:Bundle):JSONObject {
        val app by lazy { (runner.targetContext.applicationContext as ApplicationLoader).controller }
        val report=json("version" to com.haseebullahjan.roshd.BuildConfig.VERSION_NAME,"informationDownloaded" to false)
        var stage="startup"
        val started=SystemClock.elapsedRealtime()
        fun operation(name:String,timeoutSeconds:Int=60,work:((Result<String>)->Unit)->Unit) {
            stage=name
            runner.sendStatus(1,Bundle().apply { putString("stream","Checking $name on the phone…\n") })
            val latch=CountDownLatch(1);var outcome:Result<String>?=null
            val before=SystemClock.elapsedRealtime()
            runner.runOnMainSync { work { outcome=it;latch.countDown() } }
            var elapsed=0
            while(!latch.await(5,TimeUnit.SECONDS)) {
                elapsed+=5
                check(elapsed<timeoutSeconds) { "$name timed out" }
                runner.sendStatus(1,Bundle().apply { putString("stream","$name: ${elapsed}s elapsed\n") })
            }
            val result=checkNotNull(outcome)
            report.put(name,json("success" to result.isSuccess,"elapsedMs" to SystemClock.elapsedRealtime()-before))
            result.getOrThrow()
        }
        try {
            runner.startActivitySync(Intent(runner.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            while(app.busy && SystemClock.elapsedRealtime()-started<30000)Thread.sleep(50)
            operation("initialize") { app.initialize(it) }
            val username=arguments.getString("username").orEmpty()
            val password=arguments.getString("password").orEmpty()
            if(username.isNotBlank() && password.isNotEmpty())operation("login") { app.login(username,password,it) }
            check(app.session?.valid==true) { "A valid session is required" }
            report.put("sessionValid",true)
            val owner=checkNotNull(app.session)
            val beforeScans=app.storage.list(owner.scope,"scan").size
            val beforeQueue=app.storage.queue(owner.scope).size
            operation("configuration") { app.refreshConfiguration(it) }
            report.put("configurationKeys",JSONArray(app.configuration.keys().asSequence().toList()))
            report.put("currentYearPresent",app.currentYear!=null)
            report.put("configurationCurrentYearIsNull",app.configuration.isNull("currentAcademicYear"))
            report.put("configurationCurrentYearType",app.configuration.opt("currentAcademicYear")?.javaClass?.simpleName)
            report.put("sessionCurrentYearIsNull",app.session?.raw?.isNull("currentAcademicYear"))
            report.put("yearLabel",app.currentYear?.s("label"))
            report.put("openRounds",JSONArray(app.openRounds))
            if(arguments.getString("diagnoseOnly")=="true") {
                stage="academicYears"
                val years=app.network.request("GET","/api/v1/scanner/academic-years",owner).data().a("items")
                report.put("configuredAcademicYears",JSONArray(years.objects().map { year ->
                    json("academicYearKey" to year.s("academicYearKey"),"label" to year.s("label"),
                        "isCurrent" to year.optBoolean("isCurrent"),"isActive" to year.optBoolean("isActive"),
                        "roundCount" to year.optInt("roundCount"))
                }))
                report.put("role",owner.role)
                report.put("accessScope",if(owner.province)"province" else "school")
                report.put("authorizedSchoolCount",owner.schools.size)
                report.put("examCount",app.exams.size)
                report.put("packageCount",app.packages.size)
                report.put("scannerReadyPackageCount",app.scannerPackageCount)
                report.put("selectedRound",app.round)
                report.put("features",app.configuration.o("features"))
                report.put("cameraPermissionGranted",runner.targetContext.checkSelfPermission(android.Manifest.permission.CAMERA)==android.content.pm.PackageManager.PERMISSION_GRANTED)
                report.put("scanCountUnchanged",beforeScans==app.storage.list(owner.scope,"scan").size)
                report.put("queueCountUnchanged",beforeQueue==app.storage.queue(owner.scope).size)
                report.put("diagnosticComplete",true)
                report.put("elapsedMs",SystemClock.elapsedRealtime()-started)
                report.put("requestTimings",JSONArray(app.network.diagnostics.toList()))
                return report
            }
            check(app.currentYear!=null) { "Server configuration has no current academic year" }
            check(app.openRounds.isNotEmpty()) { "Server has no open examination round" }
            runner.runOnMainSync { app.selectRound(app.round.takeIf { it in app.openRounds } ?: app.openRounds.first()) }
            report.put("selectedRound",app.round)
            operation("download",660) { app.downloadInformation(it) }
            val cached=app.storage.get(owner.scope,"information","current")
            report.put("informationDownloaded",true)
            report.put("persistedInformationPresent",cached!=null)
            report.put("examCount",app.exams.size)
            report.put("packageCount",app.packages.size)
            report.put("scannerReadyPackageCount",app.scannerPackageCount)
            report.put("studentCount",cached?.a("students")?.length() ?: 0)
            report.put("rosterRowCount",app.packages.values.sumOf { it.roster.size })
            report.put("scanCountUnchanged",beforeScans==app.storage.list(owner.scope,"scan").size)
            report.put("queueCountUnchanged",beforeQueue==app.storage.queue(owner.scope).size)
        } catch(error:Throwable) {
            report.put("failedStage",stage)
            report.put("error",json("type" to error.javaClass.simpleName,"status" to (error as? ApiFailure)?.status,
                "code" to (error as? ApiFailure)?.code,"message" to error.message.orEmpty().replace(
                    Regex("[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}"),"{id}").take(350)))
        }
        report.put("elapsedMs",SystemClock.elapsedRealtime()-started)
        report.put("requestTimings",JSONArray(app.network.diagnostics.toList()))
        return report
    }
}
