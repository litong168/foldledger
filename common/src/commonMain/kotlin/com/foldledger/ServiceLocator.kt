package com.foldledger

import com.foldledger.data.db.AppDatabase
import com.foldledger.data.prefs.AppPreferences
import com.foldledger.data.repo.LedgerRepository
import com.foldledger.data.sync.SyncManager

/**
 * 极简依赖容器（双端共享的 API 面，刻意不引入 Hilt）。
 *
 * - Android 端 actual 额外持有 [appContext]（供 SAF / WorkManager / 桌面组件使用）；
 * - 桌面端 actual 用本地路径实现（见 desktopMain）。
 * [init] 由各自平台入口调用一次（Android: Application.onCreate；桌面: main()）。
 */
expect object ServiceLocator {
    val repository: LedgerRepository
    val preferences: AppPreferences
    val db: AppDatabase
    /** M4.5 同步管理器（注册/手动与自动同步/状态），init 后可用 */
    val syncManager: SyncManager
    fun init()
}
