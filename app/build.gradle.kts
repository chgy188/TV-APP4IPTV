import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
}

// 从项目根目录读取 keystore.properties（不存在时跳过，仍能构建 unsigned release）
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

// 自动版本号：本地用固定版本；CI 打 tag 时通过 APP_VERSION_NAME 覆盖（如 v1.2.3）
val appVersionName: String = System.getenv("APP_VERSION_NAME") ?: "1.0.0"
// 注：已不按 ABI 分包，统一产出单一 APK（详见下方 splits 说明）

// 强制锁定 media3 到 1.6.1：项目内某依赖（androidx.tv:tv-material 一系）会经版本一致性解析
// 把 media3 全家族拉高到 1.7.1，而 1.7.1 要求 compileSdk 35。1.6.1 同样要求 compileSdk 35，
// 故上方已同步将 app compileSdk 升到 35；其 minSdk=21 仍兼容 Android 5 老设备（极米），
// 且与 Jellyfin ffmpeg 解码器（1.6.1+2）编译版本严格对齐。
configurations.all {
    resolutionStrategy {
        force(
            "androidx.media3:media3-exoplayer:1.6.1",
            "androidx.media3:media3-exoplayer-hls:1.6.1",
            "androidx.media3:media3-ui:1.6.1",
            "androidx.media3:media3-common:1.6.1",
            "androidx.media3:media3-extractor:1.6.1",
            "androidx.media3:media3-datasource-okhttp:1.6.1",
            "androidx.media3:media3-decoder:1.6.1",
        )
    }
}

android {
    namespace = "com.example.composedtv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.composedtv"
        minSdk = 21
        targetSdk = 34
        // 语义化版本：versionCode = 主版本*10000 + 次版本*100 + 补丁版本
        // 每次发布时手动递增 versionName 末尾（或 CI 通过 APP_VERSION_NAME 注入 tag 版本）
        versionName = appVersionName
        versionCode = versionName!!.split(".")
            .let { parts ->
                val major = parts.getOrNull(0)?.toIntOrNull() ?: 0
                val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
                val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
                // 兜底：确保 versionCode 为正整数，避免 CI 异常版本号导致构建失败
                (major * 10000 + minor * 100 + patch).coerceAtLeast(1)
            }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            if (keystoreProperties.containsKey("storeFile")) {
                // 用 rootProject.file 以项目根目录为基准解析 storeFile，
                // 保证 CI（keystore 解码到根目录 app/ 下）与本地路径一致
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    // 统一产出单一 APK，不做 ABI 分包：
    // 本项目不含 native .so（media3 / OkHttp / Compose 均为纯 Kotlin / Java，
    // 解码由系统 MediaCodec 提供），按 ABI 拆分既不节省体积，
    // 又会让 Release 出现多个 APK、导致用户装错包。
    // 若将来引入 native 库（如 media3 的 FFmpeg 解码扩展），可重新启用下面的 abi split。
    splits {
        abi {
            isEnable = false
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        // 开启 Java 8 语言特性脱糖，使 Map.putIfAbsent 等 API 在 minSdk<24 上可用
        isCoreLibraryDesugaringEnabled = true
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Kotlin 2.0+ 使用 org.jetbrains.kotlin.plugin.compose 插件提供 Compose 编译器，
    // 无需再通过 composeOptions.kotlinCompilerExtensionVersion 指定版本。

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // NewApi：把"调用高于 minSdk 的平台 API"从警告提升为 error。
    // 例：View.getRootWindowInsets()(API23) 在 minSdk=21 的极米上运行时 NoSuchMethodError 闪退，
    // 设为 error 后打包期即可拦截，避免再出现"高 compileSdk 编译通过、老设备才崩"的问题。
    lint {
        error += "NewApi"
    }
}

dependencies {
    // AndroidX Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.activity:activity-compose:1.8.2")

    // Compose BOM（2024.02 对应 Compose 1.6，兼容 compileSdk 34）
    val composeBom = platform("androidx.compose:compose-bom:2024.02.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.runtime:runtime")
    implementation("androidx.compose.animation:animation")

    // TV Compose（alpha10 兼容 compileSdk 34 + Kotlin 2.1.20）
    implementation("androidx.tv:tv-foundation:1.0.0-alpha10")
    implementation("androidx.tv:tv-material:1.0.0-alpha10")

    // Media3 ExoPlayer（1.6.1：包含 1.4.1 起累积的 MatroskaExtractor/MKV 音轨解析修复，
    // minSdk=21 兼容 Android 5 老设备。因 media3 1.5+ 要求 compileSdk 35，app 已同步升 35。
    // 版本需与下方 Jellyfin ffmpeg 解码器严格对齐）
    implementation("androidx.media3:media3-exoplayer:1.6.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.6.1")
    implementation("androidx.media3:media3-ui:1.6.1")
    implementation("androidx.media3:media3-common:1.6.1")
    implementation("androidx.media3:media3-extractor:1.6.1")
    // OkHttp 数据源（连接复用、HTTP/2），版本需与 media3 主版本严格一致
    implementation("androidx.media3:media3-datasource-okhttp:1.6.1")
    // FFmpeg 软解扩展（Jellyfin 维护的预编译 media3 ffmpeg 解码器，GPL-3.0）：
    // 为系统缺少 E-AC3/AC3/DTS 解码器的老设备（如极米 Android 5）提供软件音频解码。
    // 内含 libffmpegJNI.so（armeabi-v7a/arm64-v8a/x86/x86_64），配合 EXTENSION_RENDERER_MODE_ON 自动启用。
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.6.1+2")

    // OkHttp
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // 二维码生成（官网二维码，纯 Java 核心库，体积小、无 Android 依赖）
    implementation("com.google.zxing:core:3.5.3")

    // Java 8 核心库脱糖：minSdk=21 的设备（API<24）缺少 Map.putIfAbsent 等
    // Java 8 默认方法，运行时直接 NoSuchMethodError 闪退。需开启脱糖才能在
    // Android 6.0(API23) 等老系统上运行。
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // Lifecycle + ViewModel（锁版本，避免被拉到要求 compileSdk 35 的新版）
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")

    // Navigation Compose
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
}