package com.haseebullahjan.roshd.platform

import android.os.Looper
import com.haseebullahjan.roshd.NativeHostActivity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class NativeGateway {
    private val hosts=WeakHostRegistry<NativeHostActivity> { !it.isDestroyed }
    fun attach(activity: NativeHostActivity) { hosts.attach(activity) }
    fun detach(activity: NativeHostActivity) { hosts.detach(activity) }
    fun call(method: String,args: Map<String,Any?> = emptyMap(),callback: (Result<Any?>)->Unit) {
        val activity=hosts.current()
            ?: return callback(Result.failure(IllegalStateException("صفحهٔ برنامه در دسترس نیست.")))
        activity.nativeCall(NativeCall(method,args),object:NativeResult {
            override fun success(value: Any?)=callback(Result.success(if(value is ByteArray)value.copyOf() else value))
            override fun error(code:String,message:String?,details:Any?)=callback(Result.failure(IllegalStateException(message ?: code)))
        })
    }
    fun blocking(method: String,args: Map<String,Any?> = emptyMap()): Any? {
        check(Looper.myLooper()!=Looper.getMainLooper()) { "Native work must not block the UI" }
        val latch=CountDownLatch(1); var response: Result<Any?>?=null
        call(method,args) { response=it;latch.countDown() }
        check(latch.await(3,TimeUnit.MINUTES)) { "زمان اجرای عملیات پایان یافت؛ معلومات محفوظ است." }
        return response!!.getOrThrow()
    }
}
