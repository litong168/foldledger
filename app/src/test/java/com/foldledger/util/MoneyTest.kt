package com.foldledger.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

/**
 * 金额与日期工具测试。金额一律以「分」为单位，禁止浮点误差。
 */
class MoneyTest {

    @Test
    fun formatCents() {
        assertEquals("¥0", Money.format(0))
        assertEquals("¥0.05", Money.format(5))
        assertEquals("¥0.50", Money.format(50))
        assertEquals("¥1", Money.format(100))
        assertEquals("¥12.34", Money.format(1234))
        assertEquals("¥1,234.56", Money.format(123456))
        assertEquals("¥1,000,000", Money.format(100_000_000))
        assertEquals("-¥1,234.56", Money.format(-123456))
    }

    @Test
    fun formatPlainForInputField() {
        assertEquals("0.00", Money.centsToPlain(0))
        assertEquals("12.34", Money.centsToPlain(1234))
        assertEquals("0.05", Money.centsToPlain(5))
        assertEquals("-12.34", Money.centsToPlain(-1234))
    }

    @Test
    fun parseUserInputRoundsToCents() {
        assertEquals(1234L, Money.parseToCents("12.34"))
        assertEquals(1200L, Money.parseToCents("12"))
        assertEquals(5L, Money.parseToCents("0.05"))
        assertEquals(100L, Money.parseToCents("1.00"))
        // 多余小数位四舍五入
        assertEquals(124L, Money.parseToCents("1.235"))
        assertEquals(123L, Money.parseToCents("1.234"))
        // 容错：货币符号、千分位、空格
        assertEquals(123456L, Money.parseToCents("¥1,234.56"))
        assertEquals(123456L, Money.parseToCents(" 1234.56 "))
    }

    @Test
    fun parseIllegalInputReturnsNull() {
        assertNull(Money.parseToCents(""))
        assertNull(Money.parseToCents("   "))
        assertNull(Money.parseToCents("."))
        assertNull(Money.parseToCents("-"))
        assertNull(Money.parseToCents("abc"))
        assertNull(Money.parseToCents("1.2.3"))
    }

    @Test
    fun parseFormatRoundTrip() {
        listOf(0L, 1L, 99L, 100L, 1234L, 99_999_999L).forEach { cents ->
            assertEquals(cents, Money.parseToCents(Money.centsToPlain(cents)))
        }
    }

    @Test
    fun hugeAmountDoesNotOverflow() {
        val big = 999_999_999_999L
        assertEquals(big, Money.parseToCents(Money.centsToPlain(big)))
    }

    @Test
    fun percentFormat() {
        assertEquals("25.0%", Money.percent(25, 100))
        assertEquals("0%", Money.percent(10, 0))
        assertEquals("33.3%", Money.percent(1, 3))
    }

    @Test
    fun compactFormat() {
        assertEquals("¥1", Money.formatCompact(100))
        assertEquals("2.5万", Money.formatCompact(2_500_000))
        assertEquals("1亿", Money.formatCompact(10_000_000_000L))
    }
}

class DateExtTest {

    private fun ts(y: Int, m: Int, d: Int, h: Int = 12): Long =
        Calendar.getInstance().apply {
            set(Calendar.YEAR, y)
            set(Calendar.MONTH, m - 1)
            set(Calendar.DAY_OF_MONTH, d)
            set(Calendar.HOUR_OF_DAY, h)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    @Test
    fun monthBoundaries() {
        val t = ts(2026, 10, 13)
        val start = DateExt.startOfMonth(t)
        val end = DateExt.endOfMonth(t)

        val c1 = Calendar.getInstance().apply { timeInMillis = start }
        assertEquals(1, c1.get(Calendar.DAY_OF_MONTH))
        assertEquals(0, c1.get(Calendar.HOUR_OF_DAY))

        val c2 = Calendar.getInstance().apply { timeInMillis = end }
        assertEquals(Calendar.OCTOBER, c2.get(Calendar.MONTH))
        assertEquals(31, c2.get(Calendar.DAY_OF_MONTH))
        assertEquals(23, c2.get(Calendar.HOUR_OF_DAY))

        // 十月 31 天
        assertEquals(2_678_400_000L, end - start + 1)
    }

    @Test
    fun shiftMonthCrossesYear() {
        val jan = ts(2026, 1, 15)
        val prev = Calendar.getInstance().apply { timeInMillis = DateExt.shiftMonth(jan, -1) }
        assertEquals(2025, prev.get(Calendar.YEAR))
        assertEquals(Calendar.DECEMBER, prev.get(Calendar.MONTH))
    }

    @Test
    fun isSameDayIgnoresTime() {
        assertEquals(true, DateExt.isSameDay(ts(2026, 10, 13, 1), ts(2026, 10, 13, 23)))
        assertEquals(false, DateExt.isSameDay(ts(2026, 10, 13, 23), ts(2026, 10, 14, 0)))
    }

    @Test
    fun dayKeyFormat() {
        assertEquals("2026-10-13", DateExt.dayKey(ts(2026, 10, 13)))
    }

    /** 相对今天第 n 天（取中午，避开夏令时/时区边界），用于相对日期文案的断言 */
    private fun daysFromToday(n: Int): Long = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_YEAR, n)
        set(Calendar.HOUR_OF_DAY, 12)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Test
    fun dayRelationCoversPastAndFuture() {
        assertEquals("今天", DateExt.dayRelation(daysFromToday(0)))
        assertEquals("昨天", DateExt.dayRelation(daysFromToday(-1)))
        assertEquals("前天", DateExt.dayRelation(daysFromToday(-2)))
        assertEquals("3 天前", DateExt.dayRelation(daysFromToday(-3)))
        assertEquals("10 天前", DateExt.dayRelation(daysFromToday(-10)))
        assertEquals("明天", DateExt.dayRelation(daysFromToday(1)))
        assertEquals("5 天后", DateExt.dayRelation(daysFromToday(5)))
    }

    @Test
    fun shortDateTimeKeepsMinutePrecision() {
        val t = Calendar.getInstance().apply {
            set(Calendar.YEAR, 2026)
            set(Calendar.MONTH, Calendar.OCTOBER)
            set(Calendar.DAY_OF_MONTH, 13)
            set(Calendar.HOUR_OF_DAY, 15)
            set(Calendar.MINUTE, 40)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        assertEquals("10月13日 15:40", DateExt.shortDateTime(t))
        assertEquals("2026年10月13日 15:40", DateExt.fullDateTime(t))
    }
}
