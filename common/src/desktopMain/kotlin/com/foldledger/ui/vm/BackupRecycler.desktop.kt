package com.foldledger.ui.vm

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.foldledger.data.backup.RecycledBackup
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** 桌面端回收站未实现：删除备份直接删文件（无 SAF 回收站），此桩保持空。 */
private object DesktopBackupRecycler : BackupRecycler {
    override val backups: Flow<List<RecycledBackup>> = emptyFlow()
    override val backupTreeUri: Flow<String?> = emptyFlow()
    override suspend fun restoreBackup(item: RecycledBackup, treeUri: String?): Boolean = false
    override suspend fun purgeBackup(item: RecycledBackup) {}
}

@Composable
actual fun rememberBackupRecycler(): BackupRecycler = remember { DesktopBackupRecycler }
