package dev.paperframe.paperframe

import org.opencv.core.Mat

/** One preview geometry, owned by MainActivity's serial worker. Filters never mutate it. */
class RenderGeometryCache(private val maxPixels: Long = 3_000_000) : AutoCloseable {
    data class Hit(val image: Mat, val dewarped: Boolean)
    private var key: String? = null
    private var entry: Hit? = null

    fun find(requestKey: String): Hit? = if (key == requestKey) entry else null

    fun store(requestKey: String, image: Mat, dewarped: Boolean) {
        if (image.empty() || image.total() > maxPixels) return
        val replacement = Hit(image.clone(), dewarped)
        entry?.image?.release()
        entry = replacement
        key = requestKey
    }

    override fun close() {
        entry?.image?.release()
        entry = null
        key = null
    }
}
