package com.haseebullahjan.roshd.ui.components

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import com.haseebullahjan.roshd.R

/** Shared Study Glass tokens for every screen, with light and dark appearances. */
data class ClayTheme(val dark:Boolean) {
    private fun color(light:String,dark:String)=Color.parseColor(if(this.dark)dark else light)
    val canvas=color("#F7F4ED","#191A1D")
    val spaceBottom=color("#F0ECE3","#202125")
    val surface=color("#FFFCF5","#282A2E")
    val field=color("#EDE9E0","#222428")
    val ink=color("#171717","#F7F4ED")
    val muted=color("#625F59","#C5C2B9")
    val accent=color("#242424","#EAE6DD")
    val primary=color("#171717","#171717")
    val onAccent=color("#FFFFFF","#FFFFFF")
    val border=color("#171717","#46484D")
    val divider=color("#DDD9D0","#3E4045")
    val lightShadow=color("#FFFFFF","#515359")
    val darkShadow=color("#604D5939","#990B110B")
    val success=color("#235A3A","#A8D8B5")
    val warning=color("#714A0A","#F2C460")
    val danger=color("#A02D33","#FFA2A4")
    val mint=color("#A5DFC4","#295344")
    val peach=color("#FFAD40","#624019")
    val lilac=color("#CCB6E3","#4A3B60")
    val sky=color("#81B9F1","#284C71")
    val studyColors=listOf("#FFD731","#6DAAF0","#FFA600","#BDA0DA","#FF665F","#4BC797").map(Color::parseColor)
    val studyCardInk=Color.parseColor("#171717")
    val dock=surface
    val dockMuted=muted
    val dockSelected=ink
    val dockSelectedInk=canvas
    val radius get()=24f
    val pageGutter get()=20
    val pressDuration get()=140L
    val activityStyle get()=if(dark)R.style.StudyDarkTheme else R.style.StudyLightTheme
    fun controlsTint()=ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked),intArrayOf()),intArrayOf(accent,muted))
    companion object {
        private var regular:Typeface?=null;private var bold:Typeface?=null;private var symbols:Typeface?=null
        fun regular(context:Context):Typeface = regular ?: Typeface.createFromAsset(context.assets,"fonts/Vazirmatn-Regular.ttf").also { regular=it }
        fun bold(context:Context):Typeface = bold ?: Typeface.createFromAsset(context.assets,"fonts/Vazirmatn-Bold.ttf").also { bold=it }
        fun icons(context:Context):Typeface = symbols ?: Typeface.createFromAsset(context.assets,"fonts/Phosphor-Regular.ttf").also { symbols=it }
    }
}
fun Context.dp(value:Int):Int=(value*resources.displayMetrics.density+.5f).toInt()
