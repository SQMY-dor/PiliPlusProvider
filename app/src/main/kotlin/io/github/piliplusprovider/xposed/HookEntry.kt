package io.github.piliplusprovider.xposed

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
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

    /**
     * 允许热重载。
     *
     * 默认实现返回 false，会拒绝热重载请求（service 触发时返回
     * HotReloadResult.Status.FAILED）。这里显式返回 true 以支持
     * UI 上的「热重启」按钮与模块热更新。
     */
    override fun onHotReloading(param: HotReloadingParam): Boolean {
        return true
    }

    /**
     * 热重载完成后重新安装 Hook。
     *
     * 热重载不会自动重放 onPackageLoaded / onPackageReady 等包生命周期回调，
     * 必须在此处显式重新安装。旧一代安装的 hook 通过 param.oldHookHandles 卸载。
     */
    override fun onHotReloaded(param: HotReloadedParam) {
        // 卸载旧一代安装的 hook（原子替换交给新安装流程）
        param.oldHookHandles.forEach { it.unhook() }
        // 重新安装（MediaSession 等为 framework 类，直接用默认 classloader 解析）
        PiliPlusHook.reinstall(this)
    }
}
