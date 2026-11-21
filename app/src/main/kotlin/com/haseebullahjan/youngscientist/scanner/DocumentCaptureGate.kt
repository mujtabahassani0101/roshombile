package com.haseebullahjan.youngscientist.scanner

import kotlin.math.abs
import kotlin.math.sqrt

/** Temporal policy only; geometry and image processing stay in the independent native engine. */
class DocumentCaptureGate {
    enum class Hint { FRAME,CLOSER,LIGHT,SHARPEN,HOLD,CAPTURE,REMOVE }
    private var previous:List<Double>?=null
    private var anchor:List<Double>?=null
    private var stableSince=0L
    private var stableFrames=0
    private var lastFrame:Long?=null
    private var absentSince:Long?=null
    var awaitingRemoval=false;private set
    fun reset() { previous=null;anchor=null;stableSince=0;stableFrames=0;lastFrame=null;absentSince=null }
    fun captured() { reset();awaitingRemoval=true }
    fun failed() { awaitingRemoval=false;reset() }
    fun observe(value:DocumentDetection?,now:Long):Hint {
        require(now>=0)
        lastFrame?.let { if(now<it || now-it>500)reset() }
        lastFrame=now
        if(awaitingRemoval) {
            if(value?.found!=true) {
                if(absentSince==null)absentSince=now
                if(now-absentSince!!>=450) { awaitingRemoval=false;reset() }
            } else absentSince=null
            return if(awaitingRemoval)Hint.REMOVE else Hint.FRAME
        }
        if(value?.found!=true || value.confidence<.75) { previous=null;stableFrames=0;return Hint.FRAME }
        if(value.area<.27) { previous=null;stableFrames=0;return Hint.CLOSER }
        if(value.brightness<48) { previous=null;stableFrames=0;return Hint.LIGHT }
        if(value.sharpness<30) { previous=null;stableFrames=0;return Hint.SHARPEN }
        val p=previous
        val reference=anchor
        val movement=if(p==null)Double.MAX_VALUE else sqrt(p.indices.sumOf { i -> val d=p[i]-value.corners[i];d*d }/8)
        val drift=if(reference==null)Double.MAX_VALUE else sqrt(reference.indices.sumOf { i -> val d=reference[i]-value.corners[i];d*d }/8)
        if(movement>.010 || drift>.008 || p==null || p.indices.any { abs(p[it]-value.corners[it])>.024 }) {
            stableSince=now;stableFrames=1;anchor=value.corners
        } else stableFrames++
        previous=value.corners
        return if(stableFrames>=7 && now-stableSince>=650)Hint.CAPTURE else Hint.HOLD
    }
}
