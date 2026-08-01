package io.github.piliplusprovider

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.libxposed.service.HotReloadResult
import io.github.libxposed.service.HookedTarget
import io.github.libxposed.service.XposedService
import io.github.piliplusprovider.xposed.Constants
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.extra.SuperSwitch
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 设置界面（Miuix / HyperOS 风格）
 *
 * 设置通过 XposedService 读写 RemotePreferences（文件名 "settings"），
 * Hook 侧在宿主进程内通过 getRemotePreferences("settings") 读取同一份数据。
 * 未绑定框架（service == null）时界面显示默认值，绑定后自动刷新。
 */
class MainActivity : ComponentActivity(), App.ServiceStateListener {

    private var service by mutableStateOf<XposedService?>(null)

    /** 当前进行中的更新下载任务 ID（用于匹配下载完成广播） */
    private var pendingDownloadId = -1L

    /** 更新包下载完成广播：拉起安装器 */
    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (id != pendingDownloadId) return
            pendingDownloadId = -1L
            installDownloadedApk(context, id)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MiuixTheme {
                SettingsScreen(
                    service = service,
                    onStartDownload = ::startUpdateDownload,
                    onHotReload = ::hotReloadPiliPlus,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        App.addServiceStateListener(this, true)
        // Android 13+ 动态注册广播必须指定 RECEIVER_EXPORTED / RECEIVER_NOT_EXPORTED，
        // 否则抛 SecurityException 导致闪退。DownloadManager 广播来自系统进程，NOT_EXPORTED 可正常接收。
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(downloadReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(downloadReceiver, filter)
        }
    }

    override fun onStop() {
        runCatching { unregisterReceiver(downloadReceiver) }
        App.removeServiceStateListener(this)
        super.onStop()
    }

    override fun onServiceStateChanged(service: XposedService?) {
        this.service = service
    }

    /** 后台下载更新包，完成后自动拉起安装器 */
    private fun startUpdateDownload(url: String, version: String) {
        val id = UpdateChecker.startDownload(this, url, version)
        if (id != -1L) {
            pendingDownloadId = id
            Toast.makeText(this, "开始后台下载更新…", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "下载启动失败，请手动前往 Releases 下载", Toast.LENGTH_LONG).show()
            UpdateChecker.openReleasePage(this)
        }
    }

    /** 热重启 PiliPlus（通过 XposedService.hotReloadModule 触发热重载） */
    private fun hotReloadPiliPlus() {
        val svc = service
        if (svc == null) {
            Toast.makeText(this, "未连接到框架服务，无法热重启", Toast.LENGTH_LONG).show()
            return
        }
        val targets = svc.runningTargets.filter { it.processName in Constants.TARGET_PACKAGES }
        if (targets.isEmpty()) {
            Toast.makeText(this, "PiliPlus 未在运行，无需热重启", Toast.LENGTH_LONG).show()
            return
        }
        var pending = targets.size
        var succeeded = 0
        var failed = 0
        for (target in targets) {
            svc.hotReloadModule(target, null) { _: HookedTarget, result: HotReloadResult ->
                runOnUiThread {
                    if (result.status == HotReloadResult.Status.SUCCEEDED) succeeded++ else failed++
                    pending--
                    if (pending == 0) {
                        val msg = if (failed == 0) {
                            "热重启成功（$succeeded 个进程）"
                        } else {
                            "热重启完成：成功 $succeeded，失败 $failed"
                        }
                        Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        Toast.makeText(this, "正在热重启 PiliPlus…", Toast.LENGTH_SHORT).show()
    }

    /** 下载完成后拉起系统安装器 */
    private fun installDownloadedApk(context: Context, downloadId: Long) {
        try {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val query = DownloadManager.Query().setFilterById(downloadId)
            val cursor = dm.query(query)
            cursor.use {
                if (!it.moveToFirst()) return
                val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                if (status != DownloadManager.STATUS_SUCCESSFUL) {
                    Toast.makeText(context, "更新包下载失败（状态 $status）", Toast.LENGTH_LONG).show()
                    return
                }
            }
            val uri: Uri? = dm.getUriForDownloadedFile(downloadId)
            if (uri == null) {
                Toast.makeText(context, "更新包文件不存在", Toast.LENGTH_LONG).show()
                return
            }
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(installIntent)
            Toast.makeText(context, "下载完成，正在拉起安装…", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "拉起安装失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}

@Composable
private fun SettingsScreen(
    service: XposedService?,
    onStartDownload: (url: String, version: String) -> Unit,
    onHotReload: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var checkingUpdate by remember { mutableStateOf(false) }
    var updateInfo by remember { mutableStateOf<UpdateChecker.UpdateInfo?>(null) }
    val prefs = remember(service) {
        runCatching { service?.getRemotePreferences(Constants.PREFS_NAME) }.getOrNull()
    }
    var pushTitle by remember(service) { mutableStateOf(prefs.getBoolean(Constants.KEY_PUSH_TITLE, true)) }
    var pushArtist by remember(service) { mutableStateOf(prefs.getBoolean(Constants.KEY_PUSH_ARTIST, true)) }
    var pushDuration by remember(service) { mutableStateOf(prefs.getBoolean(Constants.KEY_PUSH_DURATION, true)) }
    var showElapsedTime by remember(service) {
        mutableStateOf(prefs.getBoolean(Constants.KEY_SHOW_ELAPSED_TIME, false))
    }

    fun writeBoolean(key: String, value: Boolean) {
        runCatching {
            prefs?.edit()?.putBoolean(key, value)?.apply()
        }
    }

    /** 检查更新：无更新 Toast 提示，有更新弹 Miuix SuperDialog */
    fun onCheckUpdate() {
        if (checkingUpdate) return
        checkingUpdate = true
        scope.launch {
            val update = UpdateChecker.checkForUpdate()
            checkingUpdate = false
            if (update == null) {
                Toast.makeText(context, "当前已是最新版本（v${BuildConfig.VERSION_NAME}）", Toast.LENGTH_SHORT).show()
            } else {
                updateInfo = update
            }
        }
    }

    // 有更新时显示 Miuix 风格对话框
    updateInfo?.let { info ->
        SuperDialog(
            show = true,
            title = "发现新版本 v${info.latestVersion}",
            summary = info.body.ifBlank { "请前往 GitHub Releases 下载更新" }.take(500),
            onDismissRequest = { updateInfo = null },
            content = {
                Row(horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(
                        text = "取消",
                        onClick = { updateInfo = null },
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(20.dp))
                    TextButton(
                        text = "去更新",
                        onClick = {
                            updateInfo = null
                            onStartDownload(info.apkUrl, info.latestVersion)
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            },
        )
    }

    Scaffold(
        topBar = { TopAppBar(title = "PiliPlus Provider") }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            item {
                SmallTitle(
                    text = "推送内容",
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
            item {
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    SuperSwitch(
                        title = "推送标题",
                        summary = "向外部软件推送当前视频标题",
                        checked = pushTitle,
                        onCheckedChange = {
                            pushTitle = it
                            writeBoolean(Constants.KEY_PUSH_TITLE, it)
                        }
                    )
                    SuperSwitch(
                        title = "推送UP主",
                        summary = "向外部软件推送当前UP主名字",
                        checked = pushArtist,
                        onCheckedChange = {
                            pushArtist = it
                            writeBoolean(Constants.KEY_PUSH_ARTIST, it)
                        }
                    )
                    SuperSwitch(
                        title = "推送时长",
                        summary = "向外部软件推送视频总时长",
                        checked = pushDuration,
                        onCheckedChange = {
                            pushDuration = it
                            writeBoolean(Constants.KEY_PUSH_DURATION, it)
                        }
                    )
                }
            }
            item {
                SmallTitle(
                    text = "播放进度",
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
            item {
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    SuperSwitch(
                        title = "显示已播放时间",
                        summary = "同步当前播放位置给外部软件，按 mm:ss 显示进度",
                        checked = showElapsedTime,
                        onCheckedChange = {
                            showElapsedTime = it
                            writeBoolean(Constants.KEY_SHOW_ELAPSED_TIME, it)
                        }
                    )
                }
            }
            item {
                SmallTitle(
                    text = "关于",
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
            item {
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onCheckUpdate() }
                            .padding(16.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        BasicText(
                            text = if (checkingUpdate) "正在检查更新…" else "检查更新（当前 v${BuildConfig.VERSION_NAME}）",
                        )
                    }
                }
            }
            item {
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onHotReload() }
                            .padding(16.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        BasicText(
                            text = if (service == null) "热重启 PiliPlus（未连接框架）" else "热重启 PiliPlus",
                        )
                    }
                }
            }
        }
    }
}

private fun SharedPreferences?.getBoolean(key: String, defaultValue: Boolean): Boolean =
    this?.getBoolean(key, defaultValue) ?: defaultValue
