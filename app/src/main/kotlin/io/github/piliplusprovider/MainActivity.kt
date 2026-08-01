package io.github.piliplusprovider

import android.content.Context
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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
 * 设置写入默认 SharedPreferences（文件名 `${applicationId}_preferences`），
 * 与 Manifest 中 xposedsharedprefs=true 的配置一致，Hook 端可跨进程读取。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MiuixTheme {
                SettingsScreen()
            }
        }
    }
}

@Composable
private fun SettingsScreen() {
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences("${BuildConfig.APPLICATION_ID}_preferences", Context.MODE_PRIVATE)
    }
    var pushTitle by remember { mutableStateOf(prefs.getBoolean(Constants.KEY_PUSH_TITLE, true)) }
    var pushArtist by remember { mutableStateOf(prefs.getBoolean(Constants.KEY_PUSH_ARTIST, true)) }
    var pushDuration by remember { mutableStateOf(prefs.getBoolean(Constants.KEY_PUSH_DURATION, true)) }
    var showElapsedTime by remember { mutableStateOf(prefs.getBoolean(Constants.KEY_SHOW_ELAPSED_TIME, false)) }

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
                            prefs.edit().putBoolean(Constants.KEY_PUSH_TITLE, it).apply()
                        }
                    )
                    SuperSwitch(
                        title = "推送UP主",
                        summary = "向外部软件推送当前UP主名字",
                        checked = pushArtist,
                        onCheckedChange = {
                            pushArtist = it
                            prefs.edit().putBoolean(Constants.KEY_PUSH_ARTIST, it).apply()
                        }
                    )
                    SuperSwitch(
                        title = "推送时长",
                        summary = "向外部软件推送视频总时长",
                        checked = pushDuration,
                        onCheckedChange = {
                            pushDuration = it
                            prefs.edit().putBoolean(Constants.KEY_PUSH_DURATION, it).apply()
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
                            prefs.edit().putBoolean(Constants.KEY_SHOW_ELAPSED_TIME, it).apply()
                        }
                    )
                }
            }
        }
    }
}
