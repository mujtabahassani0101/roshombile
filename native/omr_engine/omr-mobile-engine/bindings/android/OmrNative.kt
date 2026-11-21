package com.lajvard.omr

internal object OmrNative {
  init {
    System.loadLibrary("omr_scanner_jni")
  }

  @JvmStatic external fun apiVersion(): Int
  @JvmStatic external fun engineRevision(): String
  @JvmStatic external fun processJson(requestJson: String): String
}
