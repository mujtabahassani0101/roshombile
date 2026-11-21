package dev.paperframe.paperframe

import android.os.SystemClock
import org.opencv.calib3d.Calib3d
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video
import kotlin.math.*

/** Forward/backward LK validation and a RANSAC homography between learned detections. */
class PageTracker(private val maxAgeMs: Long = 900) : AutoCloseable {
    private val previous = Mat()
    private var features = emptyList<Point>()
    private var quad = emptyList<Point>()
    private var lastDetection = 0L
    var confidence = 0.0
        private set
    val capturedAt: Long get() = lastDetection
    val corners: List<Point> get() = quad
    fun alignTo(reference: List<Point>): List<Point> {
        quad = CornerOrder.match(quad, reference)
        return quad
    }

    fun track(gray: Mat): List<Point>? {
        if (previous.empty() || previous.size() != gray.size() || features.size < 4 ||
            SystemClock.elapsedRealtime() - lastDetection > maxAgeMs) return null
        val before = MatOfPoint2f(*features.toTypedArray()); val after = MatOfPoint2f(); val back = MatOfPoint2f()
        val status = MatOfByte(); val reverseStatus = MatOfByte(); val errors = MatOfFloat(); val reverseErrors = MatOfFloat()
        val mask = Mat(); val inliers = Mat()
        var homography: Mat? = null
        val from = MatOfPoint2f(); val to = MatOfPoint2f(); val inputQuad = MatOfPoint2f(*quad.toTypedArray()); val outputQuad = MatOfPoint2f()
        try {
            Video.calcOpticalFlowPyrLK(previous, gray, before, after, status, errors, Size(21.0, 21.0), 3)
            Video.calcOpticalFlowPyrLK(gray, previous, after, back, reverseStatus, reverseErrors, Size(21.0, 21.0), 3)
            val next = after.toArray(); val returned = back.toArray()
            val ok = status.toArray(); val reverse = reverseStatus.toArray(); val error = errors.toArray()
            val valid = features.indices.filter { i -> ok[i].toInt() != 0 && reverse[i].toInt() != 0 && error[i] < 25 &&
                hypot(returned[i].x - features[i].x, returned[i].y - features[i].y) < 1.2 &&
                next[i].x in 1.0..(gray.cols() - 2.0) && next[i].y in 1.0..(gray.rows() - 2.0) }
            if (valid.size < 4 || valid.size < features.size * .55) return null
            from.fromList(valid.map { features[it] }); to.fromList(valid.map { next[it] })
            homography = Calib3d.findHomography(from, to, Calib3d.RANSAC, 1.8, inliers)
            if (homography.empty() || Core.countNonZero(inliers) < max(4.0, valid.size * .75)) return null
            Core.perspectiveTransform(inputQuad, outputQuad, homography)
            val result = outputQuad.toArray().toList()
            if (result.any { it.x !in 0.0..(gray.cols() - 1.0) || it.y !in 0.0..(gray.rows() - 1.0) } ||
                result.indices.any { hypot(result[it].x - quad[it].x, result[it].y - quad[it].y) > min(gray.rows(), gray.cols()) * .12 }) return null
            val polygon = MatOfPoint(*result.toTypedArray())
            val convex = Imgproc.isContourConvex(polygon); polygon.release()
            if (!convex) return null
            features = valid.map { next[it] }; quad = result; gray.copyTo(previous)
            return result
        } finally {
            before.release(); after.release(); back.release(); status.release(); reverseStatus.release(); errors.release(); reverseErrors.release()
            mask.release(); inliers.release(); from.release(); to.release(); inputQuad.release(); outputQuad.release(); homography?.release()
        }
    }

    fun seed(gray: Mat, corners: List<Point>?, score: Double, capturedAt: Long = SystemClock.elapsedRealtime()) {
        reset()
        if (corners == null) return
        val mask = Mat.zeros(gray.size(), CvType.CV_8UC1); val polygon = MatOfPoint(*corners.toTypedArray()); val found = MatOfPoint()
        try {
            Imgproc.fillConvexPoly(mask, polygon, Scalar(255.0))
            Imgproc.goodFeaturesToTrack(gray, found, 64, .015, 8.0, mask)
            features = found.toArray().toList()
            quad = corners; confidence = score
            gray.copyTo(previous); lastDetection = capturedAt
        } finally { mask.release(); polygon.release(); found.release() }
    }

    fun reset() { previous.release(); features = emptyList(); quad = emptyList(); confidence = 0.0; lastDetection = 0 }
    override fun close() = reset()
}
