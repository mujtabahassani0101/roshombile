package com.haseebullahjan.roshd.qa

import com.haseebullahjan.roshd.ui.camera.CameraFramePacker as P
import java.nio.ByteBuffer

internal object CameraPackingChecks {
    fun run():String {
        val w=1280;val h=960;val stride=w+16
        val y=ByteBuffer.allocateDirect(stride*h)
        val vu=ByteBuffer.allocateDirect(stride*h/2)
        for(i in 0 until y.capacity())y.put(i,(i*17).toByte())
        for(i in 0 until vu.capacity())vu.put(i,(i*29).toByte())
        val u=vu.duplicate().apply { position(1) }
        fun current()=P.pack(w,h,P.Plane(y,stride,1),P.Plane(u,stride,2),P.Plane(vu,stride,2),1280)
        fun previous()=LegacyCameraFramePacker1123.pack(w,h,
            LegacyCameraFramePacker1123.Plane(y,stride,1),LegacyCameraFramePacker1123.Plane(u,stride,2),
            LegacyCameraFramePacker1123.Plane(vu,stride,2),1280)
        val a=current();val b=previous()
        check(a.bytes.contentEquals(b.bytes) && a.chroma!!.contentEquals(b.chroma!!))
        check(y.position()==0 && vu.position()==0 && u.position()==1)
        val before=mutableListOf<Double>();val after=mutableListOf<Double>()
        repeat(12) { i ->
            var start=System.nanoTime();previous();val old=(System.nanoTime()-start)/1e6
            start=System.nanoTime();current();val new=(System.nanoTime()-start)/1e6
            if(i>=4) { before.add(old);after.add(new) }
        }
        return "PASS: 1280x960 direct padded YUV buffers match 1.1.23 byte for byte\n"+
            "PASS: Packing preserves shared chroma offsets and original buffer positions\n"+
            "PACKING_MS: previousMedian=${before.sorted()[before.size/2]}; currentMedian=${after.sorted()[after.size/2]}; Android x86_64 test, not a phone\n"
    }
}
