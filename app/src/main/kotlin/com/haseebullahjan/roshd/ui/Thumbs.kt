package com.haseebullahjan.roshd.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.LruCache
import android.view.View
import android.widget.ImageView
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.FutureTask
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Small archive previews are reusable; full-page bitmaps belong only to their view. */
internal object Thumbs {
    private data class Key(val path:String,val max:Int,val bytes:Long,val modified:Long)
    private val cache=object:LruCache<Key,Bitmap>((Runtime.getRuntime().maxMemory()/16).coerceIn(2L*1024*1024,12L*1024*1024).toInt()) {
        override fun sizeOf(key:Key,value:Bitmap)=value.allocationByteCount
        // A displayed bitmap can outlive its cache entry. Let GC reclaim it.
    }
    private val worker=ThreadPoolExecutor(2,2,0,TimeUnit.MILLISECONDS,LinkedBlockingQueue<Runnable>()) { job ->
        Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);job.run() },"roshd-thumbnails")
    }
    private val main=Handler(Looper.getMainLooper())

    private class Request(view:ImageView,val key:Key):View.OnAttachStateChangeListener {
        val target=WeakReference(view)
        var task:FutureTask<Unit>?=null
        override fun onViewAttachedToWindow(view:View) { start() }
        override fun onViewDetachedFromWindow(view:View) {
            task?.let { it.cancel(false);worker.remove(it) };task=null
        }
        fun start() {
            val view=target.get() ?: return
            if(view.tag!==this || task!=null)return
            cache.get(key)?.let { view.setImageBitmap(it);view.removeOnAttachStateChangeListener(this);return }
            val job=FutureTask<Unit> {
                if(!current())return@FutureTask
                val options=BitmapFactory.Options().apply { inJustDecodeBounds=true }
                BitmapFactory.decodeFile(key.path,options)
                options.inJustDecodeBounds=false;options.inSampleSize=1
                while(maxOf(options.outWidth,options.outHeight)/options.inSampleSize>key.max)options.inSampleSize*=2
                if(!current())return@FutureTask
                val bitmap=runCatching { BitmapFactory.decodeFile(key.path,options) }.getOrNull()
                main.post {
                    val target=target.get()
                    if(target!=null && target.tag===this && target.isAttachedToWindow) {
                        if(bitmap!=null && key.max<=512)cache.put(key,bitmap)
                        target.setImageBitmap(bitmap);target.removeOnAttachStateChangeListener(this)
                    } else bitmap?.recycle()
                }
            }
            task=job;worker.execute(job)
        }
        private fun current():Boolean=target.get()?.let { it.tag===this && it.isAttachedToWindow }==true
        fun cancel() {
            task?.let { it.cancel(false);worker.remove(it) };task=null
            target.get()?.removeOnAttachStateChangeListener(this)
        }
    }
    fun load(view:ImageView,path:String,max:Int) {
        require(max>0)
        (view.tag as? Request)?.cancel()
        val file=File(path)
        val request=Request(view,Key(path,max,file.length(),file.lastModified()))
        view.tag=request;view.setImageDrawable(null)
        view.addOnAttachStateChangeListener(request)
        if(view.isAttachedToWindow)request.start()
    }
}
