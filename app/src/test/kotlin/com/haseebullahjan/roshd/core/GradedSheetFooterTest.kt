package com.haseebullahjan.roshd.core

import android.graphics.Bitmap
import android.graphics.Canvas
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.math.BigDecimal

/** The score box printed at the bottom of the graded (and uploaded) sheet. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GradedSheetFooterTest {
    private fun score(verdicts:List<String>,raw:String?=null)=mapOf("questionVerdicts" to verdicts,"questionCount" to verdicts.size,
        "correctCount" to verdicts.count { it=="correct" || it=="voided" },"rawScore" to raw,"needsReview" to verdicts.contains("review"))
    private val forty=List(40) { when { it<30->"correct";it<34->"incorrect";it<35->"multiple";it<36->"voided";else->"blank" } }

    @Test fun withoutPointsTheScoreIsCorrectAnswersOutOfQuestions() {
        val summary=GradedSheetFooter.summary(score(forty,"28.75"),BigDecimal("40"),showPoints=false)
        assertEquals("31",summary.score);assertEquals("40",summary.outOf)
        assertEquals(listOf(31,5,4,0),listOf(summary.correct,summary.incorrect,summary.blank,summary.review))
        assertEquals("نمره: \u2066۳۱\u2069 از \u2066۴۰\u2069",GradedSheetFooter.headline(summary))
        // No scoring policy: correct answers, even if points were allowed.
        assertEquals("31",GradedSheetFooter.summary(score(forty),null,showPoints=true).score)
    }
    @Test fun withPointsTheScoreIsPointsOutOfTheMaximum() {
        val summary=GradedSheetFooter.summary(score(forty,"28.75"),BigDecimal("40.00"),showPoints=true)
        assertEquals("نمره: \u2066۲۸٫۷۵\u2069 از \u2066۴۰\u2069",GradedSheetFooter.headline(summary))
        assertEquals("\u2066−۲٫۵\u2069",GradedSheetFooter.number("-2.5"))
        val reviewed=GradedSheetFooter.summary(score(List(40) { if(it<2)"review" else "blank" }),null,false)
        assertEquals(4,GradedSheetFooter.details(reviewed).size)
        assertThrows(IllegalArgumentException::class.java) { GradedSheetFooter.summary(score(forty).plus("questionCount" to 41),null,false) }
    }
    @Test fun writesPremultipliedRgbaOfTheExactBoxSize() {
        val context=RuntimeEnvironment.getApplication()
        val summary=GradedSheetFooter.summary(score(forty,"28.75"),BigDecimal("40"),showPoints=true)
        val target=File("build/graded-footer").apply { mkdirs() }.resolve("footer.rgba")
        GradedSheetFooter.write(context,summary,1330,163,target)
        val bytes=target.readBytes()
        assertEquals(1330*163*4,bytes.size)
        fun pixel(x:Int,y:Int)=(0..3).map { bytes[(y*1330+x)*4+it].toInt() and 255 }
        assertEquals(listOf(0,0,0,0),pixel(0,0))                  // outside the rounded corner
        // Border in RGBA order (35,55,76): red and blue must not be swapped on any platform.
        pixel(665,2).let { assertTrue(it.toString(),kotlin.math.abs(it[0]-35)<=6 && kotlin.math.abs(it[1]-55)<=6 && kotlin.math.abs(it[2]-76)<=6 && it[3]==255) }
        assertEquals(listOf(255,255,255,255),pixel(40,81))         // white fill inside the box
        val inked=(20 until 143).sumOf { y -> (100 until 1230).count { x -> pixel(x,y).let { it[0]<200 && it[3]==255 } } }
        assertTrue("headline and counts are drawn",inked>3000)
        val bitmap=Bitmap.createBitmap(1330,163,Bitmap.Config.ARGB_8888)
        GradedSheetFooter.draw(Canvas(bitmap),context,summary,1330,163)
        target.resolveSibling("footer.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
    }
}
