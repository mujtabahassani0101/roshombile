package com.haseebullahjan.roshd.ui.components

import com.haseebullahjan.roshd.ui.camera.CameraFramePacker as P
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class CameraFramePackerTest {
    private fun plane(a:ByteArray,row:Int,pixel:Int=1)=P.Plane(ByteBuffer.wrap(a),row,pixel)
    @Test fun preservesLuminanceAndInterleavesVBeforeU() {
        val y=ByteArray(24) { it.toByte() };val u=byteArrayOf(20,21,22,23,24,25);val v=byteArrayOf(40,41,42,43,44,45)
        val frame=P.pack(6,4,plane(y,6),plane(u,3),plane(v,3))
        assertArrayEquals(y,frame.bytes);assertArrayEquals(byteArrayOf(40,20,41,21,42,22,43,23,44,24,45,25),frame.chroma)
        assertEquals(90,frame.arguments(90)["rotation"])
    }
    @Test fun paddedRowsAndInterleavedCameraPlanesKeepTheirSamples() {
        val y=byteArrayOf(1,2,3,4,99,99,5,6,7,8,99,99)
        val u=byteArrayOf(11,99,12,99);val v=byteArrayOf(21,99,22,99)
        val frame=P.pack(4,2,plane(y,6),plane(u,4,2),plane(v,4,2))
        assertArrayEquals(byteArrayOf(1,2,3,4,5,6,7,8),frame.bytes)
        assertArrayEquals(byteArrayOf(21,11,22,12),frame.chroma)
    }
    @Test fun samplingKeepsColorAtTheSameCoordinatesAsLuminance() {
        val frame=P.pack(8,8,plane(ByteArray(64) { it.toByte() },8),plane(ByteArray(16) { (it+70).toByte() },4),plane(ByteArray(16) { (it+90).toByte() },4),4)
        assertEquals(4,frame.width);assertEquals(4,frame.height)
        assertArrayEquals(byteArrayOf(0,2,4,6,16,18,20,22,32,34,36,38,48,50,52,54),frame.bytes)
        assertArrayEquals(byteArrayOf(90,70,92,72,98,78,100,80),frame.chroma)
    }
    @Test fun readsOnlyRemainingPlaneDataWithoutMutatingBufferPositions() {
        val buffer=ByteBuffer.wrap(byteArrayOf(99,99,1,2,3,4)).apply { position(2) }
        val frame=P.pack(2,2,P.Plane(buffer,2,1),null,null)
        assertArrayEquals(byteArrayOf(1,2,3,4),frame.bytes);assertNull(frame.chroma);assertEquals(2,buffer.position())
    }
    @Test fun oddSizesAreReducedToValidEvenNv21Dimensions() {
        val frame=P.pack(7,5,plane(ByteArray(35),7),null,null)
        assertEquals(6,frame.width);assertEquals(4,frame.height);assertEquals(24,frame.bytes.size)
    }
    @Test(expected=IllegalArgumentException::class) fun truncatedCameraPlaneIsRejected() { P.pack(4,4,plane(ByteArray(8),4),null,null) }
    @Test fun direct1280CameraBuffersWithOffsetsPaddingAndSharedChromaStayExact() {
        val w=1280;val h=960;val stride=w+16
        val y=ByteBuffer.allocateDirect(3+stride*h).apply { position(3) }
        val vu=ByteBuffer.allocateDirect(5+stride*h/2).apply { position(5) }
        val expectedY=ByteArray(w*h);val expectedVu=ByteArray(w*h/2)
        for(row in 0 until h)for(x in 0 until w) {
            val value=((row*7+x*13)%256).toByte()
            y.put(3+row*stride+x,value);expectedY[row*w+x]=value
            if(row<h/2) { vu.put(5+row*stride+x,value);expectedVu[row*w+x]=value }
        }
        val u=vu.duplicate().apply { position(6) }
        val frame=P.pack(w,h,P.Plane(y,stride,1),P.Plane(u,stride,2),P.Plane(vu,stride,2),maxEdge=1280)
        assertArrayEquals(expectedY,frame.bytes);assertArrayEquals(expectedVu,frame.chroma)
        assertEquals(3,y.position());assertEquals(5,vu.position());assertEquals(6,u.position())
    }
}
