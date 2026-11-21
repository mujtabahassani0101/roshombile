package com.haseebullahjan.roshd.platform

object OmrNative {
    init { OpenCvRuntime.ensure();System.loadLibrary("roshd_jni") }
    external fun processJson(request: String): String
    external fun prepareCameraJson(request: String): String
    external fun engineRevision(): String
    external fun annotateJson(request: String): String
    external fun progress(id: String): String?
    external fun releaseProgress(id: String)
}
