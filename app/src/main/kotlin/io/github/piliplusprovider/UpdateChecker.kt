package io.github.piliplusprovider

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 版本更新检查
 *
 * 通过 GitHub Releases API 获取最新版本，与本地 BuildConfig.VERSION_NAME 比较，
 * 有新版时提示用户下载。
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

    /** 打开浏览器跳转 Release 页 */
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
