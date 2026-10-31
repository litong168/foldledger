package com.foldledger.auto

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.foldledger.MainActivity
import com.foldledger.R

/**
 * 自动记账回执通知（v10.3）——参考小米钱包 AI 自动记账的反馈方式。
 *
 * 支付被自动记录后立刻发一条系统通知：金额 / 对方 / 来源一眼可查，
 * 用户不打开 App 也能确认「这一笔记上了」。点开进 App 做归类。
 *
 * 通知渠道独立于每日记账提醒 —— 自动记账是实时回执、可以关；
 * 每日提醒是定时催办，两者用户意图不同，不该混在一条渠道里。
 */
object AutoBookkeepingNotifier {

    private const val CHANNEL_ID = "auto_bookkeeping"
    // v10.3：ID 不用常量 —— 多笔连续记账不能互相覆盖，用时间戳区分
    private const val BASE_ID = 20000

    /** 建渠道（Android 8+ 发通知前必须先调）；重复调用是幂等的 */
    fun createChannel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "自动记账",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "支付被自动记录后的即时回执"
        }
        nm.createNotificationChannel(channel)
    }

    /** 有无通知权限（Android 13+ 需运行时授权） */
    fun canNotify(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            (
                android.os.Build.VERSION.SDK_INT < 33 ||
                    context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
                )

    /**
     * 发一条「已自动记一笔」回执。
     *
     * @param direction [com.foldledger.data.db.Direction.EXPENSE] 或 INCOME
     * @param amountCents 金额（分）
     * @param merchant 对方 / 商户 / 卡描述
     * @param source 来源：微信支付 / 银行短信 …
     */
    fun notify(
        context: Context,
        direction: Int,
        amountCents: Long,
        merchant: String,
        source: String
    ) {
        if (!canNotify(context)) return
        createChannel(context)

        val isExpense = direction == com.foldledger.data.db.Direction.EXPENSE
        val verb = if (isExpense) "支出" else "收入"
        val amount = com.foldledger.util.Money.format(amountCents)
        val line = "$verb $amount · $merchant · $source"

        // 点通知打开 App —— 用户顺手就能去「自动记账」账本确认分类
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val reqCode = (System.currentTimeMillis() % 100000).toInt()
        val pending = PendingIntent.getActivity(
            context,
            reqCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle("已自动记一笔")
            .setContentText(line)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("$line\n已存入「自动记账」账本，点开确认分类")
            )
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()

        runCatching {
            NotificationManagerCompat.from(context)
                .notify(BASE_ID + (System.currentTimeMillis() % 10000).toInt(), notification)
        }
    }
}
