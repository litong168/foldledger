package com.foldledger.ui.vm

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.foldledger.ServiceLocator
import com.foldledger.data.backup.BackupManager
import com.foldledger.data.prefs.AppPreferences
import com.foldledger.data.repo.LedgerRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 需要先拿到目录授权才能继续的动作 */
enum class BackupAction { EXPORT, IMPORT, ENABLE_AUTO }

enum class BackupPhase { IDLE, WORKING, DONE, FAILED }

data class BackupUiState(
    val phase: BackupPhase = BackupPhase.IDLE,
    val message: String = "",
    /** 已授权的备份目录显示名，null 表示还没授权（或重装后失效） */
    val folderLabel: String? = null,
    val folderReady: Boolean = false,
    val autoBackupEnabled: Boolean = true,
    val lastAutoBackupAt: Long = 0L,
    val backups: List<BackupManager.BackupEntry> = emptyList(),
    /** 非 null 时，UI 应弹出系统目录选择器 */
    val needsFolderFor: BackupAction? = null,
    /** 非 null 时，UI 应弹出「确认恢复」对话框 */
    val pendingRestore: BackupManager.BackupEntry? = null,
    /** true 时，UI 应弹出「选哪一份备份来恢复」的选择器 */
    val pickingRestore: Boolean = false,
    /** 非 null 时，UI 应弹出「确认删除备份」对话框 */
    val pendingDelete: BackupManager.BackupEntry? = null,
    /** true 时，UI 应弹出「导出备份是否加密」的密码对话框 */
    val pendingExportPassword: Boolean = false,
    /** 非 null 时，UI 应弹出「输入备份密码」对话框（恢复加密备份用） */
    val pendingRestorePassword: BackupManager.BackupEntry? = null,
    /** 每次恢复成功 +1，用于让外层重置选中态 */
    val restoredTick: Int = 0
)

class BackupViewModel(
    private val appContext: Context,
    private val repo: LedgerRepository = ServiceLocator.repository,
    private val prefs: AppPreferences = ServiceLocator.preferences
) : ViewModel() {

    private val manager = BackupManager(appContext, repo)

    private val _state = MutableStateFlow(BackupUiState())
    val state: StateFlow<BackupUiState> = _state.asStateFlow()

    private var treeUri: Uri? = null

    /** 冷启动只自动备份一次 */
    private var launchAutoBackupDone = false

    init {
        viewModelScope.launch {
            prefs.settings.collect { s ->
                treeUri = s.backupTreeUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
                val ready = treeUri?.let { manager.hasPermission(it) } ?: false
                if (!ready) treeUri = null
                _state.value = _state.value.copy(
                    folderReady = ready,
                    folderLabel = s.backupFolderName?.takeIf { ready },
                    autoBackupEnabled = s.autoBackupEnabled,
                    lastAutoBackupAt = s.lastAutoBackupAt
                )
                if (ready) refreshListInternal()

                // 「每次打开软件自动备份一次」：等授权状态确定后再触发，避免和 DataStore 竞态
                if (ready && s.autoBackupEnabled && !launchAutoBackupDone) {
                    launchAutoBackupDone = true
                    autoBackupOnLaunch()
                }
            }
        }
    }

    // ---------------- 对外动作 ----------------

    /** 用户点了「导出备份」 */
    fun requestExport() {
        if (treeUri == null) {
            _state.value = _state.value.copy(needsFolderFor = BackupAction.EXPORT)
        } else {
            _state.value = _state.value.copy(pendingExportPassword = true)
        }
    }

    /** 用户在导出对话框里确认：密码留空 = 明文导出（含 Excel 对账表），填了 = 加密导出 */
    fun confirmExport(password: String) {
        _state.value = _state.value.copy(pendingExportPassword = false)
        viewModelScope.launch { doExport(auto = false, passphrase = password.ifBlank { null }) }
    }

    fun cancelExportPassword() {
        _state.value = _state.value.copy(pendingExportPassword = false)
    }

    /** 用户点了「从备份恢复」 */
    fun requestImport() = runAction(BackupAction.IMPORT)

    fun setAutoBackupEnabled(enabled: Boolean) {
        viewModelScope.launch {
            prefs.setAutoBackupEnabled(enabled)
            if (enabled) {
                if (treeUri == null) {
                    _state.value = _state.value.copy(needsFolderFor = BackupAction.ENABLE_AUTO)
                } else {
                    doExport(auto = true)
                }
            }
        }
    }

    fun onFolderGranted(uri: Uri) {
        val pending = _state.value.needsFolderFor
        if (!manager.takePermission(uri)) {
            _state.value = _state.value.copy(
                needsFolderFor = null,
                phase = BackupPhase.FAILED,
                message = "没能取得该文件夹的长期读写权限，请换一个文件夹（建议选「下载」）"
            )
            return
        }
        treeUri = uri
        val label = manager.folderLabel(uri)
        _state.value = _state.value.copy(
            needsFolderFor = null,
            folderReady = true,
            folderLabel = label
        )
        viewModelScope.launch {
            prefs.setBackupFolder(uri.toString(), label)
            when (pending) {
                BackupAction.IMPORT -> doImport()
                BackupAction.EXPORT -> _state.value = _state.value.copy(pendingExportPassword = true)
                BackupAction.ENABLE_AUTO -> doExport(auto = true)
                null -> refreshList()
            }
        }
    }

    fun onFolderPickCancelled() {
        _state.value = _state.value.copy(
            needsFolderFor = null,
            phase = BackupPhase.IDLE,
            message = ""
        )
    }

    fun refreshList() {
        viewModelScope.launch { refreshListInternal() }
    }

    /** App 冷启动时调用：已授权且有数据就静默备份一次 */
    fun autoBackupOnLaunch() {
        viewModelScope.launch {
            val uri = treeUri ?: return@launch
            if (!_state.value.autoBackupEnabled) return@launch
            if (repo.isDatabaseEmpty()) return@launch
            runCatching { manager.export(uri, auto = true) }
                .onSuccess {
                    prefs.markAutoBackupDone(System.currentTimeMillis())
                    refreshListInternal()
                }
            // 自动备份失败保持静默，不打扰用户
        }
    }

    // ---------------- 恢复确认流 ----------------

    fun cancelPendingRestore() {
        _state.value = _state.value.copy(pendingRestore = null, phase = BackupPhase.IDLE, message = "")
    }

    /** 用户从备份列表里指定某一份要恢复 */
    fun requestRestoreFrom(entry: BackupManager.BackupEntry) {
        // v15.1：加密备份先要密码，明文备份走原有的「确认恢复」对话框
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

    fun requestDelete(entry: BackupManager.BackupEntry) {
        _state.value = _state.value.copy(pendingDelete = entry, phase = BackupPhase.IDLE, message = "")
    }

    fun cancelDelete() {
        _state.value = _state.value.copy(pendingDelete = null)
    }

    fun confirmDelete() {
        val entry = _state.value.pendingDelete ?: return
        val uri = treeUri
        _state.value = _state.value.copy(pendingDelete = null)
        if (uri == null) return
        _state.value = _state.value.copy(phase = BackupPhase.WORKING, message = "正在删除…")
        viewModelScope.launch {
            runCatching { manager.deleteBackup(uri, entry) }
                .onSuccess { ok ->
                    _state.value = _state.value.copy(
                        phase = if (ok) BackupPhase.DONE else BackupPhase.FAILED,
                        message = if (ok) "已删除备份：${entry.fileName}" else "删除失败，可能文件已被移除"
                    )
                    refreshListInternal()
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(
                        phase = BackupPhase.FAILED,
                        message = e.message ?: "删除失败"
                    )
                }
        }
    }

    fun confirmRestore() {
        val entry = _state.value.pendingRestore ?: return
        _state.value = _state.value.copy(pendingRestore = null, pickingRestore = false)
        viewModelScope.launch { doRestore(entry) }
    }

    /** 用户输入密码后确认恢复加密备份 */
    fun confirmRestorePassword(password: String) {
        val entry = _state.value.pendingRestorePassword ?: return
        _state.value = _state.value.copy(pendingRestorePassword = null)
        viewModelScope.launch { doRestore(entry, password.ifBlank { null }) }
    }

    fun cancelRestorePassword() {
        _state.value = _state.value.copy(pendingRestorePassword = null)
    }

    // ---------------- 选哪一份来恢复 ----------------

    fun cancelPick() {
        _state.value = _state.value.copy(pickingRestore = false)
    }

    /** 「用最新的一份」快捷项 */
    fun pickLatest() {
        val newest = _state.value.backups.firstOrNull()
        if (newest == null) {
            _state.value = _state.value.copy(
                pickingRestore = false,
                phase = BackupPhase.FAILED,
                message = "备份目录里没有可恢复的备份"
            )
        } else if (newest.encrypted) {
            _state.value = _state.value.copy(pendingRestorePassword = newest)
        } else {
            _state.value = _state.value.copy(pendingRestore = newest)
        }
    }

    // ---------------- 实现 ----------------

    private fun runAction(action: BackupAction) {
        val uri = treeUri
        if (uri == null) {
            _state.value = _state.value.copy(needsFolderFor = action, message = "")
            return
        }
        when (action) {
            BackupAction.EXPORT -> viewModelScope.launch { doExport(auto = false) }
            BackupAction.IMPORT -> viewModelScope.launch { doImport() }
            BackupAction.ENABLE_AUTO -> viewModelScope.launch { doExport(auto = true) }
        }
    }

    private suspend fun doExport(auto: Boolean, passphrase: String? = null) {
        val uri = treeUri ?: return
        _state.value = _state.value.copy(phase = BackupPhase.WORKING, message = if (auto) "正在自动备份…" else "正在导出备份…")
        runCatching { manager.export(uri, auto, passphrase) }
            .onSuccess { r ->
                _state.value = _state.value.copy(
                    phase = BackupPhase.DONE,
                    message = buildString {
                        append(if (auto) "已自动备份" else "已导出")
                        append(" ${r.ledgerCount} 个账本 / ${r.txnCount} 笔流水")
                        append(" → ").append(r.folderLabel)
                        if (r.prunedFiles > 0) append("（清理了 ${r.prunedFiles} 个旧文件）")
                    }
                )
                if (auto) prefs.markAutoBackupDone(System.currentTimeMillis())
                refreshListInternal()
            }
            .onFailure { e ->
                _state.value = _state.value.copy(
                    phase = BackupPhase.FAILED,
                    message = e.message ?: "导出失败，请重试"
                )
            }
    }

    /**
     * 「从备份恢复」：不再默默挑最新的一份，而是把目录里所有备份摆出来让用户自己选。
     * 只有一份的时候选择器里也就一行，成本很低；有多份时这才是对的交互。
     */
    private suspend fun doImport() {
        val uri = treeUri ?: return
        _state.value = _state.value.copy(phase = BackupPhase.WORKING, message = "正在查找备份…")
        runCatching { manager.listBackups(uri) }
            .onSuccess { list ->
                if (list.isEmpty()) {
                    _state.value = _state.value.copy(
                        phase = BackupPhase.FAILED,
                        message = "备份目录里没找到「滴水记账」的备份文件，先导出一次吧"
                    )
                } else {
                    _state.value = _state.value.copy(
                        backups = list,
                        phase = BackupPhase.IDLE,
                        message = "",
                        pickingRestore = true
                    )
                }
            }
            .onFailure { e ->
                _state.value = _state.value.copy(
                    phase = BackupPhase.FAILED,
                    message = e.message ?: "读取备份目录失败"
                )
            }
    }

    private suspend fun doRestore(entry: BackupManager.BackupEntry, passphrase: String? = null) {
        val uri = treeUri ?: return
        _state.value = _state.value.copy(phase = BackupPhase.WORKING, message = "正在恢复…")
        runCatching {
            // 恢复前先把「当前现场」写成自动备份，万一恢复错了还能退回来
            if (!repo.isDatabaseEmpty()) {
                runCatching { manager.export(uri, auto = true) }
            }
            val snapshot = manager.readBackup(uri, entry.docId, passphrase)
            manager.restore(snapshot)
        }
            .onSuccess { stats ->
                _state.value = _state.value.copy(
                    phase = BackupPhase.DONE,
                    message = "已恢复 ${stats.ledgerCount} 个账本 / ${stats.categoryCount} 个分类 / ${stats.txnCount} 笔流水",
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
        val uri = treeUri ?: run {
            _state.value = _state.value.copy(backups = emptyList())
            return
        }
        runCatching { manager.listBackups(uri) }
            .onSuccess { _state.value = _state.value.copy(backups = it) }
    }

    fun resetMessage() {
        _state.value = _state.value.copy(phase = BackupPhase.IDLE, message = "")
    }

    companion object {
        val Factory = viewModelFactory {
            initializer { BackupViewModel(ServiceLocator.appContext) }
        }

        fun formatTime(ts: Long): String {
            if (ts <= 0) return "—"
            return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(ts))
        }

        fun formatSize(bytes: Long): String = when {
            bytes >= 1024 * 1024 -> String.format(Locale.CHINA, "%.2f MB", bytes / 1048576.0)
            bytes >= 1024 -> String.format(Locale.CHINA, "%.1f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}
