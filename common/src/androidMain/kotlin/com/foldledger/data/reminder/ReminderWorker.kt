package com.foldledger.data.reminder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.foldledger.R
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * 每日记账提醒（v2.1）。
 *
 * 用 WorkManager 的周期任务（最短间隔 1 天）在每天固定时间发一条本地通知，
 * 提醒用户记账。纯本地、无网络 —— 通知发出与否只取决于系统调度。
 */
object ReminderScheduler {

    private const val CHANNEL_ID = "daily_reminder"
    private const val WORK_NAME = "daily_reminder_work"

    /** 通知渠道（Android 8+ 发通知前必须先建） */
    fun createChannel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "记账提醒",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "每天定时提醒你记一笔"
        }
        nm.createNotificationChannel(channel)
    }

    /** 调度每日提醒：[hour]:[minute] 每天一次 */
    fun schedule(context: Context, hour: Int, minute: Int) {
        createChannel(context)
        val now = Calendar.getInstance()
        val next = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (before(now)) add(Calendar.DAY_OF_MONTH, 1)
        }
        val delay = next.timeInMillis - now.timeInMillis
        val request = PeriodicWorkRequestBuilder<ReminderWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    /** 是否有发通知的权限（Android 13+ 需要 POST_NOTIFICATIONS） */
    fun hasNotificationPermission(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()
}

class ReminderWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    override fun doWork(): Result {
        val ctx = applicationContext
        if (!ReminderScheduler.hasNotificationPermission(ctx)) return Result.success()

        ReminderScheduler.createChannel(ctx)
        val notification = NotificationCompat.Builder(ctx, "daily_reminder")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("滴水记账")
            .setContentText("今天记账了吗？花一分钟记下今天的收支吧")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("今天记账了吗？花一分钟记下今天的收支，滴水穿石、积少成多。")
            )
            .setAutoCancel(true)
            .build()

        runCatching {
            NotificationManagerCompat.from(ctx).notify(
                1001,
                notification
            )
        }
        return Result.success()
    }
}

/** 供调用方判断是否需要请求运行时通知权限（API 33+） */
fun needsNotificationPermission(context: Context): Boolean =
    android.os.Build.VERSION.SDK_INT >= 33 &&
        context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED
