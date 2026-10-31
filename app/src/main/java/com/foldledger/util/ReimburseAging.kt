package com.foldledger.util

import com.foldledger.data.db.ReimburseState
import com.foldledger.data.db.TransactionEntity

/**
 * 未回款账龄（v7.2）。
 *
 * 垫付最痛的不是"花了多少"，而是**"这笔钱已经压了多久"**——
 * 压了 7 天和压了 90 天是完全不同的两件事：前者等着就行，后者该催了。
 *
 * 账龄口径：从**垫付那天**（occurredAt）数到今天。钱从口袋出去那天起就在外面压着，
 * 这比"提交了几天"更贴近"我的钱被占了多久"。
 */
object ReimburseAging {

    /** 账龄分档（天数下界含、上界含） */
    data class Bucket(
        val label: String,
        val daysFrom: Int,
        val daysTo: Int,
        val cents: Long,
        val count: Int
    ) {
        /** 这一档是不是"该催了"（30 天以上） */
        val isOverdue: Boolean get() = daysFrom >= 31
    }

    /**
     * 未回款账龄汇总。
     *
     * @property buckets 四档：7 天内 / 8–30 天 / 31–90 天 / 90 天以上
     * @property oldestDays 未回款里最久的一笔压了多少天（0 = 没有未回款）
     * @property overdueCents 30 天以上未回款金额 —— "该催的那部分"
     */
    data class Summary(
        val buckets: List<Bucket>,
        val oldestDays: Int,
        val overdueCents: Long,
        val overdueCount: Int
    ) {
        companion object {
            val EMPTY = Summary(List(4) { Bucket("", 0, 0, 0L, 0) }, 0, 0L, 0)
        }
    }

    /** 垫付天数：从发生日到今天，按自然日算（当天 = 0 天） */
    fun ageDays(occurredAt: Long, now: Long): Int {
        val d1 = java.time.Instant.ofEpochMilli(occurredAt)
            .atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        val d2 = java.time.Instant.ofEpochMilli(now)
            .atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        return java.time.temporal.ChronoUnit.DAYS.between(d1, d2).toInt().coerceAtLeast(0)
    }

    /**
     * 按未回款流水算账龄。传进来的应该是**已经过滤好的未回款流水**
     * （待提交+已提交），这里只做分档，不关心筛选口径。
     */
    fun summarize(outstanding: List<TransactionEntity>, now: Long): Summary {
        if (outstanding.isEmpty()) return Summary.EMPTY
        val b1 = ArrayList<TransactionEntity>() // 0-7
        val b2 = ArrayList<TransactionEntity>() // 8-30
        val b3 = ArrayList<TransactionEntity>() // 31-90
        val b4 = ArrayList<TransactionEntity>() // 91+
        var oldest = 0
        outstanding.forEach { t ->
            val d = ageDays(t.occurredAt, now)
            if (d > oldest) oldest = d
            when {
                d <= 7 -> b1 += t
                d <= 30 -> b2 += t
                d <= 90 -> b3 += t
                else -> b4 += t
            }
        }
        fun agg(label: String, from: Int, to: Int, l: List<TransactionEntity>) = Bucket(
            label = label, daysFrom = from, daysTo = to,
            cents = l.sumOf { it.amountCents }, count = l.size
        )
        return Summary(
            buckets = listOf(
                agg("7 天内", 0, 7, b1),
                agg("8–30 天", 8, 30, b2),
                agg("31–90 天", 31, 90, b3),
                agg("90 天+", 91, Int.MAX_VALUE, b4)
            ),
            oldestDays = oldest,
            overdueCents = b3.sumOf { it.amountCents } + b4.sumOf { it.amountCents },
            overdueCount = b3.size + b4.size
        )
    }

    /**
     * 催办摘要文本（v7.2）——一键复制发给财务/领导。
     *
     * 好的催办文本三要素：**总数一眼看清、最久的一笔点名、语气不惹人烦**。
     *
     * @param projectNames 项目名映射（流水的 projectId -> 名字），让明细能写清"哪一趟的账"
     */
    fun chaseSummary(
        ledgerName: String,
        outstanding: List<TransactionEntity>,
        projectNames: Map<Long, String> = emptyMap(),
        oldestFirst: Boolean = true,
        maxItems: Int = 5,
        /** 传入固定时间便于测试（默认当前时间） */
        now: Long = System.currentTimeMillis()
    ): String {
        if (outstanding.isEmpty()) return "【报销对账】$ledgerName\n当前没有未回款的垫付。"
        val sorted = if (oldestFirst) outstanding.sortedBy { it.occurredAt } else outstanding
        val pending = outstanding.filter { it.reimburseState == ReimburseState.PENDING }
        val submitted = outstanding.filter { it.reimburseState == ReimburseState.SUBMITTED }
        val oldestDays = sorted.maxOf { ageDays(it.occurredAt, now) }
        val total = outstanding.sumOf { it.amountCents }

        val sb = StringBuilder()
        sb.appendLine("【报销对账】$ledgerName")
        if (pending.isNotEmpty()) {
            sb.appendLine("待提交 ${pending.size} 笔 ${Money.format(pending.sumOf { it.amountCents })}")
        }
        if (submitted.isNotEmpty()) {
            sb.appendLine("已提交 ${submitted.size} 笔 ${Money.format(submitted.sumOf { it.amountCents })}")
        }
        sb.appendLine("合计未回款 ${Money.format(total)}（最久已垫付 $oldestDays 天）")
        sb.appendLine("明细（按垫付时间从久到近）：")
        sorted.take(maxItems).forEach { t ->
            val d = ageDays(t.occurredAt, now)
            val project = projectNames[t.projectId]?.takeIf { it.isNotBlank() }?.let { "·$it" } ?: ""
            sb.appendLine(
                "· ${DateExt.shortDay(t.occurredAt)} ${t.categoryName}$project " +
                    "${Money.format(t.amountCents)}（压了 $d 天）"
            )
        }
        if (sorted.size > maxItems) {
            sb.appendLine("· …等共 ${sorted.size} 笔")
        }
        sb.append("麻烦帮忙跟进一下，谢谢～")
        return sb.toString()
    }
}
