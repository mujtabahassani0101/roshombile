package com.haseebullahjan.roshd.ui.components

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.widget.FrameLayout

/** App-wide, static themed canvas. No render loop or animation surface. */
class OrbitSkyDrawable(private val theme:ClayTheme):Drawable() {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    override fun draw(canvas:Canvas) {
        val w=bounds.width().toFloat();val h=bounds.height().toFloat()
        paint.shader=null
        paint.color=theme.canvas;paint.alpha=255;canvas.drawRect(bounds,paint);paint.shader=null
        canvas.save();canvas.translate(bounds.left.toFloat(),bounds.top.toFloat())
        paint.isAntiAlias=false;paint.color=if(theme.dark)Color.rgb(121,122,124) else Color.rgb(129,122,109);paint.alpha=if(theme.dark)20 else 22
        val density=android.content.res.Resources.getSystem().displayMetrics.density
        val step=(22*density).toInt().coerceAtLeast(24).toFloat();val line=maxOf(1f,density.toInt().toFloat())
        var x=0f;while(x<w) { canvas.drawRect(x,0f,x+line,h,paint);x+=step }
        var y=0f;while(y<h) { canvas.drawRect(0f,y,w,y+line,paint);y+=step }
        paint.alpha=255;canvas.restore()
    }
    override fun setAlpha(alpha:Int) { paint.alpha=alpha }
    override fun setColorFilter(colorFilter:ColorFilter?) { paint.colorFilter=colorFilter }
    @Deprecated("Deprecated in Android") override fun getOpacity()=PixelFormat.OPAQUE
}

fun orbitHeader(context:Context,theme:ClayTheme,title:String,expanded:Boolean=false,back:(()->Unit)?=null,utility:(()->Unit)?=null):FrameLayout {
    return studyHeader(context,theme,title,expanded,back,utility)
}
