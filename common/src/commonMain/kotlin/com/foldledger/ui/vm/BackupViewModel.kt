package com.foldledger.ui.vm

import androidx.compose.runtime.Composable
import com.foldledger.data.backup.BackupEntry
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.foldledger.ui.l10n.t

/** 需要先拿到目录授权才能继续的动作 */
enum class BackupAction { EXPORT, IMPORT, ENABLE_AUTO, CHANGE_FOLDER }

enum class BackupPhase { IDLE, WORKING, DONE, FAILED }

data class BackupUiState(
    val phase: BackupPhase = BackupPhase.IDLE,
    val message: String = "",
    /** 已授权的备份目录显示名，null 表示还没授权（或重装后失效） */
    val folderLabel: String? = null,
    val folderReady: Boolean = false,
    val autoBackupEnabled: Boolean = true,
    val lastAutoBackupAt: Long = 0L,
    /** 本次运行里最后一次**手动导出**的时间（只在内存里，重启就清零） */
    val lastExportAt: Long = 0L,
    val backups: List<BackupEntry> = emptyList(),
    /** 非 null 时，UI 应弹出系统目录选择器 */
    val needsFolderFor: BackupAction? = null,
    /** 非 null 时，UI 应弹出「确认恢复」对话框（覆盖 / 作为新账本） */
    val pendingRestore: BackupEntry? = null,
    /** 恢复加密备份时，用户在密码框里输的密码暂存在这里，等确认框选完方式再执行（只在内存里） */
    val pendingRestorePassphrase: String? = null,
    /** 当前平台是否支持t("打开备份文件夹")（桌面 true，Android false） */
    val canOpenFolder: Boolean = false,
    /** true 时，UI 应弹出「选哪一份备份来恢复」的选择器 */
    val pickingRestore: Boolean = false,
    /** 非 null 时，UI 应弹出「确认删除备份」对话框 */
    val pendingDelete: BackupEntry? = null,
    /** true 时，UI 应弹出「导出备份是否加密」的密码对话框 */
    val pendingExportPassword: Boolean = false,
    /** 非 null 时，UI 应弹出「输入备份密码」对话框（恢复加密备份用） */
    val pendingRestorePassword: BackupEntry? = null,
    /** 每次恢复成功 +1，用于让外层重置选中态 */
    val restoredTick: Int = 0
)

/**
 * 备份视图模型的双端共享 API 面。
 *
 * - Android 实现（AndroidBackupViewModel）：SAF 目录授权 + 文件备份/恢复；
 * - 桌面实现（desktopMain）：JFileChooser 选目录 + 文件备份/恢复，格式与 Android 互通。
 * UI（AppRoot / BackupSection / BackupPickerOverlay）只依赖这个接口。
 */
interface BackupViewModel {
    val state: StateFlow<BackupUiState>

    fun requestExport()

    fun confirmExport(password: String)

    fun cancelExportPassword()

    fun requestImport()

    fun setAutoBackupEnabled(enabled: Boolean)

    /** 目录授权回调；uri 为平台目录标识（Android=SAF tree uri 字符串，桌面暂不用） */
    fun onFolderGranted(uri: String?)

    fun onFolderPickCancelled()

    fun refreshList()

    /** App 冷启动时调用：已授权且有数据就静默备份一次 */
    fun autoBackupOnLaunch()

    fun cancelPendingRestore()

    fun requestRestoreFrom(entry: BackupEntry)

    fun requestDelete(entry: BackupEntry)

    fun cancelDelete()

    fun confirmDelete()

    /**
     * 执行恢复。
     *
     * @param asNew true = 备份内容作为**新账本**追加，当前数据一条不动；
     *              false = 覆盖当前数据（恢复前会自动留一份自动备份）。
     */
    fun confirmRestore(asNew: Boolean)

    /**
     * 更换备份目录（v16.2.7）。
     *
     * 以前一旦选定目录就再也没有入口改 —— 目录被删、想从 D 盘换到 U 盘、或者手滑选错，
     * 都只能卸载重装。Android 走 SAF 重新授权，桌面走 JFileChooser。
     */
    fun requestFolderChange()

    /**
     * 用系统文件管理器打开备份所在文件夹（桌面支持；Android 返回 false，UI 据此隐藏入口）。
     * 导出的文件到底落在哪儿，用户最想干的就是"直接看一眼"。
     */
    fun openFolder(): Boolean

    fun confirmRestorePassword(password: String)

    fun cancelRestorePassword()

    fun cancelPick()

    fun pickLatest()

    fun resetMessage()

    companion object {
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

/** 平台各自的 BackupViewModel 实例（Android=viewModel()；桌面=remember 单例）。 */
@Composable
expect fun rememberBackupViewModel(): BackupViewModel
