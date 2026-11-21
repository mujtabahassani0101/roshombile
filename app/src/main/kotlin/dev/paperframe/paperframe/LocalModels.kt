package dev.paperframe.paperframe

import android.content.res.AssetManager
import androidx.annotation.Keep
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import java.nio.ByteBuffer

/** Persistent CPU sessions, serialized across the live detector and still-image worker. */
@Keep
class LocalModels(private val assets: AssetManager) : AutoCloseable {
    companion object { init { System.loadLibrary("paperframe_inference") } }
    private external fun create(border: ByteArray, dewarp: ByteArray): Long
    private external fun infer(handle: Long, image: ByteArray, dewarp: Boolean): FloatArray
    private external fun destroy(handle: Long)
    external fun mapTile(grid: FloatArray, buffer: ByteBuffer, width: Int, height: Int, start: Int, rows: Int)
    external fun maskTile(mask: ByteArray, buffer: ByteBuffer, width: Int, height: Int, start: Int, rows: Int)
    private var handle = 0L
    private var closed = false

    @Synchronized
    fun predict(image: Mat, dewarp: Boolean = false): FloatArray {
        check(!closed) { "Scanner engine is closed" }
        if (handle == 0L) {
            handle = create(assets.open("scanner/border.mnn").use { it.readBytes() },
                assets.open("scanner/dewarp.mnn").use { it.readBytes() })
        }
        val bgr = Mat(); val small = Mat()
        try {
            // LINEAR on three-channel RGB can precede the channel permutation.
            // Preserve AREA's accumulation order and gray/RGBA SIMD rounding;
            // moving those conversions changed some real-page model tensors.
            val resizeBeforeSwap = image.channels() == 3 && !dewarp
            var input = image
            if (!resizeBeforeSwap) {
                Imgproc.cvtColor(image, bgr, when (image.channels()) {
                    1 -> Imgproc.COLOR_GRAY2BGR
                    4 -> Imgproc.COLOR_RGBA2BGR
                    else -> Imgproc.COLOR_RGB2BGR
                })
                input = bgr
            }
            if (dewarp && minOf(input.rows(), input.cols()) >= 1024) {
                Imgproc.resize(input, small, Size(1024.0, 1024.0), 0.0, 0.0, Imgproc.INTER_AREA)
                input = small
            }
            if (dewarp && minOf(input.rows(), input.cols()) >= 352) {
                Imgproc.resize(input, small, Size(352.0, 352.0), 0.0, 0.0, Imgproc.INTER_AREA)
                input = small
            }
            Imgproc.resize(input, small, Size(240.0, 240.0), 0.0, 0.0,
                if (dewarp) Imgproc.INTER_AREA else Imgproc.INTER_LINEAR)
            val bytes = ByteArray(240 * 240 * 3)
            if (resizeBeforeSwap) {
                Imgproc.cvtColor(small, bgr, Imgproc.COLOR_RGB2BGR)
                bgr.get(0, 0, bytes)
            } else small.get(0, 0, bytes)
            return infer(handle, bytes, dewarp)
        } finally { bgr.release(); small.release() }
    }

    @Synchronized
    override fun close() { closed = true; if (handle != 0L) { destroy(handle); handle = 0 } }
}
