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

    // ========== 星河岛（AstraIsland）==========

    /** 设置项：是否推送到星河岛（默认关，需用户装岛后再开） */
    const val KEY_ENABLE_ISLAND: String = "enable_island"

    /** 设置项：是否把视频封面推到星河岛（默认开） */
    const val KEY_ISLAND_PUSH_COVER: String = "island_push_cover"

    // ========== Hook → 模块进程的跨进程桥 ==========

    /**
     * 显式广播：Hook 侧（PiliPlus 进程）→ 模块进程。
     *
     * 为什么不用 ContentProvider：Android 11+ 包可见性会过滤「访问其他 App 的
     * provider」，除非**调用方**（这里是 PiliPlus，第三方 App，清单不可改）声明
     * `<queries><provider authorities=...>`。而显式广播的投递由 system_server 解析
     * 组件，不受调用方包可见性影响，是本场景唯一可行的通道。
     */
    const val BRIDGE_ACTION_UPDATE: String = "io.github.piliplusprovider.action.ISLAND_UPDATE"

    /** 停止投送（播放结束） */
    const val BRIDGE_ACTION_CLEAR: String = "io.github.piliplusprovider.action.ISLAND_CLEAR"

    /** 接收器类名（显式组件用全限定名，无需查询即可投递） */
    const val BRIDGE_RECEIVER_CLASS: String = "io.github.piliplusprovider.island.IslandReceiver"

    /**
     * 轻量防伪令牌。
     *
     * 接收器 exported=true 才能接收另一进程的广播，因此任何应用都能伪造投送内容。
     * 该令牌只用于挡住随手伪造（防不住反编译定向攻击）；本场景危害上限是
     * 「伪装正在播放的视频信息」，属低危。
     */
    const val BRIDGE_TOKEN: String = "piliplus-island-bridge-v1"

    const val BRIDGE_EXTRA_TOKEN: String = "token"
    const val BRIDGE_EXTRA_TITLE: String = "title"
    const val BRIDGE_EXTRA_ARTIST: String = "artist"
    const val BRIDGE_EXTRA_DURATION: String = "duration"
    const val BRIDGE_EXTRA_VIDEO_ID: String = "videoId"
    const val BRIDGE_EXTRA_PLAYING: String = "playing"
    const val BRIDGE_EXTRA_POSITION: String = "position"
    const val BRIDGE_EXTRA_COVER: String = "cover"

    /**
     * 封面压缩上限（最长边像素）。
     *
     * 广播的 Binder 事务上限约 1MB，而 512×512 的 ARGB_8888 位图恰好就是 1MB。
     * 因此 Hook 侧先把封面缩到 512 并压成 JPEG（几十 KB），再以字节数组投递，
     * 避免 TransactionTooLargeException。星河岛自身的限制是单边 ≤2048px、≤8MB。
     */
    const val COVER_MAX_EDGE: Int = 512

    /** 封面 JPEG 压缩质量 */
    const val COVER_JPEG_QUALITY: Int = 85
}
