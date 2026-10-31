package com.foldledger.data.export

import com.foldledger.data.ScenePresets
import com.foldledger.data.db.Direction
import com.foldledger.data.db.LedgerEntity
import com.foldledger.data.db.ProjectEntity
import com.foldledger.data.db.ReimburseState
import com.foldledger.data.db.TransactionEntity
import com.foldledger.data.repo.LedgerRepository
import com.foldledger.util.XlsxWriter
import com.foldledger.util.XlsxWriter.Sheet

/**
 * 把本机数据库导出成 Excel 工作簿。
 *
 * 工作表结构：
 *  1. 账本汇总    —— 一行一个账本，含累计收支、结余、笔数、预算执行
 *  2. 全部流水    —— 所有账本的全部记录（带场景专属字段与报销状态）
 *  3. 分类统计    —— 账本 × 分类 的金额合计与笔数
 *  4. 各账本明细  —— 每个账本单独一张表（上限 20 张，防止 sheet 爆炸）
 */
class ExcelExporter(private val repo: LedgerRepository) {

    data class Result(
        val bytes: ByteArray,
        val ledgerCount: Int,
        val txnCount: Int
    ) {
        override fun equals(other: Any?): Boolean =
            this === other || (other is Result && bytes.contentEquals(other.bytes) &&
                ledgerCount == other.ledgerCount && txnCount == other.txnCount)

        override fun hashCode(): Int =
            bytes.contentHashCode() * 31 + ledgerCount * 7 + txnCount
    }

    private companion object {
        const val MAX_LEDGER_SHEETS = 20
    }

    suspend fun build(): Result {
        val ledgers = repo.allLedgersOnce()
        val txns = repo.allTransactionsOnce()
        val projects = repo.allProjectsOnce()
        return build(ledgers, txns, projects)
    }

    /** 已经拿到快照时直接复用，避免重复查库 */
    fun build(
        ledgers: List<LedgerEntity>,
        txns: List<TransactionEntity>,
        projects: List<ProjectEntity> = emptyList()
    ): Result {
        val byLedger = txns.groupBy { it.ledgerId }
        val projectNames = projects.associate { it.id to it.name }

        val sheets = mutableListOf<Sheet>()
        sheets += summarySheet(ledgers, byLedger)
        sheets += allTransactionsSheet(ledgers, txns, projectNames)
        sheets += categorySheet(ledgers, txns)
        if (projects.isNotEmpty()) {
            sheets += projectSheet(ledgers, txns, projects)
        }
        ledgers.take(MAX_LEDGER_SHEETS).forEach { ledger ->
            sheets += ledgerDetailSheet(ledger, byLedger[ledger.id].orEmpty(), projectNames)
        }
        return Result(XlsxWriter.build(sheets), ledgers.size, txns.size)
    }

    // ---------------- 1. 账本汇总 ----------------

    private fun summarySheet(
        ledgers: List<LedgerEntity>,
        byLedger: Map<Long, List<TransactionEntity>>
    ): Sheet {
        val sheet = Sheet("账本汇总")
        sheet.widths(16.0, 10.0, 12.0, 14.0, 14.0, 12.0, 8.0, 14.0, 10.0, 22.0)
        sheet.row(
            XlsxWriter.headerRow(
                "账本名称", "场景", "图标", "累计支出(元)", "累计收入(元)", "结余(元)",
                "笔数", "预算(元)", "预算使用率", "备注"
            )
        )

        var totalExpense = 0L
        var totalIncome = 0L
        var totalCount = 0

        ledgers.forEach { ledger ->
            val list = byLedger[ledger.id].orEmpty()
            val expense = list.filter { it.direction == Direction.EXPENSE }.sumOf { it.amountCents }
            val income = list.filter { it.direction == Direction.INCOME }.sumOf { it.amountCents }
            totalExpense += expense
            totalIncome += income
            totalCount += list.size

            val budgetRatio = if (ledger.budgetCents > 0) {
                "${(expense.toDouble() / ledger.budgetCents * 100).toInt()}%"
            } else ""

            sheet.row(
                XlsxWriter.text(ledger.name),
                XlsxWriter.text(ScenePresets.byKey(ledger.sceneKey).name),
                XlsxWriter.text(ledger.emoji),
                XlsxWriter.money(expense.toYuan()),
                XlsxWriter.money(income.toYuan()),
                XlsxWriter.money((income - expense).toYuan()),
                XlsxWriter.text(list.size.toString()),
                if (ledger.budgetCents > 0) XlsxWriter.money(ledger.budgetCents.toYuan()) else XlsxWriter.text(""),
                XlsxWriter.text(budgetRatio),
                XlsxWriter.text(ledger.note)
            )
        }

        // 合计行
        sheet.row(
            XlsxWriter.text("合计"),
            XlsxWriter.text("${ledgers.size} 个账本"),
            XlsxWriter.text(""),
            XlsxWriter.money(totalExpense.toYuan()),
            XlsxWriter.money(totalIncome.toYuan()),
            XlsxWriter.money((totalIncome - totalExpense).toYuan()),
            XlsxWriter.text(totalCount.toString()),
            XlsxWriter.text(""), XlsxWriter.text(""), XlsxWriter.text("")
        )
        return sheet
    }

    // ---------------- 2. 全部流水 ----------------

    private fun allTransactionsSheet(
        ledgers: List<LedgerEntity>,
        txns: List<TransactionEntity>,
        projectNames: Map<Long, String> = emptyMap()
    ): Sheet {
        val nameOf = ledgers.associate { it.id to it.name }
        val sceneOf = ledgers.associate { it.id to ScenePresets.byKey(it.sceneKey).name }

        val sheet = Sheet("全部流水")
        sheet.widths(18.0, 16.0, 10.0, 10.0, 14.0, 14.0, 16.0, 26.0, 32.0, 10.0)
        sheet.row(
            XlsxWriter.headerRow(
                "发生时间", "账本", "场景", "收支类型", "分类", "金额(元)", "项目/空间",
                "备注", "场景专属字段", "报销状态"
            )
        )
        txns.forEach { t ->
            sheet.row(
                XlsxWriter.dateTime(t.occurredAt),
                XlsxWriter.text(nameOf[t.ledgerId] ?: "已删除账本"),
                XlsxWriter.text(sceneOf[t.ledgerId] ?: ""),
                XlsxWriter.text(if (t.direction == Direction.EXPENSE) "支出" else "收入"),
                XlsxWriter.text("${t.categoryEmoji} ${t.categoryName}"),
                XlsxWriter.money(if (t.direction == Direction.EXPENSE) -t.amountCents.toYuan() else t.amountCents.toYuan()),
                XlsxWriter.text(projectNames[t.projectId] ?: ""),
                XlsxWriter.text(t.note),
                XlsxWriter.text(extraToText(t)),
                XlsxWriter.text(reimburseLabel(t.reimburseState))
            )
        }
        return sheet
    }

    // ---------------- 3. 分类统计 ----------------

    private fun categorySheet(
        ledgers: List<LedgerEntity>,
        txns: List<TransactionEntity>
    ): Sheet {
        val sheet = Sheet("分类统计")
        sheet.widths(16.0, 10.0, 16.0, 14.0, 10.0, 12.0)
        sheet.row(
            XlsxWriter.headerRow("账本", "类型", "分类", "金额(元)", "笔数", "占账本比例")
        )

        ledgers.forEach { ledger ->
            val list = byLedgerAndDirection(txns, ledger.id)
            list.forEach { (key, items) ->
                val (direction, _) = key
                val sum = items.sumOf { it.amountCents }
                val directionTotal = txns
                    .filter { it.ledgerId == ledger.id && it.direction == direction }
                    .sumOf { it.amountCents }
                val first = items.first()
                sheet.row(
                    XlsxWriter.text(ledger.name),
                    XlsxWriter.text(if (direction == Direction.EXPENSE) "支出" else "收入"),
                    XlsxWriter.text("${first.categoryEmoji} ${first.categoryName}"),
                    XlsxWriter.money(sum.toYuan()),
                    XlsxWriter.text(items.size.toString()),
                    XlsxWriter.text(
                        if (directionTotal > 0) {
                            String.format("%.1f%%", sum.toDouble() / directionTotal * 100)
                        } else "0%"
                    )
                )
            }
        }
        if (ledgers.isEmpty()) {
            sheet.row(
                XlsxWriter.text("（暂无数据）"), XlsxWriter.text(""), XlsxWriter.text(""),
                XlsxWriter.text(""), XlsxWriter.text(""), XlsxWriter.text("")
            )
        }
        return sheet
    }

    private fun byLedgerAndDirection(
        txns: List<TransactionEntity>,
        ledgerId: Long
    ): List<Pair<Pair<Int, String>, List<TransactionEntity>>> =
        txns.filter { it.ledgerId == ledgerId }
            .groupBy { it.direction to it.categoryName }
            .map { (k, v) -> k to v.sortedByDescending { it.amountCents } }
            .sortedWith(compareBy({ it.first.first }, { -it.second.sumOf { t -> t.amountCents } }))

    // ---------------- 4. 单个账本明细 ----------------

    private fun ledgerDetailSheet(
        ledger: LedgerEntity,
        list: List<TransactionEntity>,
        projectNames: Map<Long, String> = emptyMap()
    ): Sheet {
        val scene = ScenePresets.byKey(ledger.sceneKey)
        val sheet = Sheet("${ledger.name}")
        sheet.widths(18.0, 10.0, 14.0, 14.0, 16.0, 26.0, 32.0, 10.0)

        sheet.row(
            XlsxWriter.headerRow(
                "发生时间", "收支类型", "分类", "金额(元)", "项目/空间", "备注", "场景专属字段", "报销状态"
            )
        )
        list.sortedBy { it.occurredAt }.forEach { t ->
            sheet.row(
                XlsxWriter.dateTime(t.occurredAt),
                XlsxWriter.text(if (t.direction == Direction.EXPENSE) "支出" else "收入"),
                XlsxWriter.text("${t.categoryEmoji} ${t.categoryName}"),
                XlsxWriter.money(if (t.direction == Direction.EXPENSE) -t.amountCents.toYuan() else t.amountCents.toYuan()),
                XlsxWriter.text(projectNames[t.projectId] ?: ""),
                XlsxWriter.text(t.note),
                XlsxWriter.text(extraToText(t)),
                XlsxWriter.text(reimburseLabel(t.reimburseState))
            )
        }
        val expense = list.filter { it.direction == Direction.EXPENSE }.sumOf { it.amountCents }
        val income = list.filter { it.direction == Direction.INCOME }.sumOf { it.amountCents }
        sheet.row(
            XlsxWriter.text("合计"),
            XlsxWriter.text(""),
            XlsxWriter.text("${scene.name} · ${list.size} 笔"),
            XlsxWriter.money((expense + income).toYuan()),
            XlsxWriter.text(""),
            XlsxWriter.text("支出 ${expense.toYuan()} / 收入 ${income.toYuan()}"),
            XlsxWriter.text(""),
            XlsxWriter.text("")
        )
        return sheet
    }

    // ---------------- 5. 项目 / 空间汇总（v5.1） ----------------

    private fun projectSheet(
        ledgers: List<LedgerEntity>,
        txns: List<TransactionEntity>,
        projects: List<ProjectEntity>
    ): Sheet {
        val ledgerName = ledgers.associate { it.id to it.name }
        val sheet = Sheet("项目空间")
        sheet.widths(18.0, 14.0, 14.0, 14.0, 8.0, 12.0, 12.0, 12.0, 24.0)
        sheet.row(
            XlsxWriter.headerRow(
                "项目/空间", "所属账本", "支出(元)", "预算(元)", "笔数",
                "待提交(元)", "已提交(元)", "已回款(元)", "备注"
            )
        )
        projects.forEach { p ->
            val its = txns.filter { it.projectId == p.id && it.direction == Direction.EXPENSE }
            val total = its.sumOf { it.amountCents }
            fun sumOfState(state: Int) =
                its.filter { it.reimburseState == state }.sumOf { it.amountCents }
            sheet.row(
                XlsxWriter.text("${p.emoji} ${p.name}"),
                XlsxWriter.text(ledgerName[p.ledgerId] ?: "已删除账本"),
                XlsxWriter.money(total.toYuan()),
                if (p.budgetCents > 0) XlsxWriter.money(p.budgetCents.toYuan()) else XlsxWriter.text(""),
                XlsxWriter.text(its.size.toString()),
                XlsxWriter.money(sumOfState(ReimburseState.PENDING).toYuan()),
                XlsxWriter.money(sumOfState(ReimburseState.SUBMITTED).toYuan()),
                XlsxWriter.money(sumOfState(ReimburseState.SETTLED).toYuan()),
                XlsxWriter.text(p.note)
            )
        }
        return sheet
    }

    // ---------------- 辅助 ----------------

    private fun extraToText(t: TransactionEntity): String {
        val map = LedgerRepository.decodeExtra(t.extraJson)
        if (map.isEmpty()) return ""
        val scene = ScenePresets.all.firstOrNull { s ->
            s.fields.any { map.containsKey(it.key) }
        }
        return map.entries.joinToString("；") { (k, v) ->
            val label = scene?.fields?.firstOrNull { it.key == k }?.label ?: k
            val display = scene?.fields?.firstOrNull { it.key == k }
                ?.options?.firstOrNull { it.value == v }?.label ?: v
            "$label=$display"
        }
    }

    private fun reimburseLabel(state: Int): String = when (state) {
        ReimburseState.PENDING -> "待提交"
        ReimburseState.SUBMITTED -> "已提交"
        ReimburseState.SETTLED -> "已回款"
        else -> ""
    }

    private fun Long.toYuan(): Double = this / 100.0
}

/** 导出文件推荐名：滴水记账-备份-20261013-1156.xlsx */
fun exportFileName(now: Long = System.currentTimeMillis()): String {
    val fmt = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.CHINA)
    return "滴水记账-备份-${fmt.format(java.util.Date(now))}.xlsx"
}
