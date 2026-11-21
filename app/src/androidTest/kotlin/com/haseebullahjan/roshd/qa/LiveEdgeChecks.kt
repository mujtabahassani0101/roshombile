package com.haseebullahjan.roshd.qa

import android.app.Instrumentation
import android.graphics.*
import android.os.SystemClock
import com.haseebullahjan.roshd.core.json
import com.haseebullahjan.youngscientist.scanner.DocumentScanner
import com.haseebullahjan.youngscientist.scanner.DocumentQuad
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import kotlin.math.abs

/** Real ARM64 JNI, with known projected geometry and padded read-only camera planes. */
class LiveEdgeChecks(private val runner:Instrumentation) {
    fun run():JSONObject {
        val rows=JSONArray()
        val times=mutableListOf<Long>()
        var cases=0
        for((count,asset) in listOf(40 to "omr/T3-anchors.jpg",75 to "images/omr-75-preview.png",100 to "omr/T1.jpg")) {
            val page=if(count==75)runner.targetContext.assets.open(asset).use { BitmapFactory.decodeStream(it) }
                else runner.context.assets.open(asset).use { BitmapFactory.decodeStream(it) }
            val photograph=Bitmap.createBitmap(768,1024,Bitmap.Config.ARGB_8888)
            val target=floatArrayOf(123f,67f,683f,117f,653f,953f,83f,907f)
            val transform=Matrix().apply { check(setPolyToPoly(floatArrayOf(0f,0f,(page.width-1).toFloat(),0f,
                (page.width-1).toFloat(),(page.height-1).toFloat(),0f,(page.height-1).toFloat()),0,target,0,4)) }
            Canvas(photograph).apply { drawColor(Color.rgb(64,70,74));drawBitmap(page,transform,Paint(Paint.FILTER_BITMAP_FLAG)) }
            page.recycle()
            val pixels=IntArray(768*1024);photograph.getPixels(pixels,0,768,0,0,768,1024);photograph.recycle()
            val prefix=13;val left=5;val top=7;val stride=797
            val plane=ByteBuffer.allocateDirect(prefix+stride*1038)
            for(y in 0 until 1024)for(x in 0 until 768) {
                val p=pixels[y*768+x]
                plane.put(prefix+(top+y)*stride+left+x,((Color.red(p)*77+Color.green(p)*150+Color.blue(p)*29) shr 8).toByte())
            }
            plane.position(prefix)
            for(rotation in listOf(0,90,180,270)) {
                val expected=target.toList().chunked(2).map { (x,y) ->
                    val u=x/767.0;val v=y/1023.0
                    when(rotation) { 90->listOf(1-v,u);180->listOf(1-u,1-v);270->listOf(v,1-u);else->listOf(u,v) }
                }.flatten()
                val begun=SystemClock.elapsedRealtime()
                val result=DocumentScanner.inspectFrame(plane.asReadOnlyBuffer(),stride,1,left,top,768,1024,rotation)
                val elapsed=SystemClock.elapsedRealtime()-begun;times.add(elapsed)
                check(result.found && DocumentQuad.valid(result.corners)) { "$count/$rotation: page not found" }
                // Corner order can begin at a different physical corner after rotation.
                val maxError=(0..3).maxOf { i ->
                    (0..3).minOf { j -> kotlin.math.hypot(result.corners[i*2]-expected[j*2],result.corners[i*2+1]-expected[j*2+1]) }
                }
                check(maxError<.015) { "$count/$rotation: corner error $maxError" }
                check(plane.position()==prefix)
                rows.put(json("questions" to count,"rotation" to rotation,"detectionMs" to elapsed,"maxCornerErrorFraction" to maxError))
                cases++
            }
            // Exercise sustained processing without a model refresh or stale tracked corners.
            repeat(12) {
                val begun=SystemClock.elapsedRealtime()
                check(DocumentScanner.inspectFrame(plane.asReadOnlyBuffer(),stride,1,left,top,768,1024,0).found)
                times.add(SystemClock.elapsedRealtime()-begun);cases++
            }
        }
        val blank=ByteBuffer.allocateDirect(768*1024)
        for(i in 0 until blank.capacity())blank.put(i,255.toByte())
        check(!DocumentScanner.inspectFrame(blank,768,1,0,0,768,1024,0).found);cases++
        val sorted=times.sorted()
        return json("version" to com.haseebullahjan.roshd.BuildConfig.VERSION_NAME,"casesPassed" to cases,
            "liveEngine" to "young-scientist-native","input" to "padded-read-only-Y-plane-768x1024",
            "medianDetectionMs" to sorted[sorted.size/2],"p95DetectionMs" to sorted[((sorted.size-1)*.95).toInt()],
            "maximumDetectionMs" to sorted.last(),"geometryCases" to rows,"blankRejected" to true)
    }
}
