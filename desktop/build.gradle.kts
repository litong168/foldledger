import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// ==================== :desktop —— Windows 桌面端应用 ====================
// 滴水记账 Windows 版：Compose Desktop (JVM)，业务/数据/UI 全部来自 :common，
// 本模块只负责窗口壳（Main.kt）与打包（jpackage 生成 exe/MSI）。
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            freeCompilerArgs.addAll(
                "-opt-in=kotlin.RequiresOptIn",
                "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
                "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi"
            )
        }
    }

    sourceSets {
        val desktopMain by getting
        desktopMain.dependencies {
            implementation(project(":common")) {
                // :common 的 Android 系依赖（Room/DataStore）会把 kotlinx-coroutines-android
                // 带进桌面 runtime：其 AndroidDispatcherFactory 引用 android.os.Looper，
                // 桌面缺该类会让 Dispatchers.Main 首次初始化直接 NoClassDefFoundError。
                exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-android")
            }
            implementation(compose.desktop.currentOs)
            implementation(libs.jetbrains.compose.material3)
            implementation(libs.jetbrains.compose.icons.core)
            // 桌面主调度器：Swing EDT（Dispatchers.Main 的正常桌面来源）
            implementation(libs.jetbrains.coroutines.swing)
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.foldledger.desktop.MainKt"
        // v16.1.1：桌面 release 默认跑 ProGuard，jar 合并时 META-INF 资源重复
        // 被 ProGuard 7.7 当错误直接失败；桌面端是本地 App，混淆无收益，关掉
        //（Android 端 R8 混淆独立，不受影响）。
        buildTypes.release {
            proguard {
                isEnabled.set(false)
            }
        }
    }
}
