import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android") version "1.9.22"
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
// CI 开关：true 时产出 universal APK（合并所有 ABI 成一个包）
val ciUniversal: Boolean = System.getenv("CI_UNIVERSAL") == "true"

android {
    namespace = "com.example.composedtv"
    compileSdk = 34

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
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            // 本地默认只出各 ABI；CI 设 CI_UNIVERSAL=true 时额外产出 universal 单包
            isUniversalApk = ciUniversal
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

    kotlinOptions {
        jvmTarget = "11"
        // Media3 的 UnstableApi 注解未声明为 requiresOptIn，
        // 需通过编译器参数开启 opt-in，消除 @OptIn 被忽略的警告
        freeCompilerArgs = listOf("-opt-in=androidx.media3.common.util.UnstableApi")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Kotlin 1.9.x 通过 compiler extension 接入 Compose（与 Compose BOM 2024.02 / Compose 1.6 配套）
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.10"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
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

    // Media3 ExoPlayer
    implementation("androidx.media3:media3-exoplayer:1.2.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.2.1")
    implementation("androidx.media3:media3-ui:1.2.1")
    implementation("androidx.media3:media3-common:1.2.1")
    implementation("androidx.media3:media3-extractor:1.2.1")

    // OkHttp
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

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
