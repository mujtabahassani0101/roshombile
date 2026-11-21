package com.haseebullahjan.roshd.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import com.haseebullahjan.roshd.ui.camera.CameraOverlay
import java.io.File
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Render the real overlay on a raw off-center sheet for visual QA. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],qualifiers="mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CameraGuideRenderTest {
    @Test fun followsTheDetectedPerspectiveEdgesAwayFromTheImageCenter() {
        val root=generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }.first { File(it,"settings.gradle.kts").isFile }
        val context=RuntimeEnvironment.getApplication()
        val sheet=BitmapFactory.decodeFile(File(root,"app/src/androidTest/assets/preview/75q.jpg").path)
        val w=360;val h=480;val q=floatArrayOf(35f,45f,310f,67f,325f,430f,23f,410f)
        val bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);val canvas=Canvas(bitmap);canvas.drawColor(Color.rgb(65,70,75))
        val transform=Matrix().apply { assertTrue(setPolyToPoly(floatArrayOf(0f,0f,sheet.width.toFloat(),0f,
            sheet.width.toFloat(),sheet.height.toFloat(),0f,sheet.height.toFloat()),0,q,0,4)) }
        canvas.drawBitmap(sheet,transform,null)
        val overlay=CameraOverlay(context,false);overlay.layout(0,0,w,h)
        overlay.update(q,true,.75f)
        overlay.draw(canvas)
        for(i in 0..3) {
            val j=(i+1)%4;val x=((q[i*2]+q[j*2])/2).toInt();val y=((q[i*2+1]+q[j*2+1])/2).toInt()
            assertTrue("Detected edge $i must be visible at its physical position",(-2..2).any { dx -> (-2..2).any { dy ->
                val c=bitmap.getPixel(x+dx,y+dy);Color.green(c)>200 && Color.green(c)>Color.red(c)+50
            } })
        }
        assertEquals(Color.rgb(65,70,75),bitmap.getPixel(5,5))
        val out=File(root,"output/ama-scanner/preview-overlay.png");out.parentFile.mkdirs()
        out.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }
        bitmap.recycle();sheet.recycle()
    }
}
