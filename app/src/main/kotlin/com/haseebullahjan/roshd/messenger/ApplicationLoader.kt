package com.haseebullahjan.roshd.messenger

import android.app.Application
import com.haseebullahjan.roshd.platform.NativeGateway

class ApplicationLoader: Application() {
    lateinit var controller: ExamsController
        private set
    override fun onCreate() {
        super.onCreate()
        com.haseebullahjan.youngscientist.scanner.DocumentScanner.initialize(this)
        controller=ExamsController(this,MessagesStorage(this),ConnectionsManager(),NativeGateway(),NotificationCenter())
    }
}
