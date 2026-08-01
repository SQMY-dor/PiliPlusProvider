package io.github.piliplusprovider.xposed

import android.app.Application
import android.content.SharedPreferences
import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.ProviderLogo

/**
 * PiliPlus Hook 主逻辑（libxposed 现代 API 版）
 *
 * 通过 Hook MediaSession.setMetadata 获取 PiliPlus 当前播放视频的：
 * - 视频标题 (title)
 * - UP主名字 (artist)
 * - 视频时长 (duration)
 *
 * 并通过 Lyricon Provider 接口向外部软件（如词幕等）提供这些信息。
 * 推送内容与是否同步播放位置由模块设置（RemotePreferences "settings"）控制。
 */
object PiliPlusHook {
    private const val TAG = "PiliPlusProvider"

    /**
     * 安装 Hook（由 HookEntry.onPackageReady 调用，仅对目标包名生效）
     *
     * @param module libxposed 模块实例（提供 hook() / getRemotePreferences() / log()）
     * @param param  onPackageReady 参数，param.classLoader 为宿主 classloader
     */
    fun install(module: XposedModule, param: PackageReadyParam) {
        module.log(Log.DEBUG, TAG, "Hooking PiliPlus: ${param.packageName}")

        val manager = VideoInfoProviderManager(module)

        // 宿主 Application.onCreate 之后注册 LyriconProvider（与原 onAppLifecycle.onCreate 行为一致），
        // chain.thisObject 即为宿主 Application 实例
        try {
            val appClass = Class.forName("android.app.Application", false, param.classLoader)
            module.hook(appClass.getDeclaredMethod("onCreate")).intercept { chain ->
                chain.proceed()
                val app = chain.thisObject as? Application
                if (app != null) manager.setupProvider(app)
                null
            }
        } catch (t: Throwable) {
            module.log(Log.WARN, TAG, "Failed to hook Application.onCreate", t)
        }

        manager.hookMediaSession(param.classLoader)
    }

    /**
     * 热重载后重新安装 Hook（由 HookEntry.onHotReloaded 调用）
     *
     * 热重载不会重放 onPackageReady，且此时宿主进程已在运行（Application 已创建），
     * 因此这里不再 hook Application.onCreate，而是：
     * 1. 重新 hook MediaSession（framework 类，boot classloader 可直接解析，classLoader 传 null）
     * 2. 通过 ActivityThread 反射获取宿主 Application，直接注册 LyriconProvider
     */
    fun reinstall(module: XposedModule) {
        module.log(Log.DEBUG, TAG, "Reinstalling hooks after hot reload")
        val manager = VideoInfoProviderManager(module)
        manager.hookMediaSession(null)
        manager.ensureProvider()
    }

    /**
     * 视频信息提供者管理器
     * 负责 Hook MediaSession、管理 Lyricon Provider 生命周期、按设置推送内容
     *
     * 注意：RemotePreferences 实例是获取时的快照，UI 侧修改设置后旧实例不会刷新，
     * 因此每次读取设置都通过 [prefs] 重新获取，确保「推送时长」「显示已播放时间」
     * 等开关即时生效。
     */
    private class VideoInfoProviderManager(
        private val module: XposedModule,
    ) {
        /** 每次调用都从框架拉取最新设置（RemotePreferences 快照问题） */
        private fun prefs(): SharedPreferences = module.getRemotePreferences(Constants.PREFS_NAME)

        private var lyricProvider: LyriconProvider? = null
        private var lastSong: Song? = null
        private var currentVideoId: String = ""
        private var lastContentSignature: String = ""
        private var currentDuration: Long = 0L

        /** 最近一次 PlaybackState，用于推算实时播放进度 */
        private var lastPlaybackState: PlaybackState? = null

        /** 已播放时间同步定时任务（每秒一次，仅播放中运行） */
        private val elapsedHandler = Handler(Looper.getMainLooper())
        private var isTrackingElapsed = false
        private val elapsedRunnable = object : Runnable {
            override fun run() {
                syncElapsedTime()
                elapsedHandler.postDelayed(this, ELAPSED_UPDATE_INTERVAL_MS)
            }
        }

        /**
         * 初始化并注册 LyriconProvider（宿主进程内）
         */
        fun setupProvider(application: Application) {
            try {
                lyricProvider?.destroy()

                lyricProvider = LyriconFactory.createProvider(
                    context = application,
                    providerPackageName = Constants.PROVIDER_PACKAGE_NAME,
                    playerPackageName = application.packageName,
                    logo = ProviderLogo.fromSvg(Constants.ICON),
                ).apply {
                    register()
                }

                module.log(Log.INFO, TAG, "PiliPlus Provider registered")
            } catch (t: Throwable) {
                module.log(Log.ERROR, TAG, "Failed to register PiliPlus Provider", t)
            }
        }

        /**
         * 兜底：若宿主在 Application.onCreate hook 前就开始播放，
         * 通过 ActivityThread 反射获取 Application 以注册 Provider
         */
        fun ensureProvider() {
            if (lyricProvider != null) return
            currentApplication()?.let { setupProvider(it) }
        }

        private fun currentApplication(): Application? {
            return try {
                val activityThreadClass = Class.forName("android.app.ActivityThread")
                val currentActivityThread =
                    activityThreadClass.getMethod("currentActivityThread").invoke(null) ?: return null
                activityThreadClass.getMethod("getApplication").invoke(currentActivityThread) as? Application
            } catch (_: Throwable) {
                null
            }
        }

        /**
         * Hook MediaSession 的 setMetadata 和 setPlaybackState 方法
         *
         * PiliPlus 使用 Flutter audio_service 插件，底层通过 Android MediaSession
         * 广播媒体信息。其中：
         * - MediaMetadata.METADATA_KEY_TITLE = 视频标题
         * - MediaMetadata.METADATA_KEY_ARTIST = UP主名字
         * - MediaMetadata.METADATA_KEY_DURATION = 视频时长
         * - MediaMetadata.METADATA_KEY_MEDIA_ID = 唯一标识 (cid + herotag)
         * - MediaMetadata.METADATA_KEY_ART_URI = 视频封面
         */
        fun hookMediaSession(classLoader: ClassLoader?) {
            try {
                // MediaSession 是 framework 类，classLoader 传 null 时用 boot classloader 也可解析
                val sessionClass = Class.forName("android.media.session.MediaSession", false, classLoader)

                // Hook setMetadata - 获取视频标题和UP主
                val setMetadata = sessionClass.getDeclaredMethod("setMetadata", MediaMetadata::class.java)
                module.hook(setMetadata).intercept { chain ->
                    chain.proceed()
                    val metadata = chain.args.getOrNull(0) as? MediaMetadata
                    if (metadata != null) onMetadataChanged(metadata)
                    null
                }

                // Hook setPlaybackState - 同步播放状态并启停已播放时间同步任务
                val setPlaybackState =
                    sessionClass.getDeclaredMethod("setPlaybackState", PlaybackState::class.java)
                module.hook(setPlaybackState).intercept { chain ->
                    chain.proceed()
                    val state = chain.args.getOrNull(0) as? PlaybackState
                    lastPlaybackState = state
                    ensureProvider()
                    lyricProvider?.player?.setPlaybackState(state)
                    updateElapsedTracking()
                    null
                }

                module.log(Log.DEBUG, TAG, "MediaSession hooks installed")
            } catch (t: Throwable) {
                module.log(Log.ERROR, TAG, "Failed to install MediaSession hooks", t)
            }
        }

        /**
         * 处理 MediaMetadata 变更
         * 提取视频标题、UP主名字、时长等信息，按设置组装后推送
         *
         * 去重使用「mediaId + title + artist + duration」内容签名：
         * 即使 mediaId 相同，只要 UP主/标题/时长任一变化也会重新推送，
         * 避免 UP主信息与实际上屏不同步。
         */
        private fun onMetadataChanged(metadata: MediaMetadata) {
            val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE) ?: return
            val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
            val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
            val mediaId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID) ?: ""
            val artUri = metadata.getString(MediaMetadata.METADATA_KEY_ART_URI) ?: ""

            // 避免重复设置相同内容：mediaId 与内容签名都相同才跳过
            val videoId = mediaId.ifEmpty { "$title-$artist" }
            val contentSignature = "$videoId|$title|$artist|$duration"
            if (currentVideoId == videoId && lastContentSignature == contentSignature) return
            currentVideoId = videoId
            lastContentSignature = contentSignature
            currentDuration = duration

            module.log(
                Log.INFO, TAG,
                "Video changed: title=$title, artist=$artist, duration=$duration"
            )

            setSong(buildSong(title = title, artist = artist, duration = duration, videoId = videoId))

            // 新视频从头开始：开启「显示已播放时间」时立即同步位置为 0
            if (prefs().getBoolean(Constants.KEY_SHOW_ELAPSED_TIME, false)) {
                lyricProvider?.player?.setPosition(0L)
            }
        }

        /**
         * 按设置组装 Song
         * - push_title=false → name 置 null
         * - push_artist=false → artist 置 null
         * - push_duration=false → duration 置 0
         */
        private fun buildSong(title: String, artist: String, duration: Long, videoId: String): Song {
            val pushTitle = prefs().getBoolean(Constants.KEY_PUSH_TITLE, true)
            val pushArtist = prefs().getBoolean(Constants.KEY_PUSH_ARTIST, true)
            val pushDuration = prefs().getBoolean(Constants.KEY_PUSH_DURATION, true)
            return Song(
                id = videoId,
                name = if (pushTitle) title else null,
                artist = if (pushArtist) artist else null,
                duration = if (pushDuration) duration else 0L,
            )
        }

        /**
         * 设置当前歌曲（视频）信息到 Provider
         */
        private fun setSong(song: Song) {
            if (lastSong == song) return
            lastSong = song
            lyricProvider?.player?.setSong(song)
        }

        /**
         * 根据设置与播放状态启停已播放时间同步任务
         * 仅当「显示已播放时间」开启且处于播放中时运行
         */
        private fun updateElapsedTracking() {
            val showElapsed = prefs().getBoolean(Constants.KEY_SHOW_ELAPSED_TIME, false)
            val isPlaying = lastPlaybackState?.state == PlaybackState.STATE_PLAYING
            val shouldTrack = showElapsed && isPlaying
            if (shouldTrack && !isTrackingElapsed) {
                isTrackingElapsed = true
                elapsedRunnable.run()
            } else if (!shouldTrack && isTrackingElapsed) {
                isTrackingElapsed = false
                elapsedHandler.removeCallbacks(elapsedRunnable)
                // 停止前按暂停后的固定位置再同步一次
                syncElapsedTime()
            }
        }

        /**
         * 计算当前播放位置（毫秒）并通过 provider.setPosition 同步给词幕
         * 仅 STATE_PLAYING 时随时间推进，暂停/停止时固定为 position
         */
        private fun syncElapsedTime() {
            if (!prefs().getBoolean(Constants.KEY_SHOW_ELAPSED_TIME, false)) return
            val state = lastPlaybackState ?: return
            val positionMs = calculateCurrentPosition(state)
            lyricProvider?.player?.setPosition(positionMs)
        }

        /**
         * 推算实时进度：position + (now - lastUpdateTime)/1000 * speed
         */
        private fun calculateCurrentPosition(state: PlaybackState): Long {
            val base = state.position.coerceAtLeast(0L)
            val speed = state.playbackSpeed
            if (state.state != PlaybackState.STATE_PLAYING || speed <= 0f) return base
            // lastPositionUpdateTime 基于 SystemClock.elapsedRealtime()（开机计时），不能用 currentTimeMillis 混算
            val elapsedSinceUpdate = (SystemClock.elapsedRealtime() - state.lastPositionUpdateTime).coerceAtLeast(0L)
            val estimated = base + (elapsedSinceUpdate * speed).toLong()
            return if (currentDuration > 0) estimated.coerceIn(0L, currentDuration) else estimated.coerceAtLeast(0L)
        }

        companion object {
            private const val ELAPSED_UPDATE_INTERVAL_MS = 1000L
        }
    }
}
