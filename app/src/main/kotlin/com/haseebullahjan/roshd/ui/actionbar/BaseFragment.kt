package com.haseebullahjan.roshd.ui.actionbar

import android.view.View
import com.haseebullahjan.roshd.MainActivity
import com.haseebullahjan.roshd.messenger.AppEvent
import com.haseebullahjan.roshd.ui.components.ClayTheme
import org.json.JSONObject

data class Route(val name:String,val args:JSONObject=JSONObject())

abstract class BaseFragment(val activity:MainActivity,val route:Route) {
    protected val app get()=activity.app
    protected val theme get()=ClayTheme(app.darkMode)
    private val subscriptions=mutableListOf<AutoCloseable>()
    var fragmentView:View?=null;private set
    open fun onFragmentCreate()=true
    abstract fun createView():View
    fun view():View=fragmentView ?: createView().also { fragmentView=it }
    open fun onResume() {}
    open fun onPause() { subscriptions.forEach { it.close() };subscriptions.clear() }
    open fun onFragmentDestroy() { onPause();fragmentView=null }
    protected fun observe(event:AppEvent,observer:(Any?)->Unit) { subscriptions.add(app.notifications.observe(event,observer)) }
    protected fun present(name:String,args:JSONObject=JSONObject())=activity.navigation.presentFragment(Route(name,args))
    protected fun finishFragment() { if(!activity.navigation.closeLastFragment())activity.navigation.reset(Route(if(app.session==null)"login" else "home")) }
    protected fun result(result:Result<String>)=activity.showResult(result)
}
