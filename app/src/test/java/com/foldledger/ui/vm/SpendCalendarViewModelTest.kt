package com.foldledger.ui.vm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 消费日历的格子数学（v7.5）。
 *
 * 日历错位是最丢人的 bug —— 1 号落在星期几、当月多少天，钉死。
 */
class SpendCalendarViewModelTest {

    private fun monthTs(year: Int, month: Int): Long =
        java.time.LocalDate.of(year, month, 1)
            .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test
    fun leadingBlanksFollowsWeekdayOfFirst() {
        // 2026-10-01 是周四 -> 前面 4 个空格（日一二三）
        assertEquals(4, SpendCalendarViewModel.leadingBlanks(monthTs(2026, 10)))
        // 2026-11-01 是周日 -> 0 个空格
        assertEquals(0, SpendCalendarViewModel.leadingBlanks(monthTs(2026, 11)))
        // 2026-08-01 是周六 -> 6 个空格
        assertEquals(6, SpendCalendarViewModel.leadingBlanks(monthTs(2026, 8)))
    }

    @Test
    fun daysInMonthHandlesLeapYear() {
        assertEquals(31, SpendCalendarViewModel.daysInMonth(monthTs(2026, 10)))
        assertEquals(30, SpendCalendarViewModel.daysInMonth(monthTs(2026, 11)))
        assertEquals(28, SpendCalendarViewModel.daysInMonth(monthTs(2026, 2)))
        assertEquals(29, SpendCalendarViewModel.daysInMonth(monthTs(2028, 2))) // 闰年
    }

    @Test
    fun heatIsNormalizedAndSqrtCompressed() {
        val ui = CalUi(
            monthTs = monthTs(2026, 10),
            selectedTs = monthTs(2026, 10),
            dayExpenseCents = mapOf("2026-10-01" to 400L, "2026-10-02" to 100L),
            monthExpenseCents = 500L,
            dayCount = 2,
            dayItems = emptyList()
        )
        assertEquals(1f, ui.heat("2026-10-01"), 1e-6f)   // 最大值
        assertEquals(0.5f, ui.heat("2026-10-02"), 1e-6f) // sqrt(100/400)
        assertEquals(0f, ui.heat("2026-10-03"), 1e-6f)   // 没记录
    }

    @Test
    fun heatOnEmptyMonthIsZeroNotNaN() {
        val ui = CalUi(monthTs = 0L, selectedTs = 0L, emptyMap(), 0L, 0, emptyList())
        assertEquals(0f, ui.heat("2026-10-01"), 1e-6f)
        assertTrue(!ui.heat("2026-10-01").isNaN())
    }
}
