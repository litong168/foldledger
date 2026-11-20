package com.foldledger.desktop

import androidx.compose.runtime.mutableStateOf
import com.foldledger.APP_VERSION_NAME
import com.foldledger.ServiceLocator
import com.foldledger.data.update.UpdateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.awt.Desktop
import java.io.File
import java.util.zip.ZipFile
import kotlin.coroutines.cancellation.CancellationException

/**
 * 桌面端「检查更新」v16.6.0。
 *
 * 两条来源，GitHub 优先、本地目录兜底：
 * 1. **GitHub Releases**（主）：[UpdateManager] 拉 `releases/latest` + `update-manifest.json`，
 *    比对 versionCode，有更新则下载 exe 到用户私有目录并校验 SHA256，用户点「退出并安装」后
 *    启动 setup 再退出本进程（同 UpgradeCode 覆盖，绝不解压替换安装目录）。
 * 2. **本地来源目录**（兜底）：扫设置里的目录 / 系统「下载」文件夹找更高的安装包——
 *    GitHub 访问失败、或用户手动下好了包时用这条。
 *
 * ⚠️ 铁律：不走原地解压替换（MSI 组件表会与文件脱节）；下载目录是用户私有目录，绝不写安装目录。
 */
object DesktopAutoUpdater {

    /** [isInstaller] = true 表示安装包（.exe），false 表示旧版绿色 zip */
    data class UpdateInfo(
        val path: String,
        val versionName: String,
        val isInstaller: Boolean,
        /** 来自 GitHub 的更新（已下载并校验完）；本地扫描时为 null */
        val fromGithub: Boolean = false,
        val notes: String = ""
    )

    val updateInfo = mutableStateOf<UpdateInfo?>(null)
    val busy = mutableStateOf(false)
    val progress = mutableStateOf(0L to -1L)
    val lastError = mutableStateOf<String?>(null)

    /** 更新管理器（用共享偏好） */
    private val manager by lazy { UpdateManager(ServiceLocator.preferences) }

    /** 供 UI 直接读取的完整更新信息（含 versionCode，跳过要用） */
    val managerAvailable: UpdateManager.Available? get() = manager.available

    /** 本次下载是否已切到国内镜像（UI 提示用） */
    val usingMirror: Boolean get() = manager.usingMirror

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
            lastError.value = null
            try {
                // 主路径：GitHub 检查
                val has = manager.check(platform = "windows", force = true)
                if (has && manager.available != null) {
                    val info = manager.available!!
                    updateInfo.value = UpdateInfo(
                        path = "", // 还没下载，路径在用户点「更新」后填充
                        versionName = info.versionName,
                        isInstaller = true,
                        fromGithub = true,
                        notes = info.notes
                    )
                    return@launch
                }
                // 兜底：本地目录扫描
                updateInfo.value = updateDir()?.let { findNewer(it) }
                lastError.value = manager.lastError
            } catch (_: Exception) {
                updateInfo.value = null
            } finally {
                busy.value = false
            }
        }
    }

    /**
     * 下载当前 GitHub 更新到用户私有目录（~/.foldledger/updates），校验后填 path。
     * 返回是否成功；失败写 lastError，updateInfo 保持（弹窗可重试）。
     */
    suspend fun downloadGithubUpdate(): Boolean {
        val info = updateInfo.value ?: return false
        if (!info.fromGithub) return true // 本地包无需下载
        busy.value = true
        lastError.value = null
        try {
            val dir = File(System.getProperty("user.home"), ".foldledger/updates")
            val file = manager.downloadAndVerify(dir) { done, total ->
                progress.value = done to total
            }
            progress.value = 0L to -1L
            updateInfo.value = info.copy(path = file.absolutePath)
            return true
        } catch (e: CancellationException) {
            // 「取消下载」：让协程正常结束，别当成失败弹红字
            throw e
        } catch (e: Exception) {
            lastError.value = e.message ?: e::class.simpleName
            return false
        } finally {
            busy.value = false
        }
    }

    /** 跳过此版本（GitHub 更新） */
    suspend fun skipThisVersion() {
        manager.skipThisVersion()
        updateInfo.value = null
    }

    /** 更新来源目录（本地兜底）：设置项优先；否则系统「下载」文件夹 */
    private suspend fun updateDir(): File? {
        val set = ServiceLocator.preferences.settings.first().desktopUpdateDir
        if (!set.isNullOrBlank()) {
            val d = File(set)
            if (d.isDirectory) return d
        }
        val downloads = File(System.getProperty("user.home"), "Downloads")
        return if (downloads.isDirectory) downloads else null
    }

    /** 从 classpath 推断安装目录：<install>/app/<main.jar>。仅当存在 滴水记账.exe 时返回。 */
    fun installDir(): File? = runCatching {
        val cp = System.getProperty("java.class.path") ?: return null
        val jar = cp.split(File.pathSeparator)
            .firstOrNull { it.endsWith(".jar", ignoreCase = true) } ?: return null
        val appDir = File(jar).parentFile ?: return null
        val dir = appDir.parentFile ?: return null
        if (File(dir, "滴水记账.exe").isFile) dir else null
    }.getOrNull()

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
            if (f.length() < 20L * 1024 * 1024) return@mapNotNull null
            if (!isInstaller && !zipLooksValid(f)) return@mapNotNull null
            Triple(f, Version(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()), isInstaller)
        }?.filter { it.second > current } ?: return null
        val best = candidates.maxByOrNull { it.second } ?: return null
        val v = best.second
        return UpdateInfo(
            path = best.first.absolutePath,
            versionName = "${v.major}.${v.minor}.${v.patch}",
            isInstaller = best.third,
            fromGithub = false
        )
    }

    private fun currentVersion(): Version {
        val parts = APP_VERSION_NAME.split('.').mapNotNull { it.toIntOrNull() }
        return Version(parts.getOrElse(0) { 0 }, parts.getOrElse(1) { 0 }, parts.getOrElse(2) { 0 })
    }

    private fun zipLooksValid(zip: File): Boolean = runCatching {
        ZipFile(zip).use { zf ->
            val names = zf.entries().asSequence().map { it.name.replace('\\', '/') }.toList()
            names.any { it.endsWith("/app/滴水记账.cfg") } && names.any { it.endsWith("/滴水记账.exe") }
        }
    }.getOrDefault(false)

    /** 在资源管理器里定位更新文件（本地兜底用） */
    fun openUpdateLocation(): Boolean {
        val info = updateInfo.value ?: return false
        val f = File(info.path)
        if (!f.exists()) return false
        runCatching { ProcessBuilder("explorer.exe", "/select,${f.absolutePath}").start() }
            .onSuccess { return true }
        return runCatching {
            val dir = f.parentFile ?: return false
            Desktop.getDesktop().open(dir)
            true
        }.getOrDefault(false)
    }

    /**
     * 启动安装包，然后退出本进程。
     * 先起安装器再退出：等用户点完向导本进程已没了，MSI 不会撞上文件占用。
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
