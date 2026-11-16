package com.foldledger.ui.screens.detail

import com.foldledger.data.db.ProjectEntity
import com.foldledger.data.db.ProjectStateAgg
import com.foldledger.data.db.ReimburseState

/**
 * 哪些项目已经"不需要再盯着"、可以收进抽屉。
 *
 * ### 判据（v16.5 改）
 * **只剩「已到账」才归档**。「待提交」和「已提交」都还留在项目条上：
 *  - 待提交 —— 单子还没交，这是眼下要做的事；
 *  - 已提交 —— 单子交了，但**钱还没回来**，还得跟进公司什么时候打款。
 *
 * v13.8 曾用"没有待提交就归档"，把已提交的一起收进抽屉，用户明确否掉：
 * 那等于把待办藏起来，等项目也就失去了"这趟差旅办完没有"的意义。
 *
 * 其它两条保持不变：
 *  - 手动「已结束」（[ProjectEntity.closed]）仍然归档 —— 用户亲手画的中止符；
 *  - 一条支出都没记过的空项目不归档 —— 建了还没用，不是"办完了"。
 *
 * 归档**不删数据**：流水、归属、金额全在，只是从项目条挪到抽屉里，随时能放回来。
 *
 * 抽成纯函数是为了能单测 —— 这段判定被用户反复推翻过两次。
 */
internal fun archivedProjectIds(
    projects: List<ProjectEntity>,
    stateTotals: Map<Long, List<ProjectStateAgg>>
): Set<Long> = projects.filter { p ->
    if (p.closed) return@filter true
    val states = stateTotals[p.id].orEmpty()
    // 空项目：只是空壳，用户建了还没用
    if (states.isEmpty()) return@filter false
    // 还在流程里的条目数（待提交 + 已提交）。归零 = 全部已到账 = 这一趟真办完了
    val inFlight = states
        .filter { it.state == ReimburseState.PENDING || it.state == ReimburseState.SUBMITTED }
        .sumOf { it.count }
    inFlight == 0
}.map { it.id }.toSet()

/**
 * 一个项目在各报销阶段的笔数（v16.5.1）。
 *
 * 项目条以前只报「待提交 N」—— 单子交出去之后就什么都不显示了，
 * 于是「已提交的项目」和「全都到账的项目」在界面上长得一模一样。
 * 用户明确要：**项目上也要看到报销状态，归档的也要看到**。
 */
internal data class ProjectStateBadge(
    val pending: Int = 0,
    val submitted: Int = 0,
    val settled: Int = 0
) {
    val isEmpty: Boolean get() = pending == 0 && submitted == 0 && settled == 0
}

/**
 * 从「项目 × 状态」汇总里算出每个项目的徽标数据。
 *
 * 只在账本支持报销时有意义（其它场景 [ProjectStateAgg] 里只有 NA，会被过滤掉）。
 */
internal fun projectStateBadges(
    stateTotals: Map<Long, List<ProjectStateAgg>>
): Map<Long, ProjectStateBadge> = stateTotals.mapValues { (_, list) ->
    fun countOf(state: Int) = list.filter { it.state == state }.sumOf { it.count }
    ProjectStateBadge(
        pending = countOf(ReimburseState.PENDING),
        submitted = countOf(ReimburseState.SUBMITTED),
        settled = countOf(ReimburseState.SETTLED)
    )
}.filterValues { !it.isEmpty }

/**
 * 项目条上要显示哪几段文字。
 *
 * 规则（宽度有限，最多两段）：
 *  - 还有「待提交」→ 报待提交，顺带报已提交（这两个都还要人事后跟进）；
 *  - 没有待提交但还有「已提交」→ 只报已提交（等公司打款）；
 *  - 全部到账 → 报已到账 —— **归档的项目走这一条**，否则抽屉里的项目
 *    什么状态都看不到。
 *  - 已到账的笔数在"还没走完"的项目上不展示：那段信息在项目汇总卡里，
 *    挤进 chip 只会把它撑到看不清。
 */
internal fun ProjectStateBadge.segments(): List<Segment> = buildList {
    if (pending > 0) {
        add(Segment("待提交 $pending", StateKind.PENDING))
        // 待提交和已提交同时存在时都报：这两档都还要人跟进
        if (submitted > 0) add(Segment("已提交 $submitted", StateKind.SUBMITTED))
    } else if (submitted > 0) {
        add(Segment("已提交 $submitted", StateKind.SUBMITTED))
    } else if (settled > 0) {
        add(Segment("已到账 $settled", StateKind.SETTLED))
    }
}

/** 项目条上的一段状态文字 + 它属于哪一档（决定颜色） */
internal data class Segment(val text: String, val kind: StateKind)

internal enum class StateKind { PENDING, SUBMITTED, SETTLED }
