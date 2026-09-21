package io.github.piliplusprovider

import android.app.Application
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import io.github.piliplusprovider.island.IslandPublisher
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.concurrent.Volatile

/**
 * 模块 Application
 *
 * 负责绑定框架提供的 XposedService：模块 app 进程（UI 侧）只有通过
 * XposedService 才能读写与 Hook 侧共享的 RemotePreferences。
 */
class App : Application(), XposedServiceHelper.OnServiceListener {

    companion object {
        @Volatile
        var mService: XposedService? = null
            private set

        private val serviceStateListeners = CopyOnWriteArraySet<ServiceStateListener>()

        private fun dispatchServiceState(listener: ServiceStateListener, service: XposedService?) {
            if (serviceStateListeners.contains(listener)) {
                listener.onServiceStateChanged(service)
            }
        }

        fun addServiceStateListener(listener: ServiceStateListener, notifyImmediately: Boolean) {
            serviceStateListeners.add(listener)
            if (notifyImmediately) {
                dispatchServiceState(listener, mService)
            }
        }

        fun removeServiceStateListener(listener: ServiceStateListener) {
            serviceStateListeners.remove(listener)
        }
    }

    private fun notifyServiceStateChanged(service: XposedService?) {
        for (listener in serviceStateListeners) {
            dispatchServiceState(listener, service)
        }
    }

    override fun onCreate() {
        super.onCreate()
        XposedServiceHelper.registerListener(this)
        // 建立与星河岛（AstraIsland）的连接。
        // 必须由模块进程投送：星河岛按调用方 uid 核对来源包名，而 Hook 跑在
        // PiliPlus 进程（uid 属于 PiliPlus，其清单未声明星河岛权限）。
        IslandPublisher.attach(this)
    }

    interface ServiceStateListener {
        fun onServiceStateChanged(service: XposedService?)
    }

    override fun onServiceBind(service: XposedService) {
        mService = service
        notifyServiceStateChanged(mService)
    }

    override fun onServiceDied(service: XposedService) {
        mService = null
        notifyServiceStateChanged(mService)
    }
}
