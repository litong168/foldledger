package com.foldledger.ui.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.foldledger.ServiceLocator
import com.foldledger.data.db.Direction
import com.foldledger.data.db.TransactionEntity
import com.foldledger.data.repo.LedgerRepository
import com.foldledger.util.DateExt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * 消费日历（v7.5）——**按天看账**。
 *
 * 列表按时间倒序堆着，回答不了"这个月钱主要花在哪几天"；
 * 月历热力一眼看到消费的节奏：哪几天烧得凶、哪几天干净。
 * 折叠屏展开后左日历右当日明细，是这套布局最舒服的形态。
 */
class SpendCalendarViewModel(
    private val repo: LedgerRepository,
    val ledgerId: Long
) : ViewModel() {

    /** 当前展示的月（锚定到该月 1 号） */
    private val _monthAnchor = MutableStateFlow(startOfMonthMillis(System.currentTimeMillis()))
    val monthAnchor: StateFlow<Long> = _monthAnchor.asStateFlow()

    /** 选中的某一天 */
    private val _selectedDay = MutableStateFlow(System.currentTimeMillis())
    val selectedDay: StateFlow<Long> = _selectedDay.asStateFlow()

    fun prevMonth() { _monthAnchor.value = shiftMonth(_monthAnchor.value, -1) }
    fun nextMonth() { _monthAnchor.value = shiftMonth(_monthAnchor.value, 1) }

    fun selectDay(ts: Long) {
        _selectedDay.value = DateExt.startOfDay(ts)
        // 选的天不在当前展示的月里（跨月点击）时，把月也带过去
        if (startOfMonthMillis(ts) != _monthAnchor.value) {
            _monthAnchor.value = startOfMonthMillis(ts)
        }
    }

    fun goToday() {
        val now = System.currentTimeMillis()
        _monthAnchor.value = startOfMonthMillis(now)
        _selectedDay.value = DateExt.startOfDay(now)
    }

    /**
     * 月历状态一次算全：当月每日支出、月合计、选中日的流水。
     *
     * 全部在内存里从 transactions 推 —— 记账体量下（几千条）比再开查询轻，
     * 也顺带避免了"格子金额和列表对不上"的经典问题（同源）。
     */
    val uiState: StateFlow<CalUi> =
        combine(repo.transactions(ledgerId), _monthAnchor, _selectedDay) { list, monthTs, selTs ->
            val monthEnd = shiftMonth(monthTs, 1)
            val inMonth = list.filter {
                it.direction == Direction.EXPENSE && it.occurredAt >= monthTs && it.occurredAt < monthEnd
            }
            val byDay = inMonth
                .groupBy { DateExt.dayKey(it.occurredAt) }
                .mapValues { e -> e.value.sumOf { it.amountCents } }
            val dayList = list
                .filter { DateExt.isSameDay(it.occurredAt, selTs) }
                .sortedByDescending { it.occurredAt }
            CalUi(
                monthTs = monthTs,
                selectedTs = DateExt.startOfDay(selTs),
                dayExpenseCents = byDay,
                monthExpenseCents = inMonth.sumOf { it.amountCents },
                dayCount = byDay.size,
                dayItems = dayList
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CalUi.EMPTY)

    companion object {
        fun factory(ledgerId: Long) = viewModelFactory {
            initializer { SpendCalendarViewModel(ServiceLocator.repository, ledgerId) }
        }

        fun startOfMonthMillis(ts: Long) = DateExt.startOfMonth(ts)

        fun shiftMonth(monthTs: Long, delta: Int) = DateExt.shiftMonth(DateExt.startOfMonth(monthTs), delta)

        /**
         * 月历第一行前面留几个空格（周日开头）。
         * 返回 0..6 —— 给测试钉死，别让日历错位。
         */
        fun leadingBlanks(monthTs: Long): Int {
            val first = DateExt.startOfMonth(monthTs)
            val cal = java.util.Calendar.getInstance().apply { timeInMillis = first }
            return cal.get(java.util.Calendar.DAY_OF_WEEK) - 1 // 周日=1 -> 0 空格
        }

        /** 该月有多少天 */
        fun daysInMonth(monthTs: Long): Int {
            val cal = java.util.Calendar.getInstance().apply { timeInMillis = DateExt.startOfMonth(monthTs) }
            return cal.getActualMaximum(java.util.Calendar.DAY_OF_MONTH)
        }
    }
}

/** 消费日历的整屏状态（一次全给，避免 UI 东拼西凑） */
data class CalUi(
    val monthTs: Long,
    val selectedTs: Long,
    /** "yyyy-MM-dd" -> 当日支出（分） */
    val dayExpenseCents: Map<String, Long>,
    val monthExpenseCents: Long,
    val dayCount: Int,
    val dayItems: List<TransactionEntity>
) {
    val maxDayCents: Long get() = dayExpenseCents.values.maxOrNull() ?: 0L

    /** 该日在当月的热力强度 0f..1f（按当月最大值归一，开方让人眼更容易分档） */
    fun heat(dayKey: String): Float {
        val v = dayExpenseCents[dayKey] ?: return 0f
        val max = maxDayCents
        if (max <= 0L) return 0f
        return kotlin.math.sqrt(v.toFloat() / max.toFloat())
    }

    companion object {
        val EMPTY = CalUi(0L, 0L, emptyMap(), 0L, 0, emptyList())
    }
}
