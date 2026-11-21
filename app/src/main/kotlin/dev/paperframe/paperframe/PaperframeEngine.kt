package dev.paperframe.paperframe

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Build
import android.os.SystemClock
import androidx.exifinterface.media.ExifInterface
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.ByteArrayOutputStream
import kotlin.math.*

/** Typed replacement for the former Flutter MethodCall transport. No Flutter runtime is used. */
class ScanArguments(private val values: Map<String, Any?>) {
    @Suppress("UNCHECKED_CAST")
    fun <T> argument(key: String): T? = values[key] as? T
}

/** Paperframe 1.2.4's complete image pipeline, consumed on serial background workers. */
class PaperframeEngine(context: Context) : AutoCloseable {
    private val assets = context.applicationContext.assets
    // Original supplied MNN binary is ARM64. Other ABIs use the contour detector and perspective renderer.
    val learnedAvailable = Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a"
    private val modelDelegate = lazy { LocalModels(assets) }
    private val models by modelDelegate
    private val geometry by lazy { LearnedGeometry(models) }
    private val renderGeometry = RenderGeometryCache()
    private val renderMemo = RenderMemoCache()
    private val liveDelegate = lazy { LiveScanner { gray, color -> detect(gray, color) } }
    private val live by liveDelegate
    init { check(OpenCVLoader.initLocal()) { "OpenCV could not initialize" } }
    fun resetLive() { if (liveDelegate.isInitialized()) live.reset() }
    override fun close() {
        if (liveDelegate.isInitialized()) live.close()
        renderGeometry.close()
        renderMemo.close()
        if (modelDelegate.isInitialized()) models.close()
    }

    fun detectFrame(call: ScanArguments): Map<String, Any?> {
        val width = call.argument<Int>("width")!!
        val height = call.argument<Int>("height")!!
        val stride = call.argument<Int>("stride")!!
        val pixelStride = call.argument<Int>("pixelStride") ?: 1
        val data = call.argument<ByteArray>("bytes")!!
        require(width > 0 && height > 0 && stride >= width)
        val step = max(1, ceil(max(width, height) / 720.0).toInt())
        val w = width / step
        val h = height / step
        val packed = ByteArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val offset = y * step * stride + x * step * pixelStride
            require(offset < data.size) { "Invalid camera plane layout" }
            packed[y * w + x] = data[offset]
        }
        val gray = Mat(h, w, CvType.CV_8UC1)
        try {
            gray.put(0, 0, packed)
            when (call.argument<Int>("rotation") ?: 0) {
                90 -> Core.rotate(gray, gray, Core.ROTATE_90_CLOCKWISE)
                180 -> Core.rotate(gray, gray, Core.ROTATE_180)
                270 -> Core.rotate(gray, gray, Core.ROTATE_90_COUNTERCLOCKWISE)
            }
            val started = SystemClock.elapsedRealtime()
            val chroma = call.argument<ByteArray>("chroma")
            val detected = live.scan(gray) {
                if (chroma == null || step != 1 || w % 2 != 0 || h % 2 != 0) return@scan gray.clone()
                require(chroma.size == w * h / 2) { "Invalid camera chroma layout" }
                val yuv = Mat(h + h / 2, w, CvType.CV_8UC1); val color = Mat()
                try {
                    yuv.put(0, 0, packed); yuv.put(h, 0, chroma)
                    Imgproc.cvtColor(yuv, color, Imgproc.COLOR_YUV2RGBA_NV21)
                    when (call.argument<Int>("rotation") ?: 0) {
                        90 -> Core.rotate(color, color, Core.ROTATE_90_CLOCKWISE)
                        180 -> Core.rotate(color, color, Core.ROTATE_180)
                        270 -> Core.rotate(color, color, Core.ROTATE_90_COUNTERCLOCKWISE)
                    }
                    color
                } catch (e: Exception) { color.release(); throw e }
                finally { yuv.release() }
            }
            return detected + mapOf("elapsedMs" to SystemClock.elapsedRealtime() - started)
        } finally { gray.release() }
    }

    fun importImage(call: ScanArguments): Map<String, Any?> {
        if (liveDelegate.isInitialized()) live.reset()
        val source = call.argument<String>("path")!!
        val output = call.argument<String>("output")!!
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Unsupported or damaged image" }
        var sample = 1
        while (bounds.outWidth.toLong() * bounds.outHeight / (sample * sample) > 21_250_000) sample *= 2
        val decoded = BitmapFactory.decodeFile(source, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: error("Image could not be decoded")
        // A 13 MP camera must not fall all the way to 3.2 MP because of a power-of-two sample.
        val pixelScale = min(1.0, sqrt(12_500_000.0 / (decoded.width.toDouble() * decoded.height)))
        val bitmap = if (pixelScale < 1.0) Bitmap.createScaledBitmap(decoded,
            (decoded.width * pixelScale).roundToInt(), (decoded.height * pixelScale).roundToInt(), true).also {
                if (it !== decoded) decoded.recycle()
            } else decoded
        val exif = ExifInterface(source)
        val matrix = Matrix()
        when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(270f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(270f)
        }
        val upright = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        val rgba = Mat(); val gray = Mat(); val small = Mat()
        try {
            File(output).parentFile?.mkdirs()
            File(output).outputStream().use { check(upright.compress(Bitmap.CompressFormat.JPEG, 98, it)) }
            Utils.bitmapToMat(upright, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            val factor = min(1.0, 1200.0 / max(gray.cols(), gray.rows()))
            Imgproc.resize(gray, small, Size(), factor, factor, Imgproc.INTER_AREA)
            val reducedColor = Mat()
            try {
                Imgproc.resize(rgba, reducedColor, small.size(), 0.0, 0.0, Imgproc.INTER_AREA)
                val started = SystemClock.elapsedRealtime()
                val detected = detect(small, reducedColor)
                return detected + mapOf("path" to output, "width" to upright.width, "height" to upright.height,
                    "analysisMs" to SystemClock.elapsedRealtime() - started)
            } finally { reducedColor.release() }
        } finally {
            rgba.release(); gray.release(); small.release()
            if (upright !== bitmap) upright.recycle()
            bitmap.recycle()
        }
    }

    private fun detect(gray: Mat, color: Mat): Map<String, Any?> {
        val blurred = Mat(); val edges = Mat(); val lap = Mat()
        val mean = MatOfDouble(); val deviation = MatOfDouble()
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
        var best: List<Point>? = null
        var bestScore = 0.0
        val imageArea = gray.total().toDouble()
        try {
            Imgproc.Laplacian(gray, lap, CvType.CV_16S)
            Core.meanStdDev(lap, mean, deviation)
            val sharpness = deviation.toArray()[0].pow(2)
            Core.meanStdDev(gray, mean, deviation)
            if (deviation.toArray()[0] < 4.0) {
                Core.meanStdDev(color, mean, deviation)
                if (deviation.toArray().take(3).max() < 4.0) return mapOf("corners" to null, "score" to 0.0,
                    "sharpness" to sharpness, "engine" to "blank")
            }
            val learned = if (learnedAvailable) geometry.detect(color) else null
            if (learned != null) {
                val points = geometry.refineSource(gray, learned.corners).map {
                    Point(it.x.coerceIn(0.0, gray.cols() - 1.0), it.y.coerceIn(0.0, gray.rows() - 1.0))
                }
                val polygon = MatOfPoint(*points.toTypedArray())
                val area = abs(Imgproc.contourArea(polygon)) / imageArea; polygon.release()
                return mapOf("corners" to points.flatMap { listOf(it.x / (gray.cols() - 1), it.y / (gray.rows() - 1)) },
                    "score" to area * (.7 + .3 * learned.confidence), "sharpness" to sharpness, "engine" to "learned")
            }
            val luminance = ByteArray(gray.total().toInt())
            gray.get(0, 0, luminance)
            Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)
            for (pass in 0..3) {
                when (pass) {
                    0 -> Imgproc.Canny(blurred, edges, 35.0, 105.0)
                    1 -> Imgproc.Canny(blurred, edges, 75.0, 200.0)
                    2 -> Imgproc.threshold(blurred, edges, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)
                    3 -> Imgproc.adaptiveThreshold(blurred, edges, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY, 51, 9.0)
                }
                Imgproc.morphologyEx(edges, edges, Imgproc.MORPH_CLOSE, kernel)
                val contours = ArrayList<MatOfPoint>(); val hierarchy = Mat()
                try {
                    Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
                    for (contour in contours.sortedByDescending { abs(Imgproc.contourArea(it)) }.take(24)) {
                        if (abs(Imgproc.contourArea(contour)) < imageArea * 0.12) continue
                        val curve = MatOfPoint2f(*contour.toArray()); val approx = MatOfPoint2f()
                        try {
                            val perimeter = Imgproc.arcLength(curve, true)
                            for (epsilon in doubleArrayOf(0.015, 0.025, 0.04)) {
                                Imgproc.approxPolyDP(curve, approx, perimeter * epsilon, true)
                                if (approx.total() != 4L) continue
                                val poly = MatOfPoint(*approx.toArray())
                                val convex = Imgproc.isContourConvex(poly)
                                val area = abs(Imgproc.contourArea(poly)) / imageArea
                                poly.release()
                                if (!convex || area !in 0.12..0.96) continue
                                val points = order(approx.toArray().toList())
                                val minSide = points.indices.minOf { distance(points[it], points[(it + 1) % 4]) }
                                if (minSide < min(gray.cols(), gray.rows()) * 0.12) continue
                                val cosines = points.indices.map { i ->
                                    val a = points[(i + 3) % 4]; val b = points[i]; val c = points[(i + 1) % 4]
                                    abs(((a.x - b.x) * (c.x - b.x) + (a.y - b.y) * (c.y - b.y)) /
                                        (distance(a, b) * distance(c, b) + 1e-9))
                                }
                                if (cosines.max() > 0.80) continue
                                val borderCount = points.count { it.x < 3 || it.y < 3 || it.x > gray.cols() - 4 || it.y > gray.rows() - 4 }
                                if (borderCount >= 3) continue
                                // Reject artificial halos produced by adaptive thresholding.
                                val support = edgeSupport(points, luminance, gray.cols(), gray.rows())
                                if (support < 0.35) continue
                                val score = area * (0.55 + support * 0.45) * (1 - cosines.average() * 0.35) * (1 - borderCount * 0.08)
                                if (score > bestScore) { bestScore = score; best = points }
                            }
                        } finally { curve.release(); approx.release() }
                    }
                } finally { contours.forEach { it.release() }; hierarchy.release() }
            }
            val candidate = best
            if (candidate != null && candidate.all { it.x > 8 && it.y > 8 && it.x < gray.cols() - 9 && it.y < gray.rows() - 9 }) {
                val refined = MatOfPoint2f(*candidate.toTypedArray())
                try {
                    Imgproc.cornerSubPix(gray, refined, Size(5.0, 5.0), Size(-1.0, -1.0), TermCriteria(TermCriteria.EPS or TermCriteria.MAX_ITER, 30, 0.05))
                    val adjusted = refined.toArray().toList()
                    if (adjusted.indices.all { distance(adjusted[it], candidate[it]) < 9 }) best = adjusted
                } finally { refined.release() }
            }
            return mapOf("corners" to best?.flatMap { listOf(it.x / (gray.cols() - 1), it.y / (gray.rows() - 1)) }, "score" to bestScore, "sharpness" to sharpness, "engine" to "contour")
        } finally { blurred.release(); edges.release(); lap.release(); mean.release(); deviation.release(); kernel.release() }
    }

    private fun order(points: List<Point>): List<Point> {
        val cx = points.map { it.x }.average(); val cy = points.map { it.y }.average()
        val sorted = points.sortedBy { atan2(it.y - cy, it.x - cx) }
        val start = sorted.indices.minBy { sorted[it].x + sorted[it].y }
        return List(4) { sorted[(start + it) % 4] }
    }
    private fun distance(a: Point, b: Point) = hypot(a.x - b.x, a.y - b.y)

    private fun edgeSupport(points: List<Point>, data: ByteArray, width: Int, height: Int): Double {
        fun sample(x: Double, y: Double): Int = data[y.roundToInt().coerceIn(0, height - 1) * width + x.roundToInt().coerceIn(0, width - 1)].toInt() and 255
        var supported = 0
        for (edge in 0..3) {
            val a = points[edge]; val b = points[(edge + 1) % 4]
            val length = distance(a, b)
            val nx = -(b.y - a.y) / length; val ny = (b.x - a.x) / length
            for (index in 0 until 24) {
                val t = 0.08 + 0.84 * index / 23.0
                val x = a.x + (b.x - a.x) * t; val y = a.y + (b.y - a.y) * t
                var difference = 0
                for (offset in -2..2 step 2) {
                    difference = max(difference, abs(sample(x + nx * (offset + 3), y + ny * (offset + 3)) - sample(x + nx * (offset - 3), y + ny * (offset - 3))))
                }
                if (difference >= 8) supported++
            }
        }
        return supported / 96.0
    }

    fun render(call: ScanArguments): Map<String, Any> {
        val path = call.argument<String>("path")!!; val output = call.argument<String>("output")!!
        val corners = call.argument<List<Number>>("corners")!!
        val mode = call.argument<String>("filter") ?: "original"
        val turns = (call.argument<Int>("turns") ?: 0).mod(4)
        val brightness = (call.argument<Number>("brightness")?.toDouble() ?: 0.0).coerceIn(-50.0, 50.0)
        val contrast = (call.argument<Number>("contrast")?.toDouble() ?: 1.0).coerceIn(0.5, 2.0)
        require(corners.size == 8 && corners.all { it.toDouble().isFinite() && it.toDouble() in 0.0..1.0 })
        val limit = (call.argument<Int>("maxDimension") ?: 6000).coerceIn(320, 6000)
        val useDewarp = call.argument<Boolean>("dewarp") ?: true
        val file = File(path)
        val cropKey = "$path:${file.lastModified()}:${file.length()}:${corners.joinToString(",")}"
        val cacheKey = "$cropKey:$limit:$useDewarp"
        renderGeometry.find(cacheKey)?.let { hit ->
            renderMemo.useGeometry(cacheKey)
            return finishRender(hit.image, output, mode, turns, brightness, contrast, true) +
                mapOf("dewarped" to hit.dewarped, "geometryCached" to true)
        }
        val bitmap = BitmapFactory.decodeFile(path) ?: error("Original image is missing")
        val rgba = Mat(); val rgb = Mat(); val warpedRgba = Mat(); val warped = Mat(); val flattened = Mat()
        val from = MatOfPoint2f(); val to = MatOfPoint2f(); var transform: Mat? = null
        try {
            Utils.bitmapToMat(bitmap, rgba)
            bitmap.recycle()
            val points = List(4) { i -> Point(corners[i * 2].toDouble() * (rgba.cols() - 1), corners[i * 2 + 1].toDouble() * (rgba.rows() - 1)) }
            val poly = MatOfPoint(*points.toTypedArray())
            val valid = Imgproc.isContourConvex(poly) && abs(Imgproc.contourArea(poly)) > rgba.total() * 0.005
            poly.release(); require(valid) { "Corners must form a non-crossing quadrilateral" }
            val target = CropGeometry.targetSize(points)
            val rawWidth = target.width
            val rawHeight = target.height
            val scale = min(max(1.0, 3000.0 / max(rawWidth, rawHeight)),
                min(limit / max(rawWidth, rawHeight), sqrt(12_500_000.0 / (rawWidth * rawHeight))))
            val w = (rawWidth * scale).toInt().coerceAtLeast(32)
            val h = (rawHeight * scale).toInt().coerceAtLeast(32)
            from.fromArray(*points.toTypedArray())
            to.fromArray(Point(0.0, 0.0), Point(w.toDouble(), 0.0), Point(w.toDouble(), h.toDouble()), Point(0.0, h.toDouble()))
            transform = Imgproc.getPerspectiveTransform(from, to)
            if (w.toLong() * h <= rgba.total() / 2) {
                // A small preview does not need a full-size RGB conversion/copy.
                // INTER_LINEAR uses the same per-channel values before/after this permutation.
                Imgproc.warpPerspective(rgba, warpedRgba, transform, Size(w.toDouble(), h.toDouble()),
                    Imgproc.INTER_LINEAR, Core.BORDER_CONSTANT, Scalar(255.0, 255.0, 255.0, 255.0))
                rgba.release()
                Imgproc.cvtColor(warpedRgba, warped, Imgproc.COLOR_RGBA2RGB)
                warpedRgba.release()
            } else {
                Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
                rgba.release()
                Imgproc.warpPerspective(rgb, warped, transform, Size(w.toDouble(), h.toDouble()),
                    Imgproc.INTER_LINEAR, Core.BORDER_CONSTANT, Scalar(255.0, 255.0, 255.0))
                rgb.release()
            }
            val dewarped = useDewarp && learnedAvailable && geometry.flatten(warped, flattened, cropKey, limit)
            val geometryImage = if (dewarped) flattened else warped
            renderGeometry.store(cacheKey, geometryImage, dewarped)
            val retainPreview = renderGeometry.find(cacheKey) != null
            if (retainPreview) renderMemo.useGeometry(cacheKey) else renderMemo.close()
            return finishRender(geometryImage, output, mode, turns, brightness, contrast, retainPreview) +
                mapOf("dewarped" to dewarped, "geometryCached" to false)
        } finally { bitmap.recycle(); rgba.release(); rgb.release(); warpedRgba.release(); warped.release(); flattened.release(); from.release(); to.release(); transform?.release() }
    }

    private fun finishRender(source: Mat, output: String, mode: String, turns: Int,
                             brightness: Double, contrast: Double, retainPreview: Boolean): Map<String, Any> {
        val variant = RenderMemoCache.Variant(mode, turns, brightness, contrast)
        val destination = File(output)
        destination.parentFile?.mkdirs()
        if (retainPreview) renderMemo.encoded(variant)?.let { cached ->
            destination.writeBytes(cached.bytes)
            return mapOf("path" to output, "width" to cached.width, "height" to cached.height,
                "filterCached" to (mode != "original" && renderMemo.filter(mode) != null), "encodedCached" to true)
        }
        val filtered = Mat(); val adjusted = Mat(); val rotated = Mat()
        try {
            val cachedFilter = if (retainPreview && mode != "original") renderMemo.filter(mode) else null
            var image = when {
                mode == "original" -> source
                cachedFilter != null -> cachedFilter
                else -> {
                    applyFilter(source, filtered, mode)
                    if (retainPreview) renderMemo.storeFilter(mode, filtered)
                    filtered
                }
            }
            if (contrast != 1.0 || brightness != 0.0) {
                image.convertTo(adjusted, -1, contrast, brightness)
                image = adjusted
            }
            if (turns != 0) {
                Core.rotate(image, rotated, when (turns) {
                    1 -> Core.ROTATE_90_CLOCKWISE
                    2 -> Core.ROTATE_180
                    else -> Core.ROTATE_90_COUNTERCLOCKWISE
                })
                image = rotated
            }
            val result = Bitmap.createBitmap(image.cols(), image.rows(), Bitmap.Config.ARGB_8888)
            try {
                Utils.matToBitmap(image, result)
                if (retainPreview) {
                    val stream = ByteArrayOutputStream()
                    check(result.compress(Bitmap.CompressFormat.JPEG, 96, stream))
                    val bytes = stream.toByteArray()
                    destination.writeBytes(bytes)
                    renderMemo.storeEncoded(variant, bytes, result.width, result.height)
                } else destination.outputStream().use { check(result.compress(Bitmap.CompressFormat.JPEG, 96, it)) }
                return mapOf("path" to output, "width" to result.width, "height" to result.height,
                    "filterCached" to (cachedFilter != null), "encodedCached" to false)
            } finally { result.recycle() }
        } finally { filtered.release(); adjusted.release(); rotated.release() }
    }

    private fun applyFilter(src: Mat, dst: Mat, mode: String) {
        if (mode == "original") { src.copyTo(dst); return }
        val gray = Mat(); val background = Mat(); val normalized = Mat()
        try {
            if (mode == "color") {
                val small = Mat()
                try {
                    Imgproc.resize(src, small, Size(64.0, 64.0), 0.0, 0.0, Imgproc.INTER_AREA)
                    Imgproc.GaussianBlur(small, small, Size(15.0, 15.0), 0.0)
                    Imgproc.resize(small, background, src.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
                    Core.divide(src, background, dst, 245.0)
                } finally { small.release() }
                return
            }
            Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGB2GRAY)
            if (mode == "gray") {
                val clahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
                try { clahe.apply(gray, dst) } finally { clahe.collectGarbage() }
                return
            }
            val small = Mat()
            try {
                Imgproc.resize(gray, small, Size(64.0, 64.0), 0.0, 0.0, Imgproc.INTER_AREA)
                Imgproc.GaussianBlur(small, small, Size(15.0, 15.0), 0.0)
                Imgproc.resize(small, background, gray.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
                Core.divide(gray, background, normalized, 245.0)
                val block = (min(src.rows(), src.cols()) / 25).coerceIn(15, 101) or 1
                Imgproc.adaptiveThreshold(normalized, dst, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY, block, 13.0)
            } finally { small.release() }
        } finally { gray.release(); background.release(); normalized.release() }
    }
}


