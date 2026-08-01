# 任务：为 PiliPlusProvider 新增「推送内容选择」设置 + 可选显示已播放时间，GUI 使用 Miuix 主题

## 项目位置
工作目录：/root/PiliPlusProvider（Xposed 模块，YukiHookAPI 框架，Kotlin）
先阅读现有代码理解结构，再动手改。

## 项目现状
- 功能：Hook PiliPlus（B站第三方客户端）的 MediaSession，把当前播放视频的标题、UP主推送给外部软件（词幕等，通过 Lyricon Provider 接口）。
- 关键文件：
  - `app/src/main/kotlin/io/github/piliplusprovider/xposed/PiliPlusHook.kt` —— 核心 Hook 逻辑（MediaSession.setMetadata / setPlaybackState）
  - `app/src/main/kotlin/io/github/piliplusprovider/xposed/HookEntry.kt` —— Hook 入口
  - `app/src/main/kotlin/io/github/piliplusprovider/xposed/Constants.kt` —— 常量（包名、图标 SVG）
  - `app/src/main/AndroidManifest.xml` —— 已有 xposedsharedprefs=true（模块与宿主共享 SharedPreferences 的基础）
  - `app/src/main/res/values/strings.xml`、`themes.xml`、`arrays.xml`
- 依赖版本：YukiHookAPI 1.3.1、KavaRef 1.0.2、Lyricon provider 0.1.70（io.github.proify.lyricon:provider）、AGP 9.2.1、Kotlin 2.2.10、Gradle 9.4.1、compileSdk 36 / minSdk 28 / targetSdk 35
- Lyricon API（已确认）：
  - `io.github.proify.lyricon.lyric.model.Song`：data class Song(id: String?, name: String?, artist: String?, duration: Long, metadata, lyrics)
  - `io.github.proify.lyricon.provider.RemotePlayer`：setSong(Song?)、setPlaybackState(Boolean)、setPlaybackState(PlaybackState?)、seekTo(Long)、setPosition(Long)、setPositionUpdateInterval(Int)、sendText(String?) 等
  - 现有代码构造 Song(name=title, artist=artist, duration=duration) 并通过 lyricProvider.player.setSong(song) 推送；setPlaybackState(PlaybackState) 同步播放状态。
- 已播放时间来源：PlaybackState.getPosition()（毫秒，当前播放位置），配合 getState()（STATE_PLAYING=3）、getLastUpdateTime()、getPlaybackSpeed() 可推算实时进度。

## 需求

### 1. 新增设置界面（GUI），使用 Miuix 主题
- 添加 Miuix 依赖（Xiaomi HyperOS 风格 Compose Multiplatform UI 库，最新 0.8.8）：
  - `implementation("top.yukonga.miuix.kmp:miuix-ui:0.8.8")`
  - `implementation("top.yukonga.miuix.kmp:miuix-preference:0.8.8")`（提供 SwitchPreference、CheckboxPreference 等组件）
  - 需要启用 Jetpack Compose：加 `org.jetbrains.kotlin.plugin.compose` 插件（版本与 Kotlin 2.2.10 一致）、`androidx.compose:compose-bom`、`androidx.activity:activity-compose`，`buildFeatures { compose = true }`。Miuix 组件文档参考 https://github.com/compose-miuix-ui/miuix （MiuixTheme { SettingsScaffold { SettingsSection { SwitchPreference(...) } } } 的用法；包名 top.yukonga.miuix.kmp.basic / .preference）。
- 新建 MainActivity（Kotlin + Compose + Miuix）作为设置界面，Manifest 注册为 launcher activity（模块图标点击进入设置）。
- 设置项（SharedPreferences，文件名用默认 `${applicationId}_preferences`，与 Xposed 共享一致）：
  1. 「推送标题」开关（默认开）—— 控制是否推送视频标题
  2. 「推送UP主」开关（默认开）—— 控制是否推送UP主名字
  3. 「推送时长」开关（默认开）—— 控制是否推送视频总时长
  4. 「显示已播放时间」开关（默认关）—— 开启后推送内容附带当前已播放位置（格式 mm:ss，如 03:45），并同步播放位置给词幕
  - 界面中文文案，Miuix 风格（标题栏 "PiliPlus Provider" 或 "设置"，分组标题如「推送内容」）。开关用 SwitchPreference(checked, onCheckedChange, title, summary)。
- 设置写入用模块进程内普通 SharedPreferences（getSharedPreferences("${BuildConfig.APPLICATION_ID}_preferences", Context.MODE_PRIVATE)），确保与 manifest 的 xposedsharedprefs 匹配。

### 2. Hook 端读取设置并按配置推送
- 在 PiliPlusHook 内用 YukiHookAPI 的 prefs API 读取模块设置（宿主进程内跨进程读模块 SP）：
  - 在 YukiBaseHooker 内可通过 `packageParam.prefs` 获取 YukiHookPrefsBridge，调用 `getBoolean(key, default)`（包 com.highcapable.yukihookapi.hook.xposed.prefs）。YukiHookAPI 会自动用 XSharedPreferences 读模块 SP（默认文件名 `${modulePackageName}_preferences`）。
  - 或者在 onHook 里用 YukiHookPrefsBridge.from() 读取。注意读取时机与缓存（XSharedPreferences 有缓存，设置变化后可能需要 reload；hook 内每次推送前读取即可，能容忍轻微延迟）。
- 推送内容按设置组装 Song：
  - push_title=false → name 置 null（或 "")
  - push_artist=false → artist 置 null
  - push_duration=false → duration 置 0
  - show_elapsed_time=true → 启动一个定时任务（协程或 Handler，每 1 秒）：
    - 从最近一次 PlaybackState 计算当前进度：position + (now - lastUpdateTime)/1000*speed（仅 STATE_PLAYING 时推进，暂停时固定 position）
    - 格式化 mm:ss（超过 1 小时用 h:mm:ss）
    - 通过 provider.player.setPosition(positionMs) 同步播放位置（词幕显示进度）
    - 若想同时让文本显示已播放时间，可把时间文本附加到标题后（如 "标题 03:45"）—— 由你决定最稳妥的呈现方式：优先用 setPosition 同步 + 保持标题纯净；若认为附加文本更有用则附加。实现要防止频繁 setSong 造成闪烁。
  - 播放状态切换（STATE_PLAYING/PAUSED）时相应启停定时任务。
- 保持现有"视频切换去重"（currentVideoId）逻辑不变。

### 3. 构建验证（必须通过）
- 项目无 gradlew 脚本，直接用系统 Gradle：`/opt/gradle-9.4.1/bin/gradle`
- Android SDK 已装于 /opt/android-sdk（platforms/android-36、build-tools/36.0.0-3 都有）。设置 local.properties 写入 `sdk.dir=/opt/android-sdk`，或 export ANDROID_HOME=/opt/android-sdk。
- 构建命令：`cd /root/PiliPlusProvider && ANDROID_HOME=/opt/android-sdk /opt/gradle-9.4.1/bin/gradle :app:assembleDebug`
- 首次构建会下载依赖（Miuix、Compose 等），网络较慢，用环境代理（http://127.0.0.1:7890 已在环境中）。若 mavenCentral/google 下载超时，可在 settings.gradle.kts 增加阿里云镜像（maven.aliyun.com/repository/public、google、gradle-plugin），保持原有仓库顺序即可。
- 若 Miuix 0.8.8 与 Kotlin 2.2.10 有 klib/编译器不兼容（报 kotlin version 不匹配等），可尝试降 Miuix 到 0.7.x 或相应调整（以能构建通过为准，优先保持 Kotlin 2.2.10 不动）。
- 修复所有编译错误直到 BUILD SUCCESSFUL。

### 4. 收尾
- 不要提交 reasonix.toml（项目级配置文件）——git rm --cached reasonix.toml 或加入 .gitignore（reasonix.toml 是运行工具配置，不属于项目本身）。
- git add 所有改动并 commit（git -c user.email=dev@local -c user.name=dev commit），message 描述本次改动（中文即可）。
- 最后报告：改动文件清单、构建结果（BUILD SUCCESSFUL + APK 输出路径 app/build/outputs/apk/debug/）、设置项说明。
