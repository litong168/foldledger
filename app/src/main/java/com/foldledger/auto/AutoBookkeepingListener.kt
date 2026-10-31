package com.foldledger.auto

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.foldledger.ServiceLocator
import com.foldledger.util.PaymentParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 支付通知监听（v8.1 自动记账）——参考小米钱包自动记账的取数思路。
 *
 * v10.6 修（用户实报"时灵时不灵 / 京东不记"）：
 *  - **去掉 `isOngoing` 一刀切**：京东/美团的订单类通知常带常驻标记，
 *    原来在这里就被丢掉，解析器根本没机会看到 → 改为只跳过系统/自身包。
 *  - **文本提取补全**：除了 BIG_TEXT/TEXT，再读 TEXT_LINES 与 MessagingStyle，
 *    有些 App 把正文塞在这些位置。
 *  - **每条都落诊断日志**（[AutoDiag]），能看到"为什么没记"。
 */
class AutoBookkeepingListener : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onListenerConnected() {
        super.onListenerConnected()
        ServiceLocator.init(applicationContext)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val pkg = sbn.packageName ?: return
        // 只跳过自身与系统 UI；不再用 isOngoing 一刀切（会误杀京东等订单类通知）。
        // 其余交给解析器判定 —— 广收窄判，比在入口拍脑袋丢更可靠。
        if (pkg == packageName) return
        if (pkg == "android") return
        if (pkg.startsWith("android.") || pkg.startsWith("com.android.systemui")) return

        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = collectText(notification) ?: ""
        if (title.isBlank() && text.isBlank()) return

        val outcome = PaymentParser.parseNotificationOutcome(
            pkg = pkg,
            title = title,
            text = text,
            postTime = sbn.postTime
        )

        scope.launch {
            runCatching {
                val enabled = ServiceLocator.preferences.settings.first().autoBookkeepingEnabled
                if (!enabled) return@launch

                // v12.1：诊断日志只留"和钱有关"的。
                // 原来每条通知都落盘，米家设备状态 / 全场景音乐 / 通用剪贴板 / USB 充电
                // 几小时就把 150 条容量冲光，真正的误记反而查不到。
                // 注意：**只降噪日志，不降噪判定** —— 解析器仍然看得到每一条。
                val moneyRelated = PaymentParser.looksMoneyRelated(pkg, "$title $text")

                when (outcome) {
                    is PaymentParser.ParseOutcome.Rejected -> {
                        if (!moneyRelated) return@launch
                        AutoDiag.record(
                            applicationContext,
                            AutoDiag.Entry(
                                at = sbn.postTime,
                                channel = "通知",
                                pkg = pkg,
                                title = title,
                                text = text,
                                accepted = false,
                                amountCents = null,
                                direction = null,
                                note = outcome.reason
                            )
                        )
                    }

                    is PaymentParser.ParseOutcome.Accepted -> {
                        val p = outcome.payment
                        val id = ServiceLocator.repository.recordAutoPayment(p)
                        AutoDiag.record(
                            applicationContext,
                            AutoDiag.Entry(
                                at = sbn.postTime,
                                channel = "通知",
                                pkg = pkg,
                                title = title,
                                text = text,
                                accepted = id != null,
                                amountCents = p.amountCents,
                                direction = p.direction,
                                note = if (id != null) "已入账·${p.source}" else "重复已跳过",
                                txnId = id
                            )
                        )
                        if (id != null) {
                            AutoBookkeepingNotifier.notify(
                                context = applicationContext,
                                direction = p.direction,
                                amountCents = p.amountCents,
                                merchant = p.merchant,
                                source = p.source
                            )
                        }
                    }
                }
            }.onFailure { e ->
                AutoDiag.record(
                    applicationContext,
                    AutoDiag.Entry(
                        at = sbn.postTime,
                        channel = "通知",
                        pkg = pkg,
                        title = title,
                        text = text,
                        accepted = false,
                        amountCents = null,
                        direction = null,
                        note = "异常:${e.javaClass.simpleName}"
                    )
                )
            }
        }
    }

    /**
     * 尽量把通知正文拼全（v10.6）。
     * 微信/支付宝在 EXTRA_BIG_TEXT，京东在 EXTRA_TEXT，
     * 部分 App 用 MessagingStyle —— 只读前两个会漏。
     */
    private fun collectText(n: Notification): String? {
        val extras = n.extras ?: return null
        val parts = mutableListOf<String>()

        // v12.1：title 不再拼进正文 —— 解析侧本来就会 "$title $text"，
        // 拼两次会让日志里出现"浦发银行 浦发银行 …"，也会干扰对方名提取。
        extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.let { parts += it }
        extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.let { parts += it }
        extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.toString()?.let { parts += it }

        // InboxStyle / MessagingStyle 的历史行
        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
        lines?.forEach { s -> s?.toString()?.let { parts += it } }

        val merged = parts.filter { it.isNotBlank() }
            .distinct()
            .joinToString(" ")
        return merged.ifBlank { null }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    companion object {
        /**
         * 请求系统重新绑定监听服务（v10.6）。
         *
         * MIUI/ColorOS 等激进后台管控会静默杀死通知监听服务，
         * 用户看到的就是"有时候记、有时候完全不记"。每次回到前台请求一次重绑，
         * 是第三方 App 能做的唯一补救（用户仍需在系统设置里允许自启动）。
         */
        fun requestRebind(context: android.content.Context) {
            runCatching {
                val cn = android.content.ComponentName(
                    context, AutoBookkeepingListener::class.java
                )
                NotificationListenerService.requestRebind(cn)
            }
        }
    }
}
