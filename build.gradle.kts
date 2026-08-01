plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.compose) apply false
}

// ===== 版本号递增任务：./gradlew bumpVersion（或 gradle bumpVersion） =====
// 每次发布新版本时执行：versionCode +1，并可选指定新的 versionName。
// 用法：
//   gradle bumpVersion                    # 仅 versionCode +1
//   gradle bumpVersion -PnewVersion=1.2.0 # versionCode +1 且 versionName 设为 1.2.0
tasks.register("bumpVersion") {
    group = "versioning"
    description = "递增 version.properties 中的 versionCode（可选 -PnewVersion=1.2.0 设置新 versionName）"
    doLast {
        val file = rootProject.file("version.properties")
        val props = java.util.Properties().apply {
            if (file.exists()) file.inputStream().use { load(it) }
        }
        val oldCode = (props.getProperty("versionCode") ?: "1").toInt()
        val newCode = oldCode + 1
        props.setProperty("versionCode", newCode.toString())
        project.findProperty("newVersion")?.let { props.setProperty("versionName", it.toString()) }
        file.outputStream().use { props.store(it, "Version info - bumped by bumpVersion task") }
        println("versionCode: $oldCode -> $newCode  |  versionName: ${props.getProperty("versionName")}")
    }
}
