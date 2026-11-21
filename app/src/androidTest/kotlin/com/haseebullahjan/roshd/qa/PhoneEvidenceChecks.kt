package com.haseebullahjan.roshd.qa

import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import com.haseebullahjan.roshd.MainActivity
import com.haseebullahjan.roshd.core.*
import com.haseebullahjan.roshd.messenger.ApplicationLoader
import org.json.JSONObject
import org.json.JSONArray
import java.io.File

/** Read-only diagnostic export of the signed-in owner's recent camera evidence. */
class PhoneEvidenceChecks(private val runner:Instrumentation) {
    fun audit():JSONObject {
        val context=runner.targetContext
        runner.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        val app=(context.applicationContext as ApplicationLoader).controller
        val begun=SystemClock.elapsedRealtime()
        while(app.busy && SystemClock.elapsedRealtime()-begun<30_000)Thread.sleep(50)
        check(!app.busy && !app.cameraProcessing)
        val owner=checkNotNull(app.session)
        val scans=app.storage.list(owner.scope,"scan")
        return json("scanCount" to scans.size,"queueCount" to app.storage.queue(owner.scope).size,
            "pendingDeletionCount" to app.storage.list(owner.scope,"scan-trash").size,
            "photos" to scans.map { json("sourceSha256" to it.s("sourceImageSha256"),
                "sourceExists" to File(it.s("sourcePath")).isFile) })
    }
    fun run():JSONObject {
        val context=runner.targetContext
        runner.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        val app=(context.applicationContext as ApplicationLoader).controller
        val begun=SystemClock.elapsedRealtime()
        while(app.busy && SystemClock.elapsedRealtime()-begun<30_000)Thread.sleep(50)
        check(!app.busy && !app.cameraProcessing)
        val owner=checkNotNull(app.session)
        val before=app.storage.list(owner.scope,"scan");val queue=app.storage.queue(owner.scope).size
        val folder=File(checkNotNull(context.getExternalFilesDir(null)),"qa/accuracy-evidence-1150").apply { mkdirs() }
        File(folder,"bindings.json").writeText(JSONArray(OmrRosterIndex(app.packages.values,app.round).recognitionBindings).toString(2))
        val qrScanner=com.google.mlkit.vision.barcode.BarcodeScanning.getClient(
            com.google.mlkit.vision.barcode.BarcodeScannerOptions.Builder().setBarcodeFormats(com.google.mlkit.vision.barcode.common.Barcode.FORMAT_QR_CODE).build())
        val manifest=JSONArray()
        for((index,record) in before.filter { it.s("inputSource")=="camera" && File(it.s("sourcePath")).isFile }
            .sortedByDescending { it.s("createdAt") }.take(12).withIndex()) {
            val label="case-%02d".format(index+1);val output=File(folder,label).apply { mkdirs() }
            val engine=record.o("engine")
            val snapshot=JSONObject()
            for(key in listOf("createdAt","inputSource","captureQuestionCount","state","sourceImageSha256","engine","answers","annotation","captureToResultMs"))
                if(record.has(key))snapshot.put(key,record.get(key))
            File(output,"record.json").writeText(snapshot.toString(2))
            val qr=runCatching {
                val image=com.google.mlkit.vision.common.InputImage.fromFilePath(context,android.net.Uri.fromFile(File(record.s("sourcePath"))))
                val decoded=com.google.android.gms.tasks.Tasks.await(qrScanner.process(image),10,java.util.concurrent.TimeUnit.SECONDS)
                json("width" to image.width,"height" to image.height,"rotation" to image.rotationDegrees,
                    "codes" to decoded.map { code -> json("text" to code.rawValue,
                        "box" to code.boundingBox?.let { listOf(it.left,it.top,it.width(),it.height()) },
                        "corners" to code.cornerPoints?.flatMap { listOf(it.x,it.y) }) })
            }.getOrElse { json("error" to it.javaClass.simpleName) }
            File(output,"qr-probe.json").writeText(qr.toString(2))
            val files=JSONObject()
            for((kind,path) in listOf("source" to record.s("sourcePath"),"normalized" to engine.s("normalizedImagePath"),
                "annotation" to record.o("annotation").s("path"),"annotation-base" to record.s("annotationBasePath"))) {
                if(path.isBlank())continue
                val input=File(path)
                if(!input.isFile)continue
                check(input.canonicalPath.startsWith(context.filesDir.parentFile!!.canonicalPath+"/"))
                val dest=File(output,"$kind.${input.extension.ifBlank { "jpg" }}")
                input.copyTo(dest,true);check(EvidenceArchive.hash(input)==EvidenceArchive.hash(dest))
                files.put(kind,dest.name)
            }
            manifest.put(json("case" to label,"questionCount" to record.optInt("captureQuestionCount"),"files" to files,
                "nativeSuccess" to engine.optBoolean("success"),"qrDecoded" to OmrResultParser.qr(engine).isNotBlank()))
        }
        qrScanner.close()
        File(folder,"manifest.json").writeText(json("cases" to manifest).toString(2))
        check(canonical(JSONArray(before))==canonical(JSONArray(app.storage.list(owner.scope,"scan"))))
        check(queue==app.storage.queue(owner.scope).size)
        return json("exportedCases" to manifest.length(),"exportFolder" to folder.path,"recordsUnchanged" to true,"queueUnchanged" to true)
    }
}
