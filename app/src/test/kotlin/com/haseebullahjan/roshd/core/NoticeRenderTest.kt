package com.haseebullahjan.roshd.core

import android.graphics.Bitmap
import android.graphics.Canvas
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Draws the backend notice payload with its 1.1.57 fields on a real host canvas. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NoticeRenderTest {
    private fun notice()=JSONObject(javaClass.classLoader!!.getResourceAsStream("notices/renderer-v4-blue.json")!!.bufferedReader().use { it.readText() }).getJSONObject("data")
    private fun draw(data:JSONObject,name:String):Bitmap {
        NoticeRenderer.validate(data)
        val bitmap=Bitmap.createBitmap(1190,1684,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap);canvas.scale(2f,2f)
        NoticeRenderer(RuntimeEnvironment.getApplication()).draw(canvas,data.put("noticeColor","blue"),1,1)
        File("build/notice-render").apply { mkdirs() }.resolve("$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        return bitmap
    }
    private fun inked(bitmap:Bitmap,top:Int,bottom:Int)=(top until bottom step 2).count { y -> (0 until bitmap.width step 2).any { x -> bitmap.getPixel(x,y)!=-1 } }
    @Test fun currentPayloadDrawsRanksSectionAndGroups() {
        val data=notice().put("section","الف").put("hasResult",true)
        data.getJSONObject("ranks").put("province",3).put("nationalPopulation",1200).put("provincePopulation",300).put("schoolPopulation",30).put("classPopulation",15)
        data.getJSONArray("groupRanks").getJSONObject(0).put("province",2).put("class",1)
        val bitmap=draw(data,"notice-current")
        assertTrue(inked(bitmap,428,470)>0)
    }
    @Test fun noticeWithoutResultDrawsOnlyIdentityAndStatement() {
        val data=notice().put("hasResult",false).put("publicationVersion",0).put("subjects",JSONArray()).put("questions",JSONArray()).put("history",JSONArray())
        data.remove("totalScore");data.remove("ranks")
        val bitmap=draw(data,"notice-without-result")
        assertTrue(inked(bitmap,330,380)>0)
        assertTrue(inked(bitmap,480,1580)==0)
    }
}
