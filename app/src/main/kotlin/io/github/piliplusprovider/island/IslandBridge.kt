package io.github.piliplusprovider.island

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import io.github.piliplusprovider.xposed.Constants
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.max

/**
 * Hook 侧的投送桥：把 PiliPlus 进程内拿到的视频信息，用**显式广播**发给模块进程。
 *
 * ## 为什么绕这一圈
 * 星河岛按「调用方 uid」核对来源包名，Hook 跑在 PiliPlus 进程（uid 属于 PiliPlus，
 * 其清单没有声明 `com.astraisland.permission.PUBLISH_ACTIVITY`），直接投送会被拒。
 * 模块进程的 uid 与自报包名一致，才能通过 `bind()`。
 *
 * ## 为什么用广播而不是 ContentProvider
 * Android 11+ 包可见性会过滤「访问其他 App 的 ContentProvider」，除非**调用方**
 * 声明 `<queries><provider authorities=...>`；调用方 PiliPlus 是第三方 App，清单
 * 不可改。显式广播由 system_server 解析组件投递，不受调用方包可见性限制。
 *
 * ## 线程模型
 * `sendVideo` / `sendClear` 可能被框架回调线程与主线程同时调用（setPlaybackState、
 * setMetadata、每秒位置同步），因此所有跨调用状态都用 @Volatile 保护，并容忍
 * 偶发重复投递——重复投递由接收端的签名去重兜住，不会放大成副作用。
 */
object IslandBridge {

    private const val TAG = "PiliPlusProvider"

    /** seek 判定阈值（毫秒）：实际位置与推算期望位置偏差超过它即认为用户拖动过进度 */
    private const val SEEK_THRESHOLD_MS = 5000L

    /** 日志回调，由 Hook 侧注入到模块日志 */
    @Volatile
    var logger: ((String) -> Unit)? = null

    private fun log(message: String) {
        runCatching { Log.i(TAG, "[island-bridge] $message") }
        // 日志绝不能反噬调用方：框架连接在热重载后可能已拆除，回调也可能抛异常
        runCatching { logger?.invoke(message) }
    }

    /** 上一次发送的内容签名，避免重复广播 */
    @Volatile
    private var lastSignature = ""

    /**
     * 上一次发送的播放位置与其对应的**单调**时刻，用于识别 seek。
     *
     * 用 `SystemClock.elapsedRealtime()` 而非 `currentTimeMillis()`：后者会被 NTP
     * 校时/用户改时间大幅跳变，导致 seek 误判或漏判。
     */
    @Volatile
    private var lastPositionMs = 0L
    @Volatile
    private var lastPositionAtElapsed = 0L

    /** 停止状态去重 */
    @Volatile
    private var lastCleared = false

    /**
     * 发送当前视频信息到模块进程。
     *
     * 去重说明：签名含 videoId/标题/UP主/时长/播放状态/封面有无，**不含播放位置**——
     * 这样每秒的进度回调不会反复重传整份 Bundle（岛会按 positionAtWallMs 自己插值）。
     * 但用户拖动进度条（seek）时位置会突变，此时必须强制重发，否则岛会继续按旧基准
     * 插值、进度显示错误。判据：实际位置与我们推算的期望位置相差超过阈值。
     *
     * @param hostContext 宿主（PiliPlus）Context，用于 sendBroadcast
     * @param coverBytes 已压缩的封面 JPEG 字节（由调用方在后台线程压缩好；
     *                   本函数不再做任何图像编解码，避免阻塞宿主主线程）
     */
    fun sendVideo(
        hostContext: Context,
        title: String,
        artist: String,
        durationMs: Long,
        videoId: String,
        playing: Boolean,
        positionMs: Long,
        coverBytes: ByteArray?,
    ) {
        val signature = "$videoId|$title|$artist|$durationMs|$playing|${if (coverBytes != null) "art" else "noart"}"

        val isSeek = playing && detectSeek(positionMs)

        if (signature == lastSignature && !isSeek) return
        lastSignature = signature
        lastCleared = false

        lastPositionMs = positionMs
        lastPositionAtElapsed = SystemClock.elapsedRealtime()

        if (isSeek) log("seek detected -> position=${positionMs}ms")

        try {
            val intent = Intent(Constants.BRIDGE_ACTION_UPDATE).apply {
                // 显式组件：不受调用方包可见性限制，无需 <queries>
                setClassName(Constants.PROVIDER_PACKAGE_NAME, Constants.BRIDGE_RECEIVER_CLASS)
                putExtra(Constants.BRIDGE_EXTRA_TOKEN, Constants.BRIDGE_TOKEN)
                putExtra(Constants.BRIDGE_EXTRA_TITLE, title)
                putExtra(Constants.BRIDGE_EXTRA_ARTIST, artist)
                putExtra(Constants.BRIDGE_EXTRA_DURATION, durationMs)
                putExtra(Constants.BRIDGE_EXTRA_VIDEO_ID, videoId)
                putExtra(Constants.BRIDGE_EXTRA_PLAYING, playing)
                putExtra(Constants.BRIDGE_EXTRA_POSITION, positionMs)
                if (coverBytes != null) putExtra(Constants.BRIDGE_EXTRA_COVER, coverBytes)
            }
            hostContext.sendBroadcast(intent)
            log("sent: $title / $artist / ${durationMs}ms / playing=$playing / cover=${coverBytes?.size ?: 0}B")
        } catch (t: Throwable) {
            log("send failed: ${t.message}")
        }
    }

    /**
     * 判断是否发生 seek：实际位置与我们推算的期望位置偏差超过 5 秒。
     *
     * 期望位置 = 上次发送的位置 + 距上次发送经过的单调时间（播放中才推进）。
     */
    private fun detectSeek(positionMs: Long): Boolean {
        val base = lastPositionAtElapsed
        if (base == 0L) return false
        val elapsed = (SystemClock.elapsedRealtime() - base).coerceAtLeast(0L)
        val expected = lastPositionMs + elapsed
        return abs(positionMs - expected) > SEEK_THRESHOLD_MS
    }

    /** 通知模块进程结束内容项（播放会话结束时调用） */
    fun sendClear(hostContext: Context) {
        if (lastCleared) return
        lastCleared = true
        lastSignature = ""
        try {
            val intent = Intent(Constants.BRIDGE_ACTION_CLEAR).apply {
                setClassName(Constants.PROVIDER_PACKAGE_NAME, Constants.BRIDGE_RECEIVER_CLASS)
                putExtra(Constants.BRIDGE_EXTRA_TOKEN, Constants.BRIDGE_TOKEN)
            }
            hostContext.sendBroadcast(intent)
            log("sent clear")
        } catch (t: Throwable) {
            log("clear failed: ${t.message}")
        }
    }

    /**
     * 压缩封面为 JPEG 字节。**可能耗时数十毫秒，必须由调用方放在后台线程执行**
     * （Hook 侧使用专用单线程 executor）。
     *
     * 广播的 Binder 事务上限约 1MB；512×512 的 ARGB_8888 位图正好 1MB，直接传
     * Parcelable 有 TransactionTooLargeException 风险。JPEG 压缩后通常几十 KB。
     * 同时这也满足星河岛的限制（单边 ≤2048px、≤8MB）。
     */
    fun compressCover(source: Bitmap): ByteArray? {
        if (source.isRecycled) return null
        return try {
            val maxEdge = max(source.width, source.height)
            val scaled = if (maxEdge <= Constants.COVER_MAX_EDGE) {
                source
            } else {
                val ratio = Constants.COVER_MAX_EDGE.toFloat() / maxEdge
                Bitmap.createScaledBitmap(
                    source,
                    (source.width * ratio).toInt().coerceAtLeast(1),
                    (source.height * ratio).toInt().coerceAtLeast(1),
                    true,
                )
            }
            ByteArrayOutputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, Constants.COVER_JPEG_QUALITY, out)
                out.toByteArray()
            }
        } catch (t: Throwable) {
            log("cover compress failed: ${t.message}")
            null
        }
    }
}
