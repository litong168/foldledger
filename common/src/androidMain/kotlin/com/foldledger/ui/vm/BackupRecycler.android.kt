package com.foldledger.ui.vm

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.foldledger.ServiceLocator
import com.foldledger.data.backup.BackupManager
import com.foldledger.data.backup.RecycledBackup
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Android 实现：SAF + BackupManager（把删除的备份搬进私有目录，还原时再搬回）。 */
private class AndroidBackupRecycler : BackupRecycler {
    private val repo = ServiceLocator.repository
    private val manager = BackupManager(ServiceLocator.appContext, repo)

    override val backups: Flow<List<RecycledBackup>> = manager.recycledBackups()

    override val backupTreeUri: Flow<String?> =
        ServiceLocator.preferences.settings.map { it.backupTreeUri }

    override suspend fun restoreBackup(item: RecycledBackup, treeUri: String?): Boolean {
        val uri = treeUri?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: return false
        return manager.restoreRecycled(uri, item)
    }

    override suspend fun purgeBackup(item: RecycledBackup) {
        manager.purgeRecycled(item)
    }
}

@Composable
actual fun rememberBackupRecycler(): BackupRecycler = remember { AndroidBackupRecycler() }
