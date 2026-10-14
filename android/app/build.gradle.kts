plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.chaquo.python")
}

android {
    namespace = "com.glpmate.native_app"
    compileSdk = 36

    // 项目专用签名密钥（刻意连同密码一起提交到仓库）。
    //
    // 为什么不用 debug 签名：`signingConfigs.getByName("debug")` 用的是 Gradle 在
    // **当前机器**自动生成的 ~/.android/debug.keystore。换一台机器构建 → 签名不同 →
    // 用户安装更新时被系统拒绝（INSTALL_FAILED_UPDATE_INCOMPATIBLE），
    // 只能卸载重装（会丢数据）。开源项目里这是很常见的坑。
    //
    // 这个密钥**不是机密**：它只用于给这个开源应用签名，任何人都能下载仓库
    // 用同一把钥匙构建出可覆盖安装的包。密码公开是刻意的——
    // 不公开的话别人重新构建就会签名不一致，反而制造问题。
    // 如果要上应用商店，请换一把只自己掌握的密钥并从这里移除。
    signingConfigs {
        create("glpmateRelease") {
            storeFile = file("../glpmate-release.jks")
            storePassword = "glpmate-release"
            keyAlias = "glpmate"
            keyPassword = "glpmate-release"
        }
    }

    defaultConfig {
        applicationId = "com.glpmate.nativeapp"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0-native"

        ndk {
            // 默认只打包手机架构（arm64-v8a），APK 约 36MB。
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
            // 开混淆 + 资源压缩：debug 版 37MB 里绝大部分是没被裁掉的
            // Compose/Kotlin 代码（classes7.dex 单独就有 10.8MB）。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // 正式包**不带** skipNotifPrompt：必须请求通知权限，
            // 否则提醒会被系统静默丢弃（见 defaultConfig 的说明）。
            manifestPlaceholders["skipNotificationPrompt"] = "false"
            signingConfig = signingConfigs.getByName("glpmateRelease")
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
