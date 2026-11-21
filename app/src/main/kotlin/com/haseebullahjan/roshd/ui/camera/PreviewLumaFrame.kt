package com.haseebullahjan.roshd.ui.camera

import java.nio.ByteBuffer
import kotlin.math.*

/** Camera admission only. Saved photographs always go through the source engines. */
object PreviewLumaFrame {
    fun copy(buffer:ByteBuffer,rowStride:Int,pixelStride:Int,left:Int,top:Int,width:Int,height:Int):CameraFramePacker.Frame {
        require(width>=64 && height>=64 && width.toLong()*height<=8_000_000 && left>=0 && top>=0 && rowStride>0 && pixelStride>0)
        val data=buffer.duplicate();val base=data.position()
        val lastColumn=(left.toLong()+width-1)*pixelStride
        require(lastColumn<rowStride)
        require(base+(top.toLong()+height-1)*rowStride+lastColumn<data.limit())
        val luma=ByteArray(width*height)
        for(y in 0 until height) {
            val start=base+(top+y)*rowStride+left*pixelStride
            if(pixelStride==1) { data.position(start);data.get(luma,y*width,width) }
            else for(x in 0 until width)luma[y*width+x]=data.get(start+x*pixelStride)
        }
        return CameraFramePacker.Frame(width,height,luma,null)
    }

    data class QrInput(val bytes:ByteArray,val width:Int,val height:Int)
    /** Two possible upright header bands with a blank separator, never the full frame. */
    fun headers(frame:CameraFramePacker.Frame,rotation:Int,guide:List<Float>):QrInput {
        require(rotation in listOf(0,90,180,270) && guide.size==8 && frame.bytes.size==frame.width*frame.height)
        val uprightWidth=if(rotation%180==0)frame.width else frame.height
        val uprightHeight=if(rotation%180==0)frame.height else frame.width
        val pageWidth=(guide[2]-guide[0])*uprightWidth
        val pageHeight=(guide[7]-guide[1])*uprightHeight
        val left=floor(guide[0]*uprightWidth-pageWidth*.035).toInt().coerceIn(0,uprightWidth-2)
        val right=ceil(guide[2]*uprightWidth+pageWidth*.035).toInt().coerceIn(left+2,uprightWidth)
        val top=floor(guide[1]*uprightHeight-pageHeight*.025).toInt().coerceIn(0,uprightHeight-2)
        val bottom=ceil(guide[7]*uprightHeight+pageHeight*.025).toInt().coerceIn(top+2,uprightHeight)
        val width=(right-left) and -2
        val bandHeight=minOf(((pageHeight*.275).roundToInt()+1) and -2,(bottom-top)/2 and -2).coerceAtLeast(2)
        val gap=16
        val height=bandHeight*2+gap
        val bytes=ByteArray(width*height*3/2) { if(it<width*height)255.toByte() else 128.toByte() }
        for(band in 0..1)for(y in 0 until bandHeight) {
            val sy=if(band==0)top+y else bottom-bandHeight+y
            val destination=(band*(bandHeight+gap)+y)*width
            for(x in 0 until width) {
                val sx=left+x
                val rawX=when(rotation) { 90->sy;180->frame.width-1-sx;270->frame.width-1-sy;else->sx }
                val rawY=when(rotation) { 90->frame.height-1-sx;180->frame.height-1-sy;270->sx;else->sy }
                bytes[destination+x]=frame.bytes[rawY*frame.width+rawX]
            }
        }
        return QrInput(bytes,width,height)
    }
}
