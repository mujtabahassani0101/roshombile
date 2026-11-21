package com.haseebullahjan.roshd.messenger

enum class AppEvent { SESSION, DATA, QUEUE, SCANS, PROGRESS, THEME, CONNECTIVITY, CAPTURE, SCAN_RESULT, SCAN_DELETED }

/** UI-thread delivery and explicit lifecycle ownership, as in Telegram. */
class NotificationCenter {
    private val observers=mutableMapOf<AppEvent,LinkedHashSet<(Any?)->Unit>>()
    fun observe(event: AppEvent, observer: (Any?)->Unit): AutoCloseable {
        check(android.os.Looper.myLooper()==android.os.Looper.getMainLooper())
        observers.getOrPut(event){linkedSetOf()}.add(observer)
        return AutoCloseable { DispatchQueue.ui { observers[event]?.remove(observer) } }
    }
    fun post(event: AppEvent,value: Any?=null) = DispatchQueue.ui { observers[event]?.toList()?.forEach { it(value) } }
}
