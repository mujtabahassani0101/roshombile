package com.haseebullahjan.roshd.ui.components

import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*

/** Native rounded surfaces, using the shared Study Glass color and shape system. */
class ClayDrawable(private val theme:ClayTheme,private val inset:Boolean=false,private val accent:Boolean=false,private val radius:Float=24f,private val scale:Float=1f,private val fillColor:Int?=null):Drawable() {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private var opacity=255
    override fun isStateful()=true
    override fun onStateChange(state:IntArray):Boolean { invalidateSelf();return true }
    override fun draw(canvas:Canvas) {
        val enabled=state.isEmpty() || state.contains(android.R.attr.state_enabled)
        val rect=RectF(bounds)
        val corner=minOf(radius*scale,rect.height()/2)
        val base=fillColor ?: if(accent)theme.primary else if(inset)theme.field else theme.surface
        paint.style=Paint.Style.FILL;paint.color=base;paint.alpha=if(enabled)opacity else (opacity*.5f).toInt()
        canvas.drawRoundRect(rect,corner,corner,paint);paint.clearShadowLayer()
        if(state.contains(android.R.attr.state_pressed)) { paint.color=theme.ink;paint.alpha=17;canvas.drawRoundRect(rect,corner,corner,paint) }
        paint.alpha=opacity;paint.style=Paint.Style.STROKE;paint.strokeWidth=scale
        paint.color=if(inset)theme.divider else Color.TRANSPARENT
        canvas.drawRoundRect(rect,corner,corner,paint)
        if(state.contains(android.R.attr.state_focused)) { paint.color=theme.accent;paint.strokeWidth=3*scale;canvas.drawRoundRect(rect,corner,corner,paint) }
        paint.style=Paint.Style.FILL
    }
    override fun setAlpha(alpha:Int) { opacity=alpha;invalidateSelf() }
    override fun setColorFilter(filter:ColorFilter?) { paint.colorFilter=filter;invalidateSelf() }
    @Deprecated("Deprecated in Android") override fun getOpacity()=PixelFormat.TRANSLUCENT
}
class ClayPanel(context:Context,val theme:ClayTheme,inset:Boolean=false,accent:Boolean=false,tone:Int?=null):LinearLayout(context) {
    init {
        orientation=VERTICAL;layoutDirection=View.LAYOUT_DIRECTION_RTL
        setLayerType(View.LAYER_TYPE_SOFTWARE,null)
        background=ClayDrawable(theme,inset,accent,radius=theme.radius,scale=resources.displayMetrics.density,fillColor=tone)
        val padding=16
        setPadding(context.dp(padding),context.dp(padding),context.dp(padding),context.dp(padding))
    }
}
fun motionEnabled(context:Context):Boolean=android.provider.Settings.Global.getFloat(context.contentResolver,android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,1f)>0f
fun View.clayPress() {
    if(!motionEnabled(context))return
    stateListAnimator=android.animation.StateListAnimator().apply {
        fun animation(value:Float)=ObjectAnimator.ofFloat(this@clayPress,"alpha",value).apply { duration=140 }
        addState(intArrayOf(android.R.attr.state_pressed),animation(.86f));addState(intArrayOf(),animation(1f))
    }
}
fun text(context:Context,theme:ClayTheme,value:String,size:Float=15f,bold:Boolean=false,muted:Boolean=false,localize:Boolean=true):TextView=TextView(context).apply {
    text=if(localize)I18n.phrase(context,value) else value;textSize=size;typeface=if(bold)ClayTheme.bold(context) else ClayTheme.regular(context)
    setTextColor(if(muted)theme.muted else theme.ink);gravity=Gravity.START;layoutDirection=View.LAYOUT_DIRECTION_RTL
    setLineSpacing(context.dp(2).toFloat(),1.08f)
}
fun icon(context:Context,theme:ClayTheme,code:Int,size:Float=27f):TextView=TextView(context).apply {
    text=String(Character.toChars(code));textSize=size;typeface=ClayTheme.icons(context);setTextColor(theme.accent);gravity=Gravity.CENTER
    importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
}
fun button(context:Context,theme:ClayTheme,label:String,primary:Boolean=false,click:()->Unit):TextView=text(context,theme,label,15f,true).apply {
    gravity=Gravity.CENTER;isClickable=true;isFocusable=true;minHeight=context.dp(54)
    setPadding(context.dp(18),context.dp(12),context.dp(18),context.dp(12))
    background=ClayDrawable(theme,accent=primary,radius=30f,scale=resources.displayMetrics.density)
    if(primary)setTextColor(theme.onAccent)
    setLayerType(View.LAYER_TYPE_SOFTWARE,null);clayPress();setOnClickListener { click() }
    accessibilityDelegate=object:View.AccessibilityDelegate() { override fun onInitializeAccessibilityNodeInfo(host:View,info:android.view.accessibility.AccessibilityNodeInfo) { super.onInitializeAccessibilityNodeInfo(host,info);info.className=Button::class.java.name } }
}
fun field(context:Context,theme:ClayTheme,hint:String,password:Boolean=false):EditText=EditText(context).apply {
    this.hint=I18n.phrase(context,hint);textSize=16f;typeface=ClayTheme.regular(context);setTextColor(theme.ink);setHintTextColor(theme.muted)
    inputType=if(password)InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT
    setSingleLine(true);minHeight=context.dp(64);setPadding(context.dp(20),context.dp(16),context.dp(20),context.dp(16))
    background=ClayDrawable(theme,inset=true,radius=18f,scale=resources.displayMetrics.density);setLayerType(View.LAYER_TYPE_SOFTWARE,null)
    backgroundTintList=null
    isSaveEnabled=!password;layoutDirection=View.LAYOUT_DIRECTION_RTL
    gravity=Gravity.RIGHT or Gravity.CENTER_VERTICAL
    if(password)transformationMethod=android.text.method.PasswordTransformationMethod.getInstance()
    if(android.os.Build.VERSION.SDK_INT>=26) {
        importantForAutofill=View.IMPORTANT_FOR_AUTOFILL_YES
        setAutofillHints(if(password)View.AUTOFILL_HINT_PASSWORD else View.AUTOFILL_HINT_USERNAME)
    }
}
fun LinearLayout.space(height:Int=12) { addView(View(context),LinearLayout.LayoutParams(1,context.dp(height))) }
fun LinearLayout.full(view:View) { addView(view,LinearLayout.LayoutParams(-1,-2)) }
fun row(context:Context):LinearLayout=LinearLayout(context).apply { orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;layoutDirection=View.LAYOUT_DIRECTION_RTL }
fun LinearLayout.weight(view:View,value:Float=1f) { addView(view,LinearLayout.LayoutParams(0,-2,value)) }
