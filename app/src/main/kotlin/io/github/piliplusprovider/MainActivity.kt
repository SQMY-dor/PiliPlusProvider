package io.github.piliplusprovider

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.libxposed.service.HotReloadResult
import io.github.libxposed.service.HookedTarget
import io.github.libxposed.service.XposedService
import io.github.piliplusprovider.xposed.Constants
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.extra.SuperArrow
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
        // 所有系统版本统一声明 NOT_EXPORTED，避免动态广播接收器被其他应用调用。
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        ContextCompat.registerReceiver(
            this,
            downloadReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStop() {
        runCatching { unregisterReceiver(downloadReceiver) }
        App.removeServiceStateListener(this)
        super.onStop()
    }

    override fun onServiceStateChanged(service: XposedService?) {
        runOnUiThread {
            this.service = service
        }
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

    /** 热重载模块到 PiliPlus（通过 XposedService.hotReloadModule 触发） */
    private fun hotReloadPiliPlus(onMessage: (String) -> Unit) {
        val svc = service
        if (svc == null) {
            onMessage("未连接到框架服务，无法热重载")
            return
        }

        if (svc.apiVersion < XposedService.API_102) {
            onMessage("当前框架不支持热重载（需要 libxposed API 102）")
            return
        }

        val targets = try {
            svc.runningTargets.filter { target ->
                target.processName.substringBefore(':') in Constants.TARGET_PACKAGES
            }
        } catch (e: Exception) {
            onMessage("读取 PiliPlus 运行状态失败：${e.message ?: e.javaClass.simpleName}")
            return
        }
        if (targets.isEmpty()) {
            onMessage("未找到已注入的 PiliPlus 进程，请确认应用正在运行且已加入模块作用域")
            return
        }

        onMessage("正在热重载 PiliPlus…")
        var pending = targets.size
        var succeeded = 0
        var failed = 0
        val failureDetails = mutableListOf<String>()

        fun finishTarget(success: Boolean, failureDetail: String? = null) {
            if (success) {
                succeeded++
            } else {
                failed++
                failureDetail?.let(failureDetails::add)
            }
            pending--
            if (pending != 0) return

            val message = if (failed == 0) {
                "热重载成功（$succeeded 个进程）"
            } else {
                buildString {
                    append("热重载完成：成功 $succeeded，失败 $failed")
                    failureDetails.firstOrNull()?.let { append("；$it") }
                }
            }
            onMessage(message)
        }

        for (target in targets) {
            try {
                svc.hotReloadModule(target, null) { callbackTarget: HookedTarget, result: HotReloadResult ->
                    runOnUiThread {
                        val success = result.status == HotReloadResult.Status.SUCCEEDED
                        val detail = if (success) {
                            null
                        } else {
                            buildString {
                                append(callbackTarget.processName)
                                append("：")
                                append(hotReloadStatusLabel(result.status))
                                result.message?.takeIf { it.isNotBlank() }?.let {
                                    append("（$it）")
                                }
                            }
                        }
                        finishTarget(success, detail)
                    }
                }
            } catch (e: Exception) {
                finishTarget(
                    success = false,
                    failureDetail = "${target.processName}：${e.message ?: e.javaClass.simpleName}",
                )
            }
        }
    }

    private fun hotReloadStatusLabel(status: HotReloadResult.Status): String = when (status) {
        HotReloadResult.Status.SUCCEEDED -> "成功"
        HotReloadResult.Status.FAILED -> "框架执行失败"
        HotReloadResult.Status.UNSUPPORTED -> "框架不支持"
        HotReloadResult.Status.IN_PROGRESS -> "已有热重载正在进行"
        HotReloadResult.Status.PROCESS_DIED -> "目标进程已退出"
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
    onHotReload: ((String) -> Unit) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var showAbout by rememberSaveable { mutableStateOf(false) }
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

    fun showMessage(message: String) {
        scope.launch {
            snackbarHostState.showSnackbar(message)
        }
    }

    /** 检查更新：结果通过页面内 Snackbar / SuperDialog 反馈 */
    fun onCheckUpdate() {
        if (checkingUpdate) return
        checkingUpdate = true
        scope.launch {
            val result = UpdateChecker.checkForUpdate()
            checkingUpdate = false
            when (result) {
                UpdateChecker.CheckResult.UpToDate -> {
                    showMessage("当前已是最新版本（v${BuildConfig.VERSION_NAME}）")
                }
                is UpdateChecker.CheckResult.UpdateAvailable -> {
                    updateInfo = result.info
                }
                is UpdateChecker.CheckResult.Failed -> {
                    showMessage(result.message)
                }
            }
        }
    }

    BackHandler(enabled = showAbout) {
        showAbout = false
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
        topBar = {
            if (showAbout) {
                TopAppBar(
                    title = "关于",
                    navigationIcon = {
                        TextButton(
                            text = "返回",
                            onClick = { showAbout = false },
                        )
                    },
                )
            } else {
                TopAppBar(title = "PiliPlus Provider")
            }
        },
        snackbarHost = { SnackbarHost(state = snackbarHostState) },
    ) { innerPadding ->
        if (showAbout) {
            AboutContent(
                checkingUpdate = checkingUpdate,
                onCheckUpdate = ::onCheckUpdate,
                onOpenRepository = {
                    if (!UpdateChecker.openRepositoryPage(context)) {
                        showMessage("未找到可打开 GitHub 的应用")
                    }
                },
                modifier = Modifier.padding(innerPadding),
            )
            return@Scaffold
        }

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
                    text = "模块",
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
            item {
                Card(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    onClick = { onHotReload(::showMessage) },
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        BasicText(
                            text = if (service == null) "热重载 PiliPlus（未连接框架）" else "热重载 PiliPlus",
                        )
                    }
                }
            }
            item {
                SmallTitle(
                    text = "其他",
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
            item {
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    SuperArrow(
                        title = "关于",
                        summary = "版本信息、检查更新与 GitHub 源码",
                        onClick = { showAbout = true },
                    )
                }
            }
        }
    }
}

@Composable
private fun AboutContent(
    checkingUpdate: Boolean,
    onCheckUpdate: () -> Unit,
    onOpenRepository: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
    ) {
        item {
            SmallTitle(
                text = "应用信息",
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        item {
            Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    BasicText(
                        text = "PiliPlus Provider\n当前版本 v${BuildConfig.VERSION_NAME}",
                    )
                }
            }
        }
        item {
            SmallTitle(
                text = "项目",
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        item {
            Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                SuperArrow(
                    title = if (checkingUpdate) "正在检查更新…" else "检查更新",
                    summary = "从 GitHub Releases 获取最新版本",
                    onClick = onCheckUpdate,
                    enabled = !checkingUpdate,
                )
                SuperArrow(
                    title = "GitHub 源码",
                    summary = UpdateChecker.REPO,
                    onClick = onOpenRepository,
                )
            }
        }
    }
}

private fun SharedPreferences?.getBoolean(key: String, defaultValue: Boolean): Boolean =
    this?.getBoolean(key, defaultValue) ?: defaultValue
