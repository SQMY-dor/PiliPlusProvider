package io.github.piliplusprovider.island

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.piliplusprovider.xposed.Constants

/**
 * 跨进程桥接收端：接收 Hook 侧（运行在 PiliPlus 进程）发来的视频信息广播，
 * 交给 [IslandPublisher] 以**模块身份**投送到星河岛。
 *
 * ## 为什么由模块进程投送
 * 星河岛按「调用方 uid」核对来源包名（bind() 时比对，之后每次调用再核一次）。
 * Hook 跑在 PiliPlus 进程，uid 属于 PiliPlus，其清单未声明星河岛权限；模块进程
 * 的 uid 与自报包名一致，才能通过身份校验。
 *
 * ## 为什么用广播
 * ContentProvider 会被 Android 11+ 包可见性过滤（调用方 PiliPlus 无法声明
 * <queries>），而显式广播不受此限。
 */
class IslandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getStringExtra(Constants.BRIDGE_EXTRA_TOKEN) != Constants.BRIDGE_TOKEN) {
            Log.w(TAG, "[island] broadcast rejected: bad token")
            return
        }

        when (intent.action) {
            Constants.BRIDGE_ACTION_CLEAR -> {
                IslandPublisher.clear()
                return
            }
            Constants.BRIDGE_ACTION_UPDATE -> Unit
            else -> return
        }

        val title = intent.getStringExtra(Constants.BRIDGE_EXTRA_TITLE).orEmpty()
        val artist = intent.getStringExtra(Constants.BRIDGE_EXTRA_ARTIST).orEmpty()
        val videoId = intent.getStringExtra(Constants.BRIDGE_EXTRA_VIDEO_ID).orEmpty()

        // 仅丢弃「整条为空」的广播。用户在设置里关掉标题与 UP 主推送时两者都会是空串，
        // 此时岛上应回退成兜底文案（"未知视频"），而不是被整条丢掉、什么都不显示。
        if (title.isBlank() && artist.isBlank() && videoId.isBlank()) return

        // 模块进程可能刚被广播冷启动，此时与星河岛的连接尚未就绪
        // （connect() 有 3 秒超时）。IslandPublisher 会缓存本次内容，
        // 并在 onReadyChanged(true) 后补投。
        IslandPublisher.submit(
            title = title,
            artist = artist,
            durationMs = intent.getLongExtra(Constants.BRIDGE_EXTRA_DURATION, 0L),
            videoId = intent.getStringExtra(Constants.BRIDGE_EXTRA_VIDEO_ID).orEmpty(),
            playing = intent.getBooleanExtra(Constants.BRIDGE_EXTRA_PLAYING, false),
            positionMs = intent.getLongExtra(Constants.BRIDGE_EXTRA_POSITION, 0L),
            coverBytes = intent.getByteArrayExtra(Constants.BRIDGE_EXTRA_COVER),
        )
    }

    companion object {
        private const val TAG = "PiliPlusProvider"
    }
}
