package io.github.piliplusprovider

import android.content.SharedPreferences
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MiuixTheme {
                SettingsScreen(service = service)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        App.addServiceStateListener(this, true)
    }

    override fun onStop() {
        App.removeServiceStateListener(this)
        super.onStop()
    }

    override fun onServiceStateChanged(service: XposedService?) {
        this.service = service
    }
}

@Composable
private fun SettingsScreen(service: XposedService?) {
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
                            UpdateChecker.openReleasePage(context, info.releaseUrl)
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
        }
    }
}

private fun SharedPreferences?.getBoolean(key: String, defaultValue: Boolean): Boolean =
    this?.getBoolean(key, defaultValue) ?: defaultValue
