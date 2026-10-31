package com.foldledger.ui.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.foldledger.ServiceLocator
import com.foldledger.data.db.Direction
import com.foldledger.data.db.LedgerEntity
import com.foldledger.data.db.LedgerTotalAgg
import com.foldledger.data.prefs.AppSettings
import com.foldledger.data.repo.LedgerRepository
import com.foldledger.util.DateExt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 账本卡片 UI 模型：账本 + 汇总收支 */
data class LedgerUi(
    val ledger: LedgerEntity,
    val expenseCents: Long = 0L,
    val incomeCents: Long = 0L,
    /**
     * v9.7.1：**本月**支出。
     *
     * 预算按月算（明细页也这么写："本月预算"），所以进度必须用本月支出，
     * 不能拿累计支出 ÷ 月度预算 —— 那样老账本必然"超支"，
     * 而且和明细页算出来的百分比对不上。
     */
    val monthExpenseCents: Long = 0L
) {
    val balanceCents: Long get() = incomeCents - expenseCents
    val budgetUsed: Float
        get() = if (ledger.budgetCents > 0) monthExpenseCents.toFloat() / ledger.budgetCents.toFloat() else 0f
    /** 本月预算剩余（可为负 = 超支） */
    val budgetRemainingCents: Long get() = ledger.budgetCents - monthExpenseCents
}

class AppViewModel(
    private val repo: LedgerRepository = ServiceLocator.repository
) : ViewModel() {

    val settings: StateFlow<AppSettings> =
        ServiceLocator.preferences.settings
            .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    /**
     * 时间锚点（v9.7.1）：App 常驻、跨月不重启时，本月区间必须能重算。
     * 回到前台时 [refreshPeriod] 会推一次，下游本月统计随之刷新。
     */
    private val periodAnchor = MutableStateFlow(System.currentTimeMillis())

    fun refreshPeriod() {
        periodAnchor.value = System.currentTimeMillis()
    }

    private val monthExpenseByLedger: Flow<List<LedgerTotalAgg>> =
        periodAnchor.flatMapLatest { now ->
            repo.ledgerTotalsInRange(DateExt.startOfMonth(now), DateExt.endOfMonth(now))
        }

    /** 全部账本 + 收支汇总（一次 combine，避免多流嵌套重组） */
    val ledgerCards: StateFlow<List<LedgerUi>> =
        combine(repo.ledgers, repo.balances(), monthExpenseByLedger) { ledgers, balances, monthAggs ->
            val byLedger = balances.groupBy { it.ledgerId }
            val monthExpense = HashMap<Long, Long>()
            monthAggs.forEach { a ->
                if (a.direction == Direction.EXPENSE) {
                    monthExpense[a.ledgerId] = (monthExpense[a.ledgerId] ?: 0L) + a.totalCents
                }
            }
            ledgers.map { l ->
                val rows = byLedger[l.id].orEmpty()
                LedgerUi(
                    ledger = l,
                    expenseCents = rows.firstOrNull { it.direction == Direction.EXPENSE }?.totalCents ?: 0L,
                    incomeCents = rows.firstOrNull { it.direction == Direction.INCOME }?.totalCents ?: 0L,
                    // ⚠️ v9.7.2 修：这里漏传过一次 —— monthExpense 上面算完就丢了，
                    // 于是 budgetUsed 恒为 0、「本月剩余」恒等于全额预算。
                    monthExpenseCents = monthExpense[l.id] ?: 0L
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * 全部账本的收支合计。
     *
     * v9.7.2 修：原来 `combine(...) { _, balances -> ... }` 把 ledgers 直接丢掉，
     * 而 `balances()` 是**全表**聚合（含回收站账本）→ 回收站里的账本也被算进总资产。
     * 现在按活账本 id 集合过滤。
     */
    val overallTotals: StateFlow<Pair<Long, Long>> =
        combine(repo.ledgers, repo.balances()) { ledgers, balances ->
            val alive = ledgers.mapTo(HashSet()) { it.id }
            val usable = balances.filter { it.ledgerId in alive }
            val expense = usable.filter { it.direction == Direction.EXPENSE }.sumOf { it.totalCents }
            val income = usable.filter { it.direction == Direction.INCOME }.sumOf { it.totalCents }
            expense to income
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L to 0L)

    /** v8.1 回收站 */
    val trashedLedgers: StateFlow<List<LedgerEntity>> =
        repo.trashedLedgers().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        // 首启不再自动创建预置账本：所有账本由用户在「新建分账本」页面按需创建
        //
        // v8.1 例外：预置「自动记账」账本一次 —— 自动捕获的条目要有落点，
        // 建好后用户手动把条目移去正式账本。用户删掉过就不再打扰。
        viewModelScope.launch {
            val prefs = ServiceLocator.preferences
            if (!prefs.settings.first().autoLedgerSeeded) {
                repo.ensureAutoLedger()
                prefs.setAutoLedgerSeeded(true)
            }
        }
    }

    fun createLedger(
        name: String,
        sceneKey: String,
        emoji: String,
        colorHex: String,
        budgetCents: Long,
        note: String,
        onCreated: (Long) -> Unit
    ) {
        viewModelScope.launch {
            val id = repo.createLedger(name, sceneKey, emoji, colorHex, budgetCents, note)
            ServiceLocator.preferences.setLastLedger(id)
            onCreated(id)
        }
    }

    /** v8.1：删账本 = 移入回收站（可恢复），不再是直接抹掉 */
    fun deleteLedger(id: Long) {
        viewModelScope.launch { repo.softDeleteLedger(id) }
    }

    fun restoreLedger(id: Long) {
        viewModelScope.launch { repo.restoreLedger(id) }
    }

    fun purgeLedger(id: Long) {
        viewModelScope.launch { repo.purgeLedger(id) }
    }

    fun purgeTrashed() {
        viewModelScope.launch { repo.purgeTrashed() }
    }

    fun setBudget(id: Long, budgetCents: Long) {
        viewModelScope.launch { repo.setBudget(id, budgetCents) }
    }

    fun updateLedger(ledger: LedgerEntity) {
        viewModelScope.launch { repo.updateLedger(ledger) }
    }

    fun wipeAllData() {
        viewModelScope.launch { repo.wipeEverything() }
    }

    /** 只清流水，保留账本与分类 */
    fun wipeRecords() {
        viewModelScope.launch { repo.wipeAll() }
    }

    // ---- 设置 ----
    fun setDarkMode(v: Int) = viewModelScope.launch { ServiceLocator.preferences.setDarkMode(v) }
    fun setDynamicColor(v: Boolean) = viewModelScope.launch { ServiceLocator.preferences.setDynamicColor(v) }
    fun setColorConvention(v: Int) = viewModelScope.launch { ServiceLocator.preferences.setColorConvention(v) }

    fun setHighRefreshRate(v: Boolean) =
        viewModelScope.launch { ServiceLocator.preferences.setHighRefreshRate(v) }

    /**
     * v9.4 全局回收站里的总件数（账本 + 存钱目标 + 备份文件）
     */
    val trashTotalCount: StateFlow<Int> =
        combine(
            ServiceLocator.repository.trashedLedgers(),
            ServiceLocator.repository.trashedGoals(),
            ServiceLocator.preferences.recycledBackups
        ) { ledgers, goals, backups -> ledgers.size + goals.size + backups.size }
            .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    /** v8.1 自动记账开关 */
    fun setAutoBookkeepingEnabled(v: Boolean) =
        viewModelScope.launch { ServiceLocator.preferences.setAutoBookkeepingEnabled(v) }

    /** 记住当前选中的分账本 */
    fun setLastLedger(id: Long) = viewModelScope.launch { ServiceLocator.preferences.setLastLedger(id) }

    /** 账本页「合计」范围；[all] 为 true 表示统计全部账本 */
    fun setAggregate(all: Boolean, ids: Set<Long>) =
        viewModelScope.launch { ServiceLocator.preferences.setAggregate(all, ids) }

    /** 每日记账提醒 */
    fun setReminder(enabled: Boolean, hour: Int, minute: Int) =
        viewModelScope.launch { ServiceLocator.preferences.setReminder(enabled, hour, minute) }

    // ---- v5.0 触感与动效 ----

    /** 触感强度：0 关 / 1 轻 / 2 中 / 3 强 */
    fun setHapticStrength(v: Int) =
        viewModelScope.launch { ServiceLocator.preferences.setHapticStrength(v) }

    /** 数字键盘是否震动 */
    fun setHapticKeypad(v: Boolean) =
        viewModelScope.launch { ServiceLocator.preferences.setHapticKeypad(v) }

    // ⚠️ v13.9：`setAuroraEnabled` 随「环境光晕」一起删除（背景已全静态）

    /** v10.1.0：明细页概览卡收起/展开（记住选择） */
    fun setDetailOverviewCompact(v: Boolean) =
        viewModelScope.launch { ServiceLocator.preferences.setDetailOverviewCompact(v) }

    /** v13：长按拖拽排序后写回账本顺序 */
    fun setLedgerOrder(ids: List<Long>) =
        viewModelScope.launch { ServiceLocator.preferences.setLedgerOrder(ids) }

    companion object {
        val Factory = viewModelFactory {
            initializer { AppViewModel() }
        }
    }
}
