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
