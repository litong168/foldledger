package com.foldledger.ui.screens.detail

import com.foldledger.data.db.ProjectEntity
import com.foldledger.data.db.ProjectStateAgg
import com.foldledger.data.db.ReimburseState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private fun project(id: Long, closed: Boolean = false) = ProjectEntity(
    id = id, ledgerId = 1L, name = "项目$id", closed = closed
)

private fun agg(projectId: Long, state: Int, count: Int) = ProjectStateAgg(
    projectId = projectId, state = state, totalCents = 100L * count, count = count
)

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

/**
 * 项目条上的报销状态徽标（v16.5.1）。
 *
 * 用户原话："项目上也要显示报销状态，包括已经归档的。"
 * 规则：最多两段，按「还差什么」优先 —— 待提交 > 已提交 > 已到账；
 * 全部到账时才单独显示「已到账」（归档的项目走这一条）。
 */
class ProjectStateBadgeTest {

    private fun seg( pending: Int = 0, submitted: Int = 0, settled: Int = 0) =
        ProjectStateBadge(pending, submitted, settled).segments()

    @Test
    fun pendingOnly() {
        val s = seg(pending = 2)
        assertEquals(listOf("待提交 2" to StateKind.PENDING), s.map { it.text to it.kind })
    }

    @Test
    fun pendingAndSubmittedBothShown() {
        val s = seg(pending = 2, submitted = 1)
        assertEquals(
            listOf("待提交 2" to StateKind.PENDING, "已提交 1" to StateKind.SUBMITTED),
            s.map { it.text to it.kind }
        )
    }

    @Test
    fun submittedOnly() {
        val s = seg(submitted = 2)
        assertEquals(listOf("已提交 2" to StateKind.SUBMITTED), s.map { it.text to it.kind })
    }

    /** 还有一笔在等打款时不展示已到账笔数（那段信息在汇总卡里） */
    @Test
    fun settledHiddenWhileStillWaiting() {
        val s = seg(submitted = 1, settled = 5)
        assertEquals(listOf("已提交 1"), s.map { it.text })
    }

    /** 归档的项目：全部到账，必须能看到状态 */
    @Test
    fun allSettledShowsSettled() {
        val s = seg(settled = 3)
        assertEquals(listOf("已到账 3" to StateKind.SETTLED), s.map { it.text to it.kind })
    }

    @Test
    fun emptyProducesNoSegment() {
        assertTrue(seg().isEmpty())
    }

    @Test
    fun aggregationIgnoresNaAndEmptyProjects() {
        val badges = projectStateBadges(
            mapOf(
                1L to listOf(
                    agg(1, ReimburseState.PENDING, 2),
                    agg(1, ReimburseState.SETTLED, 1)
                ),
                2L to listOf(agg(2, ReimburseState.NA, 4)),   // 非报销场景
                3L to emptyList()
            )
        )
        assertEquals(ProjectStateBadge(pending = 2, submitted = 0, settled = 1), badges[1L])
        assertNull(badges[2L])
        assertNull(badges[3L])
    }
}
