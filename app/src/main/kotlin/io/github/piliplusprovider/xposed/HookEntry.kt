package io.github.piliplusprovider.xposed

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * libxposed 模块入口（targetApiVersion=102）
 *
 * 由 META-INF/xposed/java_init.list 声明，框架在每个目标进程中实例化。
 */
class HookEntry : XposedModule() {

    override fun onPackageLoaded(param: PackageLoadedParam) {
        // 不在此处 hook，等待 onPackageReady（classloader 就绪）
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (param.packageName !in Constants.TARGET_PACKAGES) return
        PiliPlusHook.install(this, param)
    }

    override fun onHotReloaded(param: HotReloadedParam) {
        // 热重载后卸载旧一代安装的 hook，由新实例重新安装
        param.oldHookHandles.forEach { it.unhook() }
    }
}
