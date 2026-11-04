package com.foldledger.ui.vm

import androidx.compose.runtime.Composable
import com.foldledger.data.backup.RecycledBackup
import kotlinx.coroutines.flow.Flow

/**
 * 回收站里「备份文件」那一类的平台实现。
 * Android 用 SAF + BackupManager 把删除的备份搬进私有目录再还原；桌面端删除为直接删文件（无回收站）。
 */
interface BackupRecycler {
    /** 回收站里的备份文件 */
    val backups: Flow<List<RecycledBackup>>

    /** 备份目录（还原备份文件时要用） */
    val backupTreeUri: Flow<String?>

    suspend fun restoreBackup(item: RecycledBackup, treeUri: String?): Boolean

    suspend fun purgeBackup(item: RecycledBackup)
}

@Composable
expect fun rememberBackupRecycler(): BackupRecycler
