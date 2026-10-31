package com.foldledger

import android.app.Application
import android.content.Context
import com.foldledger.data.db.AppDatabase
import com.foldledger.data.prefs.AppPreferences
import com.foldledger.data.repo.LedgerRepository

/**
 * 极简依赖容器。刻意不引入 Hilt —— 这个体量的应用用对象持有点即可，
 * 也避免注解处理器叠加带来的编译负担。
 */
object ServiceLocator {

    @Volatile
    private var initialized = false

    lateinit var repository: LedgerRepository
        private set

    lateinit var preferences: AppPreferences
        private set

    /** 应用级 Context，供需要 ContentResolver / SAF 的模块使用 */
    lateinit var appContext: Context
        private set

    val db: AppDatabase get() = AppDatabase.get(appContext)

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            appContext = context.applicationContext
            repository = LedgerRepository(AppDatabase.get(appContext))
            preferences = AppPreferences(appContext)
            initialized = true
        }
    }
}

class FoldLedgerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
    }
}
