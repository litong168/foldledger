package com.foldledger.util

import com.foldledger.data.db.Direction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 支付通知/银行短信解析（v8.1 自动记账）。
 *
 * 两条底线：
 *  1. 真实支付样本必须认出来（漏记=功能没用）；
 *  2. 聊天/推送绝不能认成账（记错=污染账目，比漏记更伤）。
 */
class PaymentParserTest {

    @Test
    fun wechatPaymentNotificationIsParsed() {
        val p = PaymentParser.parseNotification(
            pkg = "com.tencent.mm",
            title = "微信支付",
            text = "微信支付凭证｜付款成功 - ¥25.00",
            postTime = 1_760_000_000_000
        )
        assertNotNull(p)
        assertEquals(Direction.EXPENSE, p!!.direction)
        assertEquals(2_500L, p.amountCents)
        assertEquals("微信支付", p.source)
        assertEquals(1_760_000_000_000L, p.atMillis)
    }

    @Test
    fun alipayPaymentWithMerchantIsParsed() {
        val p = PaymentParser.parseNotification(
            pkg = "com.eg.android.AlipayGphone",
            title = "支付宝",
            text = "支付成功 付款给星巴克 人民币32.50元",
            postTime = 1L
        )
        assertNotNull(p)
        assertEquals(3_250L, p!!.amountCents)
        assertEquals("支付宝", p.source)
        assertEquals("星巴克", p.merchant)
    }

    @Test
    fun bankSmsExpenseIsParsed() {
        val p = PaymentParser.parseSms(
            "您尾号1234的储蓄卡账户10月21日12:00支出人民币123.45元，余额5000.00元。【招商银行】"
        )
        assertNotNull(p)
        assertEquals(Direction.EXPENSE, p!!.direction)
        assertEquals(12_345L, p.amountCents)
        assertEquals("银行短信", p.source)
        assertTrue(p.merchant.contains("1234"))
    }

    @Test
    fun bankSmsIncomeIsParsed() {
        val p = PaymentParser.parseSms(
            "您尾号5678的储蓄卡账户10月21日15:30收入人民币8,000.00元。【招商银行】"
        )
        assertNotNull(p)
        assertEquals(Direction.INCOME, p!!.direction)
        assertEquals(800_000L, p.amountCents)
    }

    @Test
    fun refundIsIncomeNotExpense() {
        // 文本里同时有「支付」和「退款」——应判收入
        val p = PaymentParser.parseNotification(
            pkg = "com.tencent.mm",
            title = "微信支付",
            text = "退款成功 您在美团的订单已退款 ¥18.00",
            postTime = 1L
        )
        assertNotNull(p)
        assertEquals(Direction.INCOME, p!!.direction)
    }

    @Test
    fun chatMessageIsNotParsed() {
        // 微信聊天里发「明天吃饭200元」绝不能变成一条账
        assertNull(
            PaymentParser.parseNotification(
                pkg = "com.tencent.mm",
                title = "张三",
                text = "明天吃饭200元，你先垫一下",
                postTime = 1L
            )
        )
    }

    @Test
    fun randomPushIsNotParsed() {
        assertNull(
            PaymentParser.parseNotification(
                pkg = "com.taobao.taobao",
                title = "淘宝",
                text = "双11预售开启，爆款直降",
                postTime = 1L
            )
        )
        assertNull(PaymentParser.parseSms("【中国移动】您的话费余额不足，请及时充值"))
    }

    @Test
    fun amountWithoutPaymentHintIsRejected() {
        // 有金额但不像钱的消息 —— 不记
        assertNull(PaymentParser.parseSms("快递已放驿站 取件码 203 元丰"))
    }

    @Test
    fun garbageAmountsAreRejected() {
        assertNull(PaymentParser.extractAmount("价格面议"))
        assertEquals(100L, PaymentParser.extractAmount("支付1元"))
        // 离谱大数不是正常支付
        assertNull(PaymentParser.extractAmount("支付10000000000元"))
    }

    @Test
    fun merchantPatternsExtractName() {
        assertEquals("全家便利店", PaymentParser.extractMerchant("支付成功，付款给全家便利店 25元"))
        assertEquals("滴滴出行", PaymentParser.extractMerchant("商户：滴滴出行 支付 30元"))
        assertNull(PaymentParser.extractMerchant("支出 100元"))
    }

    @Test
    fun looksLikePaymentGate() {
        assertTrue(PaymentParser.looksLikePayment("微信支付成功 30元"))
        assertTrue(PaymentParser.looksLikePayment("尾号1234的储蓄卡支出"))
        assertFalse(PaymentParser.looksLikePayment("今天天气不错"))
    }

    // ---- v10.3.1 回归：用户实报的两类脏数据 ----

    @Test
    fun loanPromoSmsIsRejected() {
        // 银行贷款推广短信不能入账（含营销词直接拒）
        assertNull(PaymentParser.parseSms("【XX银行】恭喜您获得30000元消费贷额度，年利率3.6%，点击领取"))
        assertNull(PaymentParser.parseSms("【XX银行】您有50000元备用金可借，免息30天，立即申请"))
        assertNull(PaymentParser.parseSms("【XX银行】您的信用额度已提升至80000元，点击链接查看"))
    }

    @Test
    fun payeeContextIsExpenseNotIncome() {
        // 「收款方」是对方（我付给别人），不是我的收入
        val p = PaymentParser.parseSms("您向张三转账支出人民币100元，收款方：张三")
        assertNotNull(p)
        assertEquals(com.foldledger.data.db.Direction.EXPENSE, p!!.direction)
        assertEquals(10000L, p.amountCents)
    }

    @Test
    fun transferToOthersWithArrivalIsExpenseNotIncome() {
        // "向X转账…已到账"里的「到账」是对方收到钱，不是我的收入
        val p = PaymentParser.parseSms("您尾号1234账户向李四转账100元已到账")
        assertNotNull(p)
        assertEquals(com.foldledger.data.db.Direction.EXPENSE, p!!.direction)
    }

    @Test
    fun transferDoesNotProduceDualRecords() {
        // 同一笔转账的两条通知（银行短信 + App 推送）都该判支出，
        // 这样去重才能只留一笔 —— 原来一条支出一条收入，去重不过就记两笔
        val sms = PaymentParser.parseSms("您尾号1234账户支出人民币100元")
        val notif = PaymentParser.parseNotification(
            pkg = "com.tencent.mm",
            title = "微信支付",
            text = "你向张三转账100元已到账",
            postTime = 1L
        )
        assertNotNull(sms)
        assertNotNull(notif)
        // 两条都是 EXPENSE、同额 → 去重会丢弃第二条
        assertEquals(com.foldledger.data.db.Direction.EXPENSE, sms!!.direction)
        assertEquals(com.foldledger.data.db.Direction.EXPENSE, notif!!.direction)
        assertEquals(sms.amountCents, notif.amountCents)
    }

    @Test
    fun largeAmountWithoutCommaIsNotTruncated() {
        // v10.3 P0：无千分位大额不能截断（1234.56 曾被记成 123）
        assertEquals(123456L, PaymentParser.extractAmount("支出人民币1234.56元"))
        assertEquals(123456L, PaymentParser.extractAmount("[¥￥]1234.56".replace("[¥￥]", "¥")))
        assertEquals(100000L, PaymentParser.extractAmount("支付1000元"))
    }

    // ---- v10.4.1 回归：「一条支出一条收入」顽疾 ----

    @Test
    fun expenseWordBeatsWeakIncomeWord() {
        // "付款成功…已到账"含「到账」但是支出确认，不能判收入
        assertEquals(
            com.foldledger.data.db.Direction.EXPENSE,
            PaymentParser.extractDirection("付款成功，100元已到账")
        )
        assertEquals(
            com.foldledger.data.db.Direction.EXPENSE,
            PaymentParser.extractDirection("您尾号1234账户支出人民币100元，到账时间15:00")
        )
        assertEquals(
            com.foldledger.data.db.Direction.EXPENSE,
            PaymentParser.extractDirection("消费50元已到账商户已确认")
        )
    }

    @Test
    fun strongIncomeBeatsExpenseWord() {
        // "退款至原支付方式"含「支付」但本质是收入
        assertEquals(
            com.foldledger.data.db.Direction.INCOME,
            PaymentParser.extractDirection("退款成功100元至原支付方式")
        )
        assertEquals(
            com.foldledger.data.db.Direction.INCOME,
            PaymentParser.extractDirection("工资收入人民币5000元已入账")
        )
    }

    @Test
    fun weakIncomeOnlyStillIncome() {
        // 只有到账没有支出词 = 对方打给我
        assertEquals(
            com.foldledger.data.db.Direction.INCOME,
            PaymentParser.extractDirection("您收到转账100元已到账")
        )
    }

    // ---- v10.6：窄判重写（用户实报 京东不记 / 营销短信混入） ----

    @Test
    fun jdNotificationWithoutUnitIsParsed() {
        // 京东订单类通知常见"实付 89.00"没有单位 —— 已知支付 App 应放行
        val p = PaymentParser.parseNotification(
            pkg = "com.jingdong.app.mall",
            title = "京东",
            text = "您的订单已支付成功，实付 89.00",
            postTime = 1L
        )
        assertNotNull(p)
        assertEquals(8900L, p!!.amountCents)
        assertEquals(com.foldledger.data.db.Direction.EXPENSE, p.direction)
        assertEquals("京东", p.source)
    }

    @Test
    fun unknownAppBareAmountIsRejected() {
        // 未知 App 的无单位数字不认（防止订单号被当金额）
        assertNull(
            PaymentParser.parseNotification(
                pkg = "com.example.unknown",
                title = "某商城",
                text = "您的订单已提交 20251026",
                postTime = 1L
            )
        )
    }

    @Test
    fun marketingSmsWithPaymentWordIsRejected() {
        // 有"支付"但没有成交动词 → 营销，不记
        assertNull(PaymentParser.parseSms("【XX银行】使用信用卡支付立减20元，可享6期免息"))
        assertNull(PaymentParser.parseSms("【XX银行】您的信用卡额度已提升至80000元，点击查看详情"))
        assertNull(PaymentParser.parseSms("【XX商城】下单立享支付优惠，点击领取红包"))
    }

    @Test
    fun realPaymentWithDiscountStillParsed() {
        // 真实支付带优惠词也应记（成交动词在）
        val p = PaymentParser.parseSms("您尾号1234的储蓄卡支付成功人民币88.00元，已优惠2元")
        assertNotNull(p)
        assertEquals(8800L, p!!.amountCents)
    }

    @Test
    fun spamMarkersRejectBeforeAmount() {
        assertTrue(PaymentParser.looksLikeSpam("恭喜您获得30000元额度"))
        assertFalse(PaymentParser.looksLikeSpam("您尾号1234的储蓄卡消费成功人民币50元"))
    }

    // ============================================================
    //  v12.1：真机日志暴露的误记 / 漏记
    // ============================================================

    @Test
    fun creditLimitAdjustmentSmsIsRejected() {
        // 2026-10-28 真机日志里唯一一条误记：
        // "可用**消费额度**"里的"消费"被当成成交动词 → 记成 82000 元支出。
        assertNull(
            PaymentParser.parseSms(
                "尊敬的客户，您个人信用卡账户固定额度调整为82000元，" +
                    "当前人民币可用消费额度82000.00元。【浦发银行】"
            )
        )
    }

    @Test
    fun creditCardBillReminderIsRejected() {
        // 账单提醒说的也是"钱"，但钱没动
        assertNull(
            PaymentParser.parseSms(
                "您尾号1234的信用卡本期账单金额5,280.00元，最低还款528.00元，到期还款日11月05日。"
            )
        )
    }

    @Test
    fun failedOrPendingPaymentIsRejected() {
        assertNull(PaymentParser.parseSms("您尾号1234账户支付失败，金额100.00元，余额不足。"))
        assertNull(
            PaymentParser.parseNotification(
                pkg = "com.eg.android.AlipayGphone",
                title = "支付宝",
                text = "支付失败 金额 100.00 元",
                postTime = 1L
            )
        )
        assertNull(PaymentParser.parseSms("您的退款处理中，金额59.00元将原路退回。"))
    }

    @Test
    fun paidAmountBeatsOrderAmount() {
        // 先出现的 ¥100 是订单金额，真正划走的是实付 ¥80
        val p = PaymentParser.parseNotification(
            pkg = "com.jingdong.app.mall",
            title = "京东",
            text = "订单金额 ¥100.00，优惠 ¥20.00，实付 ¥80.00，支付成功",
            postTime = 1L
        )
        assertNotNull(p)
        assertEquals(8_000L, p!!.amountCents)
    }

    @Test
    fun realBankSmsWithBalanceWordStillParses() {
        // 反向保护：非交易闸门不能把带"余额"的真实动账短信一起拦掉。
        // （"余额"这个词故意不写进 NON_TXN_MARKERS —— 它在真实交易里太常见了）
        val p = PaymentParser.parseSms(
            "您尾号1234的储蓄卡10月28日15:30消费支出人民币268.00元，余额1,200.00元。【招商银行】"
        )
        assertNotNull(p)
        assertEquals(26_800L, p!!.amountCents)
        assertEquals(Direction.EXPENSE, p.direction)
    }

    @Test
    fun weakVerbNeedsAdjacentAmount() {
        // "消费"后面跟的是"场景"不是数字 → 只是名词，不是成交
        assertFalse(PaymentParser.isTransaction("本周消费场景推荐"))
        assertTrue(PaymentParser.isTransaction("消费 88.00 元"))
        assertTrue(PaymentParser.isTransaction("扣款成功"))
    }

    @Test
    fun appOwnBookkeepingNoticeIsRejected() {
        // 京东 App 自带的"记账成功"通知不是我的钱动了。
        // 库里曾出现过一条 0.10 元的这种记录（note 里还重复了两遍）。
        assertNull(
            PaymentParser.parseNotification(
                pkg = "com.jingdong.app.mall",
                title = "京东记账",
                text = "您在京东记账成功0.10元",
                postTime = 1L
            )
        )
    }

    @Test
    fun creditCardRepaymentIsExpense() {
        val p = PaymentParser.parseNotification(
            pkg = "com.eg.android.AlipayGphone",
            title = "支付宝",
            text = "信用卡还款成功 ¥1,000.00",
            postTime = 1L
        )
        assertNotNull(p)
        assertEquals(Direction.EXPENSE, p!!.direction)
        assertEquals(100_000L, p.amountCents)
    }

    // ---------------- v12.1.1：捕获日志降噪口径 ----------------

    @Test
    fun wechatChatMessageIsNotMoneyRelated() {
        // 微信聊天和微信支付共用 com.tencent.mm —— 不能按包名直接放行日志，
        // 否则一条群消息就能把"记错的那条"挤出日志。
        assertFalse(
            PaymentParser.looksMoneyRelated(
                "com.tencent.mm",
                "梁旭 华住会 尊敬的宾客您好，方便的时候麻烦给个好评"
            )
        )
    }

    @Test
    fun wechatPayNoticeIsMoneyRelated() {
        assertTrue(
            PaymentParser.looksMoneyRelated(
                "com.tencent.mm",
                "微信支付凭证：已支付 12.34 元，收款方 便利蜂"
            )
        )
    }

    @Test
    fun systemNoiseIsNotMoneyRelated() {
        assertFalse(PaymentParser.looksMoneyRelated("android", "正在通过 USB 充电"))
        assertFalse(PaymentParser.looksMoneyRelated("com.milink.service", "UniversalClipboardService"))
        assertFalse(PaymentParser.looksMoneyRelated("com.xiaomi.smarthome", "有人出现"))
    }

    // ---------------- v15：促销组合词 / 应用商店推送 ----------------

    @Test
    fun marketingLixiaPaymentIsRejected() {
        // 「支付立减20」里"支付"紧邻数字会命中弱动词闸门 —— 必须靠营销词拦下
        assertNull(
            PaymentParser.parseNotification(
                pkg = "com.tencent.mm",
                title = "微信支付",
                text = "使用微信支付立减20元，速抢",
                postTime = 1L
            )
        )
        assertNull(PaymentParser.parseSms("【XX商城】全场满减，支付即享8折优惠，限今天"))
    }

    @Test
    fun appStorePushIsRejected() {
        // 真机日志：抖音商城"有新版本啦～"只有"无成交动词"挡着 ——
        // 显式加进非交易闸门，判得更早、日志原因也更清楚
        assertNull(
            PaymentParser.parseNotification(
                pkg = "com.ss.android.ugc.aweme",
                title = "抖音商城",
                text = "抖音商城有新版本啦～快来看看",
                postTime = 1L
            )
        )
    }

    @Test
    fun meituanPaymentNotificationIsParsed() {
        val p = PaymentParser.parseNotification(
            pkg = "com.sankuai.meituan",
            title = "美团",
            text = "支付成功 付款给肯德基宅急送 ¥32.50",
            postTime = 1L
        )
        assertNotNull(p)
        assertEquals(Direction.EXPENSE, p!!.direction)
        assertEquals(3_250L, p.amountCents)
        assertEquals("美团", p.source)
        assertEquals("肯德基宅急送", p.merchant)
    }

    @Test
    fun didiPaymentNotificationIsParsed() {
        val p = PaymentParser.parseNotification(
            pkg = "com.sdu.didi.psnger",
            title = "滴滴出行",
            text = "行程已结束，支付成功 打车费35.00元",
            postTime = 1L
        )
        assertNotNull(p)
        assertEquals(Direction.EXPENSE, p!!.direction)
        assertEquals(3_500L, p.amountCents)
    }

    @Test
    fun wechatTransferInIsIncome() {
        val p = PaymentParser.parseNotification(
            pkg = "com.tencent.mm",
            title = "微信支付",
            text = "微信支付到账 200.00 元",
            postTime = 1L
        )
        assertNotNull(p)
        assertEquals(Direction.INCOME, p!!.direction)
        assertEquals(20_000L, p.amountCents)
    }
}
