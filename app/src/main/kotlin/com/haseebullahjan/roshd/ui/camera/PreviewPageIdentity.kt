package com.haseebullahjan.roshd.ui.camera

/** Preview QR is only a duplicate-capture hint. Native header + signed roster remain authoritative. */
class PreviewPageIdentity(private val verifiedHistorical:Set<String> = emptySet()) {
    private var candidate:String?=null
    private var observations=0
    private var at=Long.MIN_VALUE
    private var lastCallback=Long.MIN_VALUE
    private var fingerprint:PageFingerprint?=null
    fun clear() { candidate=null;observations=0;at=Long.MIN_VALUE;lastCallback=Long.MIN_VALUE;fingerprint=null }
    fun update(value:String?,timeMs:Long,completedAtMs:Long=timeMs,frame:PageFingerprint?=null) {
        if(timeMs<=lastCallback || completedAtMs-timeMs !in 0..1800)return
        lastCallback=timeMs
        // A missed decode is not evidence of a different page. The bounded
        // frame age and image descriptor below prevent stale identity reuse.
        if(value==null)return
        val accepted=value.takeIf { PRINTED.matches(it) || LEGACY.matches(it) || it in verifiedHistorical }
        observations=if(accepted!=null && accepted==candidate && timeMs-at<=1600)observations+1 else 1
        candidate=accepted;at=timeMs;fingerprint=frame
    }
    fun current(timeMs:Long,frame:PageFingerprint?=null,aligned:Boolean=false)=candidate?.takeIf {
        observations>=(if(aligned)1 else 2) && timeMs-at in 0..1800 &&
            (frame==null || fingerprint?.difference(frame)?.let { it<.18 }!=false)
    }
    companion object {
        private val PRINTED=Regex("^T[13]:[0-9]{1,32}:(?:S2[0123456789ABCDEFGHJKMNPQRSTVWXYZ]{18}|[0-9A-Za-z]{8})$")
        private val LEGACY=Regex("^S2[0123456789ABCDEFGHJKMNPQRSTVWXYZ]{18}$")
    }
}
