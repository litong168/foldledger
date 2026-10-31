package com.foldledger.util

import com.foldledger.data.db.Direction

/**
 * 支付通知 / 银行短信解析（v8.1 自动记账，v10.6 重构）。
 *
 * 通道：① 通知使用权（微信/支付宝/京东/银行等 App 的支付通知）
 *      ② 短信广播（银行动账短信）
 *
 * v10.6 重构要点（用户实报"时灵时不灵、营销短信混进来、京东不记"）：
 *  1. 闸门从「关键词包含」改为「营销特征排除 + 交易确认词」——
 *     "支付"两个字在"支付立减"里也有，原来会误放营销短信；
 *     现在必须出现"成功/已支付/已扣款/消费/支出/到账"这类**成交动词**才放行。
 *  2. 金额识别补"无单位"格式（"实付 89.00"），但**只对已知支付类 App 开放**，
 *    短信仍要求带 ¥/元/人民币，避免营销短信里随便一个数字被当成金额。
 *  3. 新增 [ParseOutcome] 把「拒绝了、为什么拒绝」带出来，
 *     供 [com.foldledger.auto.AutoDiag] 记录，排查不再靠猜。
 */
object PaymentParser {

    /** 解析结果：一条待入账的支付 */
    data class ParsedPayment(
        val direction: Int,
        val amountCents: Long,
        /** 对方：收款方 / 商户 / 卡尾号描述 */
        val merchant: String,
        /** 来源：微信支付 / 支付宝 / 银行短信 … */
        val source: String,
        val atMillis: Long?
    )

    /** v10.6：带拒绝原因的解析结果（诊断日志用） */
    sealed interface ParseOutcome {
        data class Accepted(val payment: ParsedPayment) : ParseOutcome
        data class Rejected(val reason: String) : ParseOutcome
    }

    // 支付通知的包名 → 来源名
    // v10.6：补京东/美团/抖音/拼多多/饿了么/滴滴等常见消费入口
    private val PKG_SOURCES = mapOf(
        "com.tencent.mm" to "微信支付",
        "com.eg.android.AlipayGphone" to "支付宝",
        "com.unionpay" to "云闪付",
        "com.tencent.mobileqq" to "QQ钱包",
        "com.jingdong.app.mall" to "京东",
        "com.jd.jrapp" to "京东金融",
        "com.sankuai.meituan" to "美团",
        "com.sankuai.meituan.takeoutnew" to "美团外卖",
        "com.ss.android.ugc.aweme" to "抖音",
        "com.xunmeng.pinduoduo" to "拼多多",
        "me.ele" to "饿了么",
        "com.sdu.didi.psnger" to "滴滴出行",
        "com.taobao.taobao" to "淘宝",
        "com.chinamworld.bocmbci" to "中国银行",
        "cmb.pb" to "招商银行",
        "com.icbc" to "工商银行",
        "com.android.bankabc" to "农业银行",
        "com.chinamworld.citic" to "中信银行",
        "com.yitong.mbank.psbc" to "邮储银行",
        "com.spdbccc" to "浦发银行",
        "cmbc.cc.mbank" to "民生银行"
    )

    /** 已知的「支付类」包名前缀集合 —— 金额可以放宽到无单位 */
    private fun isKnownPayApp(pkg: String): Boolean =
        pkg in PKG_SOURCES || pkg.startsWith("com.jd.") || pkg.startsWith("com.sankuai.")

    // ---------------- 闸门一：营销特征排除 ----------------

    /**
     * 营销/推广特征 —— 命中就拒。
     *
     * 这些词在真实成交通知里几乎不会出现，而推广短信里必然有一两个。
     * 注意「优惠/立减」这类词**不列**：真实支付通知也会写"已优惠 2 元"。
     */
    private val SPAM_MARKERS = listOf(
        "恭喜", "点击", "领取", "提额", "免息", "利率", "年化", "费率",
        "备用金", "推广", "营销", "立即申请", "可借", "可领", "额度已提升",
        "信用额度", "现金贷", "消费贷", "信用贷", "白条额度", "秒到", "极速",
        "预授信", "专属额度", "点击链接", "回复TD", "退订", "低至", "最高可",
        "可享", "办理", "申请分期", "快速到账", "免手续费", "抽奖", "福利",
        "限时", "抢购", "特惠", "折扣券", "红包雨", "点击查看", "详询",
        // v15：促销组合词 —— 单独出现可能蹭真实通知（"已优惠2元"），
        // 但跟在"支付/付款"后面就一定是营销："支付立减20"会命中弱动词闸门被误放行。
        "立减", "直降", "满减", "即送", "即享", "到手价", "返现"
    )

    /**
     * 成交确认词 —— 必须出现其一才认为"这笔钱真的动了"。
     *
     * 这是 v10.6 的核心改动：原来只要求出现"支付"二字，
     * 但"支付立减 20 元"、"支付即送积分"也会命中 → 营销短信混进账本。
     */
    private val TRANSACTION_VERBS = listOf(
        "成功", "已完成", "已支付", "已付款", "已扣款", "已消费",
        "支出", "消费", "扣款", "扣费", "付款", "收款", "到账", "入账",
        "收入", "汇入", "进账", "退款", "转入", "转出", "取现", "提现", "存入"
    )

    // ---------------- v12.1：三道新闸门（真机日志暴露的误记） ----------------

    /**
     * 非交易语义 —— 命中即拒。
     *
     * 事故来源（2026-10-28 真机日志唯一一条误记）：
     * 浦发「您个人信用卡账户**固定额度**调整为 82000 元，当前人民币可用**消费额度** 82000.00 元」
     * —— 里面那个"消费"命中了成交动词、`82000元` 命中了金额，于是被记成 8.2 万元支出。
     *
     * 这类文本的共同点：**它在说"钱有多少"，不是"钱动了"**。
     * 只列精确词组 —— 像"余额"这种在真实动账短信里也会出现的词绝不能列进来
     * （"消费支出的余额 5000 元"是正常交易）。
     */
    private val NON_TXN_MARKERS = listOf(
        // 额度 / 授信
        "额度调整", "固定额度", "可用额度", "消费额度", "取现额度", "临时额度",
        "额度已", "额度提升", "额度为", "提额", "授信", "预授信",
        // 账单 / 还款计划（账单不是交易）
        "账单提醒", "本期账单", "账单已出", "账单金额", "账单日为", "账单分期",
        "最低还款", "到期还款", "还款日", "自动还款", "应还金额", "应还款",
        // 账户安全 / 运营
        "验证码", "动态密码", "积分兑换", "积分即将", "签到", "问卷", "调查",
        // v15：应用商店 / 系统推送（"抖音商城有新版本啦～"这类，只说到货/更新，钱没动）
        "新版本", "版本更新", "安装包", "更新提示", "下载完成", "系统升级"
    )

    /**
     * 未完成 / 失败 —— 钱没有真正动。
     *
     * "支付失败"里也有"支付"、"退款处理中"里也有"退款"，
     * 所以这一层必须排在最前面：先确认"成了没成"，再谈金额与方向。
     */
    private val FAIL_MARKERS = listOf(
        "支付失败", "交易失败", "扣款失败", "付款失败", "转账失败", "充值失败",
        "余额不足", "已取消", "已关闭", "交易关闭", "支付超时",
        "待支付", "未支付", "待付款", "处理中", "受理中", "退款中", "退款处理中"
    )

    /**
     * 强成交动词 —— 出现即认为交易成立，不需要额外上下文。
     *
     * 与弱动词的分界是"这个词本身是否已经表达了完成态"：
     * "已扣款"是完成，"扣款"只是一个动作名词（"自动扣款签约"里也有）。
     */
    private val STRONG_VERBS = listOf(
        "已支付", "已付款", "已扣款", "已消费", "已入账", "已到账", "已完成",
        "退款成功", "退款已", "转账成功", "转入", "转出", "存入", "汇入",
        "取现", "提现", "入账成功", "到账成功", "扣款成功", "支付成功", "付款成功",
        // v12.1：补全"动作+成功"这一族。
        // 注意**故意不含裸"成功"** —— 京东等 App 的"记账成功"通知就是这个形状，
        // 它是 App 自己在记账，不是我的钱动了。
        "消费成功", "充值成功", "缴费成功", "还款成功", "扣费成功",
        "代扣成功", "转出成功", "提现成功", "收款成功"
    )

    /**
     * 弱动词 + 邻近金额 —— 例："消费 88.00 元"、"支出人民币 100 元"。
     *
     * 为什么必须"邻近金额"：弱动词单独出现时几乎都是名词用法
     * （消费额度、消费场景、支付方式、收款方…），只有紧跟着一个数字才是真动词。
     * 中间允许 8 个字符 —— 够覆盖"了/￥/人民币/：/约"这些连接成分。
     */
    private val WEAK_VERB_NEAR_AMOUNT = Regex(
        "(消费|支出|付款|支付|扣款|扣费|收款|缴费|收入|到账|入账|退货)[^0-9]{0,8}?\\d"
    )

    /** 和钱有关的内容特征（v12.1：诊断日志降噪用） */
    private val MONEY_HINTS = listOf(
        "¥", "￥", "元", "金额", "实付", "支付", "付款", "扣款", "扣费", "退款",
        "到账", "入账", "收入", "支出", "消费", "账单", "转账", "余额", "收款",
        "缴费", "还款", "转账"
    )

    // ---------------- 闸门二：金额 ----------------

    // 带单位的金额（短信主要走这条）
    private val AMOUNT_PATTERNS = listOf(
        Regex("""[¥￥]\s*(\d+(?:,\d{3})*(?:\.\d{1,2})?)"""),
        Regex("""人民币\s*(\d+(?:,\d{3})*(?:\.\d{1,2})?)"""),
        Regex("""(\d+(?:,\d{3})*(?:\.\d{1,2})?)\s*元""")
    )

    /**
     * 无单位金额（v10.6）：只在**已知支付类 App** 的通知里启用。
     * 必须跟前置标签一起匹配，避免订单号/时间被当成金额。
     * 例："实付 89.00" / "支付金额：89.00" / "合计 89.00"
     */
    private val AMOUNT_LABELED = listOf(
        Regex("""(?:实付|支付金额|付款金额|扣款金额|消费金额|订单金额|合计|总计|金额)\s*[:：]?\s*[¥￥]?\s*(\d+(?:,\d{3})*(?:\.\d{1,2})?)"""),
        Regex("""[¥￥]\s*(\d+(?:,\d{3})*(?:\.\d{1,2})?)""")
    )

    /**
     * v12.1：「我实际付了多少」标签 —— 优先级最高。
     *
     * "订单金额 ¥100，优惠后实付 ¥80"这种通知里，先出现的 ¥100 不是这笔的钱。
     * 只有这些标签后面的数字才是**真正从账户划走/到账**的金额。
     */
    private val AMOUNT_PAID_LABELED = listOf(
        Regex("""(?:实付|实收|支付金额|付款金额|扣款金额|消费金额|交易金额|实收金额)\s*[:：]?\s*[¥￥]?\s*(\d+(?:,\d{3})*(?:\.\d{1,2})?)""")
    )

    // 方向关键词
    // v10.4.1：拆强弱两档 —— 强收入词在任何上下文下都是收入（退款/工资），
    // 弱收入词（到账/入账/收款）碰到支出词就得让路（"付款成功…已到账"是支出确认）。
    private val STRONG_INCOME_HINTS = listOf("退款", "收入", "存入", "汇入", "转入", "已入账", "工资")
    private val WEAK_INCOME_HINTS = listOf("到账", "入账", "收款")
    private val EXPENSE_HINTS = listOf(
        "支出", "付款", "支付", "转出", "消费", "扣款", "扣费", "缴费", "取出", "汇出", "还款"
    )

    /** v15：真正在"花/扣"的动作词（不含通道名"支付"）—— 与弱收入词对撞时胜出 */
    private val REAL_EXPENSE_WORDS = listOf(
        "支出", "付款", "转出", "消费", "扣款", "扣费", "缴费", "取出", "汇出", "还款"
    )

    // 「我付给别人」的上下文 —— 这些短语里的"收款/到账"是对方收到钱，不是我的收入
    private val PAYING_OTHERS = Regex(
        "付款给|转账给|付给|收款方|收款人|转账至|转至|转到|向.{0,12}(转账|付款|汇款)"
    )

    // 对方名称模式
    private val MERCHANT_PATTERNS = listOf(
        Regex("""付款给\s*[「『]?(.{1,24}?)[」』]?(\s|$|，|,|。)"""),
        Regex("""向\s*[「『]?(.{1,24}?)[」』]?\s*付款"""),
        Regex("""收款方[:：]?\s*[「『]?(.{1,24}?)[」』]?(\s|$|，|,|。)"""),
        Regex("""商户[:：]?\s*[「『]?(.{1,24}?)[」』]?(\s|$|，|,|。)"""),
        Regex("""来自\s*[「『]?(.{1,24}?)[」』]?(\s|$|，|,|。)""")
    )

    private val CARD_TAIL = Regex("""尾号\s*(\d{3,6})""")

    /** 微信/QQ 同时承载聊天与支付，标题是唯一区分点 */
    private val PAYMENT_TITLE_HINTS = listOf(
        "支付", "付款", "收款", "转账", "账单", "交易", "银行", "钱包",
        "银联", "云闪付", "扣款", "退款", "京东", "支付宝", "微信"
    )

    // ============================================================
    //  解析入口
    // ============================================================

    /** 解析支付通知（兼容旧签名，测试与既有调用方用） */
    fun parseNotification(pkg: String, title: String, text: String, postTime: Long): ParsedPayment? =
        when (val r = parseNotificationOutcome(pkg, title, text, postTime)) {
            is ParseOutcome.Accepted -> r.payment
            is ParseOutcome.Rejected -> null
        }

    /** 解析银行动账短信（兼容旧签名） */
    fun parseSms(body: String): ParsedPayment? =
        when (val r = parseSmsOutcome(body)) {
            is ParseOutcome.Accepted -> r.payment
            is ParseOutcome.Rejected -> null
        }

    fun parseNotificationOutcome(
        pkg: String,
        title: String,
        text: String,
        postTime: Long
    ): ParseOutcome {
        val all = "$title $text".trim()
        if (all.isEmpty()) return ParseOutcome.Rejected("空内容")

        // 微信/QQ 聊天通知（标题是昵称）不放行
        val isChatApp = pkg == "com.tencent.mm" || pkg == "com.tencent.mobileqq"
        if (isChatApp && !PAYMENT_TITLE_HINTS.any { title.contains(it) }) {
            return ParseOutcome.Rejected("聊天标题")
        }

        val spam = SPAM_MARKERS.firstOrNull { all.contains(it) }
        if (spam != null) return ParseOutcome.Rejected("营销词:$spam")

        // v12.1：先判"成了没成"（"支付失败""退款处理中"里也有"支付""退款"）
        val fail = FAIL_MARKERS.firstOrNull { all.contains(it) }
        if (fail != null) return ParseOutcome.Rejected("未完成:$fail")

        // v12.1：再判"这是不是一笔交易"（额度调整 / 账单提醒只是在说"钱有多少"）
        val nonTxn = NON_TXN_MARKERS.firstOrNull { all.contains(it) }
        if (nonTxn != null) return ParseOutcome.Rejected("非交易:$nonTxn")

        if (!isTransaction(all)) return ParseOutcome.Rejected("无成交动词")

        val known = isKnownPayApp(pkg)
        val amount = extractAmountLoose(all, allowBare = known)
            ?: return ParseOutcome.Rejected("无金额")

        val direction = extractDirection(all) ?: return ParseOutcome.Rejected("方向不明")
        val source = PKG_SOURCES[pkg] ?: title.ifBlank { "支付通知" }
        return ParseOutcome.Accepted(
            ParsedPayment(
                direction = direction,
                amountCents = amount,
                merchant = extractMerchant(all) ?: source,
                source = source,
                atMillis = if (postTime > 0) postTime else null
            )
        )
    }

    fun parseSmsOutcome(body: String): ParseOutcome {
        val all = body.trim()
        if (all.isEmpty()) return ParseOutcome.Rejected("空内容")

        val spam = SPAM_MARKERS.firstOrNull { all.contains(it) }
        if (spam != null) return ParseOutcome.Rejected("营销词:$spam")

        // v12.1：先判"成了没成"（"支付失败""退款处理中"里也有"支付""退款"）
        val fail = FAIL_MARKERS.firstOrNull { all.contains(it) }
        if (fail != null) return ParseOutcome.Rejected("未完成:$fail")

        // v12.1：再判"这是不是一笔交易"（额度调整 / 账单提醒只是在说"钱有多少"）
        val nonTxn = NON_TXN_MARKERS.firstOrNull { all.contains(it) }
        if (nonTxn != null) return ParseOutcome.Rejected("非交易:$nonTxn")

        if (!isTransaction(all)) return ParseOutcome.Rejected("无成交动词")

        // 短信不放宽金额（营销短信里裸数字太多），必须带单位
        val amount = extractAmount(all) ?: return ParseOutcome.Rejected("无金额")
        val direction = extractDirection(all) ?: return ParseOutcome.Rejected("方向不明")

        val card = CARD_TAIL.find(all)?.groupValues?.get(1)
        val merchant = extractMerchant(all)
            ?: (if (card != null) "尾号${card}银行卡" else "银行短信")
        return ParseOutcome.Accepted(
            ParsedPayment(
                direction = direction,
                amountCents = amount,
                merchant = merchant,
                source = "银行短信",
                atMillis = null // 短信里的日期不可靠，用接收时间（调用方给）
            )
        )
    }

    // ============================================================
    //  判定部件（public：单测直接调）
    // ============================================================

    /** 是否含成交动词（v10.6 的宽松闸门，保留给旧调用方与单测） */
    fun hasTransactionVerb(text: String): Boolean =
        TRANSACTION_VERBS.any { text.contains(it) }

    /**
     * v12.1 严格闸门：强动词直接放行；弱动词必须紧邻一个数字。
     *
     * 这一条是这次误记的直接原因 —— 旧实现只要文本里出现"消费"就认为成交，
     * 于是"可用**消费额度** 82000 元"也被当成消费。
     */
    fun isTransaction(text: String): Boolean {
        if (STRONG_VERBS.any { text.contains(it) }) return true
        return WEAK_VERB_NEAR_AMOUNT.containsMatchIn(text)
    }

    /**
     * v12.1：这条内容是否"和钱有关"。
     *
     * 用途：诊断日志降噪。原来每个通知都记（米家设备状态、全场景音乐、
     * 通用剪贴板、USB 充电…），150 条容量几小时就被冲光，
     * 真正要排查的那条反而被挤掉了。现在只留和钱有关的。
     */
    @Suppress("UNUSED_PARAMETER")
    fun looksMoneyRelated(pkg: String, text: String): Boolean {
        // v12.1.1：**不再对已知支付 App 直接放行**。
        // 微信/支付宝的消息通道和聊天通道共用同一个包名，"梁旭 华住会 麻烦给个好评"
        // 这类群消息也会顶着 com.tencent.mm 进来 —— 一天几十条就把日志容量冲光，
        // 真正记错的那条反而被挤没了。日志只认「文本里有金额线索」。
        // 注意：这条只影响**日志**，解析判定仍是全量（广收窄判）。
        return MONEY_HINTS.any { text.contains(it) }
    }

    /** 是否看起来像营销推送 */
    fun looksLikeSpam(text: String): Boolean =
        SPAM_MARKERS.any { text.contains(it) }

    /**
     * 旧闸门（保留兼容）：是否像"钱的消息"。
     * v10.6 语义 = 非营销 且 有成交动词。
     */
    fun looksLikePayment(text: String): Boolean =
        !looksLikeSpam(text) && hasTransactionVerb(text)

    fun extractAmount(text: String): Long? {
        for (p in AMOUNT_PATTERNS) {
            val m = p.find(text) ?: continue
            val cents = Money.parseToCents(m.groupValues[1]) ?: continue
            if (cents in 1..1_000_000_000) return cents
        }
        return null
    }

    /**
     * 金额识别（v10.6）：先试带单位，再试带标签的无单位格式。
     *
     * @param allowBare 是否允许"实付 89.00"这种无单位金额 ——
     *   只对已知支付类 App 开放，短信/未知来源仍然要求带单位。
     */
    fun extractAmountLoose(text: String, allowBare: Boolean): Long? {
        // v12.1：「实付/扣款金额」优先于任何先出现的数字（订单金额、优惠前金额）
        for (p in AMOUNT_PAID_LABELED) {
            val m = p.find(text) ?: continue
            val cents = Money.parseToCents(m.groupValues[1]) ?: continue
            if (cents in 1..1_000_000_000) return cents
        }
        extractAmount(text)?.let { return it }
        if (!allowBare) return null
        for (p in AMOUNT_LABELED) {
            val m = p.find(text) ?: continue
            val cents = Money.parseToCents(m.groupValues[1]) ?: continue
            if (cents in 1..1_000_000_000) return cents
        }
        return null
    }

    /**
     * 判方向。
     *
     * 优先级：
     * 1.「付给别人」→ EXPENSE（"收款方=对方"、"向X转账已到账"是对方到账）
     * 2. 强收入词（退款/收入/存入/汇入/转入）→ INCOME
     * 3. 支出词（支出/付款/支付/消费/扣款…）→ EXPENSE
     * 4. 弱收入词（到账/入账/收款）→ INCOME
     * 5. 都没有 → null（不记）
     */
    fun extractDirection(text: String): Int? {
        if (PAYING_OTHERS.containsMatchIn(text)) return Direction.EXPENSE
        if (STRONG_INCOME_HINTS.any { text.contains(it) }) return Direction.INCOME
        // v15：「微信支付到账」「支付宝到账」里的"支付"只是通道名，不是支出动作。
        // 只要出现"到账/入账/收款"这类弱收入词、又没有真正的支出动作词（付款/消费/扣款…），
        // 就是一笔收入；有真支出词（"付款成功…已到账"）仍是支出。
        if (WEAK_INCOME_HINTS.any { text.contains(it) } &&
            !REAL_EXPENSE_WORDS.any { text.contains(it) }
        ) return Direction.INCOME
        if (EXPENSE_HINTS.any { text.contains(it) }) return Direction.EXPENSE
        if (WEAK_INCOME_HINTS.any { text.contains(it) }) return Direction.INCOME
        return null
    }

    fun extractMerchant(text: String): String? {
        for (p in MERCHANT_PATTERNS) {
            val m = p.find(text) ?: continue
            val raw = m.groupValues[1].trim().trim('「', '」', '『', '』', ':', '：', ' ', '\t')
            if (raw.isNotBlank() && !raw.all { it.isDigit() }) return raw.take(24)
        }
        return null
    }
}
