package com.haseebullahjan.roshd.ui.camera

import java.nio.ByteBuffer
import kotlin.math.ceil
import kotlin.math.max

/** Preserves both luminance and NV21 chroma before the existing Paperframe live detector. */
object CameraFramePacker {
    data class Plane(val buffer:ByteBuffer,val rowStride:Int,val pixelStride:Int)
    data class Frame(val width:Int,val height:Int,val bytes:ByteArray,val chroma:ByteArray?) {
        fun arguments(rotation:Int)=mapOf("width" to width,"height" to height,"stride" to width,
            "pixelStride" to 1,"rotation" to rotation,"bytes" to bytes,"chroma" to chroma)
    }
    fun pack(width:Int,height:Int,y:Plane,u:Plane?,v:Plane?,maxEdge:Int=720):Frame {
        require(width>=2 && height>=2 && maxEdge>=2)
        val step=max(1,ceil(max(width,height).toDouble()/maxEdge).toInt())
        val w=(width/step) and -2;val h=(height/step) and -2
        require(w>=2 && h>=2)
        fun checkedBuffer(plane:Plane,columns:Int,rows:Int):ByteBuffer {
            require(plane.rowStride>0 && plane.pixelStride>0)
            val data=plane.buffer.duplicate();val start=data.position();val end=data.limit()
            val last=start.toLong()+(rows-1).toLong()*step*plane.rowStride+(columns-1).toLong()*step*plane.pixelStride
            require(last in start.toLong() until end.toLong()) { "Invalid camera plane layout" }
            return data
        }
        val readY=checkedBuffer(y,w,h);val yStart=readY.position();val luminance=ByteArray(w*h)
        // Y is contiguous on normal CameraX devices: one bulk copy per row.
        // Avoid Function2<Int,Int,Byte> calls and boxed pixel coordinates for
        // every sample (over a million calls for each 1280x960 camera frame).
        if(step==1 && y.pixelStride==1) {
            if(y.rowStride==w)readY.get(luminance)
            else for(row in 0 until h) { readY.position(yStart+row*y.rowStride);readY.get(luminance,row*w,w) }
        } else for(row in 0 until h) {
            val source=yStart+row*step*y.rowStride
            for(x in 0 until w)luminance[row*w+x]=readY.get(source+x*step*y.pixelStride)
        }
        val chroma=if(u!=null && v!=null) {
            val readU=checkedBuffer(u,w/2,h/2);val readV=checkedBuffer(v,w/2,h/2)
            val uStart=readU.position();val vStart=readV.position()
            ByteArray(w*h/2).also { out ->
                for(row in 0 until h/2) {
                    val su=uStart+row*step*u.rowStride;val sv=vStart+row*step*v.rowStride
                    for(x in 0 until w/2) {
                        val offset=row*w+x*2
                        out[offset]=readV.get(sv+x*step*v.pixelStride);out[offset+1]=readU.get(su+x*step*u.pixelStride)
                    }
                }
            }
        } else null
        return Frame(w,h,luminance,chroma)
    }
}
