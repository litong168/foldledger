package com.foldledger.util

import com.foldledger.data.db.ReimburseState
import com.foldledger.data.db.TransactionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 未回款账龄与催办摘要（v7.2）。
 */
class ReimburseAgingTest {

    private val dayMs = 24L * 3600 * 1000
    private val now: Long = java.time.LocalDate.of(2026, 10, 21)
        .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() + 12 * 3600 * 1000

    private fun txn(id: Long, daysAgo: Int, cents: Long, state: Int = ReimburseState.PENDING) =
        TransactionEntity(
            id = id,
            ledgerId = 1L,
            categoryName = "差旅",
            categoryEmoji = "✈️",
            amountCents = cents,
            occurredAt = now - daysAgo * dayMs,
            reimburseState = state
        )

    @Test
    fun ageDaysCountsCalendarDaysFromOccurrence() {
        assertEquals(0, ReimburseAging.ageDays(now, now))
        assertEquals(45, ReimburseAging.ageDays(now - 45 * dayMs, now))
        // 未来的时间不给负数（改账后发生日在未来是用户故意设置）
        assertEquals(0, ReimburseAging.ageDays(now + 3 * dayMs, now))
    }

    @Test
    fun bucketsSplitAtSevenThirtyNinety() {
        val s = ReimburseAging.summarize(
            listOf(
                txn(1, daysAgo = 3, cents = 100),
                txn(2, daysAgo = 10, cents = 200),
                txn(3, daysAgo = 45, cents = 400),
                txn(4, daysAgo = 120, cents = 800)
            ),
            now
        )
        assertEquals(listOf(100L, 200L, 400L, 800L), s.buckets.map { it.cents })
        assertEquals(listOf(1, 1, 1, 1), s.buckets.map { it.count })
        assertEquals(120, s.oldestDays)
        // 30 天以上 = 31–90 + 90+
        assertEquals(1200L, s.overdueCents)
        assertEquals(2, s.overdueCount)
    }

    @Test
    fun emptySummaryIsZero() {
        val s = ReimburseAging.summarize(emptyList(), now)
        assertEquals(0, s.oldestDays)
        assertEquals(0L, s.overdueCents)
        assertTrue(s.buckets.all { it.count == 0 })
    }

    @Test
    fun overdueFlagOnlyForBucketsOverThirtyDays() {
        val s = ReimburseAging.summarize(listOf(txn(1, 3, 100), txn(2, 45, 200)), now)
        assertFalse(s.buckets[0].isOverdue)  // 7 天内
        assertFalse(s.buckets[1].isOverdue)  // 8–30
        assertTrue(s.buckets[2].isOverdue)   // 31–90
    }

    @Test
    fun chaseSummaryContainsTotalsAndOldest() {
        val text = ReimburseAging.chaseSummary(
            "日常",
            listOf(
                txn(1, daysAgo = 45, cents = 2_500, state = ReimburseState.SUBMITTED),
                txn(2, daysAgo = 3, cents = 1_000, state = ReimburseState.PENDING)
            ),
            now = now
        )
        assertTrue(text.contains("【报销对账】日常"))
        assertTrue(text.contains("待提交 1 笔"))
        assertTrue(text.contains("已提交 1 笔"))
        assertTrue(text.contains("最久已垫付 45 天"))
        // 明细按垫付时间从久到近：45 天那笔排在前面
        val i45 = text.indexOf("压了 45 天")
        val i3 = text.indexOf("压了 3 天")
        assertTrue(i45 in 0 until i3)
    }

    @Test
    fun chaseSummaryShowsProjectNameAndTruncates() {
        val many = (1..8).map { txn(it.toLong(), daysAgo = it, cents = 100) }
        val text = ReimburseAging.chaseSummary(
            "差旅",
            many,
            projectNames = mapOf(0L to "上海出差"),
            maxItems = 5
        )
        assertTrue(text.contains("·上海出差"))
        assertTrue(text.contains("…等共 8 笔"))
    }

    @Test
    fun chaseSummaryForEmptyOutstanding() {
        val text = ReimburseAging.chaseSummary("日常", emptyList())
        assertTrue(text.contains("没有未回款"))
    }
}
