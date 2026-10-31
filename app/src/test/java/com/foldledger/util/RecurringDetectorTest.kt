package com.foldledger.util

import com.foldledger.data.db.Direction
import com.foldledger.data.db.RecurringInterval
import com.foldledger.data.db.TransactionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 周期性支出识别（v8.0）。
 *
 * 识别的三道闸门要钉死：次数够、间隔稳、金额稳 ——
 * 识别得出来（房租要认出来）比什么都重要，但**误报更伤**（把打车当订阅会让人关掉这功能）。
 */
class RecurringDetectorTest {

    private val dayMs = 24L * 3600 * 1000
    private val now: Long = java.time.LocalDate.of(2026, 10, 21)
        .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() + 3600 * 1000

    private fun txn(id: Long, daysAgo: Int, cents: Long, name: String = "房租") =
        TransactionEntity(
            id = id,
            ledgerId = 1L,
            categoryName = name,
            categoryEmoji = "🏠",
            direction = Direction.EXPENSE,
            amountCents = cents,
            occurredAt = now - daysAgo * dayMs
        )

    @Test
    fun monthlyRentIsDetected() {
        // 每月 1 号房租 3000，金额带 1% 毛刺
        val s = RecurringDetector.detect(
            listOf(
                txn(1, 180, 3_000_00),
                txn(2, 150, 3_000_00),
                txn(3, 120, 2_970_00),
                txn(4, 90, 3_000_00),
                txn(5, 60, 3_010_00),
                txn(6, 30, 3_000_00)
            ),
            now
        )
        assertEquals(1, s.size)
        val rent = s.first()
        assertEquals(RecurringInterval.MONTHLY, rent.intervalKind)
        assertEquals("房租", rent.name)
        assertEquals(6, rent.occurrences)
        // 中位数金额
        assertEquals(3_000_00, rent.amountCents)
        // 下一次 = 最近一次 + 30 天
        assertEquals(30, ReimburseAging.ageDays(rent.lastAt, rent.predictedNextAt))
    }

    @Test
    fun weeklySubscriptionIsDetected() {
        val s = RecurringDetector.detect(
            listOf(
                txn(1, 28, 1_500, name = "视频会员"),
                txn(2, 21, 1_500, name = "视频会员"),
                txn(3, 14, 1_500, name = "视频会员"),
                txn(4, 7, 1_500, name = "视频会员")
            ),
            now
        )
        assertEquals(1, s.size)
        assertEquals(RecurringInterval.WEEKLY, s.first().intervalKind)
    }

    @Test
    fun irregularSpendingIsNotDetected() {
        // 打车：间隔乱、金额乱 —— 绝不能误报
        val s = RecurringDetector.detect(
            listOf(
                txn(1, 100, 2_300, name = "打车"),
                txn(2, 88, 4_500, name = "打车"),
                txn(3, 60, 1_800, name = "打车"),
                txn(4, 55, 3_200, name = "打车"),
                txn(5, 10, 2_100, name = "打车")
            ),
            now
        )
        assertTrue(s.none { it.name == "打车" })
    }

    @Test
    fun needsAtLeastThreeOccurrences() {
        val s = RecurringDetector.detect(
            listOf(txn(1, 60, 3_000_00), txn(2, 30, 3_000_00)),
            now
        )
        assertTrue(s.isEmpty())
    }

    @Test
    fun amountDriftBeyondToleranceIsRejected() {
        // 水电费：金额差 3 倍，不算一条稳定的周期流
        val s = RecurringDetector.detect(
            listOf(
                txn(1, 90, 1_000, name = "水电"),
                txn(2, 60, 2_800, name = "水电"),
                txn(3, 30, 3_500, name = "水电")
            ),
            now
        )
        assertTrue(s.isEmpty())
    }

    @Test
    fun existingRecurringNamesAreExcluded() {
        val s = RecurringDetector.detect(
            listOf(txn(1, 90, 3_000_00), txn(2, 60, 3_000_00), txn(3, 30, 3_000_00)),
            now,
            excludeNames = setOf("房租")
        )
        assertTrue(s.isEmpty())
    }

    @Test
    fun sameDayBurstIsNotAPeriod() {
        // 同一天连刷 3 笔不算周期
        val s = RecurringDetector.detect(
            listOf(txn(1, 30, 1_000, name = "外卖"), txn(2, 30, 1_000, name = "外卖"), txn(3, 30, 1_000, name = "外卖")),
            now
        )
        assertTrue(s.isEmpty())
    }
}
