package com.foldledger.desktop

import androidx.compose.runtime.mutableStateOf
import com.foldledger.APP_VERSION_NAME
import com.foldledger.ServiceLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.awt.Desktop
import java.io.File
import java.util.zip.ZipFile

/**
 * 桌面端「检查更新」。
 *
 * ⚠️ v16.2.9 起桌面端**只以安装包分发**（jpackage exe 安装器 → MSI），不再提供绿色免安装版，
 * 所以这里**不再原地解压替换安装目录**。被原地替换过的目录，MSI 的组件表（文件清单 / 版本号 /
 * 卸载信息）会与实际文件脱节：之后卸载会留下残文件，升级会被判定为"文件被改过"而走修复。
 *
 * 现在只做两件事：
 * 1. 在「更新来源目录」里找比当前版本高的安装包（也认旧版 zip，方便从绿色版过渡），提示用户；
 * 2. 用户点按钮时用资源管理器定位到该文件，由用户运行安装包 —— 升级流程交给安装器本身
 *    （同 UpgradeCode + 更高版本，安装器会先卸旧版再装新版，快捷方式/卸载项一并更新）。
 */
object DesktopAutoUpdater {

    /** [isInstaller] = true 表示是安装包（.exe），false 表示旧版绿色 zip */
    data class UpdateInfo(val path: String, val versionName: String, val isInstaller: Boolean)

    /** 发现的新版本（null = 无更新或还没检查） */
    val updateInfo = mutableStateOf<UpdateInfo?>(null)

    val busy = mutableStateOf(false)

    /** 应用更新后退出本进程（由 Main.kt 注入 exitApplication） */
    var onExit: () -> Unit = {}

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var backgroundStarted = false

    /** 启动后延迟检查一次（等窗口/托盘就绪，避免和首帧争抢） */
    fun startBackgroundCheck() {
        if (backgroundStarted) return
        backgroundStarted = true
        scope.launch {
            delay(12_000)
            checkNow()
        }
    }

    fun checkNow() {
        if (busy.value) return
        scope.launch {
            busy.value = true
            try {
                val dir = updateDir()
                updateInfo.value = dir?.let { findNewer(it) }
            } catch (_: Exception) {
                updateInfo.value = null
            } finally {
                busy.value = false
            }
        }
    }

    /**
     * 更新来源目录：设置项优先；否则用系统的「下载」文件夹
     * （安装包默认也下载到那里）。安装目录本身不再参与扫描 ——
     * 它现在由 MSI 管理，往里塞文件只会让卸载不干净。
     */
    private suspend fun updateDir(): File? {
        val set = ServiceLocator.preferences.settings.first().desktopUpdateDir
        if (!set.isNullOrBlank()) {
            val d = File(set)
            if (d.isDirectory) return d
        }
        val downloads = File(System.getProperty("user.home"), "Downloads")
        return if (downloads.isDirectory) downloads else null
    }

    /**
     * 从 classpath 推断安装目录：<install>/app/<main.jar>。
     * 仅当目录里存在 滴水记账.exe 时返回（开发版返回 null）。
     */
    fun installDir(): File? = runCatching {
        val cp = System.getProperty("java.class.path") ?: return null
        val jar = cp.split(File.pathSeparator)
            .firstOrNull { it.endsWith(".jar", ignoreCase = true) } ?: return null
        val appDir = File(jar).parentFile ?: return null
        val dir = appDir.parentFile ?: return null
        if (File(dir, "滴水记账.exe").isFile) dir else null
    }.getOrNull()

    /** exe 绝对路径（开发版 = null）。开机自启写的就是这个路径。 */
    fun currentExePath(): String? =
        installDir()?.let { File(it, "滴水记账.exe").absolutePath }

    private data class Version(val major: Int, val minor: Int, val patch: Int) :
        Comparable<Version> {
        override fun compareTo(other: Version): Int =
            compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })
    }

    private val installerPattern = Regex("""滴水记账-v(\d+)\.(\d+)\.(\d+)-win-x64-setup\.exe""")
    private val legacyZipPattern = Regex("""滴水记账-v(\d+)\.(\d+)\.(\d+)-win-x64\.zip""")

    private fun findNewer(dir: File): UpdateInfo? {
        val current = currentVersion()
        val candidates = dir.listFiles { f -> f.isFile }?.mapNotNull { f ->
            val name = f.name
            val isInstaller = installerPattern.matchEntire(name) != null
            val m = installerPattern.matchEntire(name) ?: legacyZipPattern.matchEntire(name)
                ?: return@mapNotNull null
            // 半截下载文件不提示（安装包 ~90MB，小于 20MB 基本可以确定没下完）
            if (f.length() < 20L * 1024 * 1024) return@mapNotNull null
            if (!isInstaller && !zipLooksValid(f)) return@mapNotNull null
            Triple(
                f,
                Version(
                    m.groupValues[1].toInt(),
                    m.groupValues[2].toInt(),
                    m.groupValues[3].toInt()
                ),
                isInstaller
            )
        }?.filter { it.second > current } ?: return null
        val best = candidates.maxByOrNull { it.second } ?: return null
        val v = best.second
        return UpdateInfo(
            path = best.first.absolutePath,
            versionName = "${v.major}.${v.minor}.${v.patch}",
            isInstaller = best.third
        )
    }

    private fun currentVersion(): Version {
        val parts = APP_VERSION_NAME.split('.').mapNotNull { it.toIntOrNull() }
        return Version(parts.getOrElse(0) { 0 }, parts.getOrElse(1) { 0 }, parts.getOrElse(2) { 0 })
    }

    private fun zipLooksValid(zip: File): Boolean = runCatching {
        ZipFile(zip).use { zf ->
            val names = zf.entries().asSequence().map { it.name.replace('\\', '/') }.toList()
            names.any { it.endsWith("/app/滴水记账.cfg") } &&
                names.any { it.endsWith("/滴水记账.exe") }
        }
    }.getOrDefault(false)

    /**
     * 在资源管理器里定位到找到的那个更新文件（选中它），由用户双击运行。
     * 返回 false 表示当前没有可定位的目标（或系统不支持）。
     */
    fun openUpdateLocation(): Boolean {
        val info = updateInfo.value ?: return false
        val f = File(info.path)
        if (!f.exists()) return false
        runCatching {
            ProcessBuilder("explorer.exe", "/select,${f.absolutePath}").start()
        }.onSuccess { return true }
        // 兜底：打开所在目录
        return runCatching {
            val dir = f.parentFile ?: return false
            Desktop.getDesktop().open(dir)
            true
        }.getOrDefault(false)
    }

    /**
     * 启动安装包，然后退出本进程。
     *
     * 先起安装器再退出：安装向导要用户点几下才真正开始拷文件，那时本进程已经没了，
     * MSI 不会撞上"滴水记账.exe / jar 正在被使用"而要求重启。
     */
    fun installUpdate(): Boolean {
        val info = updateInfo.value ?: return false
        if (!info.isInstaller) return false
        val f = File(info.path)
        if (!f.isFile) return false
        val started = runCatching { ProcessBuilder(f.absolutePath).start() }.isSuccess
        if (started) onExit()
        return started
    }
}
