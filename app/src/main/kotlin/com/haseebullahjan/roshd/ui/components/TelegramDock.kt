package com.haseebullahjan.roshd.ui.components

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout

/** Study Glass navigation for every account and screen. */
class TelegramDock(context:Context,theme:ClayTheme,onSelect:(String)->Unit):FrameLayout(context) {
    private val studyDock=StudyGlassDock(context,theme,onSelect)
    val barHeight=studyDock.barHeight
    init {
        tag="dock-style:study"
        addView(studyDock,LayoutParams(-1,context.dp(barHeight),Gravity.CENTER))
    }
    override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int) {
        val width=minOf(MeasureSpec.getSize(widthMeasureSpec),context.dp(420))
        super.onMeasure(MeasureSpec.makeMeasureSpec(width,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(context.dp(barHeight),MeasureSpec.EXACTLY))
    }
    fun bindBackdrop(view:View) { studyDock.bindBackdrop(view) }
    fun setRenderingActive(active:Boolean) { studyDock.setRenderingActive(active) }
    fun refreshBackdrop() { studyDock.refresh() }
    fun select(route:String) { studyDock.select(route) }
}
