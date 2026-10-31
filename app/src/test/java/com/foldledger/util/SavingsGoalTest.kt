package com.foldledger.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * 存钱目标算术（v9.2）。
 *
 * 口径钉死：进度封顶、每月要存**向上取整到元**、没设期限不给建议。
 */
class SavingsGoalTest {

    private val now = 1_760_000_000_000L // 2025-10 左右
    private fun daysFromNow(days: Long) = now + TimeUnit.DAYS.toMillis(days)

    @Test
    fun progressIsCappedAtOne() {
        assertEquals(0f, SavingsGoal.progress(0L, 10000L), 0.001f)
        assertEquals(0.5f, SavingsGoal.progress(5000L, 10000L), 0.001f)
        assertEquals(1f, SavingsGoal.progress(10000L, 10000L), 0.001f)
        assertEquals(1f, SavingsGoal.progress(20000L, 10000L), 0.001f) // 超额也封顶
        assertEquals(0f, SavingsGoal.progress(1000L, 0L), 0.001f)    // 没目标金额
    }

    @Test
    fun remainingAndReached() {
        assertEquals(5000L, SavingsGoal.remaining(5000L, 10000L))
        assertEquals(0L, SavingsGoal.remaining(12000L, 10000L))
        assertTrue(SavingsGoal.reached(10000L, 10000L))
        assertTrue(!SavingsGoal.reached(9999L, 10000L))
    }

    @Test
    fun daysLeftHandlesNoDeadlineAndPast() {
        assertEquals(0, SavingsGoal.daysLeft(0L, now))
        assertEquals(10, SavingsGoal.daysLeft(daysFromNow(10), now))
        assertTrue(SavingsGoal.daysLeft(daysFromNow(-5), now) < 0)
    }

    @Test
    fun monthlyNeededRoundsUpToYuan() {
        // 差 1000 元，还有 100 天（约 4 个月）→ 每月 250 元
        assertEquals(25_000L, SavingsGoal.monthlyNeeded(0L, 100_000L, daysFromNow(100), now))
        // 差 1000 元，剩 1 天 → 不足一月按一月算 → 1000 元
        assertEquals(100_000L, SavingsGoal.monthlyNeeded(0L, 100_000L, daysFromNow(1), now))
        // 已达成 → 0
        assertEquals(0L, SavingsGoal.monthlyNeeded(100_000L, 100_000L, daysFromNow(100), now))
        // 没设期限 → 不给建议
        assertEquals(0L, SavingsGoal.monthlyNeeded(0L, 100_000L, 0L, now))
    }

    @Test
    fun monthlyNeededWhenOverdueIsAllOfIt() {
        assertEquals(100_000L, SavingsGoal.monthlyNeeded(0L, 100_000L, daysFromNow(-3), now))
    }

    @Test
    fun targetDateFromRespectsCustomOverPreset() {
        // 不设期限
        assertEquals(0L, SavingsGoal.targetDateFrom(now, 0, null))
        // 预设 3 个月 ≈ 90 天
        assertEquals(now + TimeUnit.DAYS.toMillis(90), SavingsGoal.targetDateFrom(now, 3, null))
        // 自选日期优先于预设
        val custom = now + TimeUnit.DAYS.toMillis(200)
        assertEquals(custom, SavingsGoal.targetDateFrom(now, 3, custom))
        // 自选值非法（<=0）时退回预设
        assertEquals(now + TimeUnit.DAYS.toMillis(30), SavingsGoal.targetDateFrom(now, 1, 0L))
    }

    @Test
    fun percentIsReadable() {
        assertEquals(62, SavingsGoal.percent(6200L, 10000L))
        assertEquals(100, SavingsGoal.percent(12000L, 10000L))
    }
}
