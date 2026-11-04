import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// ==================== :common —— KMP 共享模块 ====================
// 滴水记账双端共享的业务/数据/UI 代码：Android 保留 + 新增 Compose Desktop (JVM)。
// - commonMain : 纯逻辑 + 数据层（Room DAO/Entity）+ 共享 Compose UI + ViewModel
// - androidMain: Android 平台实现（SAF/WorkManager/窗口小部件/自动记账等）
// - desktopMain: 桌面平台实现（SQLite 驱动/DataStore/窗口适配等）
// 铁律：这里出现的东西改动都会同时影响双端，DB 迁移链与备份格式语义与 :app 时代一致。
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            freeCompilerArgs.addAll(
                "-opt-in=kotlin.RequiresOptIn",
                "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
                "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi",
                "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
                "-opt-in=androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi"
            )
        }
    }

    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            freeCompilerArgs.addAll(
                "-opt-in=kotlin.RequiresOptIn",
                "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
                "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi",
                "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi"
            )
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.jetbrains.compose.runtime)
            implementation(libs.jetbrains.compose.foundation)
            implementation(libs.jetbrains.compose.ui)
            implementation(libs.jetbrains.compose.material3)
            implementation(libs.jetbrains.compose.icons.core)
            implementation(libs.androidx.room.runtime)
            implementation(libs.androidx.room.ktx)
            implementation(libs.androidx.datastore.preferences)
            implementation(libs.jetbrains.lifecycle.viewmodel.compose)
            implementation(libs.jetbrains.lifecycle.runtime.compose)
            implementation(libs.jetbrains.navigation.compose)
            // org.json 是纯 JVM 库（无 KMP 元数据）：双端都是 JVM 目标，放 commonMain
            // 让备份编解码 / 模板编解码共用；若元数据编译拒绝则改用 jvm 共享源集
            implementation(libs.json.test)
        }

        androidMain.dependencies {
            // 折叠屏铰链特征（WindowInfoTracker）+ 记账提醒（WorkManager）
            implementation(libs.androidx.window)
            implementation(libs.androidx.work.runtime.ktx)
        }

        val desktopMain by getting
        desktopMain.dependencies {
            implementation(libs.androidx.sqlite.bundled)
        }

        // M4.2：Room 真库测试跑在 JVM（BundledSQLiteDriver），
        // 验证打戳/软删/查询过滤 —— Android 侧同类行为由双端共享代码保证
        // M4.5：Compose UI 测试渲染同步卡（补桌面目检缺口）
        val desktopTest by getting
        desktopTest.dependencies {
            implementation(kotlin("test"))
            implementation(compose.desktop.uiTestJUnit4)
            implementation(compose.desktop.currentOs)
            // UI 测试需要 Dispatchers.Main（lifecycle-compose collect 用）；setMain 到 Swing EDT
            implementation(libs.jetbrains.coroutines.swing)
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
        }
    }
}

android {
    namespace = "com.foldledger"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "META-INF/*.kotlin_module"
            )
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    add("kspAndroid", libs.androidx.room.compiler)
    add("kspDesktop", libs.androidx.room.compiler)
}
