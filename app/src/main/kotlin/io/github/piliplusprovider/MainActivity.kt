package io.github.piliplusprovider

import android.content.SharedPreferences
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.libxposed.service.XposedService
import io.github.piliplusprovider.xposed.Constants
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TopAppBar
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
        }
    }
}

private fun SharedPreferences?.getBoolean(key: String, defaultValue: Boolean): Boolean =
    this?.getBoolean(key, defaultValue) ?: defaultValue
