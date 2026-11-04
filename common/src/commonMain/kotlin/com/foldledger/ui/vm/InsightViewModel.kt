package com.foldledger.ui.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.foldledger.ServiceLocator
import com.foldledger.data.db.Direction
import com.foldledger.data.db.MonthTotal
import com.foldledger.data.db.RecurringEntity
import com.foldledger.data.db.RecurringInterval
import com.foldledger.data.db.TransactionEntity
import com.foldledger.data.repo.LedgerRepository
import com.foldledger.util.RecurringDetector
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 智能洞察（v8.0）——从「记录」到「懂账」。
 *
 * 记账 App 用到后来会遇到一堵墙：数据越记越多，但 App 只会"存"，不会"看"。
 * 8.0 补三件事：
 *  1. **周期性支出识别** —— 扫历史流水，自动发现房租/会员这类周期流，一键加入周期记账；
 *  2. **净资产走势** —— 一年下来，我是在变富还是在吃老本；
 *  3. **支出趋势与预测** —— 按当前节奏，今年大概要花掉多少。
 */
class InsightViewModel(
    private val repo: LedgerRepository,
    val ledgerId: Long
) : ViewModel() {

    // ================= 周期性支出识别 =================

    /**
     * 疑似周期支出（已在周期记账里的名字会被剔掉）。
     * 识别逻辑在 [RecurringDetector]（纯函数，可单测）。
     */
    val suggestions: StateFlow<List<RecurringDetector.Suggestion>> =
        combine(repo.transactions(ledgerId), repo.recurring(ledgerId)) { txns, recur ->
            RecurringDetector.detect(
                txns = txns.filter { it.direction == Direction.EXPENSE },
                now = System.currentTimeMillis(),
                excludeNames = recur.map { it.name }.toSet()
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 一键把识别出的周期流加入周期记账 */
    fun addSuggestion(s: RecurringDetector.Suggestion) = viewModelScope.launch {
        repo.createRecurring(
            RecurringEntity(
                ledgerId = ledgerId,
                name = s.name,
                categoryId = s.categoryId,
                categoryName = s.name,
                categoryEmoji = s.categoryEmoji,
                direction = s.direction,
                amountCents = s.amountCents,
                note = "智能识别加入",
                intervalKind = s.intervalKind,
                nextDueAt = s.predictedNextAt,
                enabled = true
            )
        )
    }

    // ================= 净资产走势 =================

    /**
     * 近 12 个月的月末净资产（分）。
     *
     * 不落库，从账户初值 + 流水倒推 —— 余额不落库是老规矩（落了迟早对不上）。
     * 只算 `includeInNetWorth` 的账户，和资产页口径一致。
     */
    val netWorthSeries: StateFlow<List<NetWorthPoint>> =
        combine(
            repo.accounts(ledgerId),
            repo.transactions(ledgerId)
        ) { accounts, txns ->
            val base = accounts.filter { it.includeInNetWorth && !it.closed }
            if (base.isEmpty()) return@combine emptyList<NetWorthPoint>()
            val now = java.time.LocalDate.now()
            (11 downTo 0).map { back ->
                val monthEnd = now.minusMonths(back.toLong()).withDayOfMonth(1)
                    .plusMonths(1).atStartOfDay(java.time.ZoneId.systemDefault())
                    .toInstant().toEpochMilli()
                val worth = base.sumOf { acc ->
                    acc.initialBalanceCents + txns.asSequence()
                        .filter { it.accountId == acc.id && it.occurredAt < monthEnd }
                        .sumOf { if (it.direction == Direction.INCOME) it.amountCents else -it.amountCents }
                }
                NetWorthPoint(monthEnd - 1, worth)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ================= 支出趋势与预测 =================

    /** 近 6 个月支出（按月） */
    val expenseTrend: StateFlow<List<MonthTotal>> =
        repo.monthlyTotals(ledgerId)
            .map { list ->
                list.filter { it.direction == Direction.EXPENSE }
                    .sortedBy { it.month }
                    .takeLast(6)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * 年度支出预测：按近 3 个月的月均 × 12。
     *
     * v9.7.1 修：原来取的是"最近 **3 个有数据的**月份" —— 中间断了几个月也会被当成连续，
     * 而 `monthlyTotals` 只返回有流水的月份，空月不会补 0，于是"3 个月均值 ×12"里的
     * 分母其实是 3 个散落的月份，误差最多放大 12 倍。
     * 现在要求：**连续 3 个月**，且最新那个月必须是本月或上月，否则宁可不给结论。
     */
    val yearForecast: StateFlow<Long?> =
        expenseTrend
            .map { trend ->
                val last3 = trend.takeLast(3)
                if (last3.size < 3) return@map null
                val months = last3.mapNotNull { parseYearMonth(it.month) }
                if (months.size < 3) return@map null
                val consecutive = months[1] == months[0].plusMonths(1) &&
                    months[2] == months[1].plusMonths(1)
                if (!consecutive) return@map null
                val nowYm = java.time.YearMonth.now()
                val fresh = months[2] == nowYm || months[2] == nowYm.minusMonths(1)
                if (!fresh) return@map null
                last3.sumOf { it.totalCents } / 3L * 12L
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private fun parseYearMonth(text: String): java.time.YearMonth? =
        runCatching { java.time.YearMonth.parse(text) }.getOrNull()

    companion object {
        fun factory(ledgerId: Long) = viewModelFactory {
            initializer { InsightViewModel(ServiceLocator.repository, ledgerId) }
        }
    }
}

/** 净资产走势上的一个点 */
data class NetWorthPoint(val monthTs: Long, val cents: Long)

/** 周期间隔 -> 中文标签（UI 用） */
fun intervalLabel(kind: Int): String = RecurringInterval.label(kind)
