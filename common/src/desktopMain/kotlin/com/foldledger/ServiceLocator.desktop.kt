package com.foldledger

import com.foldledger.data.db.AppDatabase
import com.foldledger.data.prefs.AppPreferences
import com.foldledger.data.repo.LedgerRepository
import com.foldledger.data.sync.SyncEngine
import com.foldledger.data.sync.SyncManager
import com.foldledger.platform.createAppDatabase
import com.foldledger.platform.createPreferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 极简依赖容器（桌面实现）。数据落在 ~/.foldledger/。
 */
actual object ServiceLocator {

    @Volatile
    private var initialized = false

    private lateinit var _db: AppDatabase

    private lateinit var _repository: LedgerRepository

    private lateinit var _preferences: AppPreferences

    private lateinit var _syncManager: SyncManager

    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    actual val repository: LedgerRepository get() = _repository

    actual val preferences: AppPreferences get() = _preferences

    actual val db: AppDatabase get() = _db

    actual val syncManager: SyncManager get() = _syncManager

    actual fun init() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            _db = createAppDatabase()
            _repository = LedgerRepository(_db)
            _preferences = AppPreferences(createPreferencesDataStore())
            _syncManager = SyncManager(_repository, _preferences, SyncEngine(_db), syncScope)
            // 异步恢复同步状态（prefs 是异步 DataStore）
            syncScope.launch { _syncManager.initFromPrefs() }
            initialized = true
        }
    }
}
