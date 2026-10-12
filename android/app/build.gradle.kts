plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.chaquo.python")
}

android {
    namespace = "com.glpmate.native_app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.glpmate.nativeapp"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0-native"

        ndk {
            // 默认只打包真机架构（PLC110 是 arm64-v8a），APK 约 28MB。
            // 要在 x86_64 模拟器上调试时加 -PwithEmulatorAbi 会额外打包一套运行时
            // （体积翻倍，所以不作为默认）。用 Gradle 属性而不是注释切换，
            // 避免"为了测试改了文件忘了改回来"。
            val abis = mutableListOf("arm64-v8a")
            if (project.hasProperty("withEmulatorAbi")) abis.add("x86_64")
            abiFilters += abis
        }
    }

    buildFeatures { compose = true }

    defaultConfig {
        // 调试用：-PskipNotifPrompt 构建出"不弹通知权限"的包，方便在锁屏/远程调试时
        // 看到界面。默认 false —— 正式包必须请求权限，否则提醒会被系统静默丢弃。
        manifestPlaceholders["skipNotificationPrompt"] =
            if (project.hasProperty("skipNotifPrompt")) "true" else "false"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

chaquopy {
    defaultConfig {
        version = "3.12"
        // 后端只用 Python 标准库，没有 pip 依赖，所以不写 pip.install（避免联网拉包）
    }
    sourceSets {
        getByName("main") {
            srcDir("src/main/python")
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.3")
    implementation("androidx.core:core-ktx:1.13.0")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
}
