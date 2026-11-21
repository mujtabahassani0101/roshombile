package com.haseebullahjan.youngscientist.scanner

import com.haseebullahjan.youngscientist.DocumentImagePreparation
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import android.content.Context
import dev.paperframe.paperframe.PaperframeEngine
import dev.paperframe.paperframe.ScanArguments
import kotlin.math.*

object DocumentScannerNative {
    init { System.loadLibrary("document_scanner") }
    external fun detectLuma(bytes:ByteArray,width:Int,height:Int,rotation:Int):String
    external fun detectBuffer(buffer:ByteBuffer,offset:Int,limit:Int,rowStride:Int,left:Int,top:Int,width:Int,height:Int,rotation:Int):String
    external fun inspectFile(input:String):String
    external fun rectifyFile(input:String,output:String,corners:DoubleArray?,maxDimension:Int,turns:Int):String
}

data class DocumentDetection(val found:Boolean,val corners:List<Double>,val confidence:Double,
    val area:Double,val brightness:Double,val sharpness:Double,val reason:String) {
    companion object {
        fun parse(json:JSONObject):DocumentDetection {
            val values=json.optJSONArray("corners")
            val corners=if(values==null)emptyList() else (0 until values.length()).map { values.getDouble(it) }
            val found=json.optBoolean("found") && DocumentQuad.valid(corners,.18)
            return DocumentDetection(found,if(found)corners else emptyList(),json.optDouble("confidence",0.0),
                json.optDouble("area",0.0),json.optDouble("brightness",0.0),json.optDouble("sharpness",0.0),json.optString("reason","no_document"))
        }
    }
}

object DocumentQuad {
    val inset=listOf(.04,.04,.96,.04,.96,.96,.04,.96)
    fun valid(q:List<Double>,minimumArea:Double=.06):Boolean {
        if(q.size!=8 || q.any { !it.isFinite() || it !in 0.0..1.0 })return false
        var area=0.0
        for(i in 0..3) {
            val j=(i+1)%4;val k=(i+2)%4
            area+=q[i*2]*q[j*2+1]-q[j*2]*q[i*2+1]
            val cross=(q[j*2]-q[i*2])*(q[k*2+1]-q[j*2+1])-(q[j*2+1]-q[i*2+1])*(q[k*2]-q[j*2])
            if(cross<=.0001 || hypot(q[j*2]-q[i*2],q[j*2+1]-q[i*2+1])<.035)return false
        }
        return area*.5>=minimumArea
    }
}

object DocumentScanner {
    data class Prepared(val path:String,val rectified:Boolean,val metadata:JSONObject)
    private lateinit var context:Context
    private val engine by lazy { PaperframeEngine(context) }
    fun initialize(value:Context) { context=value.applicationContext }

    /** Still imports and renders share the source engine's serial entry point. */
    @Synchronized
    fun inspectFile(input:File,directory:File):List<Double>? {
        check(directory.isDirectory || directory.mkdirs())
        val imported=File(directory,"detected-upright.jpg")
        return try {
            val result=engine.importImage(ScanArguments(mapOf("path" to input.path,"output" to imported.path)))
            (result["corners"] as? List<*>)?.map { (it as Number).toDouble() }?.takeIf { DocumentQuad.valid(it) }
        } finally { imported.delete() }
    }
    fun inspectFrame(buffer:ByteBuffer,rowStride:Int,pixelStride:Int,left:Int,top:Int,width:Int,height:Int,rotation:Int):DocumentDetection {
        val report=if(buffer.isDirect && pixelStride==1)
            DocumentScannerNative.detectBuffer(buffer,buffer.position(),buffer.limit(),rowStride,left,top,width,height,rotation)
        else {
            val frame=DocumentFramePacker.pack(buffer,rowStride,pixelStride,left,top,width,height)
            DocumentScannerNative.detectLuma(frame.bytes,frame.width,frame.height,rotation)
        }
        return DocumentDetection.parse(JSONObject(report))
    }
    fun prepareForOmr(input:File,directory:File,corners:List<Double>?=null,turns:Int=0,maxDimension:Int=2600,
                      fastUpright:Boolean=true,uprightOutsideLock:Boolean=true):Prepared {
        check(directory.isDirectory || directory.mkdirs())
        if(corners!=null)require(DocumentQuad.valid(corners)) { "گوشه‌های برگه معتبر نیست؛ دوباره تنظیم کنید." }
        require(maxDimension in 320..6000)
        // Same pixels, without the importer's single-threaded Skia quarter turn.
        // The copy touches no engine state, so it is built before the engine
        // lock: a queued photo is prepared while the previous one is in Moji.
        fun upright()=runCatching { UprightImport.prepare(input,directory) }.getOrNull()
        val early=if(fastUpright && uprightOutsideLock)upright() else null
        try {
            return prepareLocked(input,directory,corners,turns,maxDimension) {
                early ?: if(fastUpright && !uprightOutsideLock)upright() else null
            }
        } finally { early?.delete() }
    }

    @Synchronized
    private fun prepareLocked(input:File,directory:File,corners:List<Double>?,turns:Int,maxDimension:Int,
                              uprightCopy:()->File?):Prepared {
        val imported=File(directory,"upright.jpg")
        val destination=File(directory,"document.jpg")
        return try {
            val upright=uprightCopy()
            val photo=try { engine.importImage(ScanArguments(mapOf("path" to (upright ?: input).path,"output" to imported.path))) }
                finally { upright?.delete() }
            val detected=(photo["corners"] as? List<*>)?.map { (it as Number).toDouble() }?.takeIf { DocumentQuad.valid(it) }
            val selected=corners ?: detected
            if(selected==null)return Prepared(imported.path,false,JSONObject().put("engine","moji-paperframe-3.0.2")
                .put("applied",false).put("dewarped",false).put("reason","no_document"))
            val rendered=engine.render(ScanArguments(mapOf("path" to imported.path,"output" to destination.path,
                "corners" to selected,"turns" to turns,"filter" to "original","maxDimension" to maxDimension,"dewarp" to true)))
            check(destination.isFile && destination.length()>0)
            Prepared(destination.path,true,JSONObject(rendered).put("engine","moji-paperframe-3.0.2")
                .put("applied",true).put("corners",org.json.JSONArray(selected)).put("learnedAvailable",engine.learnedAvailable)
                .put("detection",JSONObject().put("reason",if(corners==null)"automatic_crop" else "manual_crop")))
        } catch(error:Exception) {
            if(corners!=null)throw error
            val original=DocumentImagePreparation.prepareForHeader(input,File(directory,"fallback"),2000)
            Prepared(original,false,JSONObject().put("engine","moji-paperframe-3.0.2").put("applied",false)
                .put("dewarped",false).put("reason","scanner_unavailable").put("error",error.javaClass.simpleName))
        }
    }
}

/** Only the Y plane is copied. Sampling covers both ends of the crop rectangle exactly. */
object DocumentFramePacker {
    data class Frame(val bytes:ByteArray,val width:Int,val height:Int)
    fun pack(buffer:ByteBuffer,rowStride:Int,pixelStride:Int,left:Int,top:Int,width:Int,height:Int,limit:Int=960):Frame {
        require(width>=64 && height>=64 && left>=0 && top>=0 && rowStride>0 && pixelStride>0 && limit in 128..1200)
        val scale=min(1.0,limit.toDouble()/max(width,height))
        val w=(width*scale).roundToInt();val h=(height*scale).roundToInt()
        require(w>=64 && h>=64) { "Camera crop is too narrow" }
        val data=buffer.duplicate();val base=data.position()
        val lastColumn=(left.toLong()+width-1)*pixelStride
        require(lastColumn<rowStride) { "Camera row is truncated" }
        val last=base+(top.toLong()+height-1)*rowStride+lastColumn
        require(last<data.limit()) { "Incomplete camera frame" }
        val xs=IntArray(w) { x -> left+(x.toDouble()*(width-1)/(w-1)).roundToInt() }
        val result=ByteArray(w*h)
        for(y in 0 until h) {
            val sy=top+(y.toDouble()*(height-1)/(h-1)).roundToInt()
            val row=base+sy*rowStride
            for(x in 0 until w)result[y*w+x]=data.get(row+xs[x]*pixelStride)
        }
        return Frame(result,w,h)
    }
}
