package com.haseebullahjan.roshd.ui.components

import com.haseebullahjan.roshd.ui.camera.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class PreviewLumaFrameTest {
    @Test fun copyingRespectsCropPaddingPixelStrideAndReadOnlyBufferPosition() {
        for(pixelStride in listOf(1,2)) {
            val width=96;val height=128;val left=7;val top=3;val prefix=11;val stride=(width+left+5)*pixelStride
            val buffer=ByteBuffer.allocateDirect(prefix+stride*(height+top+1))
            val expected=ByteArray(width*height) { (it*17).toByte() }
            for(y in 0 until height)for(x in 0 until width)buffer.put(prefix+(y+top)*stride+(x+left)*pixelStride,expected[y*width+x])
            buffer.position(prefix)
            val readOnly=buffer.asReadOnlyBuffer()
            val actual=PreviewLumaFrame.copy(readOnly,stride,pixelStride,left,top,width,height)
            assertArrayEquals(expected,actual.bytes);assertNull(actual.chroma);assertEquals(prefix,readOnly.position())
        }
    }
    @Test fun truncatedCameraRowsAreRejectedBeforeReading() {
        val buffer=ByteBuffer.allocateDirect(96*128)
        for(params in listOf(intArrayOf(96,1,1,0),intArrayOf(96,1,0,1),intArrayOf(96,2,0,0))) {
            assertThrows(IllegalArgumentException::class.java) { PreviewLumaFrame.copy(buffer,params[0],params[1],params[2],params[3],96,128) }
        }
    }
    @Test fun allSensorRotationsProduceTheSameUprightHeaderPixelsAndNeutralChroma() {
        val width=192;val height=256
        val pixels=ByteArray(width*height) { ((it/width*3+it%width*7)%230).toByte() }
        val expected=PreviewLumaFrame.headers(CameraFramePacker.Frame(width,height,pixels,null),0,ScanGuide.corners(width,height))
        var current=pixels;var w=width;var h=height
        for(turn in 1..3) {
            val rotated=ByteArray(current.size)
            for(y in 0 until h)for(x in 0 until w)rotated[x*h+h-1-y]=current[y*w+x]
            current=rotated;val old=w;w=h;h=old
            val actual=PreviewLumaFrame.headers(CameraFramePacker.Frame(w,h,current,null),(360-turn*90)%360,ScanGuide.corners(width,height))
            assertEquals(expected.width,actual.width);assertEquals(expected.height,actual.height);assertArrayEquals(expected.bytes,actual.bytes)
        }
        assertEquals(0,expected.width%2);assertEquals(0,expected.height%2)
        assertTrue(expected.bytes.drop(expected.width*expected.height).all { it==128.toByte() })
        val bandHeight=(expected.height-16)/2
        assertTrue(expected.bytes.slice(bandHeight*expected.width until (bandHeight+16)*expected.width).all { it==255.toByte() })
    }
}
