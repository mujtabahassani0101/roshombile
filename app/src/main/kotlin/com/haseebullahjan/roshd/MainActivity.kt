package com.haseebullahjan.roshd

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.haseebullahjan.roshd.core.json
import com.haseebullahjan.roshd.messenger.*
import com.haseebullahjan.roshd.ui.Screens
import com.haseebullahjan.roshd.ui.actionbar.*
import com.haseebullahjan.roshd.ui.components.*

class MainActivity:NativeHostActivity() {
    val app get()=(application as ApplicationLoader).controller
    lateinit var navigation:ActionBarLayout;private set
    private val subscriptions=mutableListOf<AutoCloseable>()
    private lateinit var dock:FrameLayout
    private lateinit var dockTabs:TelegramDock
    private var darkSurface=false
    private var keyboardVisible=false
    private fun startupRoute()=Route(if(app.session==null)"login" else "home")
    override fun onCreate(savedInstanceState:Bundle?) {
        darkSurface=app.darkMode
        setTheme(ClayTheme(app.darkMode).activityStyle)
        super.onCreate(savedInstanceState)
        app.native.attach(this)
        WindowCompat.setDecorFitsSystemWindows(window,false)
        val theme=ClayTheme(app.darkMode)
        WindowCompat.getInsetsController(window,window.decorView).apply { isAppearanceLightStatusBars=!theme.dark;isAppearanceLightNavigationBars=!theme.dark }
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;background=OrbitSkyDrawable(theme);layoutDirection=View.LAYOUT_DIRECTION_RTL;clipChildren=false }
        val wallpaper=OrbitSkyDrawable(theme)
        root.background=object:android.graphics.drawable.Drawable() {
            private val paint=android.graphics.Paint()
            override fun draw(canvas:android.graphics.Canvas) {
                wallpaper.bounds=bounds;wallpaper.draw(canvas)
                if(::navigation.isInitialized && navigation.current?.name in setOf("home","login")) {
                    paint.color=android.graphics.Color.rgb(23,23,23)
                    canvas.drawRect(0f,0f,bounds.width().toFloat(),root.paddingTop.toFloat(),paint)
                }
            }
            override fun setAlpha(alpha:Int) {}
            override fun setColorFilter(filter:android.graphics.ColorFilter?) {}
            @Deprecated("Deprecated in Android") override fun getOpacity()=android.graphics.PixelFormat.OPAQUE
        }
        val busy=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate=true;indeterminateTintList=android.content.res.ColorStateList.valueOf(theme.accent);visibility=View.GONE }
        root.addView(busy,LinearLayout.LayoutParams(-1,dp(3)))
        val busyLabel=text(this,theme,"",12f,muted=true).apply { setTextColor(theme.ink);visibility=View.GONE;gravity=Gravity.CENTER;setPadding(dp(12),dp(4),dp(12),dp(4)) }
        root.addView(busyLabel,LinearLayout.LayoutParams(-1,-2))
        val stage=FrameLayout(this)
        navigation=ActionBarLayout(this) { Screens.create(this,it) }.apply { background=OrbitSkyDrawable(theme) }
        stage.addView(navigation,FrameLayout.LayoutParams(dp(720),-1,Gravity.CENTER_HORIZONTAL).apply { width=if(resources.displayMetrics.widthPixels>dp(720))dp(720) else -1 })
        root.addView(stage,LinearLayout.LayoutParams(-1,0,1f))
        dock=FrameLayout(this).apply { clipChildren=false;clipToPadding=false }
        dockTabs=TelegramDock(this,theme) { name ->
            if(navigation.current?.name!=name)navigation.presentFragment(Route(name),replace=navigation.current?.name in setOf("home","history","archive","settings"))
        }
        dock.addView(dockTabs,FrameLayout.LayoutParams(-1,dp(dockTabs.barHeight),Gravity.CENTER_HORIZONTAL))
        stage.addView(dock,FrameLayout.LayoutParams(-1,dp(dockTabs.barHeight),Gravity.BOTTOM))
        dockTabs.bindBackdrop(navigation)
        setContentView(root)
        applySystemBarAppearance()
        ViewCompat.setOnApplyWindowInsetsListener(root) { view,insets ->
            val bars=insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val keyboard=insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left,bars.top,bars.right,maxOf(bars.bottom,keyboard.bottom))
            keyboardVisible=insets.isVisible(WindowInsetsCompat.Type.ime())
            renderDock()
            insets
        }
        navigation.onChanged={ renderDock();applySystemBarAppearance();root.background.invalidateSelf() }
        subscriptions.add(app.notifications.observe(AppEvent.PROGRESS) { busy.visibility=if(app.busy)View.VISIBLE else View.GONE;busyLabel.text=app.progress;busyLabel.visibility=busy.visibility })
        subscriptions.add(app.notifications.observe(AppEvent.SESSION) { navigation.reset(startupRoute()) })
        subscriptions.add(app.notifications.observe(AppEvent.THEME) { recreate() })
        onBackPressedDispatcher.addCallback(this,object:OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if(!navigation.closeLastFragment())finish() }
        })
        app.initialize { result ->
            if(result.isFailure)showResult(result)
            val saved=savedInstanceState?.getString("navigation")
            val canRestore=saved!=null && runCatching {
                val pages=org.json.JSONArray(saved)
                pages.length() in 1..64 && (0 until pages.length()).all {
                    val name=pages.getJSONObject(it).getString("name")
                    name!="theme-choice" && (app.session!=null || name in setOf("login","settings"))
                }
            }.getOrDefault(false)
            if(canRestore)runCatching { navigation.restore(saved) }.onFailure { navigation.reset(startupRoute()) }
            else if(navigation.current==null || navigation.current?.name=="theme-choice")navigation.reset(startupRoute())
        }
    }
    private fun renderDock() {
        dock.visibility=if(app.session!=null && navigation.current!=null && navigation.current?.name!="theme-choice" && !keyboardVisible)View.VISIBLE else View.GONE
        dockTabs.select(navigation.current?.name ?: "home")
        dockTabs.refreshBackdrop()
    }
    private fun applySystemBarAppearance() {
        @Suppress("DEPRECATION")
        window.statusBarColor=android.graphics.Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor=android.graphics.Color.TRANSPARENT
        if(android.os.Build.VERSION.SDK_INT>=29) {
            window.isStatusBarContrastEnforced=false
            window.isNavigationBarContrastEnforced=false
            window.decorView.isForceDarkAllowed=false
        }
        WindowCompat.getInsetsController(window,window.decorView).apply {
            isAppearanceLightStatusBars=!(darkSurface || (::navigation.isInitialized && navigation.current?.name in setOf("home","login")))
            isAppearanceLightNavigationBars=!darkSurface
        }
    }
    override fun onWindowFocusChanged(hasFocus:Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if(hasFocus)applySystemBarAppearance()
    }

    fun showResult(result:Result<String>) {
        val message=result.fold({it},{it.message ?: "عملیات کامل نشد؛ معلومات محفوظ است."})
        if(message.isNotEmpty())Toast.makeText(this,I18n.phrase(this,message),Toast.LENGTH_LONG).show()
    }
    @Deprecated("Activity result bridge for the retained native file services")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:android.content.Intent?) {
        if(requestCode==9501) {
            if(resultCode==RESULT_OK && data?.getBooleanExtra("continuous",false)==true) { navigation.presentFragment(Route("history"));return }
            if(resultCode==RESULT_OK)data?.getStringExtra("path")?.let { navigation.presentFragment(Route("editor",json("path" to it))) }
            return
        }
        super.onActivityResult(requestCode,resultCode,data)
    }
    override fun onSaveInstanceState(out:Bundle) { if(::navigation.isInitialized)out.putString("navigation",navigation.serialize());super.onSaveInstanceState(out) }
    override fun onPause() { if(::dockTabs.isInitialized)dockTabs.setRenderingActive(false);if(::navigation.isInitialized)navigation.pause();super.onPause() }
    override fun onResume() { super.onResume();if(::dockTabs.isInitialized)dockTabs.setRenderingActive(true);if(::navigation.isInitialized)navigation.resume() }
    override fun onDestroy() { subscriptions.forEach { it.close() };if(::navigation.isInitialized)navigation.destroy();app.native.detach(this);super.onDestroy() }
}
