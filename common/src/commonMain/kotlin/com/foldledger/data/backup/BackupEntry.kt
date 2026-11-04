package com.foldledger.data.backup

/**
 * 备份目录里的一份备份（v15.1 起含加密/损坏标记）。
 *
 * 从 BackupManager 抽出到 commonMain：备份列表 UI（BackupSection / BackupPickerOverlay）
 * 与同步引擎都要读它，而 SAF 读写留在 androidMain 的 BackupManager。
 */
data class BackupEntry(
    val docId: String,
    val fileName: String,
    val savedAt: Long,
    val ledgerCount: Int,
    val txnCount: Int,
    val appVersion: String,
    val sizeBytes: Long,
    val isAuto: Boolean,
    /** v15.1：这份备份是否加密（恢复时需输入密码） */
    val encrypted: Boolean = false,
    /** v15：摘要都读不出来 —— 文件损坏或不是本 App 的备份。可见但禁用恢复 */
    val corrupt: Boolean = false
)
