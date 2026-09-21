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

    /** 仓库主页 */
    const val REPO_URL = "https://github.com/$REPO"

    /** 最新 Release 页面（无 APK 资源或 API 地址时的兜底） */
    const val LATEST_RELEASE_URL = "$REPO_URL/releases/latest"

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

    /** 明确区分“没有更新”和“检查失败”，避免网络失败时误报已是最新版。 */
    sealed interface CheckResult {
        data object UpToDate : CheckResult
        data class UpdateAvailable(val info: UpdateInfo) : CheckResult
        data class Failed(val message: String) : CheckResult
    }

    /**
     * 检查是否有新版本（网络操作，需在 IO 线程调用）
     */
    suspend fun checkForUpdate(): CheckResult = withContext(Dispatchers.IO) {
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
                    return@withContext CheckResult.Failed(
                        "检查更新失败（GitHub HTTP ${conn.responseCode}），请稍后重试"
                    )
                }

                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                val tagName = json.optString("tag_name", "")
                if (tagName.isBlank()) {
                    Log.w(TAG, "GitHub release response has no tag_name")
                    return@withContext CheckResult.Failed("检查更新失败：GitHub 未返回有效版本号")
                }
                val latestVersion = tagName.removePrefix("v")
                // 版本比较：仅当远端版本 > 本地版本才提示
                if (!isNewer(latestVersion, BuildConfig.VERSION_NAME)) {
                    Log.d(TAG, "Already latest: local=${BuildConfig.VERSION_NAME}, remote=$latestVersion")
                    return@withContext CheckResult.UpToDate
                }
                CheckResult.UpdateAvailable(
                    UpdateInfo(
                        latestVersion = latestVersion,
                        tagName = tagName,
                        releaseUrl = json.optString("html_url", LATEST_RELEASE_URL),
                        apkUrl = findApkUrl(json),
                        body = json.optString("body", ""),
                        publishedAt = json.optString("published_at", ""),
                    )
                )
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Update check failed: ${e.message}")
            CheckResult.Failed("检查更新失败，请检查网络后重试")
        }
    }

    /**
     * 从 Release 的 assets 中挑选 APK 直链：
     * 优先找 release 版（名称含 "-release.apk"），没有则回退任意 .apk
     */
    private fun findApkUrl(json: JSONObject): String {
        val assets = json.optJSONArray("assets") ?: return LATEST_RELEASE_URL
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
        return fallback.ifBlank { LATEST_RELEASE_URL }
    }

    /**
     * 语义化版本比较：remote > local 返回 true
     *
     * 规则：
     * - 逐段数值比较，段数不同时短的一方按 0 补齐（修复 1.2.0.1 vs 1.2.0 被判为「无更新」）
     * - 数值完全相同时，正式版 > 预发布版（修复 1.2.0-beta 被判为比 1.2.0 新）
     * - 无法解析（非 `数字[.数字...][-预发布]` 形态）时回退字符串比较
     */
    private fun isNewer(remote: String, local: String): Boolean {
        val r = parseVersion(remote)
        val l = parseVersion(local)
        if (r == null || l == null) return remote.compareTo(local) > 0

        val segments = maxOf(r.numeric.size, l.numeric.size)
        for (i in 0 until segments) {
            val a = r.numeric.getOrElse(i) { 0 }
            val b = l.numeric.getOrElse(i) { 0 }
            if (a != b) return a > b
        }

        val rPre = r.preRelease
        val lPre = l.preRelease
        return when {
            rPre == null && lPre == null -> false
            rPre == null -> true          // 远端正式版 > 本地预发布版
            lPre == null -> false         // 远端预发布版 < 本地正式版
            else -> rPre > lPre           // 同为预发布：按标识符字典序
        }
    }

    /** 解析结果：数值段 + 可选的预发布标识符（`-` 或 `+` 之后的部分） */
    private data class Version(val numeric: List<Int>, val preRelease: String?)

    private fun parseVersion(v: String): Version? {
        val clean = v.trim().removePrefix("v").removePrefix("V")
        if (clean.isEmpty()) return null
        val cut = clean.indexOfFirst { it == '-' || it == '+' }
        val core = if (cut >= 0) clean.substring(0, cut) else clean
        val pre = if (cut >= 0) clean.substring(cut + 1).takeIf { it.isNotEmpty() } else null
        val parts = core.split(".")
        val numeric = parts.map { it.toIntOrNull() ?: return null }
        if (numeric.isEmpty()) return null
        return Version(numeric, pre)
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

    /** 打开仓库主页。 */
    fun openRepositoryPage(context: Context): Boolean = openWebPage(context, REPO_URL)

    /** 打开浏览器跳转 Release 页（兜底）。 */
    fun openReleasePage(context: Context, url: String = LATEST_RELEASE_URL): Boolean =
        openWebPage(context, url)

    private fun openWebPage(context: Context, url: String): Boolean {
        val uri = Uri.parse(url)
        if (uri.scheme != "https") {
            Log.e(TAG, "Refusing to open non-HTTPS URL: $url")
            return false
        }
        return try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "openWebPage failed: ${e.message}")
            false
        }
    }
}
