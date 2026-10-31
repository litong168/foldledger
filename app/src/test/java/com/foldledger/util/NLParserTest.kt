package com.foldledger.util

import com.foldledger.data.db.Direction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * 自然语言速记解析（v8.5）。
 *
 * 口径要钉死三件事：**金额别抓错**（"10月3号"里的 10 不是金额）、
 * **日期别算错**（今天周四说"周三"= 昨天）、**方向别搞反**（收到红包是收入）。
 */
class NLParserTest {

    private val zone: ZoneId = ZoneId.systemDefault()

    /** 2026-10-22（周四）12:00 */
    private val now: Long = LocalDate.of(2026, 10, 22)
        .atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

    private fun parse(text: String) = NLParser.parse(text, now, zone)

    private fun dayOf(millis: Long): LocalDate =
        java.time.Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

    // ---------- 周 / 月 的边界（v9.7.1 修） ----------

    @Test
    fun bareWeekdayMeansMostRecent() {
        // 今天 2026-10-22 周四：说"周三"= 昨天
        assertEquals(LocalDate.of(2026, 10, 21), dayOf(parse("周三买菜30")!!.dateMillis))
    }

    @Test
    fun thisWeekPrefixMayBeInFuture() {
        // "这周三"= 本周三（2026-10-21），"这周五"= 本周五（10-23，未来）
        assertEquals(LocalDate.of(2026, 10, 21), dayOf(parse("这周三买菜30")!!.dateMillis))
        assertEquals(LocalDate.of(2026, 10, 23), dayOf(parse("这周五聚餐200")!!.dateMillis))
    }

    @Test
    fun lastAndNextWeekPrefix() {
        assertEquals(LocalDate.of(2026, 10, 14), dayOf(parse("上周三买菜30")!!.dateMillis))
        assertEquals(LocalDate.of(2026, 10, 28), dayOf(parse("下周三买菜30")!!.dateMillis))
    }

    @Test
    fun weekWithoutWeekdayDoesNotJumpBackSevenDays() {
        // 曾经的 bug："周末" 会被推成 7 天前
        assertEquals(LocalDate.of(2026, 10, 22), dayOf(parse("周末看电影80")!!.dateMillis))
        assertEquals(LocalDate.of(2026, 10, 15), dayOf(parse("上周聚会200")!!.dateMillis))
        assertEquals(LocalDate.of(2026, 10, 29), dayOf(parse("下周聚会200")!!.dateMillis))
    }

    @Test
    fun farFutureMonthDayRollsBackOneYear() {
        // 10 月说"12月25号"→ 去年 12 月，而不是两个月后
        assertEquals(LocalDate.of(2025, 12, 25), dayOf(parse("12月25号房租3000")!!.dateMillis))
        // 近未来（30 天内）不回滚
        assertEquals(LocalDate.of(2026, 11, 3), dayOf(parse("11月3号话费100")!!.dateMillis))
    }

    @Test
    fun pastMonthDayStaysThisYear() {
        assertEquals(LocalDate.of(2026, 3, 5), dayOf(parse("3月5号交房租3500")!!.dateMillis))
    }

    // ---------- 金额 ----------

    @Test
    fun bareNumberAndUnitBothWork() {
        assertEquals(3_500L, parse("昨天打车35")!!.amountCents)
        assertEquals(3_250L, parse("星巴克32.5")!!.amountCents)
        assertEquals(3_500L, parse("打车35元")!!.amountCents)
        assertEquals(300L, parse("地铁3块")!!.amountCents)
    }

    @Test
    fun chineseNumbersAreUnderstood() {
        assertEquals(3_000L, parse("三十块")!!.amountCents)
        assertEquals(1_100L, parse("十一元")!!.amountCents)
        assertEquals(2_300L, parse("二十三元")!!.amountCents)
        assertEquals(12_000L, parse("超市花了一百二")!!.amountCents) // 口语省略
        assertEquals(350L, parse("三块五")!!.amountCents)
    }

    @Test
    fun dateNumbersAreNotTakenAsAmount() {
        val i = parse("10月3号房租3000")!!
        assertEquals(300_000L, i.amountCents)
        assertEquals(LocalDate.of(2026, 10, 3), dayOf(i.dateMillis))
    }

    @Test
    fun textWithoutAmountIsRejected() {
        assertNull(parse(""))
        assertNull(parse("   "))
        assertNull(parse("明天再说"))
        assertNull(parse("abc"))
    }

    // ---------- 日期 ----------

    @Test
    fun relativeDays() {
        assertEquals(LocalDate.of(2026, 10, 22), dayOf(parse("今天买菜56.8")!!.dateMillis))
        assertEquals(LocalDate.of(2026, 10, 21), dayOf(parse("昨天打车35")!!.dateMillis))
        assertEquals(LocalDate.of(2026, 10, 20), dayOf(parse("前天加油200")!!.dateMillis))
        assertEquals(LocalDate.of(2026, 10, 19), dayOf(parse("大前天加油200")!!.dateMillis))
    }

    @Test
    fun weekdayUsesMostRecentOccurrence() {
        // 2026-10-22 是周四 → 最近一次周三 = 10-21
        assertEquals(LocalDate.of(2026, 10, 21), dayOf(parse("周三买菜45")!!.dateMillis))
        // 上周三 = 10-14
        assertEquals(LocalDate.of(2026, 10, 14), dayOf(parse("上周三看电影45")!!.dateMillis))
    }

    @Test
    fun explicitMonthDayIsAbsolute() {
        assertEquals(LocalDate.of(2026, 10, 3), dayOf(parse("10月3号房租3000")!!.dateMillis))
        // 只有日号 → 本月
        assertEquals(LocalDate.of(2026, 10, 9), dayOf(parse("9号加油200")!!.dateMillis))
    }

    @Test
    fun timeOfDayIsRefined() {
        val morning = parse("今天早上打车20")!!
        val night = parse("今天晚上打车20")!!
        assertTrue(dayOf(morning.dateMillis) == dayOf(night.dateMillis))
        assertTrue(morning.dateMillis < night.dateMillis)
    }

    // ---------- 方向 ----------

    @Test
    fun incomeVsExpense() {
        assertEquals(Direction.EXPENSE, parse("打车35")!!.direction)
        assertEquals(Direction.INCOME, parse("工资8000")!!.direction)
        assertEquals(Direction.INCOME, parse("收到红包50")!!.direction)
        assertEquals(Direction.EXPENSE, parse("还花呗2000")!!.direction)
    }

    // ---------- 分类线索 ----------

    @Test
    fun categoryHints() {
        assertEquals("交通", parse("昨天打车35")!!.categoryHint)
        assertEquals("餐饮", parse("星巴克32.5")!!.categoryHint)
        assertEquals("交通", parse("地铁3块")!!.categoryHint)
        assertEquals("买菜", parse("超市花了一百二")!!.categoryHint)
        assertEquals("居家", parse("10月3号房租3000")!!.categoryHint)
        assertEquals("通讯网费", parse("话费100")!!.categoryHint)
        assertNull(parse("xyz35")!!.categoryHint)
    }

    // ---------- 备注 ----------

    @Test
    fun noteKeepsUsefulContext() {
        assertEquals("房租", parse("10月3号房租3000")!!.note)
        assertTrue(parse("昨天打车35")!!.note.contains("打车"))
    }

    // ---------- 中文数字单元 ----------

    @Test
    fun chineseNumberConversion() {
        assertEquals(1L, NLParser.cnToInt("一"))
        assertEquals(10L, NLParser.cnToInt("十"))
        assertEquals(11L, NLParser.cnToInt("十一"))
        assertEquals(23L, NLParser.cnToInt("二十三"))
        assertEquals(100L, NLParser.cnToInt("一百"))
        assertEquals(120L, NLParser.cnToInt("一百二"))
        assertEquals(250L, NLParser.cnToInt("两百五"))
        assertEquals(1300L, NLParser.cnToInt("一千三百"))
        assertEquals(2L, NLParser.cnToInt("两"))
        // v15：万级 + 口语省略（一万二/五千八/三万五千八，零断开省略）
        assertEquals(12000L, NLParser.cnToInt("一万二"))
        assertEquals(5800L, NLParser.cnToInt("五千八"))
        assertEquals(35800L, NLParser.cnToInt("三万五千八"))
        assertEquals(6589L, NLParser.cnToInt("六千五百八十九"))
        assertEquals(10002L, NLParser.cnToInt("一万零二"))
        assertNull(NLParser.cnToInt("微信"))
    }

    // ---------------- v15：口语大数 + 中文点小数 ----------------

    @Test
    fun spokenLargeNumberWithPointDecimal() {
        // 「六千五百八十九点八四元」：语音念法，原来解析失败（「点」不在任何规则里）
        assertEquals(658_984L, parse("六千五百八十九点八四元")!!.amountCents)
        // 「三万五千八百元」= 35800 元（分值为 3_580_000）
        assertEquals(3_580_000L, parse("三万五千八百元")!!.amountCents)
    }

    @Test
    fun chinesePointDecimalVariants() {
        assertEquals(350L, parse("三块五")!!.amountCents)
        // 两位小数：三块四五 = 3.45（旧实现误算成 7.5）
        assertEquals(345L, parse("三块四五")!!.amountCents)
        // 整数部分带单位：打车三十五块六 = 35.6（旧实现错位匹配成"五块六"=5.6）
        assertEquals(3_560L, parse("打车三十五块六")!!.amountCents)
    }

    @Test
    fun wanLevelParsing() {
        // 「一万二」= 12000 元（分值为 1_200_000）
        assertEquals(1_200_000L, parse("一万二打车")!!.amountCents)
        assertEquals(580_000L, parse("五千八百")!!.amountCents)
    }

    @Test
    fun confidenceReflectsHowMuchIsKnown() {
        assertTrue(parse("昨天打车35")!!.confidence > parse("xyz35")!!.confidence)
    }
}
