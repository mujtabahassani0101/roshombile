package com.haseebullahjan.roshd.messenger

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process

/** Serial Looper queues follow Telegram's storage/stage separation. */
class DispatchQueue(name: String): AutoCloseable {
    private val thread=HandlerThread(name, Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
    private val handler=Handler(thread.looper)
    fun post(delay: Long=0,task: ()->Unit): Runnable = Runnable(task).also { handler.postDelayed(it,delay) }
    fun cancel(task: Runnable) { handler.removeCallbacks(task) }
    override fun close() { thread.quitSafely() }
    companion object {
        val main=Handler(Looper.getMainLooper())
        fun ui(task: ()->Unit) { if(Looper.myLooper()==Looper.getMainLooper()) task() else main.post(task) }
    }
}
