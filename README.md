# PiliPlusProvider

基于 Xposed 的视频信息提供器插件，从 [PiliPlus](https://github.com/bggRGjQaUbCoE/PiliPlus) 获取当前播放视频的**标题**、**UP主名字**和**封面**，并通过两个渠道向外部软件提供：

- [Lyricon](https://github.com/tomakino/LyricProvider) Provider 接口（词幕等）
- [星河岛 AstraIsland](https://github.com/MuYuanXing/AstraIsland-Developers) 接入库（岛胶囊 / 展开卡片）

> 本项目由 Qwen 3.8 Max Preview  Deepseek V4.1 Flash 生成构建。

## 功能

- 实时获取 PiliPlus 当前播放视频的标题
- 实时获取视频 UP 主名字
- 实时获取视频封面
- 同步播放/暂停状态与播放进度
- 通过 Lyricon Provider 接口广播给外部显示软件（如词幕等）
- 推送到星河岛（MEDIA 模板：封面 + 标题 + UP主 + 播放进度）

## 工作原理

PiliPlus 使用 Flutter `audio_service` 插件，底层通过 Android `MediaSession` 广播媒体信息：

| MediaSession 字段 | 含义 |
|---|---|
| `METADATA_KEY_TITLE` | 视频标题 |
| `METADATA_KEY_ARTIST` | UP主名字 |
| `METADATA_KEY_DURATION` | 视频时长 |
| `METADATA_KEY_ART_URI` | 视频封面 URL（字符串，**不能直接用**） |
| `METADATA_KEY_ALBUM_ART` | 视频封面 Bitmap（audio_service 下载解码后放入） |

本模块 Hook `MediaSession.setMetadata` 和 `MediaSession.setPlaybackState`，拦截上述信息并转发给 Lyricon Provider。

### 封面从哪来（无需额外下载）

PiliPlus 只在 `artUri` 里放一个 B 站 http URL。`audio_service`（PiliPlus 用的 fork）会自动：

1. **Dart 侧**发现 `artUri` 不是 `content:`/`file:` → 用缓存管理器下载图片，把本地路径写进 `extras["artCacheFile"]`
2. **Java 侧** `setMetadata` 见到 `artCacheFile` → `loadArtBitmap()` 解码成 Bitmap → 放入 `METADATA_KEY_ALBUM_ART` / `METADATA_KEY_DISPLAY_ICON`

所以本模块直接取现成的 Bitmap，**不产生任何额外网络请求**。

> ⚠️ 封面是**分两次**推来的（先无图，下载完再带图重推）。因此去重签名必须包含「封面有无」，
> 否则第二次推送会被当成重复内容丢弃，封面永远上不了岛。

### 星河岛投送为什么要绕一圈

星河岛按**调用方 uid** 核对来源包名（`bind()` 时比对，之后每次调用再核一次）。
Hook 代码跑在 PiliPlus 进程，uid 属于 PiliPlus，而 PiliPlus 清单没有声明
`com.astraisland.permission.PUBLISH_ACTIVITY`（第三方 App，清单不可改），
直接投送必然被拒。

因此链路是：

```
PiliPlus 进程（Hook）--显式广播--> 模块进程（IslandPublisher）--Binder--> 星河岛
```

模块进程的 uid 与自报包名一致，才过得了身份校验。

用**广播**而非 ContentProvider：Android 11+ 包可见性会过滤「访问其他 App 的
ContentProvider」，除非**调用方**声明 `<queries><provider authorities=...>`，而调用方
PiliPlus 清单不可改；显式广播由 system_server 解析组件投递，不受调用方包可见性限制。

## 星河岛接入

- 接入库：`app/libs/astraisland-client.aar`（1.0.0，协议版本 5）
  - SHA256：`058a477e43b54441fb8137e69328a0569a34b4d353c37d8177fb60aba6a1c3bc`
- 来源：[MuYuanXing/AstraIsland-Developers](https://github.com/MuYuanXing/AstraIsland-Developers)（PolyForm Noncommercial 1.0.0，禁止商用）
- 需要星河岛 1.0+（系统 Android 15+）并启用其模块，作用域勾选「系统界面」
- 内容项用 `MEDIA` 模板，固定 id，每次整份替换（不占 3 个配额）

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
6. 如需推送到星河岛，在模块设置中开启「推送到星河岛」

> **注意**：PiliPlus 需开启「后台播放」功能，MediaSession 才会被激活。

## 技术栈

- [libxposed API 102](https://github.com/libxposed/api) - Xposed Hook 框架（现代 API）
- [Lyricon Provider](https://github.com/tomakino/LyricProvider) - 信息提供接口
- [AstraIsland Client](https://github.com/MuYuanXing/AstraIsland-Developers) - 星河岛接入库
- Miuix - HyperOS 风格设置界面
- AGP 9.2.1 / Kotlin 2.2.10 / Gradle 9.4.1

## 构建

```bash
./gradlew assembleDebug
```

输出路径：`app/build/outputs/apk/debug/`

## CI 自动构建与发布（GitHub Actions）

仓库带有一个开箱即用的 workflow（`.github/workflows/build.yml`）：

- **push 到 `master`**：自动编译 debug + release 验证，不上传产物
- **push tag `v*`**（如 `v1.1.7`）：自动编译 + 用正式密钥签名 + 创建 GitHub Release 并上传
  `PiliPlusProvider-v<版本>-release.apk` 与 `debug.apk`

tag 必须与 `version.properties` 的 `versionName` 一致，否则发布会直接失败：

```bash
./gradlew bumpVersion -PnewVersion=1.1.7   # versionCode +1 并写入新版本号
git add version.properties && git commit -m "chore: bump 1.1.7"
git tag v1.1.7 && git push origin master v1.1.7
```

### CI 签名密钥（必需配置一次）

CI 的 release 签名密钥从 GitHub Secrets 注入，**仓库内不保存任何密钥材料**。
到仓库 **Settings → Secrets and variables → Actions → New repository secret**，
添加以下 4 个：

| Secret 名 | 值 |
|---|---|
| `KEYSTORE_BASE64` | keystore 文件的 base64（见下方命令） |
| `KEYSTORE_PASSWORD` | keystore 口令 |
| `KEY_ALIAS` | 密钥别名（本项目为 `piliplus`） |
| `KEY_PASSWORD` | 密钥口令 |

在本机生成 `KEYSTORE_BASE64` 的值（一整行，直接粘贴进 Secret）：

```bash
base64 -w0 piliplus-release.keystore
```

未配置 Secrets 时：master 分支构建仍可编译（release 产物为 unsigned，仅验证）；
tag 构建会在「Verify signing secrets」一步直接失败，防止发出未签名包。

## 参考

- [LyricProvider](https://github.com/tomakino/LyricProvider) - 歌词提供器插件架构参考
- [AstraIsland-Developers](https://github.com/MuYuanXing/AstraIsland-Developers) - 星河岛开发者接入库
- [PiliPlus](https://github.com/bggRGjQaUbCoE/PiliPlus) - BiliBili 第三方客户端

## License

Apache License 2.0
test gpg signing
