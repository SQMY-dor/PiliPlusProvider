package io.github.piliplusprovider.xposed

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.piliplusprovider.island.IslandBridge
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.ProviderLogo
import java.util.concurrent.Executors

/**
 * PiliPlus Hook 主逻辑（libxposed 现代 API 版）
 *
 * 通过 Hook MediaSession.setMetadata 获取 PiliPlus 当前播放视频的：
 * - 视频标题 (title)
 * - UP主名字 (artist)
 * - 视频时长 (duration)
 * - 视频封面 (Bitmap)
 *
 * 并推送给两个消费方：
 * 1. **词幕（Lyricon）** —— 通过 Lyricon Provider 接口（现有行为）
 * 2. **星河岛（AstraIsland）** —— 经显式广播转发到模块进程投送（见 [IslandBridge]）
 *
 * 推送内容与是否同步播放位置由模块设置（RemotePreferences "settings"）控制。
 */
object PiliPlusHook {
    private const val TAG = "PiliPlusProvider"

    /**
     * 当前这一「代」的 manager。
     *
     * 热重载会更换 classloader，新代无法触碰旧代对象，因此旧代只能在
     * [cleanup] 里自行收尾（由 HookEntry.onHotReloading 在旧 classloader 内调用）。
     */
    @Volatile
    private var activeManager: VideoInfoProviderManager? = null

    /**
     * 安装 Hook（由 HookEntry.onPackageReady 调用，仅对目标包名生效）
     *
     * @param module libxposed 模块实例（提供 hook() / getRemotePreferences() / log()）
     * @param param  onPackageReady 参数，param.classLoader 为宿主 classloader
     */
    fun install(module: XposedModule, param: PackageReadyParam) {
        module.log(Log.DEBUG, TAG, "Hooking PiliPlus: ${param.packageName}")

        val manager = VideoInfoProviderManager(module)
        activeManager = manager

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
        activeManager = manager
        manager.hookMediaSession(null)
        manager.ensureProvider()
    }

    /**
     * 热重载前的旧代收尾（在旧 classloader 内执行）。
     *
     * 不做这一步的后果：
     * - 旧代的每秒定时 Runnable 已在主线程 Handler 上自调度，旧 hook 被卸载后没人再
     *   调用 updateElapsedTracking()，它会**无限循环**——既泄漏旧 classloader，
     *   又会在旧模块实例上读设置（框架连接已拆除时抛异常 → 主线程崩溃）；
     * - 旧代的 LyriconProvider 不会被新代销毁（新代持有的是自己的空引用），
     *   于是同包名双注册，外部软件可能绑到永不更新的死 Provider。
     *
     * 这里只释放资源、不设「永久退役」标记：若热重载最终失败，旧 hook 仍然在，
     * 下一次 setPlaybackState 会经 ensureProvider()/updateElapsedTracking() 自动重建。
     */
    fun cleanup() {
        activeManager?.shutdown()
        activeManager = null
        // 断开日志回调：旧代模块实例在换代后其框架连接会被拆除，
        // 继续经它写日志可能抛异常（日志绝不能反噬调用方）
        IslandBridge.logger = null
    }

    /**
     * 视频信息提供者管理器
     * 负责 Hook MediaSession、管理 Lyricon Provider 生命周期、按设置推送内容
     */
    private class VideoInfoProviderManager(
        private val module: XposedModule,
    ) {
        /**
         * 设置快照：一次读取全部开关。
         *
         * 原先每条推送会分 5~6 次调用 getRemotePreferences()，而它是「获取时的
         * 快照」——在播放中每秒一次的位置同步路径上，等于每秒 6 次快照拉取。
         */
        private data class Settings(
            val pushTitle: Boolean = true,
            val pushArtist: Boolean = true,
            val pushDuration: Boolean = true,
            val showElapsedTime: Boolean = false,
            val enableIsland: Boolean = false,
            val islandPushCover: Boolean = true,
        )

        /** 最近一次成功读取的设置；读取失败时沿用，避免开关被静默关掉 */
        @Volatile
        private var lastSettings = Settings()

        /**
         * 读取设置。
         *
         * RemotePreferences 实例是获取时的快照，UI 侧修改后旧实例不会刷新，
         * 因此每次都重新拉取以保证开关即时生效；拉取失败（框架连接已拆除）
         * 时回退上一次成功快照，并把异常吃掉——不能让宿主主线程崩。
         */
        private fun settings(): Settings {
            return try {
                val p = module.getRemotePreferences(Constants.PREFS_NAME)
                Settings(
                    pushTitle = p.getBoolean(Constants.KEY_PUSH_TITLE, true),
                    pushArtist = p.getBoolean(Constants.KEY_PUSH_ARTIST, true),
                    pushDuration = p.getBoolean(Constants.KEY_PUSH_DURATION, true),
                    showElapsedTime = p.getBoolean(Constants.KEY_SHOW_ELAPSED_TIME, false),
                    enableIsland = p.getBoolean(Constants.KEY_ENABLE_ISLAND, false),
                    islandPushCover = p.getBoolean(Constants.KEY_ISLAND_PUSH_COVER, true),
                ).also { lastSettings = it }
            } catch (t: Throwable) {
                module.log(Log.WARN, TAG, "read settings failed, keep last snapshot", t)
                lastSettings
            }
        }

        private var lyricProvider: LyriconProvider? = null
        private var hostApplication: Application? = null

        /**
         * 以下状态会被三类线程访问：框架 Hook 回调线程（setMetadata/setPlaybackState）、
         * 主线程（每秒位置同步与封面补推）、封面压缩线程。全部标注 @Volatile 保证可见性，
         * 复合更新仍可能交叉，但最坏结果是重复投递一次（接收端签名去重兜住），
         * 不会放大成副作用。
         */
        @Volatile
        private var lastSong: Song? = null
        @Volatile
        private var currentVideoId: String = ""
        @Volatile
        private var lastContentSignature: String = ""
        @Volatile
        private var currentDuration: Long = 0L

        /** Provider 注册失败后的退避截止时刻（避免每秒重试刷屏/抖开销） */
        @Volatile
        private var providerRetryBlockedUntil: Long = 0L

        /** 最近一次的标题/UP主，供播放状态变化时复用 */
        @Volatile
        private var lastMetadata: Pair<String, String>? = null

        /**
         * 最近一次的封面 JPEG 字节与其对应视频 id。
         *
         * 不再长期持有宿主 Bitmap：album art 原始尺寸常有 1080p+（ARGB_8888 可达 8MB），
         * 且该 Bitmap 归 audio_service 所有，随时可能被回收。
         */
        @Volatile
        private var lastCoverJpeg: ByteArray? = null

        @Volatile
        private var lastCoverVideoId: String = ""

        /** 封面压缩线程：JPEG 编码是 CPU 活，不能压在宿主主线程上 */
        private val coverExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "island-cover-compress").apply { isDaemon = true }
        }

        /** 最近一次 PlaybackState，用于推算实时播放进度 */
        private var lastPlaybackState: PlaybackState? = null

        /** 已播放时间同步定时任务（每秒一次，仅播放中运行） */
        private val elapsedHandler = Handler(Looper.getMainLooper())
        @Volatile
        private var isTrackingElapsed = false
        private val elapsedRunnable = object : Runnable {
            override fun run() {
                // 已被 shutdown()/停止跟踪时不再自调度，避免旧代残留在主线程无限循环
                if (!isTrackingElapsed) return
                // 旧代残留执行、或框架连接拆除后读设置抛异常，都不能让宿主崩溃
                runCatching { syncElapsedTime() }
                    .onFailure { module.log(Log.WARN, TAG, "elapsed sync failed", it) }
                if (isTrackingElapsed) {
                    elapsedHandler.postDelayed(this, ELAPSED_UPDATE_INTERVAL_MS)
                }
            }
        }

        init {
            IslandBridge.logger = { message ->
                runCatching { module.log(Log.INFO, TAG, message) }
            }
        }

        /** 宿主 Context（用于发广播）；优先 Application，回退反射获取 */
        private fun hostContext(): Context? = hostApplication ?: currentApplication()

        /**
         * 初始化并注册 LyriconProvider（宿主进程内）
         *
         * 创建与注册分开处理：任一步失败都把 lyricProvider 置空，使后续
         * ensureProvider() 还能重试（失败退避见 PROVIDER_RETRY_BACKOFF_MS）。
         * 若沿用旧写法（在 apply{} 里 register()），注册抛异常时字段会被留在
         * 「已 destroy 但非 null」的状态，导致 Provider 永久失效。
         */
        fun setupProvider(application: Application) {
            hostApplication = application

            lyricProvider?.destroy()
            lyricProvider = null

            val provider = try {
                LyriconFactory.createProvider(
                    context = application,
                    providerPackageName = Constants.PROVIDER_PACKAGE_NAME,
                    playerPackageName = application.packageName,
                    logo = ProviderLogo.fromSvg(Constants.ICON),
                )
            } catch (t: Throwable) {
                module.log(Log.ERROR, TAG, "Failed to create PiliPlus Provider", t)
                providerRetryBlockedUntil = SystemClock.elapsedRealtime() + PROVIDER_RETRY_BACKOFF_MS
                return
            }

            try {
                provider.register()
                lyricProvider = provider
                module.log(Log.INFO, TAG, "PiliPlus Provider registered")
            } catch (t: Throwable) {
                runCatching { provider.destroy() }
                module.log(Log.ERROR, TAG, "Failed to register PiliPlus Provider", t)
                providerRetryBlockedUntil = SystemClock.elapsedRealtime() + PROVIDER_RETRY_BACKOFF_MS
            }
        }

        /**
         * 兜底：若宿主在 Application.onCreate hook 前就开始播放，
         * 通过 ActivityThread 反射获取 Application 以注册 Provider
         */
        fun ensureProvider() {
            if (lyricProvider != null && hostApplication != null) return
            if (SystemClock.elapsedRealtime() < providerRetryBlockedUntil) return
            currentApplication()?.let { setupProvider(it) }
        }

        /**
         * 本代收尾（热重载前由 [PiliPlusHook.cleanup] 调用）
         *
         * 停定时器 + 释放 Provider。可逆：后续回调会重新拉起。
         */
        fun shutdown() {
            isTrackingElapsed = false
            elapsedHandler.removeCallbacksAndMessages(null)
            runCatching { lyricProvider?.destroy() }
            lyricProvider = null
            lastSong = null
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
         * - MediaMetadata.METADATA_KEY_ART_URI = 视频封面 URL（字符串）
         * - MediaMetadata.METADATA_KEY_ALBUM_ART = 封面 Bitmap（audio_service 下载并解码后放入）
         */
        fun hookMediaSession(classLoader: ClassLoader?) {
            try {
                // MediaSession 是 framework 类，classLoader 传 null 时用 boot classloader 也可解析
                val sessionClass = Class.forName("android.media.session.MediaSession", false, classLoader)

                // Hook setMetadata - 获取视频标题、UP主与封面
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
                    if (state != null) {
                        lyricProvider?.player?.setPlaybackState(state)
                    }
                    // 会话结束（清空 / STOPPED）要撤销岛上的内容项，否则卡片会一直滞留
                    val ended = state == null ||
                        state.state == PlaybackState.STATE_NONE ||
                        state.state == PlaybackState.STATE_STOPPED
                    if (ended) {
                        onPlaybackStopped()
                    } else {
                        updateElapsedTracking()
                        pushToIsland()
                    }
                    null
                }

                module.log(Log.DEBUG, TAG, "MediaSession hooks installed")
            } catch (t: Throwable) {
                module.log(Log.ERROR, TAG, "Failed to install MediaSession hooks", t)
            }
        }

        /**
         * 处理 MediaMetadata 变更
         * 提取视频标题、UP主名字、时长、封面等信息，按设置组装后推送
         *
         * 去重使用「mediaId + title + artist + duration + 封面有无」内容签名：
         * - 任一字段变化都会重新推送，避免 UP主信息与实际上屏不同步
         * - **封面有无也算进去**：audio_service 会分两次推 metadata（先无图，
         *   封面下载完成后再带图重推），只按文本去重会把封面永久丢掉
         */
        private fun onMetadataChanged(metadata: MediaMetadata) {
            val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE) ?: return
            val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
            val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
            val mediaId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID) ?: ""
            val cover = extractCover(metadata)

            val videoId = mediaId.ifEmpty { "$title-$artist" }
            val contentSignature = "$videoId|$title|$artist|$duration|${if (cover != null) "art" else "noart"}"
            if (currentVideoId == videoId && lastContentSignature == contentSignature) return
            currentVideoId = videoId
            lastContentSignature = contentSignature
            currentDuration = duration
            lastMetadata = title to artist

            if (cover != null) {
                // 压缩放到后台线程；完成后会用带图的字节再推一次岛
                submitCoverCompress(cover, videoId)
            } else {
                lastCoverJpeg = null
                lastCoverVideoId = ""
            }

            module.log(
                Log.INFO, TAG,
                "Video changed: title=$title, artist=$artist, duration=$duration, cover=${cover != null}"
            )

            val snapshot = settings()
            setSong(
                buildSong(
                    title = title,
                    artist = artist,
                    duration = duration,
                    videoId = videoId,
                    settings = snapshot,
                )
            )

            // 新视频从头开始：开启「显示已播放时间」时立即同步位置为 0
            if (snapshot.showElapsedTime) {
                lyricProvider?.player?.setPosition(0L)
            }

            pushToIsland(settings = snapshot)
        }

        /**
         * 取封面 Bitmap。
         *
         * audio_service 的行为（已核对 fork 源码）：
         * - Dart 侧发现 artUri 不是 content:/file: 就先用缓存管理器下载图片，
         *   把本地路径写进 extras["artCacheFile"]；
         * - Java 侧 `setMetadata` 见到 artCacheFile 就 `loadArtBitmap()` 解码成 Bitmap，
         *   塞进 METADATA_KEY_ALBUM_ART 与 METADATA_KEY_DISPLAY_ICON。
         * 所以这里直接取现成的 Bitmap，**不需要我们发起任何下载**。
         * METADATA_KEY_ART_URI 只是那个 http URL 字符串，不能直接用。
         */
        private fun extractCover(metadata: MediaMetadata): Bitmap? {
            return try {
                metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                    ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
                    ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
            } catch (_: Throwable) {
                null
            }
        }

        /**
         * 后台压缩封面并缓存字节。
         *
         * 完成时若视频已切换则丢弃（避免旧任务覆盖新封面）；否则触发一次补推，
         * 把岛上先前的「无图版」升级为「带图版」。
         */
        private fun submitCoverCompress(source: Bitmap, videoId: String) {
            coverExecutor.execute {
                val bytes = IslandBridge.compressCover(source)
                if (bytes == null || bytes.isEmpty()) return@execute
                if (currentVideoId != videoId) return@execute
                lastCoverJpeg = bytes
                lastCoverVideoId = videoId
                elapsedHandler.post {
                    runCatching { pushToIsland() }
                        .onFailure { module.log(Log.WARN, TAG, "cover repush failed", it) }
                }
            }
        }

        /**
         * 按设置组装 Song
         * - push_title=false → name 置 null
         * - push_artist=false → artist 置 null
         * - push_duration=false → duration 置 0
         */
        private fun buildSong(
            title: String,
            artist: String,
            duration: Long,
            videoId: String,
            settings: Settings,
        ): Song {
            return Song(
                id = videoId,
                name = if (settings.pushTitle) title else null,
                artist = if (settings.pushArtist) artist else null,
                duration = if (settings.pushDuration) duration else 0L,
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
            val showElapsed = settings().showElapsedTime
            val isPlaying = lastPlaybackState?.state == PlaybackState.STATE_PLAYING
            val shouldTrack = showElapsed && isPlaying
            if (shouldTrack && !isTrackingElapsed) {
                isTrackingElapsed = true
                // 统一投到主线程执行，避免首次同步跑在 binder 线程上
                elapsedHandler.post(elapsedRunnable)
            } else if (!shouldTrack && isTrackingElapsed) {
                isTrackingElapsed = false
                elapsedHandler.removeCallbacks(elapsedRunnable)
                // 停止前按暂停后的固定位置再同步一次
                runCatching { syncElapsedTime() }
            }
        }

        /**
         * 播放会话结束：停定时器、撤销岛上的内容项、清空内容缓存。
         *
         * 此前只有「推送」没有「撤销」，播放结束后岛上的卡片会一直滞留到下一个视频。
         * 清空 currentVideoId/lastContentSignature 也让同一视频再次播放时能重新推送。
         */
        private fun onPlaybackStopped() {
            if (isTrackingElapsed) {
                isTrackingElapsed = false
                elapsedHandler.removeCallbacks(elapsedRunnable)
            }
            if (settings().enableIsland) {
                hostContext()?.let { IslandBridge.sendClear(it) }
            }
            lastMetadata = null
            lastCoverJpeg = null
            lastCoverVideoId = ""
            currentVideoId = ""
            lastContentSignature = ""
            lastSong = null
        }

        /**
         * 计算当前播放位置（毫秒）并通过 provider.setPosition 同步给词幕
         * 仅 STATE_PLAYING 时随时间推进，暂停/停止时固定为 position
         */
        private fun syncElapsedTime() {
            val state = lastPlaybackState ?: return
            val positionMs = calculateCurrentPosition(state)
            val snapshot = settings()
            if (snapshot.showElapsedTime) {
                lyricProvider?.player?.setPosition(positionMs)
            }
            pushToIsland(positionMs = positionMs, settings = snapshot)
        }

        /**
         * 把当前视频信息转发给模块进程（由模块以自己身份投送到星河岛）。
         *
         * 仅在「推送到星河岛」开关开启时执行。封面走 JPEG 字节（见 IslandBridge）。
         */
        private fun pushToIsland(positionMs: Long? = null, settings: Settings? = null) {
            val snapshot = settings ?: this.settings()
            if (!snapshot.enableIsland) return
            val context = hostContext() ?: return
            val metadata = lastMetadata ?: return

            val title = if (snapshot.pushTitle) metadata.first else ""
            val artist = if (snapshot.pushArtist) metadata.second else ""
            val duration = if (snapshot.pushDuration) currentDuration else 0L
            val coverBytes =
                if (snapshot.islandPushCover && lastCoverVideoId == currentVideoId) lastCoverJpeg else null

            val state = lastPlaybackState
            val playing = state?.state == PlaybackState.STATE_PLAYING
            val position = positionMs ?: state?.let { calculateCurrentPosition(it) } ?: 0L

            IslandBridge.sendVideo(
                hostContext = context,
                title = title,
                artist = artist,
                durationMs = duration,
                videoId = currentVideoId,
                playing = playing,
                positionMs = position,
                coverBytes = coverBytes,
            )
        }

        /**
         * 推算实时进度：position + (now - lastUpdateTime)/1000 * speed
         *
         * `lastPositionUpdateTime` 基于 `SystemClock.elapsedRealtime()`（开机计时），
         * 不能用 `currentTimeMillis()` 混算，否则进度会错到离谱。
         */
        private fun calculateCurrentPosition(state: PlaybackState): Long {
            val base = state.position.coerceAtLeast(0L)
            val speed = state.playbackSpeed
            if (state.state != PlaybackState.STATE_PLAYING || speed <= 0f) return base
            val elapsedSinceUpdate =
                (SystemClock.elapsedRealtime() - state.lastPositionUpdateTime).coerceAtLeast(0L)
            val estimated = base + (elapsedSinceUpdate * speed).toLong()
            return if (currentDuration > 0) {
                estimated.coerceIn(0L, currentDuration)
            } else {
                estimated.coerceAtLeast(0L)
            }
        }

        companion object {
            private const val ELAPSED_UPDATE_INTERVAL_MS = 1000L

            /** Provider 注册失败后的重试退避（毫秒） */
            private const val PROVIDER_RETRY_BACKOFF_MS = 30_000L
        }
    }
}
