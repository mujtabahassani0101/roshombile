package com.haseebullahjan.roshd.ui.components

import kotlin.math.*

/** Bounded, linear-time compatibility renderer for API 24–32. No RenderEffect/RuntimeShader. */
object SoftwareGlass {
    fun render(source:IntArray,width:Int,height:Int,radius:Int,insetX:Float,insetY:Float,corner:Float):IntArray {
        require(width>0 && height>0 && source.size==width*height)
        val blurred=boxBlur(boxBlur(source,width,height,radius),width,height,radius)
        val result=blurred.copyOf()
        val cx=width/2f;val cy=height/2f
        val halfW=cx-insetX;val halfH=cy-insetY
        val r=corner.coerceIn(1f,halfH.coerceAtLeast(1f))
        val rim=(r*.55f).coerceAtLeast(1f)
        for(y in 0 until height)for(x in 0 until width) {
            val px=x+.5f-cx;val py=y+.5f-cy
            val qx=abs(px)-(halfW-r);val qy=abs(py)-(halfH-r)
            val ox=max(qx,0f);val oy=max(qy,0f)
            val len=sqrt(ox*ox+oy*oy)
            val distance=len+min(max(qx,qy),0f)-r
            if(distance>0 || distance < -rim)continue
            val nx:Float;val ny:Float
            if(len>.001f) { nx=ox/len*sign(px);ny=oy/len*sign(py) }
            else if(qx>qy) { nx=sign(px);ny=0f } else { nx=0f;ny=sign(py) }
            // Smooth convex glass rim: pull the live image inward, decreasing to zero in the center.
            val t=(1+distance/rim).coerceIn(0f,1f)
            val shift=(1-sqrt((1-t*t).coerceAtLeast(0f)))*rim*.85f
            result[y*width+x]=sample(blurred,width,height,x-nx*shift,y-ny*shift)
        }
        return result
    }
    private fun boxBlur(src:IntArray,w:Int,h:Int,radius:Int):IntArray {
        val r=radius.coerceIn(1,24);val count=2*r+1
        val temp=IntArray(src.size);val out=IntArray(src.size)
        for(horizontal in listOf(true,false)) {
            val input=if(horizontal)src else temp;val output=if(horizontal)temp else out
            val lines=if(horizontal)h else w;val length=if(horizontal)w else h
            fun at(line:Int,p:Int)=if(horizontal)line*w+p.coerceIn(0,w-1) else p.coerceIn(0,h-1)*w+line
            for(line in 0 until lines) {
                var red=0;var green=0;var blue=0
                for(k in -r..r) { val c=input[at(line,k)];red+=c ushr 16 and 255;green+=c ushr 8 and 255;blue+=c and 255 }
                for(p in 0 until length) {
                    output[at(line,p)]=(255 shl 24) or ((red/count) shl 16) or ((green/count) shl 8) or (blue/count)
                    val a=input[at(line,p-r)];val b=input[at(line,p+r+1)]
                    red+=(b ushr 16 and 255)-(a ushr 16 and 255);green+=(b ushr 8 and 255)-(a ushr 8 and 255);blue+=(b and 255)-(a and 255)
                }
            }
        }
        return out
    }
    private fun sample(a:IntArray,w:Int,h:Int,x:Float,y:Float):Int {
        val xx=x.coerceIn(0f,w-1f);val yy=y.coerceIn(0f,h-1f)
        val x0=xx.toInt();val y0=yy.toInt();val x1=min(x0+1,w-1);val y1=min(y0+1,h-1)
        val fx=xx-x0;val fy=yy-y0
        fun channel(shift:Int):Int {
            val top=(a[y0*w+x0] ushr shift and 255)*(1-fx)+(a[y0*w+x1] ushr shift and 255)*fx
            val bottom=(a[y1*w+x0] ushr shift and 255)*(1-fx)+(a[y1*w+x1] ushr shift and 255)*fx
            return (top*(1-fy)+bottom*fy).roundToInt().coerceIn(0,255)
        }
        return (255 shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }
}
