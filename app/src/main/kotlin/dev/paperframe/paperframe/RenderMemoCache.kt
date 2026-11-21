package dev.paperframe.paperframe

import org.opencv.core.Mat
import java.util.LinkedHashMap

/** Read-only filter results and exact JPEGs for one retained preview geometry. */
class RenderMemoCache(
    private val filterBudget: Long = 9_000_000,
    private val jpegBudget: Long = 2_000_000,
) : AutoCloseable {
    data class Variant(val mode: String, val turns: Int, val brightness: Double, val contrast: Double)
    data class Encoded(val bytes: ByteArray, val width: Int, val height: Int)
    private var geometry: String? = null
    private val filters = LinkedHashMap<String, Mat>(4, .75f, true)
    private val encoded = LinkedHashMap<Variant, Encoded>(4, .75f, true)
    private var filterBytes = 0L
    private var jpegBytes = 0L

    fun useGeometry(key: String) {
        if (geometry != key) { close(); geometry = key }
    }

    fun filter(mode: String): Mat? = filters[mode]
    fun encoded(variant: Variant): Encoded? = encoded[variant]

    fun storeFilter(mode: String, image: Mat) {
        val size = (image.total() * image.elemSize()).toLong()
        if (mode == "original" || image.empty() || size > filterBudget) return
        filters.remove(mode)?.let { filterBytes -= bytes(it); it.release() }
        while (filterBytes + size > filterBudget && filters.isNotEmpty()) {
            val oldest = filters.entries.iterator()
            val entry = oldest.next()
            filterBytes -= bytes(entry.value); entry.value.release(); oldest.remove()
        }
        filters[mode] = image.clone()
        filterBytes += size
    }

    fun storeEncoded(variant: Variant, bytes: ByteArray, width: Int, height: Int) {
        if (bytes.size > jpegBudget) return
        encoded.remove(variant)?.let { jpegBytes -= it.bytes.size }
        while (jpegBytes + bytes.size > jpegBudget && encoded.isNotEmpty()) {
            val oldest = encoded.entries.iterator()
            jpegBytes -= oldest.next().value.bytes.size; oldest.remove()
        }
        encoded[variant] = Encoded(bytes, width, height)
        jpegBytes += bytes.size
    }

    private fun bytes(image: Mat) = (image.total() * image.elemSize()).toLong()

    override fun close() {
        filters.values.forEach { it.release() }
        filters.clear(); encoded.clear()
        filterBytes = 0; jpegBytes = 0; geometry = null
    }
}
