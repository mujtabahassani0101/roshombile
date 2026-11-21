package com.haseebullahjan.roshd.ui.camera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/** The detected Paperframe polygon follows the physical page in preview coordinates. */
internal class CameraOverlay(context:Context,private val card:Boolean):View(context) {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val path=Path()
    private var corners:FloatArray?=null
    private var ready=false
    private var progress=0f
    init { contentDescription="لبه‌های تشخیص‌داده‌شدهٔ برگه" }
    fun update(points:FloatArray?,stable:Boolean,fraction:Float=0f) {
        corners=points?.copyOf();ready=stable;progress=fraction;invalidate()
    }
    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas)
        val density=resources.displayMetrics.density
        val ratio=if(card)1f else 210f/148f
        val w=minOf(width*.90f,height*.90f/ratio);val h=w*ratio
        val left=(width-w)/2;val top=(height-h)/2
        val q=corners ?: floatArrayOf(left,top,left+w,top,left+w,top+h,left,top+h)
        path.reset();path.moveTo(q[0],q[1])
        for(i in 1..3)path.lineTo(q[i*2],q[i*2+1])
        path.close()
        paint.style=Paint.Style.FILL;paint.color=Color.argb(if(corners==null)10 else 24,103,236,176)
        canvas.drawPath(path,paint)
        paint.style=Paint.Style.STROKE;paint.strokeJoin=Paint.Join.ROUND
        paint.color=Color.argb(210,0,0,0);paint.strokeWidth=5*density;canvas.drawPath(path,paint)
        paint.color=if(ready)Color.rgb(103,236,176) else Color.rgb(224,255,246)
        paint.strokeWidth=2*density;canvas.drawPath(path,paint)
        if(ready && progress>0f) {
            paint.color=Color.rgb(103,236,176);paint.style=Paint.Style.STROKE;paint.strokeWidth=4*density
            val y=(maxOf(q[5],q[7])+10*density).coerceAtMost(height-10*density)
            canvas.drawLine(q[6],y,q[6]+(q[4]-q[6])*progress.coerceIn(0f,1f),y,paint)
        }
    }
}
