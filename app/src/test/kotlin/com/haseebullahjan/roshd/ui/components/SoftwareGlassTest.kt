package com.haseebullahjan.roshd.ui.components

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class SoftwareGlassTest {
    private val w=180;private val h=60
    private fun glass(a:IntArray)=SoftwareGlass.render(a,w,h,3,8f,4f,25f)
    @Test fun uniformBackdropRemainsUniform() {
        val source=IntArray(w*h) { 0xff6daaf0.toInt() }
        assertArrayEquals(source,glass(source))
    }
    @Test fun liveBackdropChangesTheResult() {
        assertFalse(glass(IntArray(w*h) { 0xffff665f.toInt() }).contentEquals(glass(IntArray(w*h) { 0xff6daaf0.toInt() })))
    }
    @Test fun blurSoftensHardContentEdges() {
        val source=IntArray(w*h) { if(it%w<w/2)0xff000000.toInt() else 0xffffffff.toInt() }
        val result=glass(source)
        assertTrue((result[h/2*w+w/2] and 255) in 90..170)
        assertEquals(0xff000000.toInt(),result[h/2*w+25])
    }
    @Test fun rimRefractsWhileInteriorPreservesPosition() {
        val gradient=IntArray(w*h) { val v=(it/w*4).coerceAtMost(255);0xff000000.toInt() or (v shl 16) or (v shl 8) or v }
        val result=glass(gradient)
        assertTrue(abs((result[5*w+w/2] and 255)-(gradient[5*w+w/2] and 255))>=4)
        assertTrue(abs((result[30*w+w/2] and 255)-(gradient[30*w+w/2] and 255))<=2)
    }
    @Test fun allSamplesStayOpaqueAndInsideSourceColorRange() {
        val source=IntArray(w*h) { val v=40+(it*73%140);0xff000000.toInt() or (v shl 16) or (v shl 8) or v }
        assertTrue(glass(source).all { it ushr 24==255 && (it and 255) in 40..179 })
    }
    @Test(expected=IllegalArgumentException::class) fun rejectsMismatchedCaptureDimensions() { SoftwareGlass.render(IntArray(3),2,2,2,0f,0f,1f) }
}
