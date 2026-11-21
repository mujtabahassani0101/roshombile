package com.haseebullahjan.roshd.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/** Real AndroidLiquidGlass layer, fed by a live capture of the native page under the dock. */
class StudyGlassDock(context:Context,private val theme:ClayTheme,onSelect:(String)->Unit):FrameLayout(context) {
    val barHeight=84
    private val image=mutableStateOf<Bitmap?>(null)
    private var selected=0
    private val cells=mutableListOf<android.widget.TextView>()
    private val routes=listOf("home","history","archive","settings")
    private val labels=listOf("خانه","نتیجه‌ها","آرشیف","تنظیمات")
    private val glyphs=listOf(0xe2c4,0xe158,0xe00c,0xe432)
    private var source:View?=null
    private var worker=Executors.newSingleThreadExecutor()
    private var pending=false
    private var processing=false
    private var dirty=false
    private var renderingActive=true
    private var generation=0
    var renderedFrames=0;private set
    var sourceFingerprint=0;private set
    val renderer get()=if(Build.VERSION.SDK_INT>=33)"kyant-runtime-shader" else "kyant-software-lens"
    private val scrollListener=ViewTreeObserver.OnScrollChangedListener { refresh() }
    private val layoutListener=View.OnLayoutChangeListener { _,_,_,_,_,_,_,_,_ -> refresh() }
    private val capture=Runnable { pending=false;captureFrame() }
    init {
        tag="glass-renderer:$renderer";clipChildren=false;clipToPadding=false
        val compose=ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                val backdrop=rememberLayerBackdrop {
                    image.value?.let { drawImage(it.asImageBitmap(),dstSize=IntSize(size.width.roundToInt(),size.height.roundToInt())) }
                }
                Box(Modifier.fillMaxSize()) {
                    // Recording-only canvas: the unprocessed texture is never painted over the page.
                    Canvas(Modifier.fillMaxSize().layerBackdrop(backdrop)) {}
                    Box(Modifier.fillMaxSize().padding(horizontal=16.dp,vertical=12.dp)
                        .drawBackdrop(backdrop=backdrop,shape={CircleShape},effects={
                            if(Build.VERSION.SDK_INT>=33) { vibrancy();blur(4.dp.toPx());lens(14.dp.toPx(),20.dp.toPx()) }
                        },onDrawSurface={ drawRect(Color(if(theme.dark)0xFF202125 else 0xFFFFFCF5).copy(alpha=if(theme.dark).60f else .56f)) }))

                }
            }
        }
        addView(compose,LayoutParams(-1,-1))
        val tabs=row(context).apply { setPadding(context.dp(6),0,context.dp(6),0) }
        routes.forEachIndexed { index,route ->
            val cell=icon(context,theme,glyphs[index],23f).apply {
                includeFontPadding=false;setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP,23f)
                tag="dock:$route";contentDescription=I18n.phrase(context,labels[index])
                importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_YES
                isClickable=true;isFocusable=true;minimumWidth=context.dp(48);minimumHeight=context.dp(48)
                setOnClickListener { onSelect(route) };clayPress()
                accessibilityDelegate=object:View.AccessibilityDelegate() { override fun onInitializeAccessibilityNodeInfo(host:View,info:android.view.accessibility.AccessibilityNodeInfo) { super.onInitializeAccessibilityNodeInfo(host,info);info.className=android.widget.Button::class.java.name;info.isSelected=host.isSelected } }
            }
            cells.add(cell);tabs.addView(cell,android.widget.LinearLayout.LayoutParams(0,-1,1f))
        }
        addView(tabs,LayoutParams(-1,context.dp(48),android.view.Gravity.CENTER).apply { leftMargin=context.dp(20);rightMargin=context.dp(20) })
        select("home")
        addOnLayoutChangeListener(layoutListener)
    }
    fun select(route:String) {
        val owner=when(route) { "review"->"history";"file","academic-archive","academic-archive-detail"->"archive";"device","manager","notices"->"settings";else->route }
        selected=routes.indexOf(owner).coerceAtLeast(0)
        cells.forEachIndexed { i,cell ->
            val active=i==selected;cell.isSelected=active
            cell.background=if(active)roundedShape(theme.ink,context.dp(24).toFloat()) else null
            cell.setTextColor(if(active)theme.canvas else theme.ink)
            androidx.core.view.ViewCompat.setStateDescription(cell,if(active)I18n.phrase(context,"انتخاب‌شده") else null)
        }
        refresh()
    }
    fun bindBackdrop(view:View) {
        unbind();source=view
        view.viewTreeObserver.addOnScrollChangedListener(scrollListener)
        view.addOnLayoutChangeListener(layoutListener);refresh()
    }
    fun setRenderingActive(active:Boolean) {
        renderingActive=active
        if(active)refresh() else { removeCallbacks(capture);pending=false;dirty=false }
    }
    fun refresh() {
        dirty=true
        if(!renderingActive || !isAttachedToWindow || pending || processing || visibility!=View.VISIBLE)return
        pending=true;postDelayed(capture,50)
    }
    private fun captureFrame() {
        val view=source ?: return
        if(!renderingActive || width<=0 || height<=0 || !isShown || !view.isLaidOut)return
        dirty=false;processing=true
        val scale=minOf(1f,600f/width)
        val w=(width*scale).roundToInt().coerceAtLeast(1);val h=(height*scale).roundToInt().coerceAtLeast(1)
        val frame=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
        val target=IntArray(2);val origin=IntArray(2);getLocationInWindow(target);view.getLocationInWindow(origin)
        val canvas=Canvas(frame);canvas.drawColor(theme.canvas);canvas.scale(scale,scale)
        canvas.translate((origin[0]-target[0]).toFloat(),(origin[1]-target[1]).toFloat())
        view.draw(canvas) // Navigation only, so the glass cannot recursively capture itself.
        val probe=IntArray(w*h);frame.getPixels(probe,0,w,0,0,w,h)
        sourceFingerprint=probe.contentHashCode()
        val ticket=generation
        val density=resources.displayMetrics.density*scale
        worker.execute {
            val ready=if(Build.VERSION.SDK_INT>=33)frame else {
                val pixels=probe
                val result=SoftwareGlass.render(pixels,w,h,(3*density).roundToInt().coerceAtLeast(1),16*density,12*density,30*density)
                Bitmap.createBitmap(result,w,h,Bitmap.Config.ARGB_8888).also { frame.recycle() }
            }
            post {
                processing=false
                if(ticket==generation && isAttachedToWindow && renderingActive) { image.value=ready;renderedFrames++ } else ready.recycle()
                // Compose retains the previous frame until its draw completes; GC owns old bitmaps.
                if(dirty)refresh()
            }
        }
    }
    private fun unbind(clear:Boolean=true) {
        source?.viewTreeObserver?.takeIf { it.isAlive }?.removeOnScrollChangedListener(scrollListener)
        source?.removeOnLayoutChangeListener(layoutListener)
        if(clear)source=null
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if(worker.isShutdown)worker=Executors.newSingleThreadExecutor()
        source?.let { bindBackdrop(it) };refresh()
    }
    override fun onDetachedFromWindow() {
        generation++;removeCallbacks(capture);pending=false;unbind(clear=false);worker.shutdown()
        super.onDetachedFromWindow()
    }
}
