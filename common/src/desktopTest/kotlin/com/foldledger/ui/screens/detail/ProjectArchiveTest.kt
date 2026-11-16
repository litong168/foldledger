package com.foldledger.ui.screens.detail

import com.foldledger.data.db.ProjectEntity
import com.foldledger.data.db.ProjectStateAgg
import com.foldledger.data.db.ReimburseState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 项目归档判定（v16.5）。
 *
 * 这条规则被推翻过一次，所以把"什么该归档、什么不该"钉成表格：
 *
 * | 项目里的条目状态 | 归档？ |
 * |---|---|
 * | 有待提交 | 否 |
 * | 只有已提交（钱还没回来） | **否** ← v16.5 修的就是这条 |
 * | 已提交 + 已到账 | 否 |
 * | 全部已到账 | 是 |
 * | 一条支出都没有 | 否（空壳） |
 * | 手动「已结束」 | 是 |
 */
class ProjectArchiveTest {

    private fun project(id: Long, closed: Boolean = false) = ProjectEntity(
        id = id, ledgerId = 1L, name = "项目$id", closed = closed
    )

    private fun agg(projectId: Long, state: Int, count: Int) = ProjectStateAgg(
        projectId = projectId, state = state, totalCents = 100L * count, count = count
    )

    @Test
    fun allSettledIsArchived() {
        val ids = archivedProjectIds(
            listOf(project(1)),
            mapOf(1L to listOf(agg(1, ReimburseState.SETTLED, 3)))
        )
        assertTrue(1L in ids)
    }

    /** v16.5 的核心：已提交 ≠ 办完，钱还没回来，必须留在项目条上盯着 */
    @Test
    fun submittedOnlyIsNotArchived() {
        val ids = archivedProjectIds(
            listOf(project(1)),
            mapOf(1L to listOf(agg(1, ReimburseState.SUBMITTED, 2)))
        )
        assertFalse(1L in ids)
    }

    @Test
    fun pendingIsNotArchived() {
        val ids = archivedProjectIds(
            listOf(project(1)),
            mapOf(1L to listOf(agg(1, ReimburseState.PENDING, 1)))
        )
        assertFalse(1L in ids)
    }

    @Test
    fun mixedSubmittedAndSettledIsNotArchived() {
        val ids = archivedProjectIds(
            listOf(project(1)),
            mapOf(
                1L to listOf(
                    agg(1, ReimburseState.SUBMITTED, 1),
                    agg(1, ReimburseState.SETTLED, 5)
                )
            )
        )
        assertFalse(1L in ids)
    }

    @Test
    fun emptyProjectIsNotArchived() {
        val ids = archivedProjectIds(listOf(project(1)), emptyMap())
        assertFalse(1L in ids)
    }

    @Test
    fun closedProjectIsArchivedEvenWithPending() {
        val ids = archivedProjectIds(
            listOf(project(1, closed = true)),
            mapOf(1L to listOf(agg(1, ReimburseState.PENDING, 2)))
        )
        assertTrue(1L in ids)
    }

    @Test
    fun onlyFinishedProjectsAreArchived() {
        val ids = archivedProjectIds(
            listOf(project(1), project(2), project(3)),
            mapOf(
                1L to listOf(agg(1, ReimburseState.SETTLED, 1)),      // 归档
                2L to listOf(agg(2, ReimburseState.SUBMITTED, 1)),    // 留
                3L to listOf(agg(3, ReimburseState.PENDING, 1))       // 留
            )
        )
        assertEquals(setOf(1L), ids)
    }
}
