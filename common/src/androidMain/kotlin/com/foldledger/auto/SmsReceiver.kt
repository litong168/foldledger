package com.foldledger.auto

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.foldledger.ServiceLocator
import com.foldledger.util.PaymentParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 银行动账短信捕获（v8.1 自动记账）。
 *
 * 短信是另一条"看得见付款"的通道（需要 READ_SMS/RECEIVE_SMS 运行时权限）。
 * v10.6：解析结果全部落 [AutoDiag]，能看到"这条银行为什么没记"。
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        ServiceLocator.attach(context.applicationContext)
        ServiceLocator.init()

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        // 长短信按 PDU 分段，拼起来再解析
        val body = messages.joinToString("") { it.messageBody ?: "" }
        if (body.isBlank()) return

        val sender = messages.firstOrNull()?.displayOriginatingAddress ?: ""
        val outcome = PaymentParser.parseSmsOutcome(body)
        val now = System.currentTimeMillis()

        // onReceive 只有 10 秒预算，丢到 IO 上写库
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val enabled = ServiceLocator.preferences.settings.first().autoBookkeepingEnabled
                if (!enabled) return@launch

                when (outcome) {
                    is PaymentParser.ParseOutcome.Rejected -> {
                        // v12.1：只记和钱有关的（验证码/营销短信不占日志容量）
                        if (!PaymentParser.looksMoneyRelated(sender, body)) return@launch
                        AutoDiag.record(
                            context.applicationContext,
                            AutoDiag.Entry(
                                at = now,
                                channel = "短信",
                                pkg = sender,
                                title = sender,
                                text = body,
                                accepted = false,
                                amountCents = null,
                                direction = null,
                                note = outcome.reason
                            )
                        )
                    }

                    is PaymentParser.ParseOutcome.Accepted -> {
                        val p = outcome.payment.copy(atMillis = now)
                        val id = ServiceLocator.repository.recordAutoPayment(p)
                        AutoDiag.record(
                            context.applicationContext,
                            AutoDiag.Entry(
                                at = now,
                                channel = "短信",
                                pkg = sender,
                                title = sender,
                                text = body,
                                accepted = id != null,
                                amountCents = p.amountCents,
                                direction = p.direction,
                                note = if (id != null) "已入账·银行短信" else "重复已跳过",
                                txnId = id
                            )
                        )
                        if (id != null) {
                            AutoBookkeepingNotifier.notify(
                                context = context.applicationContext,
                                direction = p.direction,
                                amountCents = p.amountCents,
                                merchant = p.merchant,
                                source = p.source
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                // 写库/发通知异常不能炸进程
                AutoDiag.record(
                    context.applicationContext,
                    AutoDiag.Entry(
                        at = now,
                        channel = "短信",
                        pkg = sender,
                        title = sender,
                        text = body,
                        accepted = false,
                        amountCents = null,
                        direction = null,
                        note = "异常:${e.javaClass.simpleName}"
                    )
                )
            } finally {
                pending.finish()
            }
        }
    }
}
