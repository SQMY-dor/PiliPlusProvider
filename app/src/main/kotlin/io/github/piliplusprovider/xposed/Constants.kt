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

    /** 模块图标 SVG（PiliPlus 播放器主题：粉底 + 白色电视 + 播放三角） */
    const val ICON: String =
        "<svg viewBox=\"0 0 1024 1024\" xmlns=\"http://www.w3.org/2000/svg\" width=\"64\" height=\"64\">" +
            "<defs><linearGradient id=\"bg\" x1=\"0\" y1=\"0\" x2=\"1\" y2=\"1\">" +
            "<stop offset=\"0\" stop-color=\"#FB7299\"/><stop offset=\"1\" stop-color=\"#E8547E\"/>" +
            "</linearGradient><linearGradient id=\"tv\" x1=\"0\" y1=\"0\" x2=\"0\" y2=\"1\">" +
            "<stop offset=\"0\" stop-color=\"#FFFFFF\"/><stop offset=\"1\" stop-color=\"#F5E6EC\"/>" +
            "</linearGradient></defs>" +
            "<rect x=\"32\" y=\"32\" width=\"960\" height=\"960\" rx=\"220\" fill=\"url(#bg)\"/>" +
            "<circle cx=\"512\" cy=\"212\" r=\"36\" fill=\"#FFFFFF\" opacity=\"0.9\"/>" +
            "<rect x=\"192\" y=\"280\" width=\"640\" height=\"480\" rx=\"72\" fill=\"url(#tv)\"/>" +
            "<path d=\"M448 392 L672 520 L448 648 Z\" fill=\"#FB7299\"/>" +
            "<rect x=\"392\" y=\"800\" width=\"240\" height=\"52\" rx=\"26\" fill=\"#FFFFFF\" opacity=\"0.9\"/>" +
            "<rect x=\"392\" y=\"852\" width=\"52\" height=\"72\" rx=\"26\" fill=\"#FFFFFF\" opacity=\"0.9\"/>" +
            "<rect x=\"580\" y=\"852\" width=\"52\" height=\"72\" rx=\"26\" fill=\"#FFFFFF\" opacity=\"0.9\"/>" +
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
