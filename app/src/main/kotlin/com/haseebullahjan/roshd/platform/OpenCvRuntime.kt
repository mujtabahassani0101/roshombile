package com.haseebullahjan.roshd.platform

import org.opencv.android.OpenCVLoader
import org.opencv.core.Core

/** Configure once, before any preview or OMR operation enters OpenCV. */
object OpenCvRuntime {
    private val initialized by lazy {
        check(OpenCVLoader.initLocal()) { "OpenCV could not initialize" }
        // Preview admission no longer runs OpenCV. Let the native worker pool
        // use the phone's cores; configure once, never during concurrent reads.
        Core.setNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1,8))
        true
    }
    fun ensure():Boolean=initialized
}
