# PiliPlusProvider

基于 Xposed 的视频信息提供器插件，从 [PiliPlus](https://github.com/bggRGjQaUbCoE/PiliPlus) 获取当前播放视频的**标题**和**UP主名字**，并通过 [Lyricon](https://github.com/tomakino/LyricProvider) Provider 接口向外部软件提供。

> 本项目由 Qwen 3.8 Max Preview 生成构建。

## 功能

- 实时获取 PiliPlus 当前播放视频的标题
- 实时获取视频 UP 主名字
- 同步播放/暂停状态
- 通过 Lyricon Provider 接口广播给外部显示软件（如词幕等）

## 工作原理

PiliPlus 使用 Flutter `audio_service` 插件，底层通过 Android `MediaSession` 广播媒体信息：

| MediaSession 字段 | 含义 |
|---|---|
| `METADATA_KEY_TITLE` | 视频标题 |
| `METADATA_KEY_ARTIST` | UP主名字 |
| `METADATA_KEY_DURATION` | 视频时长 |
| `METADATA_KEY_ART_URI` | 视频封面 |

本模块通过 YukiHookAPI Hook `MediaSession.setMetadata` 和 `MediaSession.setPlaybackState`，拦截上述信息并转发给 Lyricon Provider。

## 支持版本

| PiliPlus 版本 | 包名 |
|---|---|
| 正式版 | `com.example.piliplus` |
| Debug 版 | `com.example.piliplus.debug` |
| Dev 版 | `com.example.piliplus.dev` |

## 使用方法

1. 下载 Release 中的 APK 并安装
2. 在 LSPosed 管理器中启用本模块
3. 作用域勾选 PiliPlus
4. 强制停止 PiliPlus 后重新打开
5. 播放视频时，外部软件即可获取标题和 UP 主信息

> **注意**：PiliPlus 需开启「后台播放」功能，MediaSession 才会被激活。

## 技术栈

- [YukiHookAPI](https://github.com/HighCapable/YukiHookAPI) - Xposed Hook 框架
- [KavaRef](https://github.com/HighCapable/KavaRef) - 反射工具
- [Lyricon Provider](https://github.com/tomakino/LyricProvider) - 信息提供接口
- AGP 9.2.1 / Kotlin 2.2.10 / Gradle 9.4.1

## 构建

```bash
./gradlew assembleRelease
```

输出路径：`app/build/outputs/apk/release/`

## 参考

- [LyricProvider](https://github.com/tomakino/LyricProvider) - 歌词提供器插件架构参考
- [PiliPlus](https://github.com/bggRGjQaUbCoE/PiliPlus) - BiliBili 第三方客户端

## License

Apache License 2.0
