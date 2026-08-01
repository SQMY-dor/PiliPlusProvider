package io.github.piliplusprovider.xposed

import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.log.YLog
import com.highcapable.yukihookapi.hook.xposed.prefs.YukiHookPrefsBridge
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.ProviderLogo

/**
 * PiliPlus Hook 主入口
 *
 * 通过 Hook MediaSession.setMetadata 获取 PiliPlus 当前播放视频的：
 * - 视频标题 (title)
 * - UP主名字 (artist)
 * - 视频时长 (duration)
 *
 * 并通过 Lyricon Provider 接口向外部软件（如词幕等）提供这些信息。
 * 推送内容与是否同步播放位置由模块设置（SharedPreferences）控制。
 */
object PiliPlusHook : YukiBaseHooker() {
    private const val TAG = "PiliPlusProvider"

    override fun onHook() {
        YLog.debug(tag = TAG, msg = "Hooking PiliPlus: $packageName")

        // 读取模块设置（宿主进程内通过 XSharedPreferences 跨进程读取模块 SP，
        // 默认文件名 ${modulePackageName}_preferences，与 Manifest xposedsharedprefs 一致）
        // 注：YukiBaseHooker 继承自 PackageParam，prefs 为其成员属性
        val providerManager = VideoInfoProviderManager(prefs)

        onAppLifecycle {
            onCreate {
                providerManager.setupProvider()
            }
        }

        providerManager.hookMediaSession()
    }

    /**
     * 视频信息提供者管理器
     * 负责 Hook MediaSession、管理 Lyricon Provider 生命周期、按设置推送内容
     */
    private class VideoInfoProviderManager(private val prefs: YukiHookPrefsBridge) {
        private var lyricProvider: LyriconProvider? = null
        private var lastSong: Song? = null
        private var currentVideoId: String = ""
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
         * 初始化并注册 LyriconProvider
         */
        fun setupProvider() {
            val application = appContext ?: return
            lyricProvider?.destroy()

            lyricProvider = LyriconFactory.createProvider(
                context = application,
                providerPackageName = Constants.PROVIDER_PACKAGE_NAME,
                playerPackageName = application.packageName,
                logo = ProviderLogo.fromSvg(Constants.ICON)
            ).apply {
                register()
            }

            YLog.info(tag = TAG, msg = "PiliPlus Provider registered")
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
        fun hookMediaSession() {
            "android.media.session.MediaSession".toClass()
                .resolve()
                .apply {
                    // Hook setMetadata - 获取视频标题和UP主
                    firstMethod {
                        name = "setMetadata"
                        parameters(MediaMetadata::class.java)
                    }.hook {
                        after {
                            val metadata = args[0] as? MediaMetadata ?: return@after
                            onMetadataChanged(metadata)
                        }
                    }

                    // Hook setPlaybackState - 同步播放状态并启停已播放时间同步任务
                    firstMethod {
                        name = "setPlaybackState"
                        parameters(PlaybackState::class.java)
                    }.hook {
                        after {
                            val state = args[0] as? PlaybackState
                            lastPlaybackState = state
                            lyricProvider?.player?.setPlaybackState(state)
                            updateElapsedTracking()
                        }
                    }
                }

            YLog.debug(tag = TAG, msg = "MediaSession hooks installed")
        }

        /**
         * 处理 MediaMetadata 变更
         * 提取视频标题、UP主名字、时长等信息，按设置组装后推送
         */
        private fun onMetadataChanged(metadata: MediaMetadata) {
            val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE) ?: return
            val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
            val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
            val mediaId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID) ?: ""
            val artUri = metadata.getString(MediaMetadata.METADATA_KEY_ART_URI) ?: ""

            // 避免重复设置相同视频
            val videoId = mediaId.ifEmpty { "$title-$artist" }
            if (currentVideoId == videoId) return
            currentVideoId = videoId
            currentDuration = duration

            YLog.info(
                tag = TAG,
                msg = "Video changed: title=$title, artist=$artist, duration=$duration"
            )

            setSong(buildSong(title = title, artist = artist, duration = duration, videoId = videoId))

            // 新视频从头开始：开启「显示已播放时间」时立即同步位置为 0
            if (prefs.getBoolean(Constants.KEY_SHOW_ELAPSED_TIME, false)) {
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
            val pushTitle = prefs.getBoolean(Constants.KEY_PUSH_TITLE, true)
            val pushArtist = prefs.getBoolean(Constants.KEY_PUSH_ARTIST, true)
            val pushDuration = prefs.getBoolean(Constants.KEY_PUSH_DURATION, true)
            return Song(
                id = videoId,
                name = if (pushTitle) title else null,
                artist = if (pushArtist) artist else null,
                duration = if (pushDuration) duration else 0L
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
            val showElapsed = prefs.getBoolean(Constants.KEY_SHOW_ELAPSED_TIME, false)
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
            if (!prefs.getBoolean(Constants.KEY_SHOW_ELAPSED_TIME, false)) return
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
