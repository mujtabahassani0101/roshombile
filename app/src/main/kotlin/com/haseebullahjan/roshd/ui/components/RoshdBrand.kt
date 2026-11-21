package com.haseebullahjan.roshd.ui.components

import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.*

/** Use the supplied app identity without recoloring or recreating it. */
object RoshdBrand {
    private var bitmap:Bitmap?=null
    @Synchronized fun bitmap(context:Context):Bitmap = bitmap ?: context.assets.open("branding/roshd-symbol.png").use {
        BitmapFactory.decodeStream(it,null,BitmapFactory.Options().apply { inSampleSize=1 }) ?: error("App brand asset missing")
    }.also { bitmap=it }
    fun image(context:Context)=ImageView(context).apply {
        setImageBitmap(bitmap(context));scaleType=ImageView.ScaleType.FIT_CENTER
        background=roundedShape(Color.WHITE,context.dp(14).toFloat());clipToOutline=true
        importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
}

fun roundedShape(color:Int,radius:Float)=GradientDrawable().apply { setColor(color);cornerRadius=radius }

