package com.foldledger.ui.vm

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.foldledger.APP_VERSION_NAME
import com.foldledger.ServiceLocator
import com.foldledger.core.DripCore
import com.foldledger.data.backup.BackupCipher
import com.foldledger.data.backup.BackupEntry
import com.foldledger.data.backup.BackupFormat
import com.foldledger.data.backup.BackupSnapshot
import com.foldledger.data.export.ExcelExporter
import com.foldledger.data.prefs.AppPreferences
import com.foldledger.data.repo.LedgerRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import kotlin.coroutines.resume
import com.foldledger.ui.l10n.t

/**
 * 桌面端备份实现：与 Android 共用同一套备份格式（BackupFormat / BackupCipher / ExcelExporter /
 * LedgerRepository.snapshot / restoreFrom），只把 SAF 目录换成普通文件路径 + JFileChooser。
 *
 * 交互与 Android 端一致：首次点「导出备份」弹一次目录选择（默认定位到「下载」），之后一键导出；
 * 恢复/删除/列表走 BackupSection 的共享 UI。
 */
private class DesktopBackupViewModel(
    private val repo: LedgerRepository = ServiceLocator.repository,
    private val prefs: AppPreferences = ServiceLocator.preferences
) : BackupViewModel {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _state = MutableStateFlow(BackupUiState(canOpenFolder = true))
    override val state: StateFlow<BackupUiState> = _state

    private var folderPath: String? = null

    /** 每次启动只自动备份一次 */
    private var launchAutoBackupDone = false

    init {
        scope.launch {
            prefs.settings.collect { s ->
                folderPath = s.desktopBackupFolderPath?.takeIf { File(it).isDirectory }
                val ready = folderPath != null
                if (!ready) folderPath = null
                _state.value = _state.value.copy(
                    folderReady = ready,
                    folderLabel = folderPath?.let { File(it).name },
                    autoBackupEnabled = s.autoBackupEnabled,
                    lastAutoBackupAt = s.lastAutoBackupAt
                )
                if (ready) refreshListInternal()

                // 「每次打开软件自动备份一次」：等目录状态确定后再触发，避免和 DataStore 竞态
                if (ready && s.autoBackupEnabled && !launchAutoBackupDone) {
                    launchAutoBackupDone = true
                    autoBackupOnLaunch()
                }
            }
        }
    }

    // ---------------- 对外动作 ----------------

    override fun requestExport() {
        if (folderPath == null) {
            scope.launch {
                val path = pickFolder() ?: run {
                    // v16.2.5：取消也要有回执 —— 以前是静默回到 IDLE，
                    // 用户点了按钮、什么都没弹、卡片上一个字都没变，观感等同"按钮坏了"
                    _state.value = _state.value.copy(
                        phase = BackupPhase.IDLE,
                        message = t("已取消选择备份文件夹")
                    )
                    return@launch
                }
                setFolder(path)
                _state.value = _state.value.copy(pendingExportPassword = true)
            }
        } else {
            _state.value = _state.value.copy(pendingExportPassword = true)
        }
    }

    override fun confirmExport(password: String) {
        _state.value = _state.value.copy(pendingExportPassword = false)
        scope.launch { doExport(auto = false, passphrase = password.ifBlank { null }) }
    }

    override fun cancelExportPassword() {
        _state.value = _state.value.copy(pendingExportPassword = false)
    }

    override fun requestImport() {
        if (folderPath == null) {
            scope.launch {
                val path = pickFolder() ?: run {
                    _state.value = _state.value.copy(
                        phase = BackupPhase.IDLE,
                        message = t("已取消选择备份文件夹")
                    )
                    return@launch
                }
                setFolder(path)
                doImport()
            }
        } else {
            scope.launch { doImport() }
        }
    }

    override fun setAutoBackupEnabled(enabled: Boolean) {
        scope.launch {
            prefs.setAutoBackupEnabled(enabled)
            if (enabled) {
                if (folderPath == null) {
                    val path = pickFolder() ?: run {
                        // 没选目录就别假装开成功了：开关回弹 + 一句说明
                        prefs.setAutoBackupEnabled(false)
                        _state.value = _state.value.copy(
                            autoBackupEnabled = false,
                            message = t("没有选备份文件夹，自动备份未开启")
                        )
                        return@launch
                    }
                    setFolder(path)
                }
                doExport(auto = true)
            }
        }
    }

    /** 桌面端目录选择在 ViewModel 内部完成，不经过 SAF */
    override fun onFolderGranted(uri: String?) {}

    override fun onFolderPickCancelled() {}

    override fun refreshList() {
        scope.launch { refreshListInternal() }
    }

    override fun autoBackupOnLaunch() {
        scope.launch {
            val dir = folderPath?.let { File(it) } ?: return@launch
            if (!_state.value.autoBackupEnabled) return@launch
            if (repo.isDatabaseEmpty()) return@launch
            runCatching { exportInternal(dir, auto = true) }
                .onSuccess {
                    prefs.markAutoBackupDone(System.currentTimeMillis())
                    refreshListInternal()
                }
            // 自动备份失败保持静默，不打扰用户
        }
    }

    // ---------------- 恢复确认流 ----------------

    override fun cancelPendingRestore() {
        _state.value = _state.value.copy(
            pendingRestore = null,
            pendingRestorePassphrase = null,
            phase = BackupPhase.IDLE,
            message = ""
        )
    }

    override fun requestRestoreFrom(entry: BackupEntry) {
        if (entry.encrypted) {
            _state.value = _state.value.copy(
                pendingRestorePassword = entry,
                pickingRestore = false,
                phase = BackupPhase.IDLE,
                message = ""
            )
        } else {
            _state.value = _state.value.copy(
                pendingRestore = entry,
                pickingRestore = false,
                phase = BackupPhase.IDLE,
                message = ""
            )
        }
    }

    // ---------------- 删除备份（二次确认） ----------------

    override fun requestDelete(entry: BackupEntry) {
        _state.value = _state.value.copy(pendingDelete = entry, phase = BackupPhase.IDLE, message = "")
    }

    override fun cancelDelete() {
        _state.value = _state.value.copy(pendingDelete = null)
    }

    override fun confirmDelete() {
        val entry = _state.value.pendingDelete ?: return
        val dir = folderPath?.let { File(it) } ?: return
        _state.value = _state.value.copy(pendingDelete = null)
        _state.value = _state.value.copy(phase = BackupPhase.WORKING, message = t("正在移入回收站…"))
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    if (!BackupFormat.isBackupFileName(entry.fileName)) {
                        error(t("这个文件不是滴水记账的备份，已拒绝删除"))
                    }
                    val json = File(dir, entry.fileName)
                    if (!json.isFile) error(t("删除失败，可能文件已被移除"))

                    // v16.3：与 Android 一样「先留副本 → 记清单 → 最后才删原件」。
                    // 顺序不能反：副本写失败就绝不删用户的文件；清单用原子读改写，
                    // 一次瞬时读失败不会把整张回收站清单清空。
                    val item = DesktopRecycleStore.stash(dir, entry.fileName)
                    prefs.mutateRecycledBackups { current -> current + item }

                    File(dir, DesktopRecycleStore.xlsxNameOf(entry.fileName)).delete()
                    if (!json.delete()) error(t("删除失败，可能文件已被移除"))
                }
            }
                .onSuccess {
                    _state.value = _state.value.copy(
                        phase = BackupPhase.DONE,
                        message = "已移入回收站：${entry.fileName}"
                    )
                    refreshListInternal()
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(
                        phase = BackupPhase.FAILED,
                        message = e.message ?: t("删除失败")
                    )
                }
        }
    }

    override fun confirmRestore(asNew: Boolean) {
        val entry = _state.value.pendingRestore ?: return
        val pass = _state.value.pendingRestorePassphrase
        _state.value = _state.value.copy(
            pendingRestore = null,
            pendingRestorePassphrase = null,
            pickingRestore = false
        )
        scope.launch { doRestore(entry, pass, asNew) }
    }

    /** 输完密码先不恢复：把密码暂存，接着让用户选「覆盖 / 作为新账本」 */
    override fun confirmRestorePassword(password: String) {
        val entry = _state.value.pendingRestorePassword ?: return
        _state.value = _state.value.copy(
            pendingRestorePassword = null,
            pendingRestore = entry,
            pendingRestorePassphrase = password.ifBlank { null },
            pickingRestore = false
        )
    }

    /** 更换备份目录：任何状态下都能重选（以前选错就再也改不了） */
    override fun requestFolderChange() {
        scope.launch {
            val path = pickFolder() ?: run {
                _state.value = _state.value.copy(
                    phase = BackupPhase.IDLE,
                    message = t("已取消更换备份文件夹")
                )
                return@launch
            }
            setFolder(path)
            refreshListInternal()
            _state.value = _state.value.copy(
                phase = BackupPhase.DONE,
                message = "备份位置已改为：${File(path).name}，之后都往这里写"
            )
        }
    }

    /** 用资源管理器打开备份目录 —— t("导出的文件到底在哪")最直接的答案 */
    override fun openFolder(): Boolean {
        val dir = folderPath?.let { File(it) }?.takeIf { it.isDirectory } ?: run {
            _state.value = _state.value.copy(phase = BackupPhase.FAILED, message = t("还没设置备份文件夹"))
            return false
        }
        return runCatching {
            if (!java.awt.Desktop.isDesktopSupported()) error(t("当前系统不支持打开文件夹"))
            java.awt.Desktop.getDesktop().open(dir)
            true
        }.getOrElse { e ->
            _state.value = _state.value.copy(
                phase = BackupPhase.FAILED,
                message = "打不开文件夹：${e.message ?: "未知原因"}"
            )
            false
        }
    }

    override fun cancelRestorePassword() {
        _state.value = _state.value.copy(pendingRestorePassword = null)
    }

    // ---------------- 选哪一份来恢复 ----------------

    override fun cancelPick() {
        _state.value = _state.value.copy(pickingRestore = false)
    }

    override fun pickLatest() {
        val newest = _state.value.backups.firstOrNull()
        if (newest == null) {
            _state.value = _state.value.copy(
                pickingRestore = false,
                phase = BackupPhase.FAILED,
                message = t("备份目录里没有可恢复的备份")
            )
        } else if (newest.encrypted) {
            _state.value = _state.value.copy(pendingRestorePassword = newest)
        } else {
            _state.value = _state.value.copy(pendingRestore = newest)
        }
    }

    override fun resetMessage() {
        _state.value = _state.value.copy(phase = BackupPhase.IDLE, message = "")
    }

    // ---------------- 实现 ----------------

    private suspend fun setFolder(path: String) {
        folderPath = path
        prefs.setDesktopBackupFolder(path)
        _state.value = _state.value.copy(
            folderReady = true,
            folderLabel = File(path).name
        )
    }

    /**
     * 系统目录选择（JVM 桌面：JFileChooser，目录模式）。
     *
     * ⚠️ v16.2.5 修「点了导出备份没反应」：旧实现是 `pickFolderBlocking()` ——
     * 在 **Dispatchers.Main（AWT EDT）里同步** `showOpenDialog(null)`，两个后果：
     *   1. 弹窗没有父窗口 → 不模态、不置顶，很容易被主窗口盖住，用户看到的就是"什么都没发生"；
     *   2. 它是在**鼠标释放事件的派发栈里**弹出的 → 同一次 release 会落到新对话框上，
     *      对话框一出现就被判成一次点击，闪一下就没了。
     * 现在改成：`invokeLater` 把弹窗排到当前事件之后，并挂到主窗口（模态、位于窗口之上），
     * 用挂起函数等结果，全程不阻塞 EDT。
     */
    private suspend fun pickFolder(): String? = suspendCancellableCoroutine { cont ->
        SwingUtilities.invokeLater {
            val path = runCatching {
                val chooser = JFileChooser().apply {
                    fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                    isAcceptAllFileFilterUsed = false
                    dialogTitle = t("选择备份文件夹")
                    val home = System.getProperty("user.home")
                    File(home, "Downloads").takeIf { it.isDirectory }?.let { selectedFile = it }
                }
                if (chooser.showOpenDialog(appWindow()) == JFileChooser.APPROVE_OPTION) {
                    chooser.selectedFile?.absolutePath
                } else null
            }.getOrNull()
            if (cont.isActive) cont.resume(path)
        }
    }

    /** 找主窗口当对话框父级；找不到就退回 null（至少不会崩） */
    private fun appWindow(): java.awt.Window? {
        val windows = java.awt.Window.getWindows()
        return windows.firstOrNull { it is java.awt.Frame && it.isVisible && it.title == "滴水记账" }
            ?: windows.firstOrNull { it is java.awt.Frame && it.isVisible }
    }

    private data class ExportOutcome(
        val folderLabel: String,
        val ledgerCount: Int,
        val txnCount: Int,
        val prunedFiles: Int,
        val isAuto: Boolean
    )

    private suspend fun doExport(auto: Boolean, passphrase: String? = null) {
        val dir = folderPath?.let { File(it) } ?: return
        _state.value = _state.value.copy(
            phase = BackupPhase.WORKING,
            message = if (auto) t("正在自动备份…") else t("正在导出备份…")
        )
        runCatching { exportInternal(dir, auto, passphrase) }
            .onSuccess { r ->
                _state.value = _state.value.copy(
                    phase = BackupPhase.DONE,
                    message = buildString {
                        append(if (r.isAuto) t("已自动备份") else t("已导出"))
                        append(" ${r.ledgerCount} 个账本 / ${r.txnCount} 笔流水")
                        append(" → ").append(r.folderLabel)
                        if (r.prunedFiles > 0) append("（清理了 ${r.prunedFiles} 个旧文件）")
                    }
                )
                if (auto) prefs.markAutoBackupDone(System.currentTimeMillis())
                if (!auto) _state.value = _state.value.copy(lastExportAt = System.currentTimeMillis())
                refreshListInternal()
            }
            .onFailure { e ->
                _state.value = _state.value.copy(
                    phase = BackupPhase.FAILED,
                    message = e.message ?: t("导出失败，请重试")
                )
            }
    }

    /**
     * 导出核心（与 Android BackupManager.export 同名规则）：
     * 明文 = .foldledger.json + 同名 .xlsx；加密 = .foldledger.crypt 信封。
     */
    private suspend fun exportInternal(
        dir: File,
        auto: Boolean,
        passphrase: String? = null
    ): ExportOutcome = withContext(Dispatchers.IO) {
        val snapshot = repo.snapshot()
        if (snapshot.ledgerCount == 0) {
            if (auto) return@withContext ExportOutcome("", 0, 0, 0, true)
            error(t("还没有任何账本，没什么可备份的"))
        }

        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.CHINA).format(Date(snapshot.exportedAt))

        val encrypted = !auto && !passphrase.isNullOrBlank()
        val jsonName = when {
            auto -> BackupFormat.AUTO_PREFIX + stamp + BackupFormat.EXT_JSON
            encrypted -> BackupFormat.MANUAL_CRYPT_PREFIX + stamp + BackupFormat.EXT_CRYPT
            else -> "${BackupFormat.FILE_PREFIX}备份-$stamp${BackupFormat.EXT_JSON}"
        }
        val xlsxName = if (encrypted) "" else jsonName.removeSuffix(BackupFormat.EXT_JSON) + BackupFormat.EXT_XLSX

        val plainJson = BackupFormat.encode(snapshot)
        val jsonBytes = if (encrypted) {
            BackupCipher.encrypt(
                plainJson,
                passphrase!!,
                BackupCipher.EncHeader(
                    appVersion = APP_VERSION_NAME,
                    exportedAt = snapshot.exportedAt,
                    ledgerCount = snapshot.ledgerCount,
                    txnCount = snapshot.txnCount
                )
            )
        } else {
            plainJson
        }

        val excelBytes = if (encrypted) {
            ByteArray(0)
        } else {
            ExcelExporter(repo).build(snapshot.ledgers, snapshot.transactions, snapshot.projects).bytes
        }

        File(dir, jsonName).writeBytes(jsonBytes)
        if (excelBytes.isNotEmpty()) File(dir, xlsxName).writeBytes(excelBytes)

        val pruned = prune(dir)

        ExportOutcome(
            folderLabel = dir.name,
            ledgerCount = snapshot.ledgerCount,
            txnCount = snapshot.txnCount,
            prunedFiles = pruned,
            isAuto = auto
        )
    }

    /** 只清理本 App 命名规则的旧备份：自动保留 3 份、手动保留 10 份 */
    private fun prune(dir: File): Int {
        val files = dir.listFiles()?.filter { it.isFile } ?: emptyList()
        var deleted = 0

        fun dropOld(list: List<File>, keep: Int) {
            list.drop(keep).forEach { old ->
                if (old.delete()) deleted++
                val base = old.name.removeSuffix(BackupFormat.EXT_JSON)
                val xlsx = File(dir, base + BackupFormat.EXT_XLSX)
                if (xlsx.exists() && xlsx.delete()) deleted++
            }
        }

        dropOld(
            files.filter { BackupFormat.isAutoBackupName(it.name) }
                .sortedByDescending { it.lastModified() },
            KEEP_AUTO
        )
        dropOld(
            files.filter { BackupFormat.isBackupFileName(it.name) && !BackupFormat.isAutoBackupName(it.name) }
                .sortedByDescending { it.lastModified() },
            KEEP_MANUAL
        )
        return deleted
    }

    private suspend fun doImport() {
        val dir = folderPath?.let { File(it) } ?: return
        _state.value = _state.value.copy(phase = BackupPhase.WORKING, message = t("正在查找备份…"))
        runCatching { listBackups(dir) }
            .onSuccess { list ->
                if (list.isEmpty()) {
                    _state.value = _state.value.copy(
                        phase = BackupPhase.FAILED,
                        message = t("备份目录里没找到「滴水记账」的备份文件，先导出一次吧")
                    )
                } else {
                    _state.value = _state.value.copy(
                        backups = list,
                        phase = BackupPhase.IDLE,
                        message = "",
                        pickingRestore = false
                    )
                }
            }
            .onFailure { e ->
                _state.value = _state.value.copy(
                    phase = BackupPhase.FAILED,
                    message = e.message ?: t("读取备份目录失败")
                )
            }
    }

    private suspend fun doRestore(
        entry: BackupEntry,
        passphrase: String? = null,
        asNew: Boolean = false
    ) {
        val dir = folderPath?.let { File(it) } ?: return
        _state.value = _state.value.copy(phase = BackupPhase.WORKING, message = t("正在恢复…"))
        runCatching {
            // 覆盖恢复前先留一份当前现场；追加恢复不动现有数据，不用留
            if (!asNew && !repo.isDatabaseEmpty()) {
                runCatching { exportInternal(dir, auto = true) }
            }
            val snapshot = readBackup(dir, entry.fileName, passphrase)
            if (asNew) repo.restoreAsNewLedgers(snapshot) else repo.restoreFrom(snapshot)
        }
            .onSuccess { stats ->
                _state.value = _state.value.copy(
                    phase = BackupPhase.DONE,
                    message = if (asNew) {
                        "已作为新账本恢复 ${stats.ledgerCount} 个账本 / ${stats.txnCount} 笔流水，原有数据未改动"
                    } else {
                        "已恢复 ${stats.ledgerCount} 个账本 / ${stats.categoryCount} 个分类 / ${stats.txnCount} 笔流水"
                    },
                    restoredTick = _state.value.restoredTick + 1
                )
                refreshListInternal()
            }
            .onFailure { e ->
                _state.value = _state.value.copy(
                    phase = BackupPhase.FAILED,
                    message = "恢复失败：${e.message ?: "备份文件可能已损坏"}"
                )
            }
    }

    private suspend fun refreshListInternal() {
        val dir = folderPath?.let { File(it) } ?: run {
            _state.value = _state.value.copy(backups = emptyList())
            return
        }
        runCatching { listBackups(dir) }
            .onSuccess { _state.value = _state.value.copy(backups = it) }
    }

    // ---------------- 文件层（File 版 BackupManager） ----------------

    private data class Summary(
        val exportedAt: Long,
        val ledgerCount: Int,
        val txnCount: Int,
        val appVersion: String
    )

    private suspend fun listBackups(dir: File): List<BackupEntry> = withContext(Dispatchers.IO) {
        val files = dir.listFiles()?.filter { it.isFile } ?: emptyList()
        files.asSequence()
            .filter { BackupFormat.isBackupFileName(it.name) }
            .mapNotNull { f ->
                val head = runCatching {
                    f.inputStream().use { input ->
                        val buf = ByteArray(512)
                        val n = input.read(buf)
                        if (n <= 0) null else buf.copyOf(n)
                    }
                }.getOrNull()

                val isEnc = head != null && BackupCipher.isEncrypted(head)
                val summary = head?.let { h ->
                    if (isEnc) {
                        BackupCipher.peekHeader(h)?.let { Summary(it.exportedAt, it.ledgerCount, it.txnCount, it.appVersion) }
                    } else {
                        BackupFormat.peekHeader(h)?.let { Summary(it.exportedAt, it.ledgerCount, it.txnCount, it.appVersion) }
                    }
                }

                if (summary == null) {
                    return@mapNotNull BackupEntry(
                        docId = f.name,
                        fileName = f.name,
                        savedAt = f.lastModified(),
                        ledgerCount = 0,
                        txnCount = 0,
                        appVersion = "",
                        sizeBytes = f.length(),
                        isAuto = BackupFormat.isAutoBackupName(f.name),
                        corrupt = true
                    )
                }
                BackupEntry(
                    docId = f.name,
                    fileName = f.name,
                    savedAt = if (summary.exportedAt > 0) summary.exportedAt else f.lastModified(),
                    ledgerCount = summary.ledgerCount,
                    txnCount = summary.txnCount,
                    appVersion = summary.appVersion,
                    sizeBytes = f.length(),
                    isAuto = BackupFormat.isAutoBackupName(f.name),
                    encrypted = isEnc
                )
            }
            .sortedByDescending { it.savedAt }
            .toList()
    }

    private suspend fun readBackup(
        dir: File,
        fileName: String,
        passphrase: String? = null
    ): BackupSnapshot = withContext(Dispatchers.IO) {
        val file = File(dir, fileName)
        if (!file.isFile) error(t("读取备份文件失败"))
        val bytes = file.readBytes()
        val plaintext = if (BackupCipher.isEncrypted(bytes)) {
            if (passphrase.isNullOrBlank()) error(t("这份备份已加密，需要输入密码才能恢复"))
            try {
                BackupCipher.decrypt(bytes, passphrase)
            } catch (e: IllegalStateException) {
                error(e.message ?: t("备份密码不正确，无法解密"))
            }
        } else {
            bytes
        }
        if (!DripCore.validateBackup(plaintext)) {
            error("这份备份文件损坏或不是滴水记账的备份，已拒绝恢复")
        }
        BackupFormat.decode(plaintext)
    }

    private companion object {
        /** 与 Android BackupManager 的 KEEP_AUTO / KEEP_MANUAL 保持一致：自动 3 份、手动 10 份 */
        const val KEEP_AUTO = 3
        const val KEEP_MANUAL = 10
    }
}

@Composable
actual fun rememberBackupViewModel(): BackupViewModel = remember { DesktopBackupViewModel() }
