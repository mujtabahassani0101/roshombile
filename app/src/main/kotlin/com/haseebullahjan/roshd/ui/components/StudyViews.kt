package com.haseebullahjan.roshd.ui.components

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.widget.*

/** Artwork is decoded at its display size, never at the original image's full resolution. */
object StudyArt {
    private val images=mutableMapOf<String,Bitmap>()
    fun bitmap(context:Context,name:String):Bitmap=images.getOrPut(name) {
        val omr=name in setOf("omr-t1","omr-t3")
        context.assets.open(if(omr)"images/$name-preview.png" else "images/study-$name.png").use {
            BitmapFactory.decodeStream(it,null,BitmapFactory.Options().apply { inSampleSize=if(omr)2 else 4 })!!
        }
    }
    fun image(context:Context,name:String)=ImageView(context).apply {
        setImageBitmap(bitmap(context,name));scaleType=ImageView.ScaleType.FIT_CENTER
        importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
}
fun studyDisplay(context:Context,theme:ClayTheme,label:String,size:Float=24f,pashtoSize:Float=16f):TextView=
    text(context,theme,label,minOf(size,24f),true)

fun studyHeader(context:Context,theme:ClayTheme,title:String,expanded:Boolean,back:(()->Unit)?,utility:(()->Unit)?):FrameLayout=FrameLayout(context).apply {
    tag="header:study";minimumHeight=context.dp(if(resources.configuration.fontScale>1.3f)108 else 84)
    if(expanded)setBackgroundColor(Color.rgb(23,23,23))
    val ink=if(expanded)Color.rgb(250,247,239) else theme.ink
    val brand=RoshdBrand.image(context).apply { tag="brand:study";setPadding(context.dp(4),context.dp(4),context.dp(4),context.dp(4));background=roundedShape(Color.rgb(249,246,237),context.dp(24).toFloat()) }
    addView(brand,FrameLayout.LayoutParams(context.dp(46),context.dp(46),Gravity.LEFT or Gravity.CENTER_VERTICAL).apply { leftMargin=context.dp(20) })
    addView(text(context,theme,if(expanded)"رشد" else title,if(expanded)30f else 30f,true).apply { setTextColor(ink);includeFontPadding=false;gravity=Gravity.RIGHT or Gravity.CENTER_VERTICAL },FrameLayout.LayoutParams(-1,-1).apply { leftMargin=context.dp(78);rightMargin=context.dp(if(back!=null)70 else 20) })
    val action=back ?: utility
    if(action!=null && (!expanded || back!=null)) {
        addView(FrameLayout(context).apply {
            contentDescription=I18n.phrase(context,if(back!=null)"بازگشت" else "جست‌وجو");isClickable=true;isFocusable=true
            background=roundedShape(theme.field,context.dp(24).toFloat())
            addView(icon(context,theme,if(back!=null)0xe06c else 0xe30c,23f),FrameLayout.LayoutParams(-1,-1))
            setOnClickListener { action() };clayPress()
        },FrameLayout.LayoutParams(context.dp(48),context.dp(48),Gravity.RIGHT or Gravity.CENTER_VERTICAL).apply { rightMargin=context.dp(14) })
    }
}

fun studyFolderHeight(context:Context)=if(context.resources.configuration.fontScale>1.3f)204 else 148

/** A raised left tab and real OMR template previews tucked inside the folder. */
fun studyFolder(context:Context,theme:ClayTheme,title:String,detail:String,tone:Int,glyph:Int,click:()->Unit):FrameLayout=FrameLayout(context).apply {
    tag="study:folder:$glyph";minimumHeight=context.dp(studyFolderHeight(context));clipChildren=false;clipToPadding=false
    isClickable=true;isFocusable=true;contentDescription="$title، $detail";setOnClickListener { click() };clayPress()
    val back=View(context).apply { background=FolderDrawable(tone,context.resources.displayMetrics.density) }
    addView(back,FrameLayout.LayoutParams(-1,context.dp(112),Gravity.BOTTOM).apply { leftMargin=context.dp(3);rightMargin=context.dp(3);bottomMargin=context.dp(5) })
    addView(FolderOmrSheets(context,glyph),FrameLayout.LayoutParams(context.dp(118),context.dp(130),Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin=context.dp(1) })
    val front=LinearLayout(context).apply {
        orientation=LinearLayout.VERTICAL;gravity=Gravity.BOTTOM;minimumHeight=context.dp(100)
        setPadding(context.dp(14),context.dp(30),context.dp(14),context.dp(13))
        background=FolderDrawable(tone,context.resources.displayMetrics.density,false)
        full(text(context,theme,title,15f,true).apply { setTextColor(theme.studyCardInk) })
        val line=row(context)
        line.weight(text(context,theme,detail,11f).apply { setTextColor(Color.rgb(59,48,30));maxLines=2 })
        line.addView(icon(context,theme,glyph,21f).apply { setTextColor(theme.studyCardInk) },LinearLayout.LayoutParams(context.dp(28),context.dp(28)))
        full(line)
    }
    addView(front,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM).apply { leftMargin=context.dp(3);rightMargin=context.dp(3);bottomMargin=context.dp(5) })
    accessibilityDelegate=object:View.AccessibilityDelegate() { override fun onInitializeAccessibilityNodeInfo(host:View,info:android.view.accessibility.AccessibilityNodeInfo) { super.onInitializeAccessibilityNodeInfo(host,info);info.className=Button::class.java.name } }
}
internal class FolderDrawable(private val color:Int,private val density:Float,private val back:Boolean=true):Drawable() {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val path=Path()
    override fun draw(canvas:Canvas) {
        val r=RectF(bounds);val radius=8*density;val rise=18*density
        val tabEnd=r.left+r.width()*.36f;val shoulder=tabEnd+13*density
        path.reset();path.moveTo(r.left+radius,r.top)
        path.lineTo(tabEnd-radius,r.top);path.quadTo(tabEnd,r.top,tabEnd+radius*.5f,r.top+radius*.6f)
        path.lineTo(shoulder-radius*.4f,r.top+rise-radius*.5f);path.quadTo(shoulder,r.top+rise,shoulder+radius*.5f,r.top+rise)
        path.lineTo(r.right-radius,r.top+rise);path.quadTo(r.right,r.top+rise,r.right,r.top+rise+radius)
        path.lineTo(r.right,r.bottom-radius);path.quadTo(r.right,r.bottom,r.right-radius,r.bottom)
        path.lineTo(r.left+radius,r.bottom);path.quadTo(r.left,r.bottom,r.left,r.bottom-radius)
        path.lineTo(r.left,r.top+radius);path.quadTo(r.left,r.top,r.left+radius,r.top);path.close()
        paint.style=Paint.Style.FILL;paint.color=if(back)androidx.core.graphics.ColorUtils.blendARGB(color,Color.BLACK,.16f) else color
        canvas.drawPath(path,paint)
        if(!back) { paint.style=Paint.Style.STROKE;paint.strokeWidth=density;paint.color=Color.argb(18,0,0,0);canvas.drawPath(path,paint) }
    }
    override fun setAlpha(alpha:Int) { paint.alpha=alpha }
    override fun setColorFilter(filter:ColorFilter?) { paint.colorFilter=filter }
    @Deprecated("Deprecated in Android") override fun getOpacity()=PixelFormat.TRANSLUCENT
}

private class FolderOmrSheets(context:Context,glyph:Int):View(context) {
    private val front=sheet(context,when(glyph) { 0xe836,0xe158->"t3";else->"t1" })
    private val behind=sheet(context,if(glyph==0xe158)"t1" else "t3")
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    init { importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO;setLayerType(LAYER_TYPE_SOFTWARE,null) }
    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas)
        fun paper(bitmap:Bitmap,x:Float,y:Float,angle:Float) {
            val w=width*.60f;val h=w*bitmap.height/bitmap.width
            canvas.save();canvas.rotate(angle,x+w*.5f,y+h*.75f)
            paint.color=Color.WHITE;paint.setShadowLayer(context.dp(2).toFloat(),0f,context.dp(1).toFloat(),Color.argb(42,0,0,0))
            canvas.drawRoundRect(x,y,x+w,y+h,context.dp(1).toFloat(),context.dp(1).toFloat(),paint)
            paint.clearShadowLayer();canvas.drawBitmap(bitmap,null,RectF(x,y,x+w,y+h),paint);canvas.restore()
        }
        paper(behind,width*.17f,height*.06f,-13f)
        paper(front,width*.34f,height*.08f,8f)
    }
    companion object {
        private fun sheet(context:Context,code:String)=StudyArt.bitmap(context,"omr-$code")
    }
}
fun studyScanHero(context:Context,theme:ClayTheme,greeting:String,detail:String,scan:()->Unit):FrameLayout=FrameLayout(context).apply {
    tag="study:scan-hero";setBackgroundColor(Color.rgb(23,23,23))
    val words=LinearLayout(context).apply {
        orientation=LinearLayout.VERTICAL;setPadding(context.dp(20),context.dp(4),context.dp(20),context.dp(38))
        full(text(context,theme,greeting,13f).apply { setTextColor(Color.rgb(194,191,183));maxLines=1 })
        space(6)
        full(text(context,theme,"امروز، آمادهٔ یک شروع تازه",24f,true).apply { setTextColor(Color.rgb(250,247,239)) })
        space(4)
        full(text(context,theme,detail,12f).apply { setTextColor(Color.rgb(194,191,183)) })
        space(12)
        full(button(context,theme,"اسکن پاسخ‌نامه",true,scan).apply { background=roundedShape(Color.rgb(250,247,239),context.dp(28).toFloat());setTextColor(Color.rgb(23,23,23)) })
    }
    addView(words,FrameLayout.LayoutParams(-1,-2))
    addView(View(context).apply { background=android.graphics.drawable.GradientDrawable().apply {
        setColor(theme.canvas);cornerRadii=floatArrayOf(context.dp(24).toFloat(),context.dp(24).toFloat(),context.dp(24).toFloat(),context.dp(24).toFloat(),0f,0f,0f,0f)
    } },FrameLayout.LayoutParams(-1,context.dp(24),Gravity.BOTTOM))
}
fun styleStudySwitch(context:Context,theme:ClayTheme,view:Switch) { view.thumbTintList=theme.controlsTint();view.trackTintList=android.content.res.ColorStateList.valueOf(theme.divider);view.switchMinWidth=context.dp(48) }
fun styleStudyCheckBox(context:Context,theme:ClayTheme,view:CheckBox) { view.buttonTintList=theme.controlsTint() }
