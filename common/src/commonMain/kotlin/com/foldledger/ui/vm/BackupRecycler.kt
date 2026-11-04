package com.foldledger.ui.vm

import androidx.compose.runtime.Composable
import com.foldledger.data.backup.RecycledBackup
import kotlinx.coroutines.flow.Flow

/**
 * 回收站里「备份文件」那一类的平台实现。
 * 双端都是「删除前先留副本」：Android 走 SAF + BackupManager，桌面走普通文件搬运。
 * 清单统一存 DataStore（[RecycledBackupCodec]），与 Room / 备份 JSON 无关。
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
