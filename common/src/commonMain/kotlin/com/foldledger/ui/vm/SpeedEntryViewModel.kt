package com.foldledger.ui.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.foldledger.ServiceLocator
import com.foldledger.data.db.CategoryEntity
import com.foldledger.data.db.Direction
import com.foldledger.data.db.LedgerEntity
import com.foldledger.data.repo.LedgerRepository
import com.foldledger.util.CategorySuggester
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 一句话记账（v8.6 / v9.3）。
 *
 * 干的事只有一件：**把一句话变成一笔流水**。解析在 [NLParser]，
 * 分类建议在 [CategorySuggester]（从用户自己的历史里学）。
 *
 * **v9.3：账本不再写死** —— 速记是全局能力，记到哪个账本由用户当场选
 * （默认给当前账本，但随时能切）。这修掉了一个真实的别扭：在报销账本里
 * 想记一顿饭，却只能记进报销账本。
 */
class SpeedEntryViewModel(
    private val repo: LedgerRepository,
    /** 打开时默认选中的账本（0 = 不预选，让用户自己挑） */
    defaultLedgerId: Long = 0L
) : ViewModel() {

    private val _selectedLedgerId = MutableStateFlow(defaultLedgerId)
    val selectedLedgerId: StateFlow<Long> = _selectedLedgerId.asStateFlow()

    /** 全部账本（速记要能选） */
    val ledgers: StateFlow<List<LedgerEntity>> =
        repo.ledgers.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _direction = MutableStateFlow(Direction.EXPENSE)
    val direction: StateFlow<Int> = _direction.asStateFlow()

    fun setDirection(d: Int) {
        _direction.value = d
    }

    fun selectLedger(id: Long) {
        _selectedLedgerId.value = id
    }

    /** 当前选中的账本（取不到就退到第一个） */
    val selectedLedger: StateFlow<LedgerEntity?> =
        combine(ledgers, _selectedLedgerId) { list, id ->
            list.firstOrNull { it.id == id } ?: list.firstOrNull()
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** 真正生效的账本 id（没预选时退到第一个，别让分类/历史去查空账本） */
    private val resolvedLedgerId: StateFlow<Long> =
        selectedLedger.map { it?.id ?: 0L }.stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

    /** 当前方向 + 当前账本下的分类（横向 chips 展示）—— 换账本要跟着换分类 */
    val categories: StateFlow<List<CategoryEntity>> =
        combine(
            // v9.7.2：还没有账本（id = 0）时直接给空表，别去查一个不存在的账本
            resolvedLedgerId.flatMapLatest { lid ->
                if (lid == 0L) flowOf(emptyList()) else repo.categoriesAll(lid)
            },
            _direction
        ) { all, d -> all.filter { it.direction == d } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 历史样本：给分类建议用（最近 300 条足够）—— 同样跟着账本走 */
    val history: StateFlow<List<CategorySuggester.Sample>> =
        resolvedLedgerId.flatMapLatest { lid ->
            if (lid == 0L) flowOf(emptyList()) else repo.transactions(lid)
        }
            .map { list ->
                list.take(300).map {
                    CategorySuggester.Sample(
                        note = it.note,
                        categoryName = it.categoryName,
                        amountCents = it.amountCents,
                        occurredAt = it.occurredAt
                    )
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 落库（记到当前选中的账本） */
    fun save(
        amountCents: Long,
        category: CategoryEntity?,
        direction: Int,
        dateMillis: Long,
        note: String,
        onDone: (Long) -> Unit
    ) = viewModelScope.launch {
        val ledgerId = selectedLedger.value?.id ?: return@launch
        val id = repo.addTransaction(
            ledgerId = ledgerId,
            categoryId = category?.id,
            categoryName = category?.name ?: if (direction == Direction.INCOME) "其他收入" else "其他支出",
            categoryEmoji = category?.emoji ?: if (direction == Direction.INCOME) "✨" else "📦",
            direction = direction,
            amountCents = amountCents,
            note = note,
            occurredAt = dateMillis
        )
        onDone(id)
    }

    /** 撤销（记错了就撤掉） */
    fun undo(id: Long) = viewModelScope.launch { repo.deleteTransaction(id) }

    companion object {
        fun factory(defaultLedgerId: Long) = viewModelFactory {
            initializer { SpeedEntryViewModel(ServiceLocator.repository, defaultLedgerId) }
        }
    }
}
