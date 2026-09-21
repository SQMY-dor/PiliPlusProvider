plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose)
}

import java.util.Properties

// ===== 版本号：从 version.properties 读取（发布用 bumpVersion 任务递增） =====
val versionPropsFile = rootProject.file("version.properties")
val versionProps = Properties().apply {
    if (versionPropsFile.exists()) versionPropsFile.inputStream().use { load(it) }
}
val appVersionCode: Int = (versionProps.getProperty("versionCode") ?: "1").toInt()
val appVersionName: String = versionProps.getProperty("versionName") ?: "1.0.0"

// ===== Release 签名解析（优先级：环境变量 > 本机正式密钥；均缺失则 unsigned） =====
//
// 环境变量由 GitHub Actions 注入（仓库 Settings → Secrets and variables → Actions）：
//   KEYSTORE_FILE        解码后的 keystore 临时文件路径（workflow 内部处理）
//   KEYSTORE_PASSWORD    keystore 口令
//   KEY_ALIAS            密钥别名
//   KEY_PASSWORD         密钥口令
// 本机正式密钥：/root/android-keys/（本地发布用，不入库）。
// 任一来源缺失时 release 构建为 unsigned——CI 的 tag 发布流程会在前置步骤
// 显式校验 Secrets 齐备，避免发出未签名/错签名包。
data class SigningChoice(
    val storeFile: File,
    val storePassword: String,
    val keyAlias: String,
    val keyPassword: String,
)

fun resolveReleaseSigning(): SigningChoice? {
    val envStore = System.getenv("KEYSTORE_FILE")
    if (envStore != null && File(envStore).exists()) {
        val pass = System.getenv("KEYSTORE_PASSWORD") ?: ""
        logger.lifecycle("release signing: keystore from environment")
        return SigningChoice(
            storeFile = File(envStore),
            storePassword = pass,
            keyAlias = System.getenv("KEY_ALIAS") ?: "",
            keyPassword = System.getenv("KEY_PASSWORD") ?: pass,
        )
    }
    val localKey = File("/root/android-keys/piliplus-release.keystore")
    val localPass = File("/root/android-keys/keystore-pass.txt")
    if (localKey.exists() && localPass.exists()) {
        val pass = localPass.readText().trim()
        logger.lifecycle("release signing: local production keystore")
        return SigningChoice(localKey, pass, "piliplus", pass)
    }
    logger.lifecycle("release signing: no keystore available, release will be unsigned")
    return null
}

android {
    namespace = "io.github.piliplusprovider"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.piliplusprovider"
        minSdk = 28
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            resolveReleaseSigning()?.let { choice ->
                signingConfig = signingConfigs.create("release") {
                    storeFile = choice.storeFile
                    storePassword = choice.storePassword
                    keyAlias = choice.keyAlias
                    keyPassword = choice.keyPassword
                }
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    packaging {
        resources {
            merges += "META-INF/xposed/*"
            excludes += "**"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    implementation(libs.lyricon.provider)

    // 星河岛（AstraIsland）接入库 —— 本地 AAR，SHA256 见 README
    implementation(files("libs/astraisland-client.aar"))

    compileOnly(libs.libxposed.api)
    implementation(libs.libxposed.service)

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    // Compose + Miuix (HyperOS 风格设置界面)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.miuix)
}
