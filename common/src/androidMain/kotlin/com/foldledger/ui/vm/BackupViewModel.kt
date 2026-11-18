package com.foldledger.ui.vm

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.foldledger.ServiceLocator
import com.foldledger.data.backup.BackupEntry
import com.foldledger.data.backup.BackupManager
import com.foldledger.data.prefs.AppPreferences
import com.foldledger.data.repo.LedgerRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import com.foldledger.ui.l10n.t

/**
 * Android 备份视图模型：SAF 目录授权 + 文件备份/恢复。
 * 双端共享的 API 面见 [BackupViewModel]（commonMain）。
 */
class AndroidBackupViewModel(
    private val appContext: Context,
    private val repo: LedgerRepository = ServiceLocator.repository,
    private val prefs: AppPreferences = ServiceLocator.preferences
) : ViewModel(), BackupViewModel {

    private val manager = BackupManager(appContext, repo)

    private val _state = MutableStateFlow(BackupUiState())
    override val state: StateFlow<BackupUiState> = _state.asStateFlow()

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
    override fun requestExport() {
        if (treeUri == null) {
            _state.value = _state.value.copy(needsFolderFor = BackupAction.EXPORT)
        } else {
            _state.value = _state.value.copy(pendingExportPassword = true)
        }
    }

    /** 用户在导出对话框里确认：密码留空 = 明文导出（含 Excel 对账表），填了 = 加密导出 */
    override fun confirmExport(password: String) {
        _state.value = _state.value.copy(pendingExportPassword = false)
        viewModelScope.launch { doExport(auto = false, passphrase = password.ifBlank { null }) }
    }

    override fun cancelExportPassword() {
        _state.value = _state.value.copy(pendingExportPassword = false)
    }

    /** 用户点了「从备份恢复」 */
    override fun requestImport() = runAction(BackupAction.IMPORT)

    /** 更换备份目录（v16.2.7）：不管当前有没有授权，都重新弹一次 SAF 选择器 */
    override fun requestFolderChange() {
        _state.value = _state.value.copy(
            needsFolderFor = BackupAction.CHANGE_FOLDER,
            phase = BackupPhase.IDLE,
            message = ""
        )
    }

    /** Android 没有稳定的t("用文件管理器打开某个 SAF 目录")入口 → 交回 false，UI 隐藏这个按钮 */
    override fun openFolder(): Boolean = false

    override fun setAutoBackupEnabled(enabled: Boolean) {
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

    override fun onFolderGranted(uri: String?) {
        val parsed = uri?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: run {
            onFolderPickCancelled()
            return
        }
        val pending = _state.value.needsFolderFor
        if (!manager.takePermission(parsed)) {
            _state.value = _state.value.copy(
                needsFolderFor = null,
                phase = BackupPhase.FAILED,
                message = t("没能取得该文件夹的长期读写权限，请换一个文件夹（建议选「下载」）")
            )
            return
        }
        treeUri = parsed
        val label = manager.folderLabel(parsed)
        _state.value = _state.value.copy(
            needsFolderFor = null,
            folderReady = true,
            folderLabel = label
        )
        viewModelScope.launch {
            prefs.setBackupFolder(parsed.toString(), label)
            when (pending) {
                BackupAction.IMPORT -> doImport()
                BackupAction.EXPORT -> _state.value = _state.value.copy(pendingExportPassword = true)
                BackupAction.ENABLE_AUTO -> doExport(auto = true)
                // 只是换目录：刷一次列表，并明确回执 —— 不然用户不知道换成了没有
                BackupAction.CHANGE_FOLDER -> {
                    refreshListInternal()
                    _state.value = _state.value.copy(
                        phase = BackupPhase.DONE,
                        message = "备份位置已改为：$label，之后都往这里写"
                    )
                }
                null -> refreshList()
            }
        }
    }

    override fun onFolderPickCancelled() {
        _state.value = _state.value.copy(
            needsFolderFor = null,
            phase = BackupPhase.IDLE,
            message = ""
        )
    }

    override fun refreshList() {
        viewModelScope.launch { refreshListInternal() }
    }

    /** App 冷启动时调用：已授权且有数据就静默备份一次 */
    override fun autoBackupOnLaunch() {
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

    override fun cancelPendingRestore() {
        _state.value = _state.value.copy(
            pendingRestore = null,
            pendingRestorePassphrase = null,
            phase = BackupPhase.IDLE,
            message = ""
        )
    }

    /** 用户从备份列表里指定某一份要恢复 */
    override fun requestRestoreFrom(entry: BackupEntry) {
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

    override fun requestDelete(entry: BackupEntry) {
        _state.value = _state.value.copy(pendingDelete = entry, phase = BackupPhase.IDLE, message = "")
    }

    override fun cancelDelete() {
        _state.value = _state.value.copy(pendingDelete = null)
    }

    override fun confirmDelete() {
        val entry = _state.value.pendingDelete ?: return
        val uri = treeUri
        _state.value = _state.value.copy(pendingDelete = null)
        if (uri == null) return
        _state.value = _state.value.copy(phase = BackupPhase.WORKING, message = t("正在删除…"))
        viewModelScope.launch {
            runCatching { manager.deleteBackup(uri, entry) }
                .onSuccess { ok ->
                    _state.value = _state.value.copy(
                        phase = if (ok) BackupPhase.DONE else BackupPhase.FAILED,
                        message = if (ok) "已删除备份：${entry.fileName}" else t("删除失败，可能文件已被移除")
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
        viewModelScope.launch { doRestore(entry, pass, asNew) }
    }

    /**
     * 用户输入密码后**不直接恢复**（v16.2.7）：
     * 先把密码暂存，接着弹「覆盖 / 作为新账本」的选择框 —— 加密备份同样要能选恢复方式。
     */
    override fun confirmRestorePassword(password: String) {
        val entry = _state.value.pendingRestorePassword ?: return
        _state.value = _state.value.copy(
            pendingRestorePassword = null,
            pendingRestore = entry,
            pendingRestorePassphrase = password.ifBlank { null },
            pickingRestore = false
        )
    }

    override fun cancelRestorePassword() {
        _state.value = _state.value.copy(pendingRestorePassword = null)
    }

    // ---------------- 选哪一份来恢复 ----------------

    override fun cancelPick() {
        _state.value = _state.value.copy(pickingRestore = false)
    }

    /** 「用最新的一份」快捷项 */
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
            BackupAction.CHANGE_FOLDER -> _state.value =
                _state.value.copy(needsFolderFor = BackupAction.CHANGE_FOLDER)
        }
    }

    private suspend fun doExport(auto: Boolean, passphrase: String? = null) {
        val uri = treeUri ?: return
        _state.value = _state.value.copy(phase = BackupPhase.WORKING, message = if (auto) t("正在自动备份…") else t("正在导出备份…"))
        runCatching { manager.export(uri, auto, passphrase) }
            .onSuccess { r ->
                _state.value = _state.value.copy(
                    phase = BackupPhase.DONE,
                    message = buildString {
                        append(if (auto) t("已自动备份") else t("已导出"))
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
     * 「从备份恢复」：不再默默挑最新的一份，而是把目录里所有备份摆出来让用户自己选。
     * 只有一份的时候选择器里也就一行，成本很低；有多份时这才是对的交互。
     */
    private suspend fun doImport() {
        val uri = treeUri ?: return
        _state.value = _state.value.copy(phase = BackupPhase.WORKING, message = t("正在查找备份…"))
        runCatching { manager.listBackups(uri) }
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
                        pickingRestore = true
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
        val uri = treeUri ?: return
        _state.value = _state.value.copy(phase = BackupPhase.WORKING, message = t("正在恢复…"))
        runCatching {
            // 覆盖恢复前先把「当前现场」写成自动备份，万一恢复错了还能退回来；
            // 追加恢复不动现有数据，不需要这一步。
            if (!asNew && !repo.isDatabaseEmpty()) {
                runCatching { manager.export(uri, auto = true) }
            }
            val snapshot = manager.readBackup(uri, entry.docId, passphrase)
            if (asNew) repo.restoreAsNewLedgers(snapshot) else manager.restore(snapshot)
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
        val uri = treeUri ?: run {
            _state.value = _state.value.copy(backups = emptyList())
            return
        }
        runCatching { manager.listBackups(uri) }
            .onSuccess { _state.value = _state.value.copy(backups = it) }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer { AndroidBackupViewModel(ServiceLocator.appContext) }
        }
    }
}

@Composable
actual fun rememberBackupViewModel(): BackupViewModel =
    // 必须显式传类型参数：返回类型是 interface（BackupViewModel 不是 ViewModel 子类），
    // 否则 reified 推断回退成 kotlin.Any，InitializerViewModelFactory 匹配不到 initializer，
    // 首帧组合即抛 "No initializer set for given class kotlin.Any"（release 必现崩溃）。
    viewModel<AndroidBackupViewModel>(factory = AndroidBackupViewModel.Factory)
