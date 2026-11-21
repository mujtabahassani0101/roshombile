package com.haseebullahjan.roshd.platform

import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList

class WeakHostRegistry<T:Any>(private val alive:(T)->Boolean) {
    private val entries=CopyOnWriteArrayList<WeakReference<T>>()
    fun attach(host:T) { detach(host);entries.add(WeakReference(host)) }
    fun detach(host:T) { entries.removeAll { it.get()==null || it.get()===host } }
    fun current():T?=entries.asReversed().firstNotNullOfOrNull { it.get()?.takeIf(alive) }
}
