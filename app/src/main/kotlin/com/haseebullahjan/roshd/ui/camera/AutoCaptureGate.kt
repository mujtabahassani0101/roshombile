package com.haseebullahjan.roshd.ui.camera

import kotlin.math.*

/** A small, exposure-normalized page descriptor. It contains no full camera frame. */
class PageFingerprint(val values:ByteArray) {
    fun difference(other:PageFingerprint):Double {
        if(values.size!=SIZE || other.values.size!=SIZE)return 1.0
        return (0 until REGIONS).maxOf { region ->
            val start=region*SAMPLES
            var a=0.0;var b=0.0;var aa=0.0;var bb=0.0;var ab=0.0
            for(i in start until start+SAMPLES) {
                val x=(values[i].toInt() and 255).toDouble();val y=(other.values[i].toInt() and 255).toDouble()
                a+=x;b+=y;aa+=x*x;bb+=y*y;ab+=x*y
            }
            val va=(aa-a*a/SAMPLES).coerceAtLeast(0.0);val vb=(bb-b*b/SAMPLES).coerceAtLeast(0.0)
            if(va<SAMPLES*16 || vb<SAMPLES*16)0.0
            else (1-(ab-a*b/SAMPLES)/sqrt(va*vb)).coerceIn(0.0,1.0)
        }
    }
    companion object {
        private const val REGIONS=5
        private const val SAMPLES=16*12
        const val SIZE=REGIONS*SAMPLES
        fun sample(width:Int,height:Int,luma:ByteArray,corners:List<Float>,rotation:Int):PageFingerprint {
            require(width>=3 && height>=3 && luma.size==width*height && corners.size==8)
            val regions=arrayOf(floatArrayOf(.08f,.08f,.92f,.92f),floatArrayOf(.04f,.02f,.50f,.27f),
                floatArrayOf(.50f,.02f,.96f,.27f),floatArrayOf(.04f,.73f,.50f,.98f),floatArrayOf(.50f,.73f,.96f,.98f))
            val values=ByteArray(SIZE)
            // A 3×3 descriptor at a 1280 px preview reset on a 2 px hand tremor.
            // Average a small, resolution-scaled neighborhood for stability;
            // the independent Laplacian check still measures actual sharpness.
            val radius=(min(width,height)/192).coerceIn(1,6)
            for((region,box) in regions.withIndex())for(y in 0 until 12)for(x in 0 until 16) {
                val u=box[0]+(box[2]-box[0])*(x+.5f)/16
                val v=box[1]+(box[3]-box[1])*(y+.5f)/12
                val px=(corners[0]*(1-u)+corners[2]*u)*(1-v)+(corners[6]*(1-u)+corners[4]*u)*v
                val py=(corners[1]*(1-u)+corners[3]*u)*(1-v)+(corners[7]*(1-u)+corners[5]*u)*v
                val sx=when(rotation) { 90->py;180->1-px;270->1-py;else->px }
                val sy=when(rotation) { 90->1-px;180->1-py;270->px;else->py }
                val ix=(sx*(width-1)).roundToInt().coerceIn(radius,width-radius-1)
                val iy=(sy*(height-1)).roundToInt().coerceIn(radius,height-radius-1)
                var total=0
                for(dy in -radius..radius)for(dx in -radius..radius)total+=luma[(iy+dy)*width+ix+dx].toInt() and 255
                values[region*SAMPLES+y*16+x]=(total/((radius*2+1)*(radius*2+1))).toByte()
            }
            return PageFingerprint(values)
        }
    }
}

/** Main-thread state machine: stable, sharp, current observations only; one shot per page. */
class AutoCaptureGate(private val holdMs:Long=600,private val minimumFrames:Int=4) {
    enum class Hint { FIND_PAGE, MOVE_CLOSER, CENTER_PAGE, SHOW_EDGES, ALIGN_GUIDES, SHOW_QR, SHARPEN, HOLD_STILL, NEXT_PAGE, CAPTURE }
    data class Observation(val timeMs:Long,val corners:List<Float>?,val sharpness:Double,val confidence:Double,
                           val detectionAgeMs:Long,val fingerprint:PageFingerprint?,val pageId:String?=null,
                           val verifiedAnchors:Boolean=false,val guided:Boolean=false,val alignedMarkers:Boolean=false)
    data class Decision(val hint:Hint,val progress:Float=0f)
    private var lastTime=Long.MIN_VALUE
    private var anchor:Observation?=null
    private var frames=0
    private var captured:PageFingerprint?=null
    private var capturedIdentity:String?=null
    private var beforeCaptureIdentity:String?=null
    private var beforeCapture:PageFingerprint?=null
    private var latest:Observation?=null

    fun clearStability() { anchor=null;frames=0 }
    fun forgetCapturedPage() {
        captured=null;capturedIdentity=null;beforeCapture=null;beforeCaptureIdentity=null;latest=null;clearStability()
    }
    fun savedFingerprint()=captured?.values?.copyOf()
    fun savedIdentity()=capturedIdentity
    /** The original-photo reader can confirm identity when the small preview QR was unreadable. */
    fun confirmCapturedIdentity(identity:String) { if(captured!=null && identity.isNotBlank())capturedIdentity=identity }
    fun restoreFingerprint(value:ByteArray?,identity:String?=null) { if(value?.size==PageFingerprint.SIZE)captured=PageFingerprint(value.copyOf());capturedIdentity=identity;clearStability() }
    fun captureStarted() { beforeCapture=captured;beforeCaptureIdentity=capturedIdentity;captured=latest?.fingerprint ?: captured;capturedIdentity=latest?.pageId;clearStability() }
    fun captureFailed() { captured=beforeCapture;capturedIdentity=beforeCaptureIdentity;clearStability() }
    fun update(sample:Observation):Decision {
        if(sample.timeMs<=lastTime)return Decision(Hint.HOLD_STILL)
        if(lastTime!=Long.MIN_VALUE && sample.timeMs-lastTime>500)clearStability()
        lastTime=sample.timeMs;latest=sample
        val points=sample.corners
        fun reject(hint:Hint):Decision { clearStability();return Decision(hint) }
        if(points?.size!=8 || points.any { !it.isFinite() } || sample.detectionAgeMs !in 0..1000 || !sample.confidence.isFinite() || sample.confidence<.18)
            return reject(Hint.FIND_PAGE)
        val margin=if(sample.verifiedAnchors).012f else .025f
        if(points.any { it !in margin.. (1-margin) })return reject(Hint.SHOW_EDGES)
        val crosses=(0..3).map { i ->
            val j=(i+1)%4;val k=(i+2)%4
            (points[j*2]-points[i*2])*(points[k*2+1]-points[j*2+1])-
                (points[j*2+1]-points[i*2+1])*(points[k*2]-points[j*2])
        }
        if(crosses.any { it<=.002f })return reject(Hint.SHOW_EDGES)
        val area=abs((0..3).sumOf { i -> val j=(i+1)%4;(points[i*2]*points[j*2+1]-points[j*2]*points[i*2+1]).toDouble() })/2
        if(area<.40)return reject(Hint.MOVE_CLOSER)
        // Verified print geometry is safe off center; the camera meters on the
        // detected page. Generic quadrilaterals keep the conservative bounds.
        val centerTolerance=if(sample.verifiedAnchors).18 else .08
        if(abs((0..3).sumOf { points[it*2].toDouble() }/4-.5)>centerTolerance ||
            abs((0..3).sumOf { points[it*2+1].toDouble() }/4-.5)>centerTolerance)return reject(Hint.CENTER_PAGE)
        if(sample.guided && !sample.alignedMarkers)return reject(Hint.ALIGN_GUIDES)
        if(!sample.sharpness.isFinite() || sample.sharpness<65)return reject(Hint.SHARPEN)
        // Four real black-on-white targets plus sharp, steady answer detail can
        // admit the first photograph while QR decoding runs independently.
        // Later photographs still need identity to distinguish similar forms.
        if(sample.guided && sample.pageId==null && captured!=null)return reject(Hint.SHOW_QR)
        val fingerprint=sample.fingerprint ?: return reject(Hint.HOLD_STILL)
        val sameContent=captured?.difference(fingerprint)?.let { it<.24 }==true
        if(sameContent && capturedIdentity==null && sample.pageId!=null)capturedIdentity=sample.pageId
        val differentIdentity=capturedIdentity!=null && sample.pageId!=null && sample.pageId!=capturedIdentity
        if(capturedIdentity!=null && sample.pageId==capturedIdentity)return reject(Hint.NEXT_PAGE)
        if(sameContent && !differentIdentity)return reject(Hint.NEXT_PAGE)
        val first=anchor
        val verified=sample.verifiedAnchors && first?.verifiedAnchors==true
        val steady=first!=null && first.corners!=null && sample.timeMs-first.timeMs<=4000 &&
            sample.verifiedAnchors==first.verifiedAnchors && sample.guided==first.guided &&
            (0 until 4).maxOf { i -> hypot(points[i*2]-first.corners[i*2],points[i*2+1]-first.corners[i*2+1]) }<(if(verified).016f else .009f) &&
            first.fingerprint?.difference(fingerprint)?.let { it<.13 }==true &&
            (first.pageId==null || sample.pageId==null || first.pageId==sample.pageId)
        if(!steady) { anchor=sample;frames=1;return Decision(Hint.HOLD_STILL) }
        frames++
        val elapsed=sample.timeMs-first!!.timeMs
        val requiredHold=when { sample.guided->minOf(holdMs,240L);verified->minOf(holdMs,320L);else->holdMs }
        val progress=(elapsed.toFloat()/requiredHold).coerceIn(0f,1f)
        return Decision(if(elapsed>=requiredHold && frames>=minimumFrames)Hint.CAPTURE else Hint.HOLD_STILL,progress)
    }
}
