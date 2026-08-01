package io.github.piliplusprovider.xposed

import android.media.MediaMetadata
import android.media.session.PlaybackState
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.log.YLog
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
 *
 * 并通过 Lyricon Provider 接口向外部软件（如词幕等）提供这些信息。
 */
object PiliPlusHook : YukiBaseHooker() {
    private const val TAG = "PiliPlusProvider"

    override fun onHook() {
        YLog.debug(tag = TAG, msg = "Hooking PiliPlus: $packageName")

        val providerManager = VideoInfoProviderManager()

        onAppLifecycle {
            onCreate {
                providerManager.setupProvider()
            }
        }

        providerManager.hookMediaSession()
    }

    /**
     * 视频信息提供者管理器
     * 负责 Hook MediaSession、管理 Lyricon Provider 生命周期
     */
    private class VideoInfoProviderManager {
        private var lyricProvider: LyriconProvider? = null
        private var lastSong: Song? = null
        private var currentVideoId: String = ""

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

                    // Hook setPlaybackState - 同步播放状态
                    firstMethod {
                        name = "setPlaybackState"
                        parameters(PlaybackState::class.java)
                    }.hook {
                        after {
                            val state = args[0] as? PlaybackState
                            lyricProvider?.player?.setPlaybackState(state)
                        }
                    }
                }

            YLog.debug(tag = TAG, msg = "MediaSession hooks installed")
        }

        /**
         * 处理 MediaMetadata 变更
         * 提取视频标题、UP主名字、时长等信息
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

            YLog.info(
                tag = TAG,
                msg = "Video changed: title=$title, artist=$artist, duration=$duration"
            )

            val song = Song(
                id = videoId,
                name = title,
                artist = artist,
                duration = duration
            )

            setSong(song)
        }

        /**
         * 设置当前歌曲（视频）信息到 Provider
         */
        private fun setSong(song: Song) {
            if (lastSong == song) return
            lastSong = song
            lyricProvider?.player?.setSong(song)
        }
    }
}
