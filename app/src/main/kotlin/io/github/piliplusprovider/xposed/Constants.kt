package io.github.piliplusprovider.xposed

object Constants {
    /** 本模块包名 */
    const val PROVIDER_PACKAGE_NAME: String = "io.github.piliplusprovider"

    /** PiliPlus 正式版包名 */
    const val PILIPLUS_PACKAGE_NAME: String = "com.example.piliplus"

    /** PiliPlus 调试版包名 */
    const val PILIPLUS_DEBUG_PACKAGE_NAME: String = "com.example.piliplus.debug"

    /** PiliPlus dev版包名 */
    const val PILIPLUS_DEV_PACKAGE_NAME: String = "com.example.piliplus.dev"

    /** 所有目标 PiliPlus 包名（与 META-INF/xposed/scope.list 一致） */
    val TARGET_PACKAGES: Set<String> = setOf(
        PILIPLUS_PACKAGE_NAME,
        PILIPLUS_DEBUG_PACKAGE_NAME,
        PILIPLUS_DEV_PACKAGE_NAME,
    )

    /** RemotePreferences 文件名（hook 侧与 UI 侧一致） */
    const val PREFS_NAME: String = "settings"

    /** 模块图标 SVG (Bilibili TV icon) */
    const val ICON: String =
        "<svg viewBox=\"0 0 1024 1024\" xmlns=\"http://www.w3.org/2000/svg\" width=\"64\" height=\"64\">" +
            "<path d=\"M777.514667 131.669333a53.333333 53.333333 0 0 1 0 75.434667L713.856 270.762667h49.493333A160 160 0 0 1 923.349333 430.762667v320a160 160 0 0 1-160 160H260.650667a160 160 0 0 1-160-160v-320a160 160 0 0 1 160-160h49.749333l-63.914667-63.658667a53.333333 53.333333 0 0 1 75.392-75.434667L445.013333 270.762667h133.973334l123.136-139.093334a53.333333 53.333333 0 0 1 75.392 0zM763.349333 377.429333H260.650667a53.333333 53.333333 0 0 0-53.333334 53.333334v320a53.333333 53.333333 0 0 0 53.333334 53.333333h502.698666a53.333333 53.333333 0 0 0 53.333334-53.333333v-320a53.333333 53.333333 0 0 0-53.333334-53.333334zM352.256 502.058667a53.333333 53.333333 0 0 1 53.333333 53.333333v64a53.333333 53.333333 0 1 1-106.666666 0v-64a53.333333 53.333333 0 0 1 53.333333-53.333333z m319.488 0a53.333333 53.333333 0 0 1 53.333333 53.333333v64a53.333333 53.333333 0 1 1-106.666666 0v-64a53.333333 53.333333 0 0 1 53.333333-53.333333z\" fill=\"#FB7299\"/>" +
            "</svg>"

    // ========== 设置项(SharedPreferences,文件名 ${applicationId}_preferences) ==========

    /** 设置项：是否推送视频标题（默认开） */
    const val KEY_PUSH_TITLE: String = "push_title"

    /** 设置项：是否推送UP主名字（默认开） */
    const val KEY_PUSH_ARTIST: String = "push_artist"

    /** 设置项：是否推送视频总时长（默认开） */
    const val KEY_PUSH_DURATION: String = "push_duration"

    /** 设置项：是否显示已播放时间并同步播放位置（默认关） */
    const val KEY_SHOW_ELAPSED_TIME: String = "show_elapsed_time"
}
