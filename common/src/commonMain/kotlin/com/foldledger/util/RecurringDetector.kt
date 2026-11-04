package com.foldledger.util

import com.foldledger.data.db.Direction
import com.foldledger.data.db.RecurringInterval
import com.foldledger.data.db.TransactionEntity

/**
 * 周期性支出识别（v8.0）。
 *
 * 房租、会员、房贷、保险……这类「每隔一段时间同样一笔钱」的支出，用户
 * 本来就不该一条条手动记。识别的思路很朴素：**同一分类、金额相近、间隔稳定**
 * 三个条件同时满足，就是一个周期流。
 *
 * 三道闸门，宁缺毋滥：
 *  1. 至少出现 3 次（2 次算不出"间隔稳定"）；
 *  2. 相邻间隔的波动不超过 ±35%（随机消费如打车、外卖过不了这关）；
 *  3. 金额相对波动不超过 35%（水电费这类会飘，但不会差一个数量级）。
 */
object RecurringDetector {

    /** 一条识别出的周期流，可直接生成 [com.foldledger.data.db.RecurringEntity] */
    data class Suggestion(
        val name: String,
        val categoryEmoji: String,
        val categoryId: Long?,
        val direction: Int,
        /** 周期金额（取中位数，抗毛刺） */
        val amountCents: Long,
        /** 见 [RecurringInterval] */
        val intervalKind: Int,
        val occurrences: Int,
        val lastAt: Long,
        /** 按间隔推出来的下一次 */
        val predictedNextAt: Long,
        val avgGapDays: Int
    )

    /**
     * 从流水里找周期流。
     *
     * @param excludeNames 已经在周期记账里的名字 —— 不重复建议
     * @param lookbackDays 往回看多久（默认 210 天，够看 6 个月的月周期）
     */
    fun detect(
        txns: List<TransactionEntity>,
        now: Long,
        excludeNames: Set<String> = emptySet(),
        lookbackDays: Int = 210
    ): List<Suggestion> {
        val since = now - lookbackDays * 24L * 3600 * 1000
        val candidates = txns.filter {
            it.occurredAt in since..now && it.categoryName.isNotBlank()
        }
        // 分组键：分类名 + 收支方向（同名不同向不算一条流）
        return candidates
            .groupBy { it.categoryName to it.direction }
            .mapNotNull { (key, group) ->
                if (group.size < 3 || key.first in excludeNames) return@mapNotNull null
                analyze(group)
            }
            .sortedByDescending { it.occurrences }
    }

    private fun analyze(group: List<TransactionEntity>): Suggestion? {
        val sorted = group.sortedBy { it.occurredAt }
        // 金额相对波动：(max-min)/median <= 35%
        val amounts = sorted.map { it.amountCents }.sorted()
        val median = amounts[amounts.size / 2]
        if (median <= 0L) return null
        val spread = (amounts.last() - amounts.first()).toFloat() / median
        if (spread > 0.35f) return null

        // 间隔：相邻天数差，波动 <= ±35%
        val gaps = ArrayList<Int>(sorted.size - 1)
        for (i in 1 until sorted.size) {
            val d = ReimburseAging.ageDays(sorted[i - 1].occurredAt, sorted[i].occurredAt)
            if (d <= 0) return null // 同天多笔不是周期
            gaps += d
        }
        val gapMedian = gaps.sorted()[gaps.size / 2]
        if (gapMedian <= 0) return null
        if (gaps.any { it < gapMedian * 0.65 || it > gapMedian * 1.35 + 1 }) return null

        val kind = when (gapMedian) {
            in 4..10 -> RecurringInterval.WEEKLY
            in 24..38 -> RecurringInterval.MONTHLY
            in 330..400 -> RecurringInterval.YEARLY
            else -> return null // 什么周期都不是（比如 15 天一次）
        }
        val last = sorted.last()
        return Suggestion(
            name = last.categoryName,
            categoryEmoji = last.categoryEmoji,
            categoryId = last.categoryId,
            direction = last.direction,
            amountCents = median,
            intervalKind = kind,
            occurrences = sorted.size,
            lastAt = last.occurredAt,
            predictedNextAt = last.occurredAt + gapMedian * 24L * 3600 * 1000,
            avgGapDays = gapMedian
        )
    }
}
