package com.haseebullahjan.roshd.qa

import android.app.Instrumentation
import android.os.Bundle
import com.haseebullahjan.roshd.core.*
import com.haseebullahjan.roshd.messenger.MessagesStorage
import com.haseebullahjan.roshd.ui.camera.*
import org.json.JSONArray
import java.io.File
import java.util.UUID

/** Isolated, repeatable Android timings. Does not log in or submit any scans. */
class OptimizationPerformanceTest:Instrumentation() {
    private var label="current"
    override fun onCreate(arguments:Bundle?) {
        label=arguments?.getString("label") ?: label;super.onCreate(arguments);start()
    }
    override fun onStart() {
        val result=Bundle();val store=MessagesStorage(targetContext)
        val scope="qa:optimization:${UUID.randomUUID()}";var checks=0
        fun verify(ok:Boolean,message:String) { check(ok) { message };checks++ }
        try {
            val text="معلومات آزمایشی ".repeat(6000)
            store.transaction {
                repeat(120) { n -> store.put(scope,"benchmark",n.toString(),json("id" to n,"text" to text)) }
                store.put("other:$scope","benchmark","0",json("id" to "foreign"))
            }
            val timings=mutableListOf<Double>()
            repeat(7) { n ->
                val at=System.nanoTime();val rows=store.list(scope,"benchmark");val elapsed=(System.nanoTime()-at)/1e6
                verify(rows.size==120 && rows.map { it.optInt("id") }.toSet().size==120,"Every record is restored once")
                verify(rows.all { it.s("text")==text },"Unicode payload and account isolation survive bulk reads")
                if(n>=2)timings.add(elapsed)
            }
            store.put(scope,"large","small",json("id" to "small"))
            store.put(scope,"large","large",json("id" to "large","text" to "x".repeat(9*1024*1024)))
            val mixed=store.list(scope,"large")
            verify(mixed.size==2 && mixed.single { it.s("id")=="large" }.s("text").length==9*1024*1024,"Oversized BLOB and small record share a safe snapshot")
            runCatching { store.transaction {
                store.put(scope,"large","rollback",json("id" to "rollback"))
                verify(store.list(scope,"large").size==3,"List sees its enclosing transaction")
                error("intentional rollback")
            } }
            verify(store.list(scope,"large").size==2,"An enclosing rollback is preserved")
            store.put(scope,"tampered","1",json("private" to "immutable"))
            store.writableDatabase.execSQL("UPDATE documents SET payload=zeroblob(length(payload)) WHERE scope=? AND kind='tampered'",arrayOf(scope))
            verify(runCatching { store.list(scope,"tampered") }.isFailure,"Corrupted encrypted records cannot be accepted")
            verify(store.list(scope,"large").size==2,"Failed authentication does not poison later reads")

            val preview=JSONArray()
            for(rotation in listOf(0,90,180,270)) {
                val width=960;val height=1280
                val pixels=ByteArray(width*height) { p -> if((p%width/5+p/width/5)%2==0)235.toByte() else 65.toByte() }
                val frame=CameraFramePacker.Frame(width,height,pixels,null)
                val guide=ScanGuide.corners(if(rotation%180==0)width else height,if(rotation%180==0)height else width)
                repeat(30) { GuidedScanFrame.analyze(frame,rotation,guide) }
                val samples=mutableListOf<Double>()
                repeat(7) {
                    val at=System.nanoTime()
                    repeat(20) { GuidedScanFrame.analyze(frame,rotation,guide) }
                    samples.add((System.nanoTime()-at)/20e6)
                }
                val quality=GuidedScanFrame.analyze(frame,rotation,guide)
                verify(quality.sharpness>=65,"A sharp frame keeps its quality in rotation $rotation")
                preview.put(json("rotation" to rotation,"medianMs" to samples.sorted()[3],"samplesMs" to wrap(samples),"sharpness" to quality.sharpness))
            }
            val report=json("label" to label,"checks" to checks,"platform" to "Android emulator; not physical camera latency",
                "storageRecords" to 120,"storageMedianMs" to timings.sorted()[2],"storageSamplesMs" to wrap(timings),"preview" to preview)
            File(targetContext.getExternalFilesDir(null),"qa/optimization-$label.json").apply { parentFile!!.mkdirs();writeText(report.toString(2)) }
            result.putString("stream","\n${report.toString(2)}\n$checks checks passed.\n");finish(0,result)
        } catch(error:Throwable) { result.putString("stream",error.stackTraceToString());finish(1,result) }
        finally {
            store.writableDatabase.delete("documents","scope IN (?,?)",arrayOf(scope,"other:$scope"));store.close()
        }
    }
}
