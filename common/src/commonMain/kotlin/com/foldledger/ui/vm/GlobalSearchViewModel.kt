package com.foldledger.ui.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.foldledger.ServiceLocator
import com.foldledger.core.DripCore
import com.foldledger.data.db.Direction
import com.foldledger.data.db.LedgerEntity
import com.foldledger.data.db.TransactionEntity
import com.foldledger.data.repo.LedgerRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import com.foldledger.ui.l10n.t

/**
 * 全局搜索（v5.9）。
 *
 * 数据流：全部流水 → 账本/收支/时间三重筛选 → 关键词匹配。
 * 关键词匹配**复用 [DripCore.matchesQuery]**，和明细页的搜索是同一套语义
 * （多关键词 AND、纯数字词匹配金额），不会出现"同一个词在两个地方搜出不同结果"。
 */
class GlobalSearchViewModel(private val repo: LedgerRepository) : ViewModel() {

    /** 一条搜索结果：流水 + 它所属的账本（跨账本时只显示金额会不知道是哪本账） */
    data class SearchHit(
        val txn: TransactionEntity,
        val ledgerName: String,
        val ledgerEmoji: String
    )

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /** 0 = 全部账本 */
    private val _ledgerFilter = MutableStateFlow(0L)
    val ledgerFilter: StateFlow<Long> = _ledgerFilter.asStateFlow()

    /** -1 = 全部 */
    private val _directionFilter = MutableStateFlow(-1)
    val directionFilter: StateFlow<Int> = _directionFilter.asStateFlow()

    /** 0 = 不限时间；否则为t("近 N 天") */
    private val _rangeFilter = MutableStateFlow(0)
    val rangeFilter: StateFlow<Int> = _rangeFilter.asStateFlow()

    fun setQuery(v: String) { _query.value = v }

    fun setLedgerFilter(id: Long) { _ledgerFilter.value = id }

    fun setDirectionFilter(d: Int) { _directionFilter.value = d }

    fun setRangeFilter(days: Int) { _rangeFilter.value = days }

    val ledgers: StateFlow<List<LedgerEntity>> =
        repo.allLedgers.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private data class Filter(val ledgerId: Long, val direction: Int, val days: Int)

    private val filterFlow = combine(
        _ledgerFilter, _directionFilter, _rangeFilter
    ) { ledgerId, direction, days -> Filter(ledgerId, direction, days) }

    val results: StateFlow<List<SearchHit>> = combine(
        repo.allTransactionsFlow,
        _query,
        filterFlow,
        repo.allLedgers
    ) { list, q, f, ledgerList ->
        val nameOf = ledgerList.associate { it.id to (it.emoji to it.name) }
        val from = if (f.days > 0) System.currentTimeMillis() - f.days * 86_400_000L else 0L
        list.asSequence()
            .filter { f.ledgerId == 0L || it.ledgerId == f.ledgerId }
            .filter { f.direction < 0 || it.direction == f.direction }
            .filter { from == 0L || it.occurredAt >= from }
            .filter {
                q.isBlank() || DripCore.matchesQuery(it.categoryName, it.note, it.amountCents, q)
            }
            .map { txn ->
                val meta = nameOf[txn.ledgerId]
                SearchHit(
                    txn = txn,
                    ledgerName = meta?.second ?: t("已删除账本"),
                    ledgerEmoji = meta?.first ?: "📒"
                )
            }
            .toList()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    companion object {
        fun factory() = viewModelFactory {
            initializer { GlobalSearchViewModel(ServiceLocator.repository) }
        }
    }
}
