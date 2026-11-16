package com.foldledger.ui.vm

import com.foldledger.data.db.Direction
import com.foldledger.data.db.ReimburseState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 记一笔落库时的报销状态（v16.5）。
 *
 * 事故现场：在报销账本明细下点「记一笔」→ 手动把账本切成日常账本 → 保存，
 * 日常账本里多出一条「待提交」的支出。原因是判定用的是**打开时的账本**，
 * 而不是用户最后选中的目标账本。
 */
class ReimburseStateForTest {

    @Test
    fun expenseInReimburseLedgerDefaultsToPending() {
        assertEquals(
            ReimburseState.PENDING,
            reimburseStateFor(true, Direction.EXPENSE, emptyMap())
        )
    }

    @Test
    fun userPickedStateWins() {
        assertEquals(
            ReimburseState.SUBMITTED,
            reimburseStateFor(true, Direction.EXPENSE, mapOf("state" to "1"))
        )
        assertEquals(
            ReimburseState.SETTLED,
            reimburseStateFor(true, Direction.EXPENSE, mapOf("state" to "2"))
        )
    }

    /** 核心修复：目标账本不支持报销 → 一律 NA，哪怕表单里残留着 state */
    @Test
    fun nonReimburseLedgerGetsNaEvenWithLeftoverState() {
        assertEquals(
            ReimburseState.NA,
            reimburseStateFor(false, Direction.EXPENSE, mapOf("state" to "1"))
        )
    }

    @Test
    fun incomeIsNeverReimbursable() {
        assertEquals(
            ReimburseState.NA,
            reimburseStateFor(true, Direction.INCOME, mapOf("state" to "0"))
        )
    }
}
