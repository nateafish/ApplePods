package io.github.nathanxie.applepods

import android.app.Application
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.CopyOnWriteArraySet

class ApplePodsApp : Application(), XposedServiceHelper.OnServiceListener {
    interface Listener {
        fun onServiceChanged(service: XposedService?)
    }

    companion object {
        @Volatile private var service: XposedService? = null
        private val listeners = CopyOnWriteArraySet<Listener>()

        fun addListener(listener: Listener) {
            listeners.add(listener)
            listener.onServiceChanged(service)
        }

        fun removeListener(listener: Listener) {
            listeners.remove(listener)
        }
    }

    override fun onCreate() {
        super.onCreate()
        XposedServiceHelper.registerListener(this)
    }

    override fun onServiceBind(boundService: XposedService) {
        service = boundService
        listeners.forEach { it.onServiceChanged(boundService) }
    }

    override fun onServiceDied(deadService: XposedService) {
        if (service === deadService) service = null
        listeners.forEach { it.onServiceChanged(null) }
    }
}
