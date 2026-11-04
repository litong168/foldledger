package com.foldledger.platform

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.foldledger.ServiceLocator

/**
 * M4.5 Android 后台同步：WorkManager 周期调度，App 进程被杀也能同步。
 *
 * 只做事的两类时机：同步已启用且已注册。失败返回 retry（WorkManager 会按
 * 退避策略重试）；未启用/未注册直接成功退出（下次状态变化由
 * [scheduleBackgroundSync] 重新 enqueue/cancel）。
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val mgr = ServiceLocator.syncManager
        val st = mgr.state.value
        if (!st.enabled || st.deviceId <= 0L) return Result.success()
        mgr.syncNow()
        return if (mgr.state.value.lastError == null) Result.success() else Result.retry()
    }
}
