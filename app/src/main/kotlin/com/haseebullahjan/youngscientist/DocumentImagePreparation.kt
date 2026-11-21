package com.haseebullahjan.youngscientist

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/** Preserves original evidence; prepares bounded, upright recognition copies when required. */
object DocumentImagePreparation {
    data class Info(val width: Int, val height: Int, val mimeType: String, val orientation: Int)

    fun inspect(input: File): Info {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(input.path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Image cannot be decoded" }
        val mime = bounds.outMimeType ?: throw IllegalArgumentException("Unknown image format")
        val orientation = if (mime == "image/jpeg" || mime == "image/heic" || mime == "image/heif") {
            ExifInterface(input.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1).let {
                if (it == ExifInterface.ORIENTATION_UNDEFINED) 1 else it
            }
        } else 1
        require(orientation in 1..8) { "Invalid image orientation" }
        return Info(bounds.outWidth, bounds.outHeight, mime, orientation)
    }

    /** Native imread honors JPEG EXIF in both header and answer engines and applies the size cap.
     * Avoid a bitmap decode, PNG encode, disk write and second resampling for already-framed photos.
     * Formats unsupported by native codecs still get an upright lossless recognition copy.
     */
    fun prepareForHeader(input: File, output: File, maxDimension: Int): String {
        require(maxDimension in setOf(1800, 2000, 2600))
        val info = inspect(input)
        // Very large sensor images still use bounded Android decoding before entering native code.
        if (info.mimeType in setOf("image/jpeg","image/png") && info.width.toLong()*info.height<=16_000_000) return input.path
        return render(input, output, maxDimension)
    }

    fun render(input: File, output: File, maxDimension: Int): String {
        require(maxDimension > 0)
        require(output.isDirectory || output.mkdirs()) { "Image output unavailable" }
        // ImageDecoder already applies EXIF rotation/mirroring on Android 9+.
        // The older BitmapFactory path must do so explicitly, once, afterwards.
        val original=decodeBounded(input,maxDimension)
        var bitmap = original
        val target = File(output, "page-1.png")
        val partial = File(output, "page-1.png.partial")
        try {
            val largest = max(original.width, original.height)
            if (largest > maxDimension) {
                val scale = maxDimension.toFloat() / largest
                bitmap = Bitmap.createScaledBitmap(original,
                    max(1, (original.width * scale).roundToInt()),
                    max(1, (original.height * scale).roundToInt()), true)
            }
            FileOutputStream(partial).use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "Image encoding failed" }
                it.flush()
                it.fd.sync()
            }
            check(partial.renameTo(target)) { "Image commit failed" }
            return target.path
        } finally {
            partial.delete()
            if (bitmap !== original) bitmap.recycle()
            original.recycle()
        }
    }

    private fun decodeBounded(input:File,maxDimension:Int):Bitmap {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(input)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val largest = max(info.size.width, info.size.height)
                if (largest > maxDimension) {
                    val scale = maxDimension.toFloat() / largest
                    decoder.setTargetSize(max(1, (info.size.width * scale).roundToInt()),
                        max(1, (info.size.height * scale).roundToInt()))
                }
            }
        } else decodeLegacy(input, maxDimension)
    }

    private fun decodeLegacy(input: File, maxDimension: Int): Bitmap {
        val info = inspect(input)
        val options = BitmapFactory.Options().apply { inSampleSize = 1 }
        while (max(info.width, info.height) / (options.inSampleSize * 2) >= maxDimension) {
            options.inSampleSize *= 2
        }
        val raw = BitmapFactory.decodeFile(input.path, options)
            ?: throw IllegalArgumentException("Image cannot be decoded")
        val matrix = Matrix()
        when (info.orientation) {
            2 -> matrix.setScale(-1f, 1f)
            3 -> matrix.setRotate(180f)
            4 -> matrix.setScale(1f, -1f)
            5 -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
            6 -> matrix.setRotate(90f)
            7 -> { matrix.setRotate(90f); matrix.postScale(1f, -1f) }
            8 -> matrix.setRotate(-90f)
            else -> return raw
        }
        return try {
            Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, false).also {
                if (it !== raw) raw.recycle()
            }
        } catch (error: Throwable) {
            raw.recycle()
            throw error
        }
    }
}
