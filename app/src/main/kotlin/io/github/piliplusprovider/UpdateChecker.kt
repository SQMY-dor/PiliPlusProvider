package io.github.piliplusprovider

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 版本更新检查与下载
 *
 * - 通过 GitHub Releases API 获取最新版本，与本地 BuildConfig.VERSION_NAME 比较
 * - 有新版时提供 APK 下载（系统 DownloadManager 后台下载，完成后通知栏可见）
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"

    /** 仓库 owner/name（GitHub Releases API 用） */
    const val REPO = "SQMY-dor/PiliPlusProvider"

    /** 仓库主页（无 API 时的兜底跳转地址） */
    const val REPO_URL = "https://github.com/SQMY-dor/PiliPlusProvider/releases/latest"

    private const val API_URL = "https://api.github.com/repos/$REPO/releases/latest"

    /** 检查结果 */
    data class UpdateInfo(
        val latestVersion: String,       // 例如 1.2.0
        val tagName: String,             // 例如 v1.2.0
        val releaseUrl: String,          // 发布页 URL
        val apkUrl: String,              // APK 直链（优先 release 版）
        val body: String,                // 更新说明
        val publishedAt: String,         // 发布时间
    )

    /**
     * 检查是否有新版本（网络操作，需在 IO 线程调用）
     *
     * @return 有新版时返回 UpdateInfo，无新版/失败返回 null
     */
    suspend fun checkForUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val conn = URL(API_URL).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "GET"
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                conn.setRequestProperty("User-Agent", "PiliPlusProvider")

                if (conn.responseCode != 200) {
                    Log.w(TAG, "GitHub API HTTP ${conn.responseCode}")
                    return@withContext null
                }

                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                val tagName = json.optString("tag_name", "")
                val latestVersion = tagName.removePrefix("v")
                // 版本比较：仅当远端版本 > 本地版本才提示
                if (!isNewer(latestVersion, BuildConfig.VERSION_NAME)) {
                    Log.d(TAG, "Already latest: local=${BuildConfig.VERSION_NAME}, remote=$latestVersion")
                    return@withContext null
                }
                UpdateInfo(
                    latestVersion = latestVersion,
                    tagName = tagName,
                    releaseUrl = json.optString("html_url", REPO_URL),
                    apkUrl = findApkUrl(json),
                    body = json.optString("body", ""),
                    publishedAt = json.optString("published_at", ""),
                )
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Update check failed: ${e.message}")
            null
        }
    }

    /**
     * 从 Release 的 assets 中挑选 APK 直链：
     * 优先找 release 版（名称含 "-release.apk"），没有则回退任意 .apk
     */
    private fun findApkUrl(json: JSONObject): String {
        val assets = json.optJSONArray("assets") ?: return REPO_URL
        var fallback = ""
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name", "")
            if (!name.endsWith(".apk")) continue
            val url = asset.optString("browser_download_url", "")
            if (url.isBlank()) continue
            if (name.contains("-release.apk") || name.contains("release")) return url
            if (fallback.isBlank()) fallback = url
        }
        return fallback.ifBlank { REPO_URL }
    }

    /**
     * 语义化版本比较：remote > local 返回 true
     * 支持 1.2.3 或 v1.2.3 格式；无法解析时按字符串比较
     */
    private fun isNewer(remote: String, local: String): Boolean {
        val r = parseVersion(remote)
        val l = parseVersion(local)
        if (r == null || l == null) return remote.compareTo(local) > 0
        return when {
            r[0] != l[0] -> r[0] > l[0]
            r.size > 1 && l.size > 1 && r[1] != l[1] -> r[1] > l[1]
            r.size > 2 && l.size > 2 && r[2] != l[2] -> r[2] > l[2]
            else -> false
        }
    }

    private fun parseVersion(v: String): List<Int>? {
        val clean = v.trim().removePrefix("v")
        val parts = clean.split(".")
        return parts.mapNotNull { it.toIntOrNull() }.takeIf { it.isNotEmpty() && it.size == parts.size }
    }

    /**
     * 使用系统 DownloadManager 后台下载 APK 到公共 Downloads 目录
     *
     * @return 下载任务 ID（用于监听 DownloadManager.ACTION_DOWNLOAD_COMPLETE），失败返回 -1
     */
    fun startDownload(context: Context, url: String, version: String): Long {
        return try {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val fileName = "PiliPlusProvider-v$version.apk"
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setTitle("PiliPlusProvider v$version")
                setDescription("正在后台下载更新包…")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                setMimeType("application/vnd.android.package-archive")
                setAllowedOverMetered(true)
            }
            dm.enqueue(request)
        } catch (e: Exception) {
            Log.e(TAG, "startDownload failed: ${e.message}")
            -1L
        }
    }

    /** 打开浏览器跳转 Release 页（兜底） */
    fun openReleasePage(context: Context, url: String = REPO_URL) {
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            Log.e(TAG, "openReleasePage failed: ${e.message}")
        }
    }
}
