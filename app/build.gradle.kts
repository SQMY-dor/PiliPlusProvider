plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose)
}

android {
    namespace = "io.github.piliplusprovider"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.piliplusprovider"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 从仓库外读取签名密钥（/root/android-keys/），不存在则跳过签名
            val keystoreFile = File("/root/android-keys/piliplus-release.keystore")
            val passFile = File("/root/android-keys/keystore-pass.txt")
            if (keystoreFile.exists() && passFile.exists()) {
                signingConfig = signingConfigs.create("release") {
                    storeFile = keystoreFile
                    storePassword = passFile.readText().trim()
                    keyAlias = "piliplus"
                    keyPassword = passFile.readText().trim()
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

    compileOnly(libs.libxposed.api)
    implementation(libs.libxposed.service)

    implementation(libs.androidx.core.ktx)

    // Compose + Miuix (HyperOS 风格设置界面)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.miuix)
}
