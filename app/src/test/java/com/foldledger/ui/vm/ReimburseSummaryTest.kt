package com.foldledger.ui.vm

import com.foldledger.data.db.ProjectStateAgg
import com.foldledger.data.db.ReimburseState
import com.foldledger.data.db.ReimburseStateAgg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 垫付/回款核心数字（v7.1）。
 *
 * 报销账本的两个主角：**尚余垫付**（待提交+已提交）和**已回款**（已到账）。
 * 映射错了用户就看不到"公司还欠我多少"，所以这里逐条钉死。
 */
class ReimburseSummaryTest {

    @Test
    fun emptySummaryIsAllZero() {
        val s = ReimburseSummary.from(emptyList())
        assertEquals(0L, s.outstandingCents)
        assertEquals(0L, s.recoveredCents)
        assertEquals(0L, s.advancedCents)
        assertEquals(0, s.outstandingCount)
        assertEquals(0f, s.recoveredFraction, 1e-6f)
        assertFalse(s.allSettled)
    }

    @Test
    fun outstandingIsPendingPlusSubmitted() {
        val s = ReimburseSummary.from(
            listOf(
                ReimburseStateAgg(ReimburseState.PENDING, 1_000L, 2),
                ReimburseStateAgg(ReimburseState.SUBMITTED, 2_500L, 3),
                ReimburseStateAgg(ReimburseState.SETTLED, 4_000L, 4)
            )
        )
        assertEquals(3_500L, s.outstandingCents)   // 尚余垫付 = 待提交 + 已提交
        assertEquals(5, s.outstandingCount)
        assertEquals(4_000L, s.recoveredCents)      // 已回款 = 已到账
        assertEquals(4, s.recoveredCount)
        assertEquals(7_500L, s.advancedCents)       // 累计垫付 = 尚余 + 已回款
        assertEquals(9, s.advancedCount)
    }

    @Test
    fun recoveredFractionIsSettledOverAdvanced() {
        val s = ReimburseSummary.from(
            listOf(
                ReimburseStateAgg(ReimburseState.PENDING, 1_000L, 1),
                ReimburseStateAgg(ReimburseState.SETTLED, 3_000L, 1)
            )
        )
        assertEquals(0.75f, s.recoveredFraction, 1e-6f)   // 3000 / 4000
        assertFalse(s.allSettled)
    }

    @Test
    fun allSettledMeansNothingOutstandingButSomethingAdvanced() {
        val all = ReimburseSummary.from(
            listOf(ReimburseStateAgg(ReimburseState.SETTLED, 2_000L, 2))
        )
        assertTrue(all.allSettled)

        // 从没垫付过 ≠ 已结清
        assertFalse(ReimburseSummary().allSettled)

        // 还有未回款 ≠ 已结清
        val some = ReimburseSummary.from(
            listOf(
                ReimburseStateAgg(ReimburseState.SUBMITTED, 100L, 1),
                ReimburseStateAgg(ReimburseState.SETTLED, 2_000L, 2)
            )
        )
        assertFalse(some.allSettled)
    }

    @Test
    fun fromProjectMapsTheSameWay() {
        val s = ReimburseSummary.fromProject(
            listOf(
                ProjectStateAgg(7L, ReimburseState.PENDING, 500L, 1),
                ProjectStateAgg(7L, ReimburseState.SETTLED, 1_500L, 3)
            )
        )
        assertEquals(500L, s.outstandingCents)
        assertEquals(1_500L, s.recoveredCents)
        assertEquals(2_000L, s.advancedCents)
        assertEquals(0.75f, s.recoveredFraction, 1e-6f)
    }

    @Test
    fun fractionIsZeroWhenNothingAdvanced() {
        // 除零保护：没有垫付时回款率是 0 而不是 NaN
        val s = ReimburseSummary()
        assertEquals(0f, s.recoveredFraction, 1e-6f)
        assertFalse(s.recoveredFraction.isNaN())
    }
}
