package dev.paperframe.paperframe

import android.os.SystemClock
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.pow

/** A serial frame consumer with one bounded background detector, never a frame queue. */
class LiveScanner(private val detector: (Mat, Mat) -> Map<String, Any?>) : AutoCloseable {
    private class Detection(val gray: Mat, val color: Mat, val map: Map<String, Any?>,
                            val capturedAt: Long, val durationMs: Long,
                            private var candidate: PageTracker?) : AutoCloseable {
        fun takeCandidate(): PageTracker? = candidate.also { candidate = null }
        override fun close() { candidate?.close(); gray.release(); color.release() }
    }
    private class Stationary(val gray: Mat, val color: Mat, var corners: List<Point>, val capturedAt: Long) : AutoCloseable {
        override fun close() { gray.release(); color.release() }
    }
    private val inference = Executors.newSingleThreadExecutor { task -> Thread(task, "PaperframeBoundary") }
    private val busy = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val generation = AtomicLong(0)
    private val completed = AtomicReference<Detection?>(null)
    private val failure = AtomicReference<Exception?>(null)
    private var tracker = PageTracker(1200)
    private var lastRequest = Long.MIN_VALUE
    private var lastModelMs = 0L
    private var opticalFlowWarmed = false // Owned by the inference executor.
    private var stationary: Stationary? = null // Owned only by the serial frame consumer.

    private fun clearStationary() { stationary?.close(); stationary = null }
    private fun sameColor(a: Mat, b: Mat): Boolean = a.type() == b.type() && a.size() == b.size() &&
        Core.norm(a, b, Core.NORM_L1) / (a.total() * minOf(3, a.channels())) < 2.0 &&
        Core.norm(a, b, Core.NORM_INF) < 32.0

    private fun stationaryCorners(gray: Mat, colorProvider: () -> Mat, now: Long): List<Point>? {
        val reference = stationary ?: return null
        if (now - reference.capturedAt > 1200 || gray.size() != reference.gray.size() ||
            Core.norm(gray, reference.gray, Core.NORM_L1) / gray.total() >= 2.0) {
            clearStationary(); return null
        }
        val color = colorProvider()
        return try {
            if (sameColor(color, reference.color)) reference.corners
            else { clearStationary(); null }
        } finally { color.release() }
    }

    // scan/reset/close belong to the scanner's serial worker. Only detector jobs run elsewhere.
    fun scan(gray: Mat, colorProvider: () -> Mat): Map<String, Any?> {
        check(!closed.get()) { "Live scanner is closed" }
        failure.getAndSet(null)?.let { throw it }
        val now = SystemClock.elapsedRealtime()
        var adopted: List<Point>? = null
        var detectorName = "tracked"
        completed.getAndSet(null)?.use { fresh ->
            lastModelMs = fresh.durationMs
            val normalized = fresh.map["corners"] as? List<*>
            if (normalized == null) {
                tracker.reset(); clearStationary()
            } else if (now - fresh.capturedAt <= 1200 && gray.size() == fresh.gray.size()) {
                val points = List(4) { i -> Point((normalized[i * 2] as Number).toDouble() * (gray.cols() - 1),
                    (normalized[i * 2 + 1] as Number).toDouble() * (gray.rows() - 1)) }
                val candidate = fresh.takeCandidate()!!
                var registered = candidate.track(gray)
                var acceptedStationary = false
                // A textureless stationary sheet can lack enough LK points. Accept it only
                // when both luminance and color still match the frame that was detected.
                if (registered == null && Core.norm(gray, fresh.gray, Core.NORM_L1) / gray.total() < 2.0) {
                    val color = colorProvider()
                    try {
                        if (sameColor(color, fresh.color)) {
                            candidate.seed(gray, points, (fresh.map["score"] as Number).toDouble(), fresh.capturedAt)
                            registered = points
                            clearStationary()
                            stationary = Stationary(gray.clone(), color.clone(), points, fresh.capturedAt)
                            acceptedStationary = true
                        }
                    } finally { color.release() }
                }
                if (registered != null) {
                    adopted = candidate.alignTo(tracker.corners)
                    if (acceptedStationary) stationary!!.corners = adopted else clearStationary()
                    tracker.close(); tracker = candidate
                    detectorName = fresh.map["engine"] as? String ?: "learned"
                } else candidate.close()
            }
        }
        var tracked = adopted ?: tracker.track(gray)
        if (tracked == null) {
            tracked = stationaryCorners(gray, colorProvider, now)
            if (tracked != null) detectorName = "stationary"
        }
        if (!busy.get() && (tracked == null || lastRequest == Long.MIN_VALUE || now - lastRequest >= 600)) {
            val snapshot = gray.clone()
            val color = try { colorProvider() } catch (e: Exception) { snapshot.release(); throw e }
            val token = generation.get()
            lastRequest = now; busy.set(true)
            inference.execute {
                var transfer = false
                var result: Detection? = null
                var candidate: PageTracker? = null
                try {
                    if (!closed.get() && token == generation.get()) {
                        val map = detector(snapshot, color)
                        val points = map["corners"] as? List<*>
                        if (points != null) {
                            candidate = PageTracker(1200)
                            candidate.seed(snapshot, List(4) { i -> Point((points[i * 2] as Number).toDouble() * (snapshot.cols() - 1),
                                (points[i * 2 + 1] as Number).toDouble() * (snapshot.rows() - 1)) },
                                (map["score"] as Number).toDouble(), now)
                            // Initialize LK kernels/pyramids off the frame thread, including on cold start.
                            if (!opticalFlowWarmed) opticalFlowWarmed = candidate.track(snapshot) != null
                        }
                        result = Detection(snapshot, color, map, now, SystemClock.elapsedRealtime() - now, candidate)
                        candidate = null
                        synchronized(completed) {
                            if (!closed.get() && token == generation.get()) {
                                completed.getAndSet(result)?.close(); transfer = true
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (!closed.get() && token == generation.get()) failure.set(e)
                } finally {
                    candidate?.close()
                    if (!transfer) {
                        if (result != null) result.close() else { snapshot.release(); color.release() }
                    }
                    busy.set(false)
                }
            }
        }
        val lap = Mat(); val mean = MatOfDouble(); val std = MatOfDouble()
        try {
            Imgproc.Laplacian(gray, lap, CvType.CV_16S); Core.meanStdDev(lap, mean, std)
            return mapOf("corners" to tracked?.flatMap { listOf(it.x / (gray.cols() - 1), it.y / (gray.rows() - 1)) },
                "score" to if (tracked == null) 0.0 else tracker.confidence,
                "sharpness" to std.toArray()[0].pow(2), "engine" to if (tracked == null) "pending" else detectorName,
                "modelPending" to busy.get(), "modelMs" to lastModelMs,
                "detectionAgeMs" to if (tracked == null) null else now - tracker.capturedAt)
        } finally { lap.release(); mean.release(); std.release() }
    }

    fun reset() {
        generation.incrementAndGet(); tracker.reset(); clearStationary(); lastRequest = Long.MIN_VALUE
        synchronized(completed) { completed.getAndSet(null)?.close() }
        failure.set(null)
    }
    override fun close() {
        closed.set(true); reset(); tracker.close(); inference.shutdown()
        inference.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)
    }
}
