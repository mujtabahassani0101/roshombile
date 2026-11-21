package com.haseebullahjan.roshd.ui.actionbar

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import org.json.JSONArray
import com.haseebullahjan.roshd.core.json

/** A single activity owns an explicit page stack, like Telegram's ActionBarLayout. */
class ActionBarLayout(context:Context,private val factory:(Route)->BaseFragment):FrameLayout(context) {
    private val stack=mutableListOf<BaseFragment>()
    private var resumed=false
    var onChanged:(Route)->Unit={}
    val current get()=stack.lastOrNull()?.route
    fun presentFragment(route:Route,replace:Boolean=false) {
        val page=factory(route)
        if(!page.onFragmentCreate())return
        if(resumed)stack.lastOrNull()?.onPause()
        if(replace && stack.isNotEmpty())stack.removeAt(stack.lastIndex).onFragmentDestroy()
        stack.add(page);show(page)
    }
    fun reset(route:Route) {
        stack.forEach { it.onFragmentDestroy() };stack.clear();removeAllViews();presentFragment(route)
    }
    private fun show(page:BaseFragment) {
        removeAllViews();val view=page.view();(view.parent as? ViewGroup)?.removeView(view)
        addView(view,LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.MATCH_PARENT));if(resumed)page.onResume();onChanged(page.route)
    }
    fun closeLastFragment():Boolean {
        if(stack.size<=1)return false
        stack.removeAt(stack.lastIndex).onFragmentDestroy();show(stack.last());return true
    }
    fun serialize():String=JSONArray(stack.map { json("name" to it.route.name,"args" to it.route.args,"scrollY" to (it.fragmentView?.findViewWithTag<android.widget.ScrollView>("page-scroll")?.scrollY ?: 0)) }).toString()
    fun restore(value:String) {
        val routes=JSONArray(value)
        require(routes.length() in 1..64)
        stack.forEach { it.onFragmentDestroy() };stack.clear();removeAllViews()
        for(i in 0 until routes.length()) {
            val row=routes.getJSONObject(i);presentFragment(Route(row.getString("name"),row.getJSONObject("args")))
            val scroll=stack.last().fragmentView?.findViewWithTag<android.widget.ScrollView>("page-scroll")
            scroll?.post { scroll.scrollTo(0,row.optInt("scrollY",0)) }
        }
    }
    fun pause() { if(resumed)stack.lastOrNull()?.onPause();resumed=false }
    fun resume() { if(!resumed) { resumed=true;stack.lastOrNull()?.onResume() } }
    fun destroy() { stack.forEach { it.onFragmentDestroy() };stack.clear() }
}
