package dev.paperframe.paperframe

import kotlin.math.hypot

/** Rejects crossed, concave, non-finite and degenerate normalized crop polygons. */
fun validQuad(p: List<Float>): Boolean {
    if (p.size != 8 || p.any { !it.isFinite() || it !in 0f..1f }) return false
    var area = 0f
    for (i in 0..3) {
        val j = (i + 1) % 4
        val k = (i + 2) % 4
        val ax = p[j * 2] - p[i * 2]
        val ay = p[j * 2 + 1] - p[i * 2 + 1]
        val bx = p[k * 2] - p[j * 2]
        val by = p[k * 2 + 1] - p[j * 2 + 1]
        if (ax * by - ay * bx < .001f) return false
        area += p[i * 2] * p[j * 2 + 1] - p[j * 2] * p[i * 2 + 1]
    }
    return area / 2 > .005f
}

/** Paperframe's 1.2-second capture gate. A fixed anchor also prevents slow drift from triggering it. */
class CaptureGate {
    private var last: List<Float>? = null
    private var anchor: List<Float>? = null
    private var since = 0L
    private var lastAt = 0L
    var progress = 0f
        private set

    fun reset() { last = null; anchor = null; since = 0; lastAt = 0; progress = 0f }

    fun update(corners: List<Float>?, score: Double, sharpness: Double, now: Long): Boolean {
        if (corners == null || !validQuad(corners) || score < .25 || sharpness < 45) {
            reset(); return false
        }
        fun near(reference: List<Float>?) = reference != null && (0..3).all {
            hypot(corners[it * 2] - reference[it * 2], corners[it * 2 + 1] - reference[it * 2 + 1]) < .018f
        }
        if (!(near(last) && now - lastAt in 0..849) || !near(anchor)) {
            since = now
            anchor = corners.toList()
        }
        last = corners.toList()
        lastAt = now
        progress = ((now - since) / 1200f).coerceIn(0f, 1f)
        return progress >= 1f
    }
}
