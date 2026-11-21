package com.haseebullahjan.roshd.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.ImageView

/** Pinch, pan and double-tap let the individual bubbles remain readable on a saved sheet. */
// The app uses platform Material themes, and this view displays bitmaps without AppCompat tinting.
@android.annotation.SuppressLint("AppCompatCustomView")
class SheetPreviewView(context:Context):ImageView(context) {
    private val transform=Matrix()
    private var fit=1f
    private var zoom=1f
    private var lastX=0f;private var lastY=0f
    private val scaling=ScaleGestureDetector(context,object:ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector:ScaleGestureDetector):Boolean {
            val target=(zoom*detector.scaleFactor).coerceIn(fit,fit*8)
            transform.postScale(target/zoom,target/zoom,detector.focusX,detector.focusY);zoom=target;constrain();return true
        }
    })
    private val gestures=GestureDetector(context,object:GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event:MotionEvent)=true
        override fun onDoubleTap(event:MotionEvent):Boolean {
            if(zoom>fit*1.1f)reset() else { transform.postScale(3f,3f,event.x,event.y);zoom=fit*3;constrain() }
            return true
        }
        override fun onSingleTapConfirmed(event:MotionEvent):Boolean=performClick()
    })
    init { scaleType=ScaleType.MATRIX;isFocusable=true;contentDescription=I18n.phrase(context,"برگه؛ برای بزرگ‌نمایی از دو انگشت استفاده کنید.") }
    override fun setImageBitmap(bitmap:Bitmap?) { super.setImageBitmap(bitmap);reset() }
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int) { super.onSizeChanged(w,h,oldw,oldh);reset() }
    private fun reset() {
        val image=drawable ?: return
        if(width<=0 || height<=0 || image.intrinsicWidth<=0 || image.intrinsicHeight<=0)return
        fit=minOf(width.toFloat()/image.intrinsicWidth,height.toFloat()/image.intrinsicHeight);zoom=fit
        transform.setScale(fit,fit)
        transform.postTranslate((width-image.intrinsicWidth*fit)/2,(height-image.intrinsicHeight*fit)/2);imageMatrix=transform
    }
    private fun constrain() {
        val image=drawable ?: return
        val bounds=RectF(0f,0f,image.intrinsicWidth.toFloat(),image.intrinsicHeight.toFloat());transform.mapRect(bounds)
        fun offset(start:Float,end:Float,size:Int)=if(end-start<=size)(size-(end-start))/2-start else when { start>0->-start;end<size->size-end;else->0f }
        transform.postTranslate(offset(bounds.left,bounds.right,width),offset(bounds.top,bounds.bottom,height));imageMatrix=transform
    }
    @android.annotation.SuppressLint("ClickableViewAccessibility") // GestureDetector calls performClick on a confirmed tap.
    override fun onTouchEvent(event:MotionEvent):Boolean {
        scaling.onTouchEvent(event);gestures.onTouchEvent(event)
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN->{ lastX=event.x;lastY=event.y }
            MotionEvent.ACTION_MOVE->{
                if(!scaling.isInProgress && event.pointerCount==1 && zoom>fit) { transform.postTranslate(event.x-lastX,event.y-lastY);constrain() }
                lastX=event.x;lastY=event.y
            }
        }
        parent?.requestDisallowInterceptTouchEvent(event.actionMasked !in setOf(MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL) && (event.pointerCount>1 || zoom>fit*1.01f))
        return true
    }
    override fun performClick():Boolean { super.performClick();return true }
}
