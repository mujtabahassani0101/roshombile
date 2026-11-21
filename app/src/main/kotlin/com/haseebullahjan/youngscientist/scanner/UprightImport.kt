package com.haseebullahjan.youngscientist.scanner

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Hands the unchanged Moji importer an already upright, lossless copy of a camera JPEG.
 *
 * PaperframeEngine.importImage decodes the photo, scales it to at most 12.5 MP and then
 * applies the EXIF quarter turn with a filtered Skia draw on one thread (about 3.5 s on an
 * eight-core Cortex-A53 phone). Bilinear sampling of a quarter turn at scale 1 lands on
 * pixel centres, so that draw is a pure pixel permutation. This copy repeats the importer's
 * decode and scale calls verbatim, performs the same permutation with OpenCV, and stores
 * the result losslessly. The importer then finds nothing to scale or rotate and builds a
 * bitmap with exactly the pixels it would have built itself; every later Moji step,
 * including its JPEG output, is unchanged. Inputs outside this proven case return null and
 * keep the original call.
 */
internal object UprightImport {
    private const val MAX_DECODE_PIXELS = 21_250_000L
    private const val MAX_IMPORT_PIXELS = 12_500_000.0

    // createScaledBitmap is spelled out: it must be the importer's own call, not a wrapper.
    @Suppress("UseKtx")
    fun prepare(input: File, directory: File): File? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(input.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outMimeType != "image/jpeg") return null
        val rotation = when (ExifInterface(input.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> Core.ROTATE_90_CLOCKWISE
            ExifInterface.ORIENTATION_ROTATE_270 -> Core.ROTATE_90_COUNTERCLOCKWISE
            // Upright and half-turn photos are cheap for Skia (measured, no gain);
            // mirrored ones keep the importer's own path.
            else -> return null
        }
        // Same sampling rule as the importer.
        var sample = 1
        while (bounds.outWidth.toLong() * bounds.outHeight / (sample * sample) > MAX_DECODE_PIXELS) sample *= 2
        val decoded = BitmapFactory.decodeFile(input.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        if (decoded.config != Bitmap.Config.ARGB_8888 || decoded.hasAlpha() || !sRgb(decoded)) { decoded.recycle(); return null }
        // Same scale call as the importer, so its Skia resampling is reproduced exactly.
        val pixelScale = min(1.0, sqrt(MAX_IMPORT_PIXELS / (decoded.width.toDouble() * decoded.height)))
        val bitmap = if (pixelScale < 1.0) Bitmap.createScaledBitmap(decoded,
            (decoded.width * pixelScale).roundToInt(), (decoded.height * pixelScale).roundToInt(), true).also {
                if (it !== decoded) decoded.recycle()
            } else decoded
        // The importer must not scale this copy again.
        if (bitmap.width.toLong() * bitmap.height > MAX_IMPORT_PIXELS || bitmap.config != Bitmap.Config.ARGB_8888) {
            bitmap.recycle(); return null
        }
        val rgba = Mat(); val bgr = Mat()
        try {
            Utils.bitmapToMat(bitmap, rgba)
            bitmap.recycle()
            Core.rotate(rgba, rgba, rotation)
            Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
            check(directory.isDirectory || directory.mkdirs())
            val output = File(directory, "upright-input.bmp")
            if (!Imgcodecs.imwrite(output.path, bgr)) { output.delete(); return null }
            return output
        } finally { rgba.release(); bgr.release(); if (!bitmap.isRecycled) bitmap.recycle() }
    }

    private fun sRgb(bitmap: Bitmap) = Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
        bitmap.colorSpace == null || bitmap.colorSpace == ColorSpace.get(ColorSpace.Named.SRGB)
}
