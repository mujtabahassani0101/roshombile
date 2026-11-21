package com.haseebullahjan.roshd.qa

import java.nio.ByteBuffer
import kotlin.math.ceil
import kotlin.math.max

/** Test-only 1.1.23 baseline for byte parity and on-device packing timings. */
internal object LegacyCameraFramePacker1123 {
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
        fun reader(plane:Plane):(Int,Int)->Byte {
            require(plane.rowStride>0 && plane.pixelStride>0)
            val data=plane.buffer.duplicate();val start=data.position();val end=data.limit()
            return { x,row ->
                val offset=start+row*plane.rowStride+x*plane.pixelStride
                require(offset in start until end) { "Invalid camera plane layout" }
                data.get(offset)
            }
        }
        val readY=reader(y);val luminance=ByteArray(w*h)
        for(row in 0 until h)for(x in 0 until w)luminance[row*w+x]=readY(x*step,row*step)
        val chroma=if(u!=null && v!=null) {
            val readU=reader(u);val readV=reader(v)
            ByteArray(w*h/2).also { out ->
                for(row in 0 until h/2)for(x in 0 until w/2) {
                    val offset=row*w+x*2
                    out[offset]=readV(x*step,row*step);out[offset+1]=readU(x*step,row*step)
                }
            }
        } else null
        return Frame(w,h,luminance,chroma)
    }
}
