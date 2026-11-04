import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// ---------- 正式签名（release 绝不回退 debug 签名） ----------
// 签名库与口令放在 keystore.properties（不入版本库）。
// 关键：**缺签名库时直接让发布任务失败**，而不是悄悄改用 debug 签名 ——
// 那样会出一个"包名版本号都对、签名却变了"的包，老用户直接覆盖安装失败。
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}
val releaseStoreFile: File? = keystoreProps.getProperty("storeFile")
    ?.let { rootProject.file(it) }
    ?.takeIf { it.exists() }
val hasReleaseKeystore = keystorePropsFile.exists() && releaseStoreFile != null

val missingKeystoreMessage =
    """
    找不到正式签名库：keystore.properties 或它指向的 .jks 不存在。
    本项目禁止用 debug 签名发布（会导致已安装用户无法覆盖升级）。
    请把 drip-ledger-release.jks 与 keystore.properties 放回工程根目录后重试。
    """.trimIndent()

android {
    namespace = "com.foldledger"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.foldledger"
        minSdk = 26
        targetSdk = 35
        versionCode = 224
        versionName = "16.3.1"

        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
                // 四种签名方案全开：
                //  v1(JAR) 兼容 Android 6 及更早；v2/v3 校验更快、能挡篡改；v4 支持增量安装
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 只有拿到正式签名库才挂签名配置；否则产物是未签名的，
            // 同时下面的任务钩子会让 assembleRelease / bundleRelease 直接报错
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            freeCompilerArgs.addAll(
                "-opt-in=kotlin.RequiresOptIn",
                "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
                "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi",
                "-opt-in=androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi"
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
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

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    androidResources {
        generateLocaleConfig = false
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

// ==================== v4.0 Rust 核心构建（可选，绝不阻断出包） ====================
// `rust/drip-core` 是 Rust 实现的纯业务计算（金额/统计/搜索/备份完整性），编译成
// libdrip_core.so 后由 Kotlin `DripCore` 经 JNI 调用；so 缺失时 Kotlin 无缝回退。
// 所以这里**不**把 cargo 挂进默认构建链：`assembleRelease` 永远能靠回退出包。
// 需要 .so 时手动跑 `gradle :app:buildDripCore`（检测到 cargo + NDK 才编，否则提示跳过）。
val rustDir = rootProject.file("rust/drip-core")
val jniLibsDir = file("src/main/jniLibs")
val ndkDir = System.getenv("ANDROID_HOME")?.let { File(it, "ndk") }
    ?.listFiles()?.firstOrNull()?.absolutePath
    ?: System.getenv("ANDROID_NDK_HOME")

fun findCargo(): String? {
    val home = System.getenv("USERPROFILE") ?: System.getProperty("user.home")
    val candidates = listOf(
        "$home/.cargo/bin/cargo.exe",
        "$home/.cargo/bin/cargo",
        "cargo"
    )
    return candidates.firstOrNull { path ->
        try {
            ProcessBuilder(if (path == "cargo") listOf("cargo", "--version") else listOf(path, "--version"))
                .redirectErrorStream(true).start().also { it.waitFor() }.exitValue() == 0
        } catch (e: Exception) {
            false
        }
    }
}

tasks.register("buildDripCore") {
    group = "build"
    description = "用 NDK clang 作 linker 把 rust/drip-core 编成 4 个 ABI 的 libdrip_core.so（缺工具链则跳过）"
    doLast {
        val cargo = findCargo()
        if (cargo == null) {
            logger.warn("⚠️ 未找到 cargo（Rust 工具链），跳过 libdrip_core 构建；App 将用 Kotlin 回退。")
            return@doLast
        }
        if (ndkDir == null) {
            logger.warn("⚠️ 未找到 Android NDK，跳过 libdrip_core 交叉编译；App 将用 Kotlin 回退。")
            return@doLast
        }
        val ndk = File(ndkDir)
        val hostTag = "windows-x86_64"
        val bin = File(ndk, "toolchains/llvm/prebuilt/$hostTag/bin")
        if (!bin.exists()) {
            logger.warn("⚠️ NDK clang 目录不存在：$bin，跳过。")
            return@doLast
        }

        // ABI → (rust target, linker wrapper 前缀)
        val matrix = listOf(
            Triple("arm64-v8a", "aarch64-linux-android", "aarch64-linux-android26-clang"),
            Triple("armeabi-v7a", "armv7-linux-androideabi", "armv7a-linux-androideabi26-clang"),
            Triple("x86_64", "x86_64-linux-android", "x86_64-linux-android26-clang"),
            Triple("x86", "i686-linux-android", "i686-linux-android26-clang")
        )

        // 纯英文构建目录：MinGW ld 不支持中文路径（工程在「记账APP」下）
        val targetDir = File(System.getProperty("java.io.tmpdir"), "drip-core-target").absolutePath

        // 宿主编译（build script）走哪个工具链：本机无 VS Build Tools，PATH 里 Git 的 coreutils
        // `link` 会顶替 MSVC link.exe → 若装了 GNU 工具链就优先用它，否则用默认。
        val cargoHome = System.getenv("USERPROFILE") ?: System.getProperty("user.home")
        val gnuToolchain = File("$cargoHome/.rustup/toolchains/stable-x86_64-pc-windows-gnu")
            .takeIf { it.isDirectory }?.name

        var failed = 0
        matrix.forEach { (abi, target, clang) ->
            val linkerEnv = "CARGO_TARGET_${target.uppercase().replace('-', '_')}_LINKER"
            val cmd = listOf(
                cargo, "build", "--release", "--features", "android", "--target", target
            )
            val pb = ProcessBuilder(cmd)
            pb.directory(rustDir)
            pb.redirectErrorStream(true)
            val env = pb.environment()
            env["CARGO_TARGET_DIR"] = targetDir
            env[linkerEnv] = File(bin, "$clang.cmd").absolutePath
            if (gnuToolchain != null) env["RUSTUP_TOOLCHAIN"] = gnuToolchain
            val out = StringBuilder()
            val exit = try {
                val proc = pb.start()
                proc.inputStream.bufferedReader().forEachLine { out.appendLine(it) }
                proc.waitFor()
            } catch (e: Exception) {
                out.appendLine("启动失败：${e.message}")
                -1
            }
            if (exit != 0) {
                failed++
                logger.warn("⚠️ $abi（$target）构建失败：\n${out.takeLast(2000)}")
                return@forEach
            }
            val so = File(targetDir, "$target/release/libdrip_core.so")
            if (!so.exists()) {
                failed++
                logger.warn("⚠️ $abi 未产出 .so：${so.absolutePath}")
                return@forEach
            }
            val dst = File(jniLibsDir, "$abi/libdrip_core.so")
            dst.parentFile.mkdirs()
            so.copyTo(dst, overwrite = true)
            logger.lifecycle("✅ $abi → ${dst.absolutePath} (${dst.length() / 1024} KB)")
        }
        if (failed == 0) {
            logger.lifecycle("✅ libdrip_core.so 已输出到 $jniLibsDir（4 ABI）")
        } else {
            logger.warn("⚠️ 有 $failed 个 ABI 未编出；缺的 ABI 在设备上会自动走 Kotlin 回退。")
        }
    }
}

// 发布任务缺签名库时立刻失败，绝不悄悄用 debug 签名出包
tasks.matching {
    it.name == "packageRelease" || it.name == "assembleRelease" || it.name == "bundleRelease"
}.configureEach {
    doFirst { check(hasReleaseKeystore) { missingKeystoreMessage } }
}

dependencies {
    // 双端共享逻辑/UI（数据层、解析器、ViewModel、Rust 核心回退）已抽到 :common
    implementation(project(":common"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.wsc)

    // 折叠屏 / 自适应
    implementation(libs.androidx.window)

    // 导航
    implementation(libs.androidx.navigation.compose)

    // 持久化：Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // 偏好设置
    implementation(libs.androidx.datastore.preferences)

    // 记账提醒（每日定时本地通知）
    implementation(libs.androidx.work.runtime.ktx)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    // 真实 org.json 覆盖 android.jar 里的桩实现，让备份编解码能在 JVM 上真跑
    testImplementation(libs.json.test)
}
