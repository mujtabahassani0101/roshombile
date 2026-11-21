package com.haseebullahjan.roshd.platform

/** Kotlin-only boundary for the existing camera, Keystore and PDF services. */
data class NativeCall(val method: String, val arguments: Any? = null) {
    @Suppress("UNCHECKED_CAST") fun <T> argument(name: String): T? = (arguments as? Map<*, *>)?.get(name) as? T
    @Suppress("UNCHECKED_CAST") fun <T> arguments(): T? = arguments as? T
    fun hasArgument(name: String): Boolean = (arguments as? Map<*, *>)?.containsKey(name) == true
}

interface NativeResult {
    fun success(value: Any?)
    fun error(code: String, message: String?, details: Any?)
    fun notImplemented() = error("unsupported_operation", "این عملیات پشتیبانی نمی‌شود.", null)
}
