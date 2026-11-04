package com.foldledger.ui.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foldledger.data.backup.RecycledBackup
import com.foldledger.data.db.GoalEntity
import com.foldledger.data.db.LedgerEntity
import com.foldledger.data.repo.LedgerRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 全局回收站（v9.4）——**最终后悔药**。
 *
 * 三类东西进同一个回收站：
 *  1. **账本**（v8.1 就有软删，回收站升级成全局页）
 *  2. **存钱目标**（v9.4 新增软删位）
 *  3. **备份文件**（v9.4：删除前先把内容搬进 App 私有目录，再删原文件；平台实现见 [BackupRecycler]）
 *
 * 每一类都能**还原**或**彻底铲除**（铲除要二次确认，不可逆）。
 */
class RecycleBinViewModel(
    private val repo: LedgerRepository,
    private val recycler: BackupRecycler
) : ViewModel() {

    /** 回收站里的账本 */
    val ledgers: StateFlow<List<LedgerEntity>> =
        repo.trashedLedgers().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** 回收站里的存钱目标 */
    val goals: StateFlow<List<GoalEntity>> =
        repo.trashedGoals().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** 回收站里的备份文件 */
    val backups: StateFlow<List<RecycledBackup>> =
        recycler.backups.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** 备份目录（还原备份文件时要用） */
    val backupTreeUri: StateFlow<String?> =
        recycler.backupTreeUri.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // ---- 账本 ----

    fun restoreLedger(id: Long) = viewModelScope.launch { repo.restoreLedger(id) }

    fun purgeLedger(id: Long) = viewModelScope.launch { repo.purgeLedger(id) }

    fun purgeAllLedgers() = viewModelScope.launch { repo.purgeTrashed() }

    // ---- 存钱目标 ----

    fun restoreGoal(id: Long) = viewModelScope.launch { repo.restoreGoal(id) }

    fun purgeGoal(id: Long) = viewModelScope.launch { repo.purgeGoal(id) }

    // ---- 备份文件 ----

    fun restoreBackup(item: RecycledBackup, treeUri: String?, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            onDone(recycler.restoreBackup(item, treeUri))
        }
    }

    fun purgeBackup(item: RecycledBackup) = viewModelScope.launch { recycler.purgeBackup(item) }
}
