package com.foldledger.util

import com.foldledger.data.db.Direction
import java.time.LocalDate
import java.time.ZoneId

/**
 * 自然语言速记解析（v8.5）。
 *
 * 一句话记账的核心：**把「昨天打车35」「星巴克32.5」「10月3号房租3000」这种话
 * 直接变成一笔流水**。省掉"选分类、点日期、输金额"三件事。
 *
 * 解析四件事：**金额 · 日期 · 方向 · 分类线索**，剩下的文本留作备注。
 * 规矩跟自动记账一样——**宁缺毋滥**：金额提不出来就返回 null（不猜），
 * 分类认不出就留空（让人在确认卡里选），绝不硬塞一个错的分类。
 */
object NLParser {

    /** 解析出的一笔账 */
    data class NlIntent(
        val direction: Int,
        val amountCents: Long,
        /** 发生时间（已落到当天/当天的某个时段） */
        val dateMillis: Long,
        /** 分类线索（如"交通"），可能为 null —— 认不出就让用户选 */
        val categoryHint: String?,
        /** 备注：去掉金额/日期后的剩余文本 */
        val note: String,
        /** 0f..1f：金额+分类都命中才高 */
        val confidence: Float
    )

    // 分类线索表：关键词 → 建议分类名（按顺序匹配，先命中先用）
    private val CATEGORY_HINTS = listOf(
        // 交通
        "打车" to "交通", "滴滴" to "交通", "出租车" to "交通", "网约车" to "交通",
        "高铁" to "交通", "火车票" to "交通", "机票" to "交通", "飞机" to "交通",
        "地铁" to "交通", "公交" to "交通", "加油" to "交通", "停车" to "交通",
        "过路费" to "交通", "etc" to "交通", "etc卡" to "交通", "单车" to "交通",
        // 餐饮
        "外卖" to "餐饮", "午餐" to "餐饮", "晚餐" to "餐饮", "早餐" to "餐饮",
        "吃饭" to "餐饮", "午饭" to "餐饮", "晚饭" to "餐饮", "夜宵" to "餐饮",
        "麦当劳" to "餐饮", "肯德基" to "餐饮", "星巴克" to "餐饮", "瑞幸" to "餐饮",
        "奶茶" to "餐饮", "咖啡" to "餐饮", "食堂" to "餐饮", "聚餐" to "餐饮",
        "火锅" to "餐饮", "烧烤" to "餐饮", "面条" to "餐饮", "面馆" to "餐饮",
        // 买菜 / 日用
        "超市" to "买菜", "买菜" to "买菜", "菜市场" to "买菜", "生鲜" to "买菜",
        "水果" to "买菜", "蔬菜" to "买菜", "日用品" to "日用品", "纸巾" to "日用品",
        "洗发水" to "日用品", "牙膏" to "日用品", "洗衣液" to "日用品",
        // 居家
        "房租" to "居家", "物业" to "居家", "水费" to "居家", "电费" to "居家",
        "燃气" to "居家", "水费电费" to "居家", "取暖" to "居家", "物业费" to "居家",
        // 通讯
        "话费" to "通讯网费", "流量" to "通讯网费", "宽带费" to "通讯网费", "充值" to "通讯网费",
        "会员" to "娱乐", "订阅" to "娱乐", "游戏" to "娱乐", "电影" to "娱乐",
        "健身" to "娱乐", "演唱会" to "娱乐",
        // 医疗
        "药" to "医疗买药", "看病" to "医疗买药", "医院" to "医疗买药", "挂号" to "医疗买药",
        "体检" to "医疗买药", "牙医" to "医疗买药",
        // 服饰 / 学习 / 人情
        "衣服" to "服饰", "鞋" to "服饰", "裤子" to "服饰", "优衣库" to "服饰",
        "书" to "学习", "课程" to "学习", "学费" to "学习", "培训" to "学习",
        "红包" to "人情往来", "份子钱" to "人情往来", "请客" to "人情往来",
        // 宠物
        "猫粮" to "宠物", "狗粮" to "宠物", "猫砂" to "宠物", "宠物医院" to "宠物",
        // 收入
        "工资" to "其他收入", "薪水" to "其他收入", "奖金" to "其他收入",
        "报销" to "其他收入", "退款" to "其他收入", "红包收到" to "其他收入",
        // 特殊
        "还花呗" to "还款", "信用卡还款" to "还款", "房贷" to "还款", "车贷" to "还款"
    )

    // 方向线索
    private val INCOME_WORDS = listOf(
        "工资", "薪水", "奖金", "收入", "收到", "收款", "红包", "报销", "退款",
        "返现", "利息", "分红", "转我", "入账", "到账", "卖了", "赚了"
    )
    private val EXPENSE_WORDS = listOf(
        "花", "付", "买", "充", "缴", "交", "打车", "请客", "还", "转给", "消费",
        "点", "订", "送", "捐", "报名"
    )

    // 中文数字
    private val CN_DIGITS = mapOf(
        '零' to 0, '〇' to 0, '一' to 1, '壹' to 1, '二' to 2, '两' to 2, '贰' to 2,
        '三' to 3, '叁' to 3, '四' to 4, '肆' to 4, '五' to 5, '伍' to 5,
        '六' to 6, '陆' to 6, '七' to 7, '柒' to 7, '八' to 8, '捌' to 8,
        '九' to 9, '玖' to 9
    )
    private val CN_UNITS = mapOf(
        '十' to 10, '拾' to 10, '百' to 100, '佰' to 100, '千' to 1000, '仟' to 1000,
        '万' to 10000, '萬' to 10000
    )

    // 阿拉伯数字（带小数）
    private val RE_ARABIC = Regex("""(\d{1,7}(?:\.\d{1,2})?)\s*(?:元|块钱|块|钱|圆|币|¥|￥)""")
    private val RE_ARABIC_BARE = Regex("""(?<![\d.])(\d{1,7}(?:\.\d{1,2})?)(?![\d.])""")
    // 中文数字（万级大数）带单位："六千五百八十九元"
    private val RE_CN_NUMBER = Regex("""([零〇一壹二两贰三叁四肆五伍六陆七柒八捌九玖十拾百佰千仟万萬]{1,12})\s*(?:元|块钱|块|钱|圆|币)""")
    // "三块五" = 3.5；整数部分可带十百千万（"三十五块六" = 35.6，缺单位字符会错位成"五块六"）
    private val RE_CN_DECIMAL = Regex("""([零〇一壹二两贰三叁四肆五伍六陆七柒八捌九玖十拾百佰千仟万萬]{1,4})\s*(?:块|元|钱)\s*([零〇一壹二两贰三叁四肆五伍六陆七柒八捌九玖]{1,2})""")
    // v15：口语读法"六千五百八十九点八四元" —— 整数部分（万级）+ 点 + 1~2 位小数
    private val RE_CN_POINT = Regex("""([零〇一壹二两贰三叁四肆五伍六陆七柒八捌九玖十拾百佰千仟万萬]{1,12})\s*[点點]\s*([零〇一壹二两贰三叁四肆五伍六陆七柒八捌九玖]{1,2})\s*(?:元|块钱|块|钱|圆|币)?""")
    // 裸中文数字（"花了一百二"，没带单位）
    private val RE_CN_BARE = Regex("""([零〇一壹二两贰三叁四肆五伍六陆七柒八捌九玖十拾百佰千仟万萬]{1,12})""")

    private val DATE_PATTERNS: List<Pair<Regex, (MatchResult, LocalDate) -> LocalDate>> = listOf(
        Regex("大前天") to { _, d -> d.minusDays(3) },
        Regex("前天") to { _, d -> d.minusDays(2) },
        Regex("昨天|昨儿|昨晚") to { _, d -> d.minusDays(1) },
        Regex("今天|今日|今儿|今晚") to { _, d -> d },
        Regex("""(上|这|本|下)?(?:周|星期|礼拜)([一二三四五六日天1-7])?""") to { m, d ->
            weekAnchor(d, m.groupValues.getOrNull(2), m.groupValues[1])
        },
        Regex("""(\d{1,2})月(\d{1,2})[号日]""") to { m, d ->
            val mo = m.groupValues[1].toIntOrNull() ?: return@to d
            val day = m.groupValues[2].toIntOrNull() ?: return@to d
            if (mo !in 1..12 || day !in 1..31) d
            else runCatching {
                val date = LocalDate.of(d.year, mo, day)
                // v9.7.1：「12月25号房租」在 1 月说，指的是**去年**12 月，
                // 而不是 11 个月后的未来。未来超过 30 天一律回滚一年。
                if (date.isAfter(d.plusDays(30))) date.minusYears(1) else date
            }.getOrDefault(d)
        },
        Regex("""(\d{1,2})[号日]""") to { m, d ->
            val day = m.groupValues[1].toIntOrNull() ?: return@to d
            if (day !in 1..31) d
            else runCatching { LocalDate.of(d.year, d.monthValue, day) }.getOrDefault(d)
        }
    )

    private val WEEKDAY_MAP = mapOf(
        "一" to 1, "二" to 2, "三" to 3, "四" to 4, "五" to 5, "六" to 6, "日" to 7, "天" to 7,
        "1" to 1, "2" to 2, "3" to 3, "4" to 4, "5" to 5, "6" to 6, "7" to 7
    )

    /**
     * 解析一句话。
     *
     * @return 解析不出金额时返回 null（不硬猜）
     */
    fun parse(
        text: String,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault()
    ): NlIntent? {
        val raw = text.trim()
        if (raw.isEmpty()) return null

        val today = LocalDate.ofInstant(java.time.Instant.ofEpochMilli(now), zone)
        val (date, dateRanges) = extractDate(raw, today)
        // 先把日期片段挖掉，免得 "10月3号" 里的 10 被当成金额
        var work = raw
        dateRanges.sortedByDescending { it.first }.forEach { r ->
            val s = r.first
            val e = r.last + 1
            if (s in 0..work.length && e in 0..work.length && e > s) {
                work = work.removeRange(s, e)
            }
        }

        val amount = extractAmount(work) ?: return null
        val direction = extractDirection(raw)
        val hint = extractCategoryHint(raw)
        val note = work
            .replace(RE_ARABIC, "")
            .replace(RE_CN_NUMBER, "")
            .replace(RE_CN_DECIMAL, "")
            .replace(RE_CN_POINT, "")
            .replace(RE_ARABIC_BARE, "")
            .replace(RE_CN_BARE, "")
            .trim()
            .trim('，', ',', '。', '.', ' ', '\t', '的', '了', '花', '付')
            .take(40)

        val millis = applyTimeOfDay(date, raw, zone)
        val confidence = when {
            hint != null && amount > 0 -> 0.9f
            hint != null -> 0.7f
            else -> 0.4f
        }
        return NlIntent(
            direction = direction,
            amountCents = amount,
            dateMillis = millis,
            categoryHint = hint,
            note = note,
            confidence = confidence
        )
    }

    // ================= 金额 =================

    /** 提取金额（分）；提不出来返回 null */
    fun extractAmount(text: String): Long? {
        // 1) "35块" "32.5元" "¥20"
        RE_ARABIC.find(text)?.let {
            val v = it.groupValues[1].toDoubleOrNull()
            if (v != null && v > 0) return toCents(v)
        }
        // 2) "三块五" = 3.5；"三块四五" = 3.45
        RE_CN_DECIMAL.find(text)?.let {
            val a = cnToInt(it.groupValues[1])
            val b = cnToInt(it.groupValues[2])
            if (a != null && b != null && a > 0) {
                val frac = if (it.groupValues[2].length == 2) b / 100.0 else b / 10.0
                return toCents(a + frac)
            }
        }
        // 2b) v15："六千五百八十九点八四元" —— 口语大数 + 中文点小数
        RE_CN_POINT.find(text)?.let {
            val whole = cnToInt(it.groupValues[1])
            val frac = cnToInt(it.groupValues[2])
            if (whole != null && whole > 0 && frac != null) {
                val f = if (it.groupValues[2].length == 2) frac / 100.0 else frac / 10.0
                return toCents(whole + f)
            }
        }
        // 3) "三十块" "两百元"
        RE_CN_NUMBER.find(text)?.let {
            val v = cnToInt(it.groupValues[1])
            if (v != null && v > 0) return v * 100
        }
        // 4) 裸数字（"打车35"）：取最后一个
        RE_ARABIC_BARE.findAll(text).lastOrNull()?.let {
            val v = it.groupValues[1].toDoubleOrNull()
            if (v != null && v > 0) return toCents(v)
        }
        // 5) 裸中文数字（"花了一百二"）
        RE_CN_BARE.findAll(text).lastOrNull()?.let {
            val v = cnToInt(it.groupValues[1])
            if (v != null && v > 0) return v * 100
        }
        return null
    }

    private fun toCents(v: Double): Long = Math.round(v * 100).toLong()

    /**
     * 中文数字 → 阿拉伯数字。
     *
     * 支持：一二三…十、十一、二十、二十三、一百、一百二十、两百五（口语 250）、一千三百。
     * 口语省略："一百二" = 120。
     */
    fun cnToInt(s: String): Long? {
        if (s.isBlank()) return null
        // 纯数字序列（无任何单位）：「四五」=45、「八四」=84、「四零五」=405。
        // 中文小数的点后部分就是这种读法（"点八四" = 0.84），旧实现把两个数字
        // 当独立数字处理，算出 4 而不是 84。
        if (s.all { CN_DIGITS.containsKey(it) }) {
            var v = 0L
            for (ch in s) v = v * 10 + CN_DIGITS.getValue(ch).toLong()
            return v
        }
        var total = 0L
        var section = 0L
        var number = 0L
        var lastUnit = 0L
        var any = false
        for (ch in s) {
            when {
                CN_DIGITS.containsKey(ch) -> {
                    number = CN_DIGITS.getValue(ch).toLong()
                    any = true
                    // 「零」断开口语省略：一万零二 = 10002，不是 12000
                    if (number == 0L) lastUnit = 0L
                }
                CN_UNITS.containsKey(ch) -> {
                    val unit = CN_UNITS.getValue(ch).toLong()
                    any = true
                    lastUnit = unit
                    section += (if (number == 0L) 1L else number) * unit
                    number = 0L
                    if (unit >= 1000L) {
                        total += section
                        section = 0L
                    }
                }
                else -> return null
            }
        }
        if (!any) return null
        // 口语省略：结尾单个数字按它前面那个单位回缩一档
        // 「一万二」=12000（万→×1000）、「五千八」=5800（千→×100）、「三百五」=350（百→×10）
        val tailMultiplier = when (lastUnit) {
            10000L -> 1000L
            1000L -> 100L
            100L -> 10L
            else -> 1L
        }
        val result = total + section + number * tailMultiplier
        return if (result > 0) result else null
    }

    // ================= 日期 =================

    /** 返回（日期, 文本中被日期占用的区间列表） */
    private fun extractDate(text: String, today: LocalDate): Pair<LocalDate, List<IntRange>> {
        for ((re, resolve) in DATE_PATTERNS) {
            val m = re.find(text) ?: continue
            val date = resolve(m, today)
            if (date != today || m.value.isNotEmpty()) {
                return date to listOf(m.range)
            }
        }
        return today to emptyList()
    }

    /**
     * 周几锚点（v9.7.1 重写）。
     *
     * 口径：
     *  - 裸「周三」= **最近一次**（含今天）：今天周三说"周三"= 今天；今天周四说"周三"= 昨天。
     *  - 「这周三 / 本周三」= **本周的周三**（可能在未来，符合口语："这周五聚一下"）。
     *  - 「上周三」= 上周三；「下周三」= 下周三。
     *  - 只说了「上周 / 这周 / 下周」没带周几：分别回退 7 天 / 今天 / 前进 7 天。
     *
     * ⚠️ 修掉的 bug：「周末」「下周」这类**不带周几**的说法原来一律被 `coerceAtLeast(1)`
     * 推成 7 天前 —— "周末看电影80" 会记到上周同一天。
     */
    private fun weekAnchor(today: LocalDate, weekdayRaw: String?, prefix: String): LocalDate {
        val monday = today.with(java.time.DayOfWeek.MONDAY)
        val w = weekdayRaw?.let { WEEKDAY_MAP[it] }
        if (w == null) {
            return when (prefix) {
                "上" -> today.minusWeeks(1)
                "下" -> today.plusWeeks(1)
                "这", "本" -> today
                else -> today // 裸"周"无周几，按今天处理，别乱推 7 天
            }
        }
        val target = monday.plusDays((w - 1).toLong())
        return when (prefix) {
            "上" -> target.minusWeeks(1)
            "下" -> target.plusWeeks(1)
            "这", "本" -> target
            // 裸「周三」：取最近一次（今天或之前）
            else -> if (target.isAfter(today)) target.minusWeeks(1) else target
        }
    }

    /** 时段修饰：早上/中午/下午/晚上 */
    private fun applyTimeOfDay(date: LocalDate, text: String, zone: ZoneId): Long {
        val hour = when {
            text.contains("早上") || text.contains("上午") -> 9
            text.contains("中午") -> 12
            text.contains("下午") -> 15
            text.contains("晚上") || text.contains("傍晚") || text.contains("夜里") -> 20
            text.contains("凌晨") -> 2
            else -> 12
        }
        return date.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
    }

    // ================= 方向 / 分类 =================

    fun extractDirection(text: String): Int {
        val income = INCOME_WORDS.any { text.contains(it) }
        val expense = EXPENSE_WORDS.any { text.contains(it) }
        return when {
            income && !expense -> Direction.INCOME
            income && expense -> {
                // 两个都有：看谁靠前（"收到红包又花了20" 靠前的更像主语）
                val i = INCOME_WORDS.map { text.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: Int.MAX_VALUE
                val e = EXPENSE_WORDS.map { text.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: Int.MAX_VALUE
                if (i <= e) Direction.INCOME else Direction.EXPENSE
            }
            else -> Direction.EXPENSE
        }
    }

    /** 分类线索（可能为 null） */
    fun extractCategoryHint(text: String): String? {
        val lower = text.lowercase()
        for ((kw, cat) in CATEGORY_HINTS) {
            if (lower.contains(kw.lowercase())) return cat
        }
        return null
    }
}
