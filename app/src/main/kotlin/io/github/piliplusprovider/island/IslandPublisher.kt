package io.github.piliplusprovider.island

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.astraisland.client.IslandClient
import com.astraisland.protocol.ActivityBundle
import kotlin.math.max

/**
 * 星河岛（AstraIsland）投送端 — 运行在模块自己的进程里。
 *
 * ## 为什么必须由模块进程投送
 * 星河岛按「调用方 uid」核对来源包名（协议第四节：`bind()` 比对 uid 与自报包名，
 * 之后每次调用再核一次）。Hook 代码跑在 PiliPlus 进程，uid 属于 PiliPlus，而
 * PiliPlus 的清单没有声明 `com.astraisland.permission.PUBLISH_ACTIVITY`
 * （第三方 App 清单不可改），直接把 IslandClient 放在宿主进程里必然失败。
 * 模块进程 uid 与自报包名一致，才过得了身份校验。
 *
 * ## 内容项
 * 用 MEDIA 模板：hero(封面) + title + subtitle(UP主) + playing + durationMs + positionMs。
 * 固定 id，每次整份替换（不占 3 个配额）。
 */
object IslandPublisher {

    private const val TAG = "PiliPlusProvider"

    /** 固定内容项 id：始终替换同一个 */
    const val ACTIVITY_ID = "piliplus-now-playing"

    private var client: IslandClient? = null
    private var appContext: Application? = null

    /** 最近一次待投送内容：模块进程被广播冷启动时，连接尚未就绪，需等 READY 后补投 */
    @Volatile
    private var pending: PendingPayload? = null

    @Volatile
    private var lastSignature: String = ""

    var logger: ((String) -> Unit)? = null

    private fun log(message: String) {
        Log.i(TAG, "[island] $message")
        logger?.invoke(message)
    }

    /** 待投送的原始数据（封面保持压缩字节，避免在模块进程外持有 Bitmap） */
    private data class PendingPayload(
        val title: String,
        val artist: String,
        val durationMs: Long,
        val videoId: String,
        val playing: Boolean,
        val positionMs: Long,
        val coverBytes: ByteArray?,
    ) {
        // ByteArray 需要手写 equals/hashCode 语义；这里只做去重标识，用签名代替
    }

    private fun signatureOf(p: PendingPayload): String =
        "${p.videoId}|${p.title}|${p.artist}|${p.durationMs}|${p.playing}|${if (p.coverBytes != null) "art" else "noart"}"

    /**
     * 建立与星河岛的连接。在模块 Application.onCreate 调用一次。
     *
     * `connect()` 内部先查 com.astraisland 是否安装（依赖 manifest 的 <queries>），
     * 未安装则进入 NOT_INSTALLED，不会抛错。
     */
    fun attach(application: Application) {
        if (client != null) return
        appContext = application
        try {
            val instance = IslandClient(application.applicationContext) { activityId, actionId ->
                log("action: $activityId / $actionId")
            }
            instance.onReadyChanged = { ready ->
                log("ready=$ready state=${instance.state} proto=${instance.islandProtocolVersion}")
                if (ready) {
                    // 岛（系统界面）重启会清空内容项；模块进程冷启动时也会走到这里。
                    // 清掉去重签名并补投最近一次内容。
                    lastSignature = ""
                    pending?.let { publishNow(it, force = true) }
                }
            }
            instance.onEvent = { activityId, event, ts ->
                log("event: $activityId $event $ts")
            }
            instance.connect()
            client = instance
            log("client created, state=${instance.state}")
        } catch (t: Throwable) {
            log("attach failed: ${t.message}")
        }
    }

    fun isReady(): Boolean = client?.isReady == true

    fun isInstalled(): Boolean = client?.isIslandInstalled() == true

    fun currentState(): String = client?.state?.name ?: "NOT_ATTACHED"

    fun protocolVersion(): Int = client?.islandProtocolVersion ?: 0

    /**
     * 投送/更新内容项。
     *
     * 内容签名包含「封面有无」：audio_service 会分两次推 metadata
     * （先无图，封面下载完成后再带着图重推一次），只按标题/UP主去重会把封面永久丢掉。
     */
    fun submit(
        title: String,
        artist: String,
        durationMs: Long,
        videoId: String,
        playing: Boolean,
        positionMs: Long,
        coverBytes: ByteArray?,
    ): Int {
        val payload = PendingPayload(title, artist, durationMs, videoId, playing, positionMs, coverBytes)
        pending = payload

        val instance = client ?: return RESULT_NOT_CONNECTED
        if (!instance.isReady) return RESULT_NOT_CONNECTED
        return publishNow(payload, force = false)
    }

    private fun publishNow(payload: PendingPayload, force: Boolean): Int {
        val instance = client ?: return RESULT_NOT_CONNECTED
        val signature = signatureOf(payload)
        if (!force && signature == lastSignature) return RESULT_OK
        lastSignature = signature

        return try {
            instance.start(buildBundle(payload)).also { result ->
                if (result != RESULT_OK) log("start -> result=$result")
            }
        } catch (t: Throwable) {
            log("start threw: ${t.message}")
            RESULT_NOT_CONNECTED
        }
    }

    /** 结束内容项（停止播放时调用） */
    fun clear() {
        lastSignature = ""
        pending = null
        runCatching { client?.end(ACTIVITY_ID) }
    }

    private fun buildBundle(p: PendingPayload): android.os.Bundle {
        val cover = decodeCover(p.coverBytes)
        val displayTitle = p.title.ifBlank { p.artist }.ifBlank { "未知视频" }
        val subtitle = if (p.title.isBlank()) "" else p.artist

        // 身份区：有封面用缩略图，否则内置播放图标
        val leading = if (cover != null) {
            ActivityBundle.encodeSlot(
                "image",
                icon = ActivityBundle.encodeIcon("bitmap", bitmap = cover),
                rounded = true,
            )
        } else {
            ActivityBundle.encodeSlot(
                "icon",
                icon = ActivityBundle.encodeIcon("builtin", builtin = "PLAY"),
            )
        }

        // 信息区：播放中显示波形，暂停显示已播放位置
        val trailing = if (p.playing) {
            ActivityBundle.encodeSlot("waveform", active = true)
        } else {
            ActivityBundle.encodeSlot("text", text = formatDuration(p.positionMs))
        }

        val expanded = ActivityBundle.encodeExpanded(
            template = "MEDIA",
            title = displayTitle,
            subtitle = subtitle,
            hero = cover?.let { ActivityBundle.encodeIcon("bitmap", bitmap = it) },
            playing = p.playing,
            durationMs = p.durationMs,
            positionMs = p.positionMs,
            // 播放中把「该位置对应的墙上时刻」交给岛，由岛自己插值，避免每秒重传整份 Bundle
            positionAtWallMs = if (p.playing) System.currentTimeMillis() else null,
            speed = 1f,
        )

        return ActivityBundle.encodeActivity(
            id = ACTIVITY_ID,
            kind = "MEDIA",
            compactLeading = leading,
            compactTrailing = trailing,
            expanded = expanded,
            hideWhenSourceForeground = true,
            contentDescription = listOfNotNull(
                displayTitle.takeIf { it.isNotBlank() },
                subtitle.takeIf { it.isNotBlank() },
            ).joinToString(" · ").take(256),
        )
    }

    /**
     * 解码并缩放封面。
     *
     * Hook 侧传的是 JPEG 压缩字节（不走 Parcelable Bitmap —— 广播有 ~1MB
     * TransactionTooLargeException 上限，而 512² 的 ARGB_8888 位图恰好就是 1MB）。
     * 这里解码后按需缩放：星河岛限制单边 ≤2048px 且 ≤8MB。
     */
    private fun decodeCover(bytes: ByteArray?): Bitmap? {
        if (bytes == null || bytes.isEmpty()) return null
        return runCatching {
            val source = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            val maxEdge = max(source.width, source.height)
            if (maxEdge <= 512) {
                source
            } else {
                val ratio = 512f / maxEdge
                val width = (source.width * ratio).toInt().coerceAtLeast(1)
                val height = (source.height * ratio).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(source, width, height, true)
            }
        }.getOrNull()
    }

    private fun formatDuration(ms: Long): String {
        if (ms <= 0) return "--:--"
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
        else "%02d:%02d".format(minutes, seconds)
    }

    /** 与星河岛协议一致的客户端侧结果码 */
    const val RESULT_OK = 0
    const val RESULT_NOT_CONNECTED = 9
}
