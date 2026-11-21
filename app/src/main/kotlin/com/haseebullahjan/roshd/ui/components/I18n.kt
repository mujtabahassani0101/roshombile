package com.haseebullahjan.roshd.ui.components

import android.content.Context
import org.json.JSONObject

/** Static interface phrases only. Student names and server documents stay verbatim. */
object I18n {
    @Volatile private var pashto:Map<String,String>?=null
    fun isPashto(context:Context)=context.getSharedPreferences("roshd_native_settings",Context.MODE_PRIVATE).getString("language","fa")=="ps"
    fun phrase(context:Context,value:String):String {
        if(context.getSharedPreferences("roshd_native_settings",Context.MODE_PRIVATE).getString("language","fa")!="ps")return value
        val dictionary=pashto ?: synchronized(this) {
            pashto ?: JSONObject(context.assets.open("native-i18n-ps.json").bufferedReader().use { it.readText() }).let { json ->
                json.keys().asSequence().associateWith { json.getString(it) }.also { pashto=it }
            }
        }
        return dictionary[value] ?: value
    }
}
