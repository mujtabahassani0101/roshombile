package dev.paperframe.paperframe

import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/** Learned boundary proposals, robust line fitting, and a dense backward dewarp map. */
class LearnedGeometry(private val models: LocalModels) {
    data class Boundary(val corners: List<Point>, val confidence: Double)
    private var cachedCrop: String? = null
    private var cachedDisplacement: FloatArray? = null
    private var tileBuffer: ByteBuffer? = null

    fun detect(image: Mat): Boundary? {
        val prediction = models.predict(image)
        // The same 7x7 maximum was previously recomputed for every sample of every
        // candidate. Dilation computes exactly that neighbourhood once per frame.
        val support7 by lazy { neighbourhoodMax(prediction, 3) }
        val bytes = ByteArray(240 * 240) { (prediction[it] * 255).roundToInt().coerceIn(0, 255).toByte() }
        val heat = Mat(240, 240, CvType.CV_8UC1); heat.put(0, 0, bytes)
        val binary = Mat(); val hierarchy = Mat()
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(3.0, 3.0))
        var best: Boundary? = null
        var bestScore = 0.0
        try {
            // A boundary network produces a thin perimeter, not a filled paper mask.
            for (threshold in doubleArrayOf(40.0, 90.0, 155.0)) {
                Imgproc.threshold(heat, binary, threshold, 255.0, Imgproc.THRESH_BINARY)
                Imgproc.morphologyEx(binary, binary, Imgproc.MORPH_CLOSE, kernel)
                val contours = ArrayList<MatOfPoint>()
                try {
                    Imgproc.findContours(binary, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
                    for (contour in contours.sortedByDescending { abs(Imgproc.contourArea(it)) }.take(12)) {
                        val curve = MatOfPoint2f(*contour.toArray()); val approx = MatOfPoint2f()
                        try {
                            val perimeter = Imgproc.arcLength(curve, true)
                            for (epsilon in doubleArrayOf(.015, .025, .045, .065)) {
                                Imgproc.approxPolyDP(curve, approx, perimeter * epsilon, true)
                                if (approx.total() != 4L) continue
                                val polygon = MatOfPoint(*approx.toArray())
                                val area = abs(Imgproc.contourArea(polygon)) / (240 * 240)
                                val convex = Imgproc.isContourConvex(polygon); polygon.release()
                                if (!convex || area !in .10.. .97) continue
                                val points = order(approx.toArray().toList())
                                if (points.indices.any { distance(points[it], points[(it + 1) % 4]) < 25 }) continue
                                if (points.count { it.x < 2 || it.y < 2 || it.x > 237 || it.y > 237 } >= 3) continue
                                val support = edgeSupport(points, support7, 36, .06, .88)
                                if (support.min() < .28 || support.average() < .55) continue
                                val score = support.average() * (.7 + .3 * area)
                                if (score <= bestScore) continue
                                val fitted = refineHeat(points, prediction)
                                if (!validRefinement(fitted)) continue
                                val source = fitted.map { Point(it.x * image.cols() / 240, it.y * image.rows() / 240) }
                                best = Boundary(source, support.average()); bestScore = score
                            }
                        } finally { curve.release(); approx.release() }
                    }
                } finally { contours.forEach { it.release() } }
            }
            if (best == null) best = detectBrokenPerimeter(heat, prediction, image)
            return best
        } finally { heat.release(); binary.release(); hierarchy.release(); kernel.release() }
    }

    private fun neighbourhoodMax(prediction: FloatArray, radius: Int): FloatArray {
        val input = Mat(240, 240, CvType.CV_32FC1); val output = Mat()
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size((radius * 2 + 1).toDouble(), (radius * 2 + 1).toDouble()))
        return try {
            input.put(0, 0, prediction)
            Imgproc.dilate(input, output, kernel, Point(-1.0, -1.0), 1, Core.BORDER_REPLICATE)
            FloatArray(240 * 240).also { output.get(0, 0, it) }
        } finally { input.release(); output.release(); kernel.release() }
    }

    private fun edgeSupport(points: List<Point>, maximum: FloatArray, count: Int, inset: Double, span: Double): DoubleArray {
        return DoubleArray(4) { edge ->
            val a = points[edge]; val b = points[(edge + 1) % 4]
            var sum = 0.0
            for (sample in 0 until count) {
                val t = inset + span * sample / (count - 1)
                val x = (a.x + (b.x - a.x) * t).roundToInt().coerceIn(0, 239)
                val y = (a.y + (b.y - a.y) * t).roundToInt().coerceIn(0, 239)
                sum += max(0.0, maximum[y * 240 + x].toDouble())
            }
            sum / count
        }
    }

    /** Intersect supported line pairs when a shadow or a hand breaks the closed contour. */
    private fun detectBrokenPerimeter(heat: Mat, prediction: FloatArray, image: Mat): Boundary? {
        val support5 by lazy { neighbourhoodMax(prediction, 2) }
        val binary = Mat(); val segments = Mat()
        try {
            Imgproc.threshold(heat, binary, 65.0, 255.0, Imgproc.THRESH_BINARY)
            Imgproc.HoughLinesP(binary, segments, 1.0, Math.PI / 180, 35, 42.0, 20.0)
            data class Line(val a: Point, val b: Point) {
                val dx = b.x - a.x; val dy = b.y - a.y
                val length = hypot(dx, dy)
                fun signed(p: Point) = (dx * (p.y - a.y) - dy * (p.x - a.x)) / length
            }
            val proposals = (0 until segments.rows()).map { i ->
                val p = segments.get(i, 0); Line(Point(p[0], p[1]), Point(p[2], p[3]))
            }.sortedByDescending { it.length }.take(32)
            val lines = ArrayList<Line>()
            for (line in proposals) {
                if (lines.none { other ->
                    abs(line.dx * other.dx + line.dy * other.dy) / (line.length * other.length) > .996 &&
                        abs(other.signed(line.a)) < 4
                }) lines.add(line)
                if (lines.size == 20) break
            }
            data class Opposite(val a: Line, val b: Line, val priority: Double)
            val pairs = ArrayList<Opposite>()
            for (i in lines.indices) for (j in i + 1 until lines.size) {
                val a = lines[i]; val b = lines[j]
                val separation = abs(a.signed(b.a))
                if (separation >= 45 && abs(a.dx * b.dx + a.dy * b.dy) / (a.length * b.length) >= .5)
                    pairs.add(Opposite(a, b, min(a.length, b.length) * separation))
            }
            // Opposite sides are selected in their own orientation, including diagonal pages.
            // Deduplication and a bounded pair budget avoid testing thousands of duplicate quads.
            val opposites = pairs.sortedByDescending { it.priority }.take(24)
            fun crossing(a: Line, b: Line): Point? {
                val cross = a.dx * b.dy - a.dy * b.dx
                if (abs(cross) < a.length * b.length * .25) return null
                val t = ((b.a.x - a.a.x) * b.dy - (b.a.y - a.a.y) * b.dx) / cross
                return Point(a.a.x + t * a.dx, a.a.y + t * a.dy)
            }
            var best: Boundary? = null; var bestScore = 0.0
            for (i in opposites.indices) for (j in i + 1 until opposites.size) {
                    val a = opposites[i].a; val b = opposites[i].b
                    val c = opposites[j].a; val d = opposites[j].b
                    if (a === c || a === d || b === c || b === d) continue
                    val points = listOf(crossing(a, c), crossing(a, d), crossing(b, d), crossing(b, c))
                    if (points.any { it == null || it.x !in 1.0..238.0 || it.y !in 1.0..238.0 }) continue
                    val quad = order(points.filterNotNull())
                    val polygon = MatOfPoint(*quad.toTypedArray())
                    val area = abs(Imgproc.contourArea(polygon)) / 57600
                    val valid = Imgproc.isContourConvex(polygon); polygon.release()
                    if (!valid || area !in .12.. .94) continue
                    val supports = edgeSupport(quad, support5, 32, .05, .90)
                    if (supports.min() < .30 || supports.average() < .60) continue
                    val score = supports.average() * (.7 + .3 * area)
                    if (score > bestScore) {
                        val fitted = refineHeat(quad, prediction)
                        if (!validRefinement(fitted)) continue
                        best = Boundary(fitted.map { Point(it.x * image.cols() / 240, it.y * image.rows() / 240) }, supports.average())
                        bestScore = score
                    }
            }
            return best
        } finally { binary.release(); segments.release() }
    }

    private fun validRefinement(points: List<Point>): Boolean {
        if (points.any { !it.x.isFinite() || !it.y.isFinite() } ||
            points.count { it.x < 2 || it.y < 2 || it.x > 237 || it.y > 237 } >= 3) return false
        val polygon = MatOfPoint(*points.toTypedArray())
        return try { Imgproc.isContourConvex(polygon) } finally { polygon.release() }
    }

    private fun refineHeat(points: List<Point>, prediction: FloatArray): List<Point> {
        val lines = (0..3).map { i ->
            val a = points[i]; val b = points[(i + 1) % 4]
            val length = distance(a, b); val nx = -(b.y - a.y) / length; val ny = (b.x - a.x) / length
            val samples = (0 until 60).map { j ->
                val t = .08 + .84 * j / 59
                val x = a.x + (b.x - a.x) * t; val y = a.y + (b.y - a.y) * t
                var sum = 0.0; var weight = 0.0
                for (d in -5..5) {
                    val px = (x + nx * d).roundToInt().coerceIn(0, 239)
                    val py = (y + ny * d).roundToInt().coerceIn(0, 239)
                    val p = prediction[py * 240 + px].toDouble().pow(3)
                    sum += p * d; weight += p
                }
                val offset = if (weight > .1) sum / weight else 0.0
                Point(x + nx * offset, y + ny * offset)
            }
            fit(samples)
        }
        return intersections(lines, points, 9.0)
    }

    /** Fit whole sides at source resolution; individual text corners cannot pull the quad. */
    fun refineSource(gray: Mat, points: List<Point>): List<Point> {
            // Only sample the narrow bands around the four sides. Full-image floating-point
            // Sobel planes waste work and memory for a few thousand useful samples.
            val width = gray.cols(); val height = gray.rows()
            val pixels = ByteArray(width * height); gray.get(0, 0, pixels)
            fun value(x: Int, y: Int) = pixels[y.coerceIn(0, height - 1) * width + x.coerceIn(0, width - 1)].toInt() and 255
            val radius = (max(gray.rows(), gray.cols()) / 100).coerceIn(3, 16)
            val lines = (0..3).map { i ->
                val a = points[i]; val b = points[(i + 1) % 4]
                val length = distance(a, b); val nx = -(b.y - a.y) / length; val ny = (b.x - a.x) / length
                val samples = ArrayList<Point>()
                for (j in 0 until 90) {
                    val t = .08 + .84 * j / 89
                    val x = a.x + (b.x - a.x) * t; val y = a.y + (b.y - a.y) * t
                    var strongest = 20.0; var offset: Int? = null
                    for (d in -radius..radius) {
                        val px = (x + nx * d).roundToInt().coerceIn(0, width - 1)
                        val py = (y + ny * d).roundToInt().coerceIn(0, height - 1)
                        val gx = value(px + 1, py - 1) + 2 * value(px + 1, py) + value(px + 1, py + 1) -
                            value(px - 1, py - 1) - 2 * value(px - 1, py) - value(px - 1, py + 1)
                        val gy = value(px - 1, py + 1) + 2 * value(px, py + 1) + value(px + 1, py + 1) -
                            value(px - 1, py - 1) - 2 * value(px, py - 1) - value(px + 1, py - 1)
                        val strength = abs(gx * nx + gy * ny) / (1 + .025 * abs(d))
                        if (strength > strongest) { strongest = strength; offset = d }
                    }
                    if (offset != null) samples.add(Point(x + nx * offset, y + ny * offset))
                }
                if (samples.size >= 45) fit(samples) else fit(listOf(a, b))
            }
            return intersections(lines, points, radius * 1.8)
    }

    private fun fit(points: List<Point>): FloatArray {
        val input = MatOfPoint2f(*points.toTypedArray()); val output = Mat()
        try {
            Imgproc.fitLine(input, output, Imgproc.DIST_HUBER, 0.0, .01, .01)
            return FloatArray(4).also { output.get(0, 0, it) }
        } finally { input.release(); output.release() }
    }
    private fun intersections(lines: List<FloatArray>, fallback: List<Point>, maxMove: Double): List<Point> {
        val result = (0..3).map { i ->
            val a = lines[(i + 3) % 4]; val b = lines[i]
            val cross = a[0] * b[1] - a[1] * b[0]
            if (abs(cross) < .1) return fallback
            val t = ((b[2] - a[2]) * b[1] - (b[3] - a[3]) * b[0]) / cross
            Point(a[2] + t * a[0].toDouble(), a[3] + t * a[1].toDouble())
        }
        return if (result.indices.all { distance(result[it], fallback[it]) < maxMove }) result else fallback
    }

    /** Returns true only for a valid, smooth, non-folding dense map. */
    fun flatten(source: Mat, destination: Mat, cropKey: String, maxDimension: Int = 6000): Boolean {
        if (max(source.cols(), source.rows()).toDouble() / min(source.cols(), source.rows()) > 3) return false
        // Filters/rotation do not change the crop; reuse its model result across editor previews.
        val predictionKey = "$cropKey:${source.cols()}x${source.rows()}"
        val output = if (cachedCrop == predictionKey) cachedDisplacement!! else models.predict(source, dewarp = true).also {
            cachedCrop = predictionKey; cachedDisplacement = it
        }
        val flow = Mat(240, 240, CvType.CV_32FC2)
        val empty = Mat()
        val mean = MatOfDouble(); val std = MatOfDouble()
        try {
            val interleaved = FloatArray(240 * 240 * 2)
            for (i in 0 until 240 * 240) { interleaved[i * 2] = output[i]; interleaved[i * 2 + 1] = output[240 * 240 + i] }
            if (interleaved.any { !it.isFinite() || abs(it) > 120 }) return false
            flow.put(0, 0, interleaved)
            // Recovered from get_warp_mat: smooth the predicted displacement with a 39x39 kernel.
            Imgproc.GaussianBlur(flow, flow, Size(39.0, 39.0), -1.0)
            Core.meanStdDev(flow, mean, std)
            if (std.toArray().sum() < .5) return false
            flow.get(0, 0, interleaved)
            var verticalChange = 0.0
            for (i in 0 until 239) {
                // The native mode-2 denominator reads sum(xDifference)[1], zero for
                // a single-channel Mat (ELF bb4d0). Its effective factor uses only y.
                verticalChange += abs(interleaved[(120 * 240 + i + 1) * 2 + 1] - interleaved[(120 * 240 + i) * 2 + 1])
            }
            val aspect = (verticalChange / 240 + 1).coerceIn(1.0, 2.0)
            var minX = Float.POSITIVE_INFINITY; var maxX = Float.NEGATIVE_INFINITY
            var minY = Float.POSITIVE_INFINITY; var maxY = Float.NEGATIVE_INFINITY
            for (y in 0..239) for (x in 0..239) {
                val i = (y * 240 + x) * 2
                interleaved[i] += x; interleaved[i + 1] += y
                minX = min(minX, interleaved[i]); maxX = max(maxX, interleaved[i])
                minY = min(minY, interleaved[i + 1]); maxY = max(maxY, interleaved[i + 1])
            }
            if (maxX - minX < 160 || maxY - minY < 160) return false
            // Reject folding or extreme local stretching before touching the full-size image.
            for (y in 2..236 step 3) for (x in 2..236 step 3) {
                val i = (y * 240 + x) * 2; val right = i + 2; val down = i + 480
                val xx = interleaved[right] - interleaved[i]; val xy = interleaved[down] - interleaved[i]
                val yx = interleaved[right + 1] - interleaved[i + 1]; val yy = interleaved[down + 1] - interleaved[i + 1]
                if (xx * yy - xy * yx !in .15f..4f) return false
            }
            val outside = ByteArray(240 * 240)
            var needsBackground = false
            for (i in 0 until 240 * 240) {
                if (interleaved[i * 2] !in 0f..240f || interleaved[i * 2 + 1] !in 0f..240f) {
                    outside[i] = 255.toByte(); needsBackground = true
                }
                // The default dewarp_process mode 2 uses direct displacement coordinates.
                // Min/max normalization belongs to the separate get_dewarp_map API.
                interleaved[i * 2] = interleaved[i * 2] / 240 * source.cols()
                interleaved[i * 2 + 1] = interleaved[i * 2 + 1] / 240 * source.rows()
            }
            var width = source.cols().toDouble(); var height = source.rows().toDouble()
            if (aspect >= 1) width *= aspect else height /= aspect
            val scale = min(1.0, min(maxDimension / max(width, height), sqrt(12_500_000.0 / (width * height))))
            val outWidth = (width * scale).toInt().coerceAtLeast(32)
            val outHeight = (height * scale).toInt().coerceAtLeast(32)
            destination.create(outHeight, outWidth, source.type())
            val tileRows = min(256, outHeight)
            val required = outWidth * tileRows * 8
            if ((tileBuffer?.capacity() ?: 0) < required) tileBuffer = ByteBuffer.allocateDirect(required).order(ByteOrder.nativeOrder())
            val buffer = tileBuffer!!
            val background = Mat(); val backgroundTile = Mat(); val affine = Mat(2, 3, CvType.CV_64F)
            val maskBuffer = if (needsBackground) ByteBuffer.allocateDirect(outWidth * tileRows) else null
            try {
                if (needsBackground) {
                    // Only extrapolated pixels are painted. Reconstruct a smooth paper
                    // background at 320px; text and valid image pixels remain untouched.
                    val reduction = 320.0 / max(source.cols(), source.rows())
                    Imgproc.resize(source, background, Size(max(1.0, (source.cols() * reduction).toInt().toDouble()),
                        max(1.0, (source.rows() * reduction).toInt().toDouble())), 0.0, 0.0, Imgproc.INTER_AREA)
                    val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(31.0, 31.0))
                    try { Imgproc.morphologyEx(background, background, Imgproc.MORPH_CLOSE, kernel) }
                    finally { kernel.release() }
                    Imgproc.GaussianBlur(background, background, Size(7.0, 7.0), 0.0)
                }
                for (start in 0 until outHeight step tileRows) {
                    val count = min(tileRows, outHeight - start)
                    models.mapTile(interleaved, buffer, outWidth, outHeight, start, count)
                    val map = Mat(count, outWidth, CvType.CV_32FC2, buffer)
                    val tile = destination.rowRange(start, start + count)
                    try {
                        Imgproc.remap(source, tile, map, empty, Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE)
                        if (maskBuffer != null) {
                            models.maskTile(outside, maskBuffer, outWidth, outHeight, start, count)
                            val mask = Mat(count, outWidth, CvType.CV_8UC1, maskBuffer)
                            try {
                                val sx = background.cols().toDouble() / outWidth
                                val sy = background.rows().toDouble() / outHeight
                                affine.put(0, 0, sx, 0.0, .5 * sx - .5, 0.0, sy, (start + .5) * sy - .5)
                                Imgproc.warpAffine(background, backgroundTile, affine, Size(outWidth.toDouble(), count.toDouble()),
                                    Imgproc.INTER_LINEAR or Imgproc.WARP_INVERSE_MAP, Core.BORDER_REPLICATE)
                                backgroundTile.copyTo(tile, mask)
                            } finally { mask.release() }
                        }
                    } finally { tile.release(); map.release() }
                }
            } finally {
                background.release(); backgroundTile.release(); affine.release()
            }
            return true
        } finally { flow.release(); empty.release(); mean.release(); std.release() }
    }

    private fun distance(a: Point, b: Point) = hypot(a.x - b.x, a.y - b.y)
    private fun order(points: List<Point>): List<Point> {
        val cx = points.map { it.x }.average(); val cy = points.map { it.y }.average()
        val sorted = points.sortedBy { atan2(it.y - cy, it.x - cx) }
        val start = sorted.indices.minBy { sorted[it].x + sorted[it].y }
        return List(4) { sorted[(start + it) % 4] }
    }
}
