package io.github.piliplusprovider.xposed

import com.highcapable.yukihookapi.YukiHookAPI
import com.highcapable.yukihookapi.annotation.xposed.InjectYukiHookWithXposed
import com.highcapable.yukihookapi.hook.xposed.proxy.IYukiHookXposedInit

@InjectYukiHookWithXposed(modulePackageName = Constants.PROVIDER_PACKAGE_NAME)
open class HookEntry : IYukiHookXposedInit {

    override fun onHook() {
        YukiHookAPI.encase {
            loadApp(Constants.PILIPLUS_PACKAGE_NAME, PiliPlusHook)
            loadApp(Constants.PILIPLUS_DEBUG_PACKAGE_NAME, PiliPlusHook)
            loadApp(Constants.PILIPLUS_DEV_PACKAGE_NAME, PiliPlusHook)
        }
    }

    override fun onInit() {
        super.onInit()
        YukiHookAPI.configs {
            debugLog {
                tag = "PiliPlusProvider"
            }
        }
    }
}
