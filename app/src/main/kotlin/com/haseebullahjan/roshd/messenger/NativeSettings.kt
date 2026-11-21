package com.haseebullahjan.roshd.messenger

import android.content.Context
import android.content.SharedPreferences

object NativeSettings {
    fun open(context:Context):SharedPreferences = context.getSharedPreferences("roshd_native_settings",Context.MODE_PRIVATE)
}
