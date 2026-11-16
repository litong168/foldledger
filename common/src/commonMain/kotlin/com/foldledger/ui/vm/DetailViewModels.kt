package com.foldledger.ui.vm

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.foldledger.ServiceLocator
import com.foldledger.core.DripCore
import com.foldledger.data.ScenePresets
import com.foldledger.data.TxnTemplate
import com.foldledger.data.db.AccountEntity
import com.foldledger.data.db.CategoryEntity
import com.foldledger.data.db.CategoryTotal
import com.foldledger.data.db.DayTotal
import com.foldledger.data.db.Direction
import com.foldledger.data.db.LedgerEntity
import com.foldledger.data.db.MonthTotal
import com.foldledger.data.db.ProjectAgg
import com.foldledger.data.db.ProjectEntity
import com.foldledger.data.db.ProjectStateAgg
import com.foldledger.data.db.ReimburseState
import com.foldledger.data.db.ReimburseStateAgg
import com.foldledger.data.db.RecurringEntity
import com.foldledger.data.db.TransactionEntity
import com.foldledger.data.repo.LedgerRepository
import com.foldledger.data.repo.missingRows
import com.foldledger.util.DateExt
import com.foldledger.util.IconRef
import com.foldledger.platform.deleteIconFile
import com.foldledger.util.Money
import com.foldledger.util.ReimburseAging
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import java.util.Calendar

private val STOP_EAGER = SharingStarted.WhileSubscribed(5_000)

// ============================================================
//  账本详情
// ============================================================

data class DayGroup(
    val dayKey: String,
    val header: String,
    val items: List<TransactionEntity>,
    val expenseCents: Long,
    val incomeCents: Long
)

class LedgerDetailViewModel(
    private val repo: LedgerRepository,
    val ledgerId: Long
) : ViewModel() {

    val ledger: StateFlow<LedgerEntity?> =
        repo.ledger(ledgerId).stateIn(viewModelScope, STOP_EAGER, null)

    val transactions: StateFlow<List<TransactionEntity>> =
        repo.transactions(ledgerId).stateIn(viewModelScope, STOP_EAGER, emptyList())

    /**
     * v16.3：从同一个 [transactions] 派生，不再第二次订阅 DAO Flow。
     * 原来 `transactions` 与 `grouped` 各注册一个 Room observer，同一条 SQL 查两遍、
     * 各持一份全表 List；一次写操作会同时触发两条下游链重算。
     */
    val grouped: StateFlow<List<DayGroup>> =
        transactions.map { list -> groupByDay(list) }
            .stateIn(viewModelScope, STOP_EAGER, emptyList())

    // ---- 流水搜索（v2.0）----
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    // ---- 项目筛选（v5.1）：-1 全部 / 0 未归类 / >0 具体项目 ----
    // 声明放在 filteredGrouped 之前：Kotlin 属性按声明顺序初始化，
    // 放到后面会让 combine 拿到未初始化的引用。
    private val _projectFilter = MutableStateFlow(PROJECT_ALL)
    val projectFilter: StateFlow<Long> = _projectFilter.asStateFlow()

    fun setProjectFilter(id: Long) {
        _projectFilter.value = id
        // 记住这次选的项目：下次「记一笔」默认就归到它
        ProjectContext.set(ledgerId, id)
    }

    /**
     * 报销状态筛选（v5.2）：null = 不筛；否则只看待提交 / 已提交 / 已到账。
     *
     * 点报销看板或项目汇总卡上的某一格就切进来 —— 用户想看的正是"这几笔到底有哪些"，
     * 而不是只想知道一个总数。
     */
    private val _reimburseFilter = MutableStateFlow<Set<Int>>(emptySet())
    val reimburseFilter: StateFlow<Set<Int>> = _reimburseFilter.asStateFlow()

    /** 点同一格再点一次 = 取消筛选（可逆，不用去找清除按钮） */
    fun toggleReimburseFilter(state: Int) {
        _reimburseFilter.value = if (_reimburseFilter.value == setOf(state)) emptySet() else setOf(state)
    }

    /**
     * v7.1：按「垫付档位」筛 —— 点「尚余垫付」筛出待提交+已提交两档，
     * 点「已回款」筛出已到账。同档再点一次取消。
     */
    fun setReimburseFilter(states: Set<Int>) {
        _reimburseFilter.value = if (_reimburseFilter.value == states) emptySet() else states
    }

    fun clearReimburseFilter() { _reimburseFilter.value = emptySet() }

    // ============================================================
    //  多维筛选（v5.7）
    //
    //  关键词只能回答「有没有提到 XX」，回答不了「上个月 200 以上的支出有哪几笔」。
    //  时间范围 / 收支方向 / 金额区间 是记账 App 最常用的三把尺子，和关键词叠加使用。
    // ============================================================

    private val _filter = MutableStateFlow(TxnFilter())
    val filter: StateFlow<TxnFilter> = _filter.asStateFlow()

    fun setFilter(f: TxnFilter) { _filter.value = f }

    fun clearFilter() { _filter.value = TxnFilter() }

    /**
     * 按关键词 + 项目过滤后的分组流水（v4.5 搜索增强，v5.1 加项目筛选）。
     *
     * 关键词匹配**分类名 / 备注 / 金额**：空格分隔多个词是 AND 语义，
     * 纯数字关键词还会去比金额文本（所以输 `35` 能找出 35 元的那些流水）。
     * 匹配逻辑走 Rust 核心 `DripCore.matchesQuery`（.so 缺失时 Kotlin 回退）。
     */
    val filteredGrouped: StateFlow<List<DayGroup>> =
        combine(
            transactions,
            _searchQuery,
            _projectFilter,
            _reimburseFilter,
            _filter
        ) { list, q, pid, state, f ->
            // 1) 项目
            val byProject = when (pid) {
                PROJECT_ALL -> list
                else -> list.filter { it.projectId == pid }
            }
            // 2) 报销状态（点看板三格/垫付大数字切进来；集合可含多档，如「未回款」=待提交+已提交）
            //
            //    ⚠️ v13.9.2：**空筛选 = 隐藏「已到账」**。
            //    报销账本里钱已经回来了，这一档不用一直占着列表位置 ——
            //    用户原话："已到账的条目也隐藏或归档，只显示待提交和已提交就行了"。
            //    非报销账本的条目 `reimburseState = NA（-1）`，这条过滤碰不到它们。
            //    想看已到账：点看板的「已到账」格或「已回款」大数字，它就进筛选了。
            val byState = if (state.isEmpty()) {
                byProject.filter { it.reimburseState != ReimburseState.SETTLED }
            } else byProject.filter {
                it.reimburseState in state
            }
            // 3) 多维筛选（v5.7：时间范围 / 收支方向 / 金额区间）
            val byFilter = f.applyTo(byState, System.currentTimeMillis())
            // 4) 关键词
            val filtered = if (q.isBlank()) byFilter else byFilter.filter {
                DripCore.matchesQuery(it.categoryName, it.note, it.amountCents, q)
            }
            groupByDay(filtered)
        }.stateIn(viewModelScope, STOP_EAGER, emptyList())

    fun setSearchQuery(q: String) { _searchQuery.value = q }

    // ============================================================
    //  项目 / 空间（v5.1）
    //
    //  报销账本里把「一次差旅 / 一次招待」圈成一个项目，流水挂到项目上，
    //  于是可以整批看：这一趟花了多少、报了多少、还差多少没到账。
    //  筛选值：-1 = 全部，0 = 未归类，>0 = 某个项目。
    // ============================================================

    val projects: StateFlow<List<ProjectEntity>> =
        repo.projects(ledgerId).stateIn(viewModelScope, STOP_EAGER, emptyList())

    /** v6.1：账户 id → 名称，流水行副标题显示这笔钱走的哪个账户 */
    val accountNames: StateFlow<Map<Long, String>> =
        repo.accounts(ledgerId)
            .map { list -> list.associate { it.id to it.name } }
            .stateIn(viewModelScope, STOP_EAGER, emptyMap())

    // ================= v6.2 资产总览 =================

    /** 账户列表（资产总览用） */
    val accountsList: StateFlow<List<AccountEntity>> =
        repo.accounts(ledgerId).stateIn(viewModelScope, STOP_EAGER, emptyList())

    /**
     * 账户余额（v6.2）：`初始余额 + Σ流水` 实时推算，不落库。
     * 记一笔流水，余额立刻跟着变 —— 不会出现「余额 500 但流水加起来是 620」的对账灾难。
     */
    val accountBalances: StateFlow<List<Pair<AccountEntity, Long>>> =
        combine(accountsList, transactions) { accs, txns ->
            // v16.3：一次遍历算完所有账户，替代 filter → groupBy → 每组两次 filter+sumOf
            // （原来要建 3 个中间集合、对每笔流水判断 4 次）。口径分毫不变：
            // initialBalance + Σ收入 − Σ支出，余额依旧不落库。
            val sums = HashMap<Long, Long>()
            txns.forEach { t ->
                if (t.accountId > 0) {
                    val delta = if (t.direction == Direction.INCOME) t.amountCents else -t.amountCents
                    sums[t.accountId] = (sums[t.accountId] ?: 0L) + delta
                }
            }
            accs.map { a -> a to (a.initialBalanceCents + (sums[a.id] ?: 0L)) }
        }.stateIn(viewModelScope, STOP_EAGER, emptyList())

    /** 净资产 = 计入汇总且未停用的账户余额之和 */
    val netWorth: StateFlow<Long> =
        accountBalances.map { list ->
            list.filter { it.first.includeInNetWorth && !it.first.closed }
                .sumOf { it.second }
        }.stateIn(viewModelScope, STOP_EAGER, 0L)

    /** 各项目支出合计 + 笔数 */
    val projectTotals: StateFlow<Map<Long, ProjectAgg>> =
        repo.projectTotals(ledgerId)
            .map { list -> list.associateBy { it.projectId } }
            .stateIn(viewModelScope, STOP_EAGER, emptyMap())

    /** 各项目 × 报销状态（项目汇总卡三格） */
    val projectStateTotals: StateFlow<Map<Long, List<ProjectStateAgg>>> =
        repo.projectStateTotals(ledgerId)
            .map { list -> list.groupBy { it.projectId } }
            .stateIn(viewModelScope, STOP_EAGER, emptyMap())

    fun createProject(
        name: String,
        emoji: String,
        colorHex: String,
        budgetCents: Long,
        note: String
    ) = viewModelScope.launch {
        repo.createProject(ledgerId, name, emoji, colorHex, budgetCents, note)
    }

    fun updateProject(project: ProjectEntity) = viewModelScope.launch { repo.updateProject(project) }

    // ============================================================
    //  周期记账（v5.7 固定支出）
    // ============================================================

    /** 到期判定用的"现在"。App 长时间开着跨过月末时，靠 [refreshRecurring] 重算 */
    private val recurringClock = MutableStateFlow(System.currentTimeMillis())

    /** 已到期、等入账的固定支出 */
    @OptIn(ExperimentalCoroutinesApi::class)
    val recurringDue: StateFlow<List<RecurringEntity>> =
        recurringClock
            .flatMapLatest { now -> repo.recurringDue(ledgerId, now) }
            .stateIn(viewModelScope, STOP_EAGER, emptyList())

    /** 该账本全部周期项（管理弹层用） */
    val recurringAll: StateFlow<List<RecurringEntity>> =
        repo.recurring(ledgerId).stateIn(viewModelScope, STOP_EAGER, emptyList())

    fun refreshRecurring() {
        recurringClock.value = System.currentTimeMillis()
    }

    /** 全部到期项入账，回调实际写了几笔 */
    fun postAllRecurring(onDone: (Int) -> Unit = {}) = viewModelScope.launch {
        val n = repo.postAllRecurring(ledgerId, System.currentTimeMillis())
        refreshRecurring()
        onDone(n)
    }

    fun postRecurring(item: RecurringEntity) = viewModelScope.launch {
        repo.postRecurring(item, System.currentTimeMillis())
        refreshRecurring()
    }

    fun skipRecurring(item: RecurringEntity) = viewModelScope.launch {
        repo.skipRecurring(item, System.currentTimeMillis())
        refreshRecurring()
    }

    fun createRecurring(item: RecurringEntity) = viewModelScope.launch {
        repo.createRecurring(item)
        refreshRecurring()
    }

    fun updateRecurring(item: RecurringEntity) = viewModelScope.launch {
        repo.updateRecurring(item)
        refreshRecurring()
    }

    fun deleteRecurring(id: Long) = viewModelScope.launch {
        repo.deleteRecurring(id)
        refreshRecurring()
    }

    fun setRecurringEnabled(id: Long, enabled: Boolean) = viewModelScope.launch {
        repo.setRecurringEnabled(id, enabled)
        refreshRecurring()
    }

    fun deleteProject(projectId: Long) {
        if (_projectFilter.value == projectId) _projectFilter.value = PROJECT_ALL
        viewModelScope.launch { repo.deleteProject(projectId) }
    }

    /** 把某笔流水移到另一个项目（0 = 移出项目） */
    fun setTransactionProject(txnId: Long, projectId: Long) =
        viewModelScope.launch { repo.setTransactionProject(txnId, projectId) }

    /** 项目名映射，流水行副标题用 */
    val projectNames: StateFlow<Map<Long, String>> =
        projects.map { list -> list.associate { it.id to it.name } }
            .stateIn(viewModelScope, STOP_EAGER, emptyMap())

    val totalExpense: StateFlow<Long> =
        repo.total(ledgerId, Direction.EXPENSE).stateIn(viewModelScope, STOP_EAGER, 0L)

    val totalIncome: StateFlow<Long> =
        repo.total(ledgerId, Direction.INCOME).stateIn(viewModelScope, STOP_EAGER, 0L)

    /**
     * 本月区间锚点（v9.7.1）：原来在 VM 构造时把区间算死，
     * App 常驻跨月不重启时，概览卡的「本月」会一直停在上个月。
     * 回到前台调用 [refreshPeriod] 推一次锚点即可重算。
     */
    private val periodAnchor = MutableStateFlow(System.currentTimeMillis())

    fun refreshPeriod() {
        periodAnchor.value = System.currentTimeMillis()
    }

    val monthTotals: StateFlow<Pair<Long, Long>> =
        periodAnchor.flatMapLatest { now ->
            combine(
                repo.totalInRange(ledgerId, Direction.EXPENSE, DateExt.startOfMonth(now), DateExt.endOfMonth(now)),
                repo.totalInRange(ledgerId, Direction.INCOME, DateExt.startOfMonth(now), DateExt.endOfMonth(now))
            ) { e, i -> e to i }
        }.stateIn(viewModelScope, STOP_EAGER, 0L to 0L)

    val categories: StateFlow<List<CategoryEntity>> =
        repo.allCategories(ledgerId).stateIn(viewModelScope, STOP_EAGER, emptyList())

    val txnCount: StateFlow<Int> =
        repo.txnCount(ledgerId).stateIn(viewModelScope, STOP_EAGER, 0)

    /**
     * v7.1 垫付/回款：账本级汇总 —— 尚余垫付 / 已回款 / 回款率。
     *
     * 报销账本的核心问题不是"花了多少"，而是**「公司还欠我多少、已经回来多少」**。
     * 三档状态映射：待提交+已提交 = 尚余垫付（钱还压在外面），已到账 = 已回款。
     */
    val reimburseSummary: StateFlow<ReimburseSummary> =
        repo.reimburseStateTotals(ledgerId)
            .map { ReimburseSummary.from(it) }
            .stateIn(viewModelScope, STOP_EAGER, ReimburseSummary())

    /**
     * 跟随所选项目的垫付/回款汇总（v5.2 同款约定：看板数字必须和列表对得上）。
     * 选中具体项目时用该项目的状态汇总，其余情况（全部/未归类）用账本级。
     */
    val reimburseSummaryScoped: StateFlow<ReimburseSummary> =
        combine(reimburseSummary, projectStateTotals, _projectFilter) { global, byProject, pid ->
            if (pid > PROJECT_UNASSIGNED) ReimburseSummary.fromProject(byProject[pid].orEmpty())
            else global
        }.stateIn(viewModelScope, STOP_EAGER, ReimburseSummary())

    // ================= v7.2 未回款账龄 / 催办 =================

    /**
     * 未回款流水（待提交+已提交，跟随项目筛选），按垫付时间**从久到近**排 ——
     * 催办永远先催压得最久的那笔。
     */
    val outstandingItems: StateFlow<List<TransactionEntity>> =
        combine(transactions, _projectFilter) { list, pid ->
            list.filter {
                (it.reimburseState == ReimburseState.PENDING || it.reimburseState == ReimburseState.SUBMITTED) &&
                    (pid <= PROJECT_UNASSIGNED || it.projectId == pid)
            }.sortedBy { it.occurredAt }
        }.stateIn(viewModelScope, STOP_EAGER, emptyList())

    /** 未回款账龄分档（7天内/8-30/31-90/90天+）+ 最久天数 + 30 天以上"该催的" */
    val reimburseAging: StateFlow<ReimburseAging.Summary> =
        outstandingItems
            .map { ReimburseAging.summarize(it, System.currentTimeMillis()) }
            .stateIn(viewModelScope, STOP_EAGER, ReimburseAging.Summary.EMPTY)

    val pendingList: StateFlow<List<TransactionEntity>> =
        repo.reimburseList(ledgerId, ReimburseState.PENDING)
            .stateIn(viewModelScope, STOP_EAGER, emptyList())

    /** 账本所属场景是否支持报销流程 */
    val supportsReimbursement: StateFlow<Boolean> =
        repo.ledger(ledgerId)
            .map { ScenePresets.byKey(it?.sceneKey ?: ScenePresets.CUSTOM_KEY).supportsReimbursement }
            .stateIn(viewModelScope, STOP_EAGER, false)

    fun deleteTransaction(id: Long) = viewModelScope.launch {
        repo.deleteTransaction(id)
        // v9.8.0：删完给一句回执 —— 唯一的变化是"那行消失了"，分不清是删掉了还是列表刷掉了
        com.foldledger.ui.components.AppToastBus.show("已删除这一笔")
    }

    fun setReimburseState(ids: List<Long>, state: Int) =
        viewModelScope.launch { repo.setReimburseState(ids, state) }

    // ================= v5.6 批量改账 =================
    //
    // 「改一笔」要点进编辑再保存，「改十笔」就要重复十遍 —— 记账 App 最大的效率黑洞。
    // 多选模式让「一次改完」成为可能，尤其适合：事后补记、分类重整、报销状态推进。

    private val _multiSelect = MutableStateFlow(false)
    val multiSelect: StateFlow<Boolean> = _multiSelect.asStateFlow()

    private val _selectedIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedIds: StateFlow<Set<Long>> = _selectedIds.asStateFlow()

    /** 选中笔数与金额合计（UI 顶部条直接用，避免每次重组都算） */
    val selectionSummary: StateFlow<Pair<Int, Long>> =
        combine(_selectedIds, transactions) { ids, list ->
            val picked = list.filter { it.id in ids }
            picked.size to picked.sumOf { it.amountCents }
        }.stateIn(viewModelScope, STOP_EAGER, 0 to 0L)

    /** 进入多选；[seed] 非空时把它作为第一个选中项（长按进入的场景） */
    fun enterMultiSelect(seed: Long? = null) {
        _multiSelect.value = true
        _selectedIds.value = if (seed != null) setOf(seed) else emptySet()
    }

    fun exitMultiSelect() {
        _multiSelect.value = false
        _selectedIds.value = emptySet()
    }

    fun toggleSelect(id: Long) {
        _selectedIds.value = _selectedIds.value.let { cur ->
            if (id in cur) cur - id else cur + id
        }
    }

    fun isSelected(id: Long): Boolean = id in _selectedIds.value

    /** 全选当前可见的流水（由 UI 传入，因为它才知道过滤后剩哪些） */
    fun selectAll(ids: List<Long>) { _selectedIds.value = ids.toSet() }

    fun clearSelection() { _selectedIds.value = emptySet() }

    /** 反选：把「刚才漏选的那几笔」一键补上 */
    fun invertSelection(ids: List<Long>) {
        val cur = _selectedIds.value
        _selectedIds.value = ids.filter { it !in cur }.toSet()
    }

    /** 批量改分类（成功后自动退出多选） */
    fun batchSetCategory(categoryId: Long?, name: String, emoji: String) = viewModelScope.launch {
        val ids = _selectedIds.value.toList()
        if (ids.isEmpty()) return@launch
        repo.setCategoryBulk(ids, categoryId, name, emoji)
        // v9.8.0：批量操作一次改写 N 笔，必须有回执（原来只看到多选退出，不知道改没改）
        com.foldledger.ui.components.AppToastBus.show("已把 ${ids.size} 笔改为「$name」")
        exitMultiSelect()
    }

    /** 批量归入/移出项目（0 = 未归类） */
    fun batchSetProject(projectId: Long) = viewModelScope.launch {
        val ids = _selectedIds.value.toList()
        if (ids.isEmpty()) return@launch
        repo.setProjectBulk(ids, projectId)
        com.foldledger.ui.components.AppToastBus.show(
            if (projectId == 0L) "已把 ${ids.size} 笔移出项目"
            else "已把 ${ids.size} 笔归入项目"
        )
        exitMultiSelect()
    }

    /** 批量改报销状态 */
    fun batchSetReimburseState(state: Int) = viewModelScope.launch {
        val ids = _selectedIds.value.toList()
        if (ids.isEmpty()) return@launch
        repo.setReimburseState(ids, state)
        com.foldledger.ui.components.AppToastBus.show("已更新 ${ids.size} 笔的报销状态")
        exitMultiSelect()
    }

    /** 批量删除（调用方必须先二次确认） */
    fun batchDelete() = viewModelScope.launch {
        val ids = _selectedIds.value.toList()
        if (ids.isEmpty()) return@launch
        repo.deleteTransactions(ids)
        com.foldledger.ui.components.AppToastBus.show("已删除 ${ids.size} 笔（不可恢复）")
        exitMultiSelect()
    }

    // ================= v8.1 批量移动到账本 =================

    /** 可移入的目标账本（排除自己和回收站里的） */
    val otherLedgers: StateFlow<List<LedgerEntity>> =
        repo.ledgers
            .map { list -> list.filter { it.id != ledgerId } }
            .stateIn(viewModelScope, STOP_EAGER, emptyList())

    /**
     * 把选中的流水移到别的账本，自动适配目标账本的格式
     * （分类重匹配 / 报销状态归位 / 项目账户清零，见 [LedgerRepository.moveTransactionTo]）。
     */
    fun moveSelectedTo(targetLedgerId: Long) = viewModelScope.launch {
        val ids = _selectedIds.value.toList()
        if (ids.isEmpty()) return@launch
        ids.forEach { repo.moveTransactionTo(it, targetLedgerId) }
        exitMultiSelect()
    }

    // ================= v15 自动记账「快速入账」=================

    /** 上次确认入账到的目标账本（0 = 还没记过，首次由 UI 弹选择） */
    val lastConfirmLedgerId: StateFlow<Long> =
        com.foldledger.ServiceLocator.preferences.settings
            .map { it.lastConfirmLedgerId }
            .stateIn(viewModelScope, STOP_EAGER, 0L)

    /** 最近一次确认的撤销现场：ids → 原账本（入账就是从当前账本搬走，原账本恒为 ledgerId） */
    private var undoConfirm: Pair<List<Long>, Long>? = null

    /**
     * 行级「✓ 入账」：一键入账到上次确认的账本。
     * @return true = 已入账；false = 还没记过目标账本（调用方弹账本选择）
     */
    fun quickConfirm(txnId: Long): Boolean {
        val target = lastConfirmLedgerId.value
        if (target == 0L) return false
        viewModelScope.launch {
            repo.moveTransactionTo(txnId, target)
            undoConfirm = listOf(txnId) to ledgerId
            val name = repo.ledger(target).first()?.name ?: "目标账本"
            com.foldledger.ui.components.AppToastBus.show(
                "已入账到「$name」", "撤销"
            ) { undoLastConfirm() }
        }
        return true
    }

    /** 批量确认入账（多选后一键移入目标账本），并记住下次快速入账的目标 */
    fun batchConfirm(ids: List<Long>, targetLedgerId: Long) = viewModelScope.launch {
        if (ids.isEmpty()) return@launch
        com.foldledger.ServiceLocator.preferences.setLastConfirmLedger(targetLedgerId)
        ids.forEach { repo.moveTransactionTo(it, targetLedgerId) }
        undoConfirm = ids to ledgerId
        val name = repo.ledger(targetLedgerId).first()?.name ?: "目标账本"
        com.foldledger.ui.components.AppToastBus.show(
            "已确认 ${ids.size} 笔入账到「$name」", "撤销"
        ) { undoLastConfirm() }
        exitMultiSelect()
    }

    /** 撤销最近一次确认（把搬走的流水原路搬回） */
    fun undoLastConfirm() {
        val (ids, target) = undoConfirm ?: return
        undoConfirm = null
        viewModelScope.launch {
            ids.forEach { repo.moveTransactionTo(it, target) }
        }
    }

    companion object {
        /** 项目筛选：全部流水 */
        const val PROJECT_ALL = -1L

        /** 项目筛选：未归入任何项目 */
        const val PROJECT_UNASSIGNED = 0L

        fun factory(ledgerId: Long) = viewModelFactory {
            initializer { LedgerDetailViewModel(ServiceLocator.repository, ledgerId) }
        }

        fun groupByDay(list: List<TransactionEntity>): List<DayGroup> =
            list.groupBy { DateExt.dayKey(it.occurredAt) }
                .entries
                .sortedByDescending { it.key }
                .map { (key, items) ->
                    DayGroup(
                        dayKey = key,
                        header = DateExt.dayHeader(items.first().occurredAt),
                        items = items.sortedByDescending { it.occurredAt },
                        expenseCents = items.filter { it.direction == Direction.EXPENSE }
                            .sumOf { it.amountCents },
                        incomeCents = items.filter { it.direction == Direction.INCOME }
                            .sumOf { it.amountCents }
                    )
                }
    }
}

// ============================================================
//  统计
// ============================================================

@OptIn(ExperimentalCoroutinesApi::class)
class StatsViewModel(
    private val repo: LedgerRepository,
    val ledgerId: Long
) : ViewModel() {

    private val anchor = MutableStateFlow(System.currentTimeMillis())
    private val directionFlow = MutableStateFlow(Direction.EXPENSE)

    val direction: StateFlow<Int> = directionFlow.asStateFlow()

    val ledger: StateFlow<LedgerEntity?> =
        repo.ledger(ledgerId).stateIn(viewModelScope, STOP_EAGER, null)

    val monthLabel: StateFlow<String> =
        anchor.map { DateExt.monthLabel(it) }.stateIn(viewModelScope, STOP_EAGER, "")

    val monthRange: StateFlow<Pair<Long, Long>> =
        anchor.map { DateExt.startOfMonth(it) to DateExt.endOfMonth(it) }
            .stateIn(viewModelScope, STOP_EAGER, nowRange())

    val categoryTotals: StateFlow<List<CategoryTotal>> =
        monthRange.flatMapLatest { range ->
            directionFlow.flatMapLatest { d ->
                repo.categoryTotals(ledgerId, d, range.first, range.second)
            }
        }.stateIn(viewModelScope, STOP_EAGER, emptyList())

    val dailyTotals: StateFlow<List<DayTotal>> =
        monthRange.flatMapLatest { range ->
            directionFlow.flatMapLatest { d ->
                repo.dailyTotals(ledgerId, d, range.first, range.second)
            }
        }.stateIn(viewModelScope, STOP_EAGER, emptyList())

    val monthSum: StateFlow<Long> =
        monthRange.flatMapLatest { range ->
            directionFlow.flatMapLatest { d ->
                repo.totalInRange(ledgerId, d, range.first, range.second)
            }
        }.stateIn(viewModelScope, STOP_EAGER, 0L)

    /**
     * 本月支出合计 / 本月收入合计。
     *
     * 这两个是**跟方向无关**的，因为统计页要显示「收入 - 支出 = 结余」，
     * 而结余只有在两个方向都拿到的时候才算得出来。
     */
    val monthExpense: StateFlow<Long> =
        monthRange.flatMapLatest { range ->
            repo.totalInRange(ledgerId, Direction.EXPENSE, range.first, range.second)
        }.stateIn(viewModelScope, STOP_EAGER, 0L)

    val monthIncome: StateFlow<Long> =
        monthRange.flatMapLatest { range ->
            repo.totalInRange(ledgerId, Direction.INCOME, range.first, range.second)
        }.stateIn(viewModelScope, STOP_EAGER, 0L)

    val monthTxnCount: StateFlow<Int> =
        monthRange.flatMapLatest { range ->
            repo.transactionsInRange(ledgerId, range.first, range.second).map { it.size }
        }.stateIn(viewModelScope, STOP_EAGER, 0)

    /**
     * v16.3：月度汇总只订阅一次，[monthlyTrend] 与 [annual] 共用。
     * 原来两处各调一次 `repo.monthlyTotals(ledgerId)`，同一条全表 GROUP BY 注册两个 observer。
     */
    private val monthlyTotals: StateFlow<List<MonthTotal>> =
        repo.monthlyTotals(ledgerId).stateIn(viewModelScope, STOP_EAGER, emptyList())

    val monthlyTrend: StateFlow<List<MonthTotal>> = monthlyTotals

    /** 本月支出分类（固定支出方向，供「消费洞察」用，不跟随方向切换） */
    val expenseCategoryTotals: StateFlow<List<CategoryTotal>> =
        monthRange.flatMapLatest { range ->
            repo.categoryTotals(ledgerId, Direction.EXPENSE, range.first, range.second)
        }.stateIn(viewModelScope, STOP_EAGER, emptyList())

    /** 上月支出（供「消费洞察」环比用） */
    val prevMonthExpense: StateFlow<Long> =
        monthRange.flatMapLatest { range ->
            val prev = DateExt.shiftMonth(range.first, -1)
            repo.totalInRange(
                ledgerId, Direction.EXPENSE,
                DateExt.startOfMonth(prev), DateExt.endOfMonth(prev)
            )
        }.stateIn(viewModelScope, STOP_EAGER, 0L)

    /** 上月各分类支出（v5.8「谁涨得最多」用） */
    val prevMonthCategoryTotals: StateFlow<List<CategoryTotal>> =
        monthRange.flatMapLatest { range ->
            val prev = DateExt.shiftMonth(range.first, -1)
            repo.categoryTotals(
                ledgerId, Direction.EXPENSE,
                DateExt.startOfMonth(prev), DateExt.endOfMonth(prev)
            )
        }.stateIn(viewModelScope, STOP_EAGER, emptyList())

    // ================= v6.0 预算健康度 =================

    /** 全部分类（取 budgetCents 用，不分收支方向） */
    val categoriesAll: StateFlow<List<CategoryEntity>> =
        repo.categoriesAll(ledgerId).stateIn(viewModelScope, STOP_EAGER, emptyList())

    /**
     * 预算健康度（v6.0）：已设预算的分类 + 本月已用，超支/接近上限的排前。
     *
     * 只回答一个问题：**这个月我在哪些分类上超了？还剩多少可花？**
     * 比"饼图里最大的那块"更能改变行为。
     *
     * 分类预算是 [CategoryEntity.budgetCents]，本月花费按**分类名**匹配
     * （`CategoryTotal` 不带 id，而流水里冗余存的 categoryName 在当月与分类名一致）。
     */
    val budgetHealth: StateFlow<List<BudgetHealth>> =
        combine(categoriesAll, expenseCategoryTotals) { cats, totals ->
            // v9.7.2：同名的多行要**累加**而不是覆盖 —— 用 `associate` 时后一行会盖掉前一行，
            // 分类改过图标的历史流水就属于这种情况（「本月已用」会少算）。
            val usedByName = totals
                .groupingBy { it.name }
                .fold(0L) { acc, t -> acc + t.totalCents }
            cats.filter { it.budgetCents > 0 }
                .map { c ->
                    BudgetHealth(
                        categoryId = c.id,
                        name = c.name,
                        emoji = c.emoji,
                        budgetCents = c.budgetCents,
                        usedCents = usedByName[c.name] ?: 0L
                    )
                }
                .sortedByDescending { it.fraction }
        }.stateIn(viewModelScope, STOP_EAGER, emptyList())

    // ================= v7.0 年度总结 =================

    /**
     * 年度总结（v7.0）：今年总共花了多少、赚了多少、结余多少、12 个月怎么走的。
     *
     * 月度体检回答「这个月怎么样」，年度总结回答**「这一年怎么样」**——
     * 前者管节流，后者管复盘。这是记账 App 从工具变成「财务习惯」的关键一屏。
     */
    val annual: StateFlow<AnnualSummary> = combine(
        monthlyTotals,
        anchor
    ) { monthly, ts ->
        val year = java.time.Instant.ofEpochMilli(ts)
            .atZone(java.time.ZoneId.systemDefault()).year
        val prefix = "$year-"
        val expByMonth = MutableList(12) { 0L }
        var totalExp = 0L
        var totalInc = 0L
        monthly.forEach { m ->
            if (m.month.startsWith(prefix)) {
                val idx = m.month.substring(5).toIntOrNull()?.minus(1) ?: return@forEach
                if (idx in 0..11) {
                    if (m.direction == Direction.EXPENSE) {
                        expByMonth[idx] = m.totalCents
                        totalExp += m.totalCents
                    } else {
                        totalInc += m.totalCents
                    }
                }
            }
        }
        AnnualSummary(
            year = year,
            totalExpense = totalExp,
            totalIncome = totalInc,
            monthlyExpense = expByMonth
        )
    }.stateIn(viewModelScope, STOP_EAGER, AnnualSummary(java.util.Calendar.getInstance().get(java.util.Calendar.YEAR), 0, 0, List(12) { 0L }))

    /**
     * 本月体检（v5.8）。
     *
     * 三条人话结论：**比上月多花/少花多少、哪个分类涨得最凶、按当前速度月末会到哪**。
     * 全部用本地已有数据算，不做任何云端请求 —— 记账数据是最私密的一类，不该出设备。
     */
    val checkup: StateFlow<List<CheckupLine>> = combine(
        combine(monthRange, monthExpense, prevMonthExpense) { r, cur, prev -> Triple(r, cur, prev) },
        expenseCategoryTotals,
        prevMonthCategoryTotals,
        ledger
    ) { base, curCats, prevCats, lg ->
        buildCheckup(
            monthStart = base.first.first,
            cur = base.second,
            prev = base.third,
            curCats = curCats,
            prevCats = prevCats,
            budgetCents = lg?.budgetCents ?: 0L
        )
    }.stateIn(viewModelScope, STOP_EAGER, emptyList())

    fun prevMonth() { anchor.value = DateExt.shiftMonth(anchor.value, -1) }

    /**
     * 还能不能往"更晚的月份"翻。
     *
     * v9.8.0：原来「›」到最新月份后**照样能点、点了毫无反应** ——
     * 用户会以为卡住了。按钮现在按这个状态置灰。
     */
    val canGoNext: StateFlow<Boolean> =
        anchor.map { next ->
            DateExt.shiftMonth(next, 1) <= DateExt.startOfMonth(System.currentTimeMillis())
        }.stateIn(viewModelScope, STOP_EAGER, false)

    fun nextMonth() {
        val next = DateExt.shiftMonth(anchor.value, 1)
        val currentMonthStart = DateExt.startOfMonth(System.currentTimeMillis())
        if (next <= currentMonthStart) anchor.value = next
    }

    fun setDirection(d: Int) { directionFlow.value = d }

    companion object {
        private fun nowRange() =
            DateExt.startOfMonth(System.currentTimeMillis()) to
                DateExt.endOfMonth(System.currentTimeMillis())

        fun factory(ledgerId: Long) = viewModelFactory {
            initializer { StatsViewModel(ServiceLocator.repository, ledgerId) }
        }
    }
}

/** 消费体检的一条结论（v5.8） */
data class CheckupLine(
    val emoji: String,
    val title: String,
    val detail: String = "",
    /** 0 = 中性 / 1 = 好 / 2 = 需要留意 */
    val tone: Int = 0
)

/**
 * 本月体检的三条结论（v5.8）。
 *
 * 原则：**只说人话、只给可行动的结论**。「本月支出 3200 元」这种数字用户自己看得见，
 * 有价值的判断是"比上月多花 24%"「餐饮涨了 380」「按这个速度月末会超预算」。
 *
 * 全部用本地已有数据算，不需要任何新查询、也不联网 —— 记账数据不该出设备。
 */
private fun buildCheckup(
    monthStart: Long,
    cur: Long,
    prev: Long,
    curCats: List<CategoryTotal>,
    prevCats: List<CategoryTotal>,
    budgetCents: Long
): List<CheckupLine> {
    if (cur <= 0L && prev <= 0L) return emptyList()
    val out = ArrayList<CheckupLine>(3)

    // ① 环比：变化小于 5% 就别嚷嚷"涨了/降了"，那只是噪声
    if (prev > 0L) {
        val diff = cur - prev
        val pct = (diff * 100.0 / prev).roundToInt()
        out += when {
            diff > 0L && pct >= 5 ->
                CheckupLine("📈", "比上月多花 ${Money.format(diff)}", "上涨 $pct%", 2)
            diff < 0L && pct <= -5 ->
                CheckupLine("📉", "比上月少花 ${Money.format(-diff)}", "下降 ${-pct}%", 1)
            else ->
                CheckupLine("➖", "和上月基本持平", "相差 ${Money.format(kotlin.math.abs(diff))}", 0)
        }

        // ② 涨得最多的那个分类（只报"涨"，且要有上月基数可比）
        val prevMap = prevCats.associateBy { it.name }
        curCats.asSequence().mapNotNull { c ->
            val p = prevMap[c.name]?.totalCents ?: 0L
            val d = c.totalCents - p
            if (p > 0L && d > 0L) Triple(c.name, d, (d * 100.0 / p).roundToInt()) else null
        }.maxByOrNull { it.second }?.let { (name, d, pct) ->
            out += CheckupLine("🔍", "$name 涨了 ${Money.format(d)}", "比上月多 $pct%", 0)
        }
    }

    // ③ 月末预测：只有"看的是本月"才有意义（翻上月时不该预测）
    val nowStart = DateExt.startOfMonth(System.currentTimeMillis())
    if (monthStart == nowStart) {
        val cal = Calendar.getInstance()
        val dayOfMonth = cal.get(Calendar.DAY_OF_MONTH)
        val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        val passed = dayOfMonth.coerceAtLeast(1)
        // 头两天样本太少，预测会离谱
        if (cur > 0L && passed >= 3) {
            val daily = cur / passed
            val projected = daily * daysInMonth
            if (budgetCents > 0L) {
                if (projected > budgetCents) {
                    out += CheckupLine(
                        "⚠️",
                        "月末预计超预算 ${Money.format(projected - budgetCents)}",
                        "按日均 ${Money.format(daily)} 推算",
                        2
                    )
                } else {
                    val remain = budgetCents - cur
                    val left = (daysInMonth - dayOfMonth + 1).coerceAtLeast(1)
                    out += CheckupLine(
                        "✅",
                        "预算还剩 ${Money.format(remain)}",
                        "剩余 $left 天，日均可用 ${Money.format(remain / left)}",
                        1
                    )
                }
            } else {
                out += CheckupLine(
                    "📊",
                    "按当前速度，本月约 ${Money.format(projected)}",
                    "日均 ${Money.format(daily)}",
                    0
                )
            }
        }
    }
    return out
}

// ============================================================
//  分类管理
// ============================================================
class CategoryViewModel(
    private val repo: LedgerRepository,
    val ledgerId: Long
) : ViewModel() {

    val ledger: StateFlow<LedgerEntity?> =
        repo.ledger(ledgerId).stateIn(viewModelScope, STOP_EAGER, null)

    val categories: StateFlow<List<CategoryEntity>> =
        repo.allCategories(ledgerId).stateIn(viewModelScope, STOP_EAGER, emptyList())

    /**
     * 当前账本的场景模板里还缺几个预置分类。
     * 纯计算（不查库），跟着分类列表与账本一起刷新，用于「恢复预置分类」按钮上的角标。
     */
    val missingPresetCount: StateFlow<Int> = combine(categories, ledger) { cats, led ->
        if (led == null) 0 else ScenePresets.byKey(led.sceneKey).missingRows(cats).size
    }.stateIn(viewModelScope, STOP_EAGER, 0)

    /**
     * 预算建议（v7.0）：近 3 个月各分类的**月均**支出（分）。
     *
     * 「设预算该给多少」是记账 App 用户最常卡住的问题 —— 给少了月月超支没意义，
     * 给多了等于没设。拿历史月均做锚点，一眼就有参考值。
     */
    private val _budgetSuggestion = MutableStateFlow<Map<String, Long>>(emptyMap())
    val budgetSuggestion: StateFlow<Map<String, Long>> = _budgetSuggestion.asStateFlow()

    init {
        viewModelScope.launch {
            runCatching {
                val now = System.currentTimeMillis()
                val start = DateExt.startOfMonth(DateExt.shiftMonth(now, -2))
                val end = DateExt.endOfMonth(now)
                val totals = repo.categoryTotals(ledgerId, Direction.EXPENSE, start, end).first()
                _budgetSuggestion.value = totals.associate { it.name to (it.totalCents / 3L) }
            }
        }
    }

    /** 场景显示名，用于恢复确认弹窗的文案 */
    val sceneName: StateFlow<String> = ledger
        .map { ScenePresets.byKey(it?.sceneKey ?: ScenePresets.CUSTOM_KEY).name }
        .stateIn(viewModelScope, STOP_EAGER, "")

    fun add(name: String, iconRef: String, direction: Int) {
        if (name.isBlank()) return
        viewModelScope.launch {
            repo.addCategory(ledgerId, name.trim(), iconRef.ifBlank { "🏷️" }, direction)
        }
    }

    /** 改名 + 换图标。新图标若是上传的图片，顺手把不再引用的旧图片删掉 */
    fun update(category: CategoryEntity, newName: String, newIcon: String) {
        viewModelScope.launch {
            val old = category.emoji
            val name = newName.trim().ifBlank { category.name }
            val icon = newIcon.ifBlank { old }
            repo.updateCategory(category.copy(name = name, emoji = icon))
            gcOldIcon(old, icon)
        }
    }

    /** 只换图标（列表里点图标直接切） */
    fun setIcon(category: CategoryEntity, iconRef: String) {
        if (iconRef.isBlank() || iconRef == category.emoji) return
        viewModelScope.launch {
            repo.updateCategory(category.copy(emoji = iconRef))
            gcOldIcon(category.emoji, iconRef)
        }
    }

    fun delete(id: Long) = viewModelScope.launch { repo.deleteCategory(id) }

    fun setBudget(category: CategoryEntity, budgetCents: Long) {
        viewModelScope.launch { repo.setCategoryBudget(category.id, budgetCents) }
    }

    /** 一键补齐该场景的预置分类 */
    fun restorePresetCategories(onDone: (Int) -> Unit = {}) {
        viewModelScope.launch {
            val n = runCatching { repo.restorePresetCategories(ledgerId) }.getOrDefault(0)
            onDone(n)
        }
    }

    private fun gcOldIcon(oldRef: String, newRef: String) {
        if (!IconRef.isCustom(oldRef) || oldRef == newRef) return
        // 只删自己上传的图片；预置 Emoji 没有文件可删（平台桥：Android 删私有目录，桌面 no-op）
        runCatching { deleteIconFile(IconRef.fileNameOf(oldRef)) }
    }

    companion object {
        fun factory(ledgerId: Long) = viewModelFactory {
            initializer { CategoryViewModel(ServiceLocator.repository, ledgerId) }
        }
    }
}

// ============================================================
//  记一笔
// ============================================================

data class TxnFormState(
    val amountText: String = "",
    val direction: Int = Direction.EXPENSE,
    val categoryId: Long? = null,
    val categoryName: String = "",
    val categoryEmoji: String = "📦",
    val extra: Map<String, String> = emptyMap(),
    val note: String = "",
    val occurredAt: Long = System.currentTimeMillis(),
    val editingId: Long? = null,
    /** v5.1：归入的项目/空间（0 = 不归入） */
    val projectId: Long = 0L,
    /** v6.1：这笔钱走的账户（0 = 未指定） */
    val accountId: Long = 0L,
    /**
     * 目标账本 id —— 这一笔最终落在哪个账本。
     *
     * v10.4 加它是为了「自动记账的条目事后搬到真实账本」，当时默认 0 表示"不变"。
     * v16.5 起**默认就是当前账本**：账本 chip 靠它判断选中态，
     * 一直是 0 的话打开弹层看不到自己在哪个账本记账（用户报的第 4 条）。
     * 0 只作为"未初始化"的兜底，保存时仍按 `targetLedgerId ?: 当前账本` 取。
     */
    val targetLedgerId: Long = 0L
) {
    /**
     * 解析后的金额（分）。
     *
     * v9.7.2：从 `get()` 改成构造时算一次 —— 原来每读一次就跑一遍 BigDecimal
     * （`parseToCents` 里 new + setScale + movePointRight），而每次按键的 recomposition
     * 会读它好几次；金额是这一屏最热的字段。
     */
    val amountCents: Long? = Money.parseToCents(amountText)
    val canSave: Boolean get() = (amountCents ?: 0L) > 0L && categoryName.isNotBlank()
}

/**
 * 这一笔该落成什么报销状态（v16.5）。
 *
 * 判据只能看**目标账本**有没有报销场景。以前传进来的是"打开弹层时那个账本"，
 * 于是从报销账本点开、手动切成日常账本之后，流水照样写成「待提交」，
 * 日常账本里凭空多出一条待报销的支出。
 *
 * 抽成纯函数是为了能单测：这条规则同样是用户实测踩出来的。
 */
internal fun reimburseStateFor(
    targetSupportsReimburse: Boolean,
    direction: Int,
    extra: Map<String, String>
): Int = if (targetSupportsReimburse && direction == Direction.EXPENSE) {
    // 用户在表单里手选过状态就跟用户走，没选默认待提交
    extra["state"]?.toIntOrNull() ?: ReimburseState.PENDING
} else {
    // 目标账本不支持报销，或者这笔是收入 —— 一律 NA，绝不带状态过去
    ReimburseState.NA
}

class AddTxnViewModel(
    private val repo: LedgerRepository,
    val ledgerId: Long
) : ViewModel() {

    var form by mutableStateOf(
        // v5.1：默认带出上次用过的项目（同一趟差旅连记几笔不用每次重选）
        // v16.5：targetLedgerId 直接初始化成**当前账本** —— 以前默认 0，
        // 账本 chip 的 isActive 判断永远不成立，"我在哪个账本记账"看不出来，
        // 每次都得点一下才亮。
        TxnFormState(projectId = ProjectContext.get(ledgerId), targetLedgerId = ledgerId)
    )
        private set

    /** v9.7.2：上一次成功发起保存的时间戳，用来挡住连点造成的重复入账 */
    private var lastSaveAt = 0L

    val ledger: StateFlow<LedgerEntity?> =
        repo.ledger(ledgerId).stateIn(viewModelScope, STOP_EAGER, null)

    /** v5.1：可归入的项目列表 */
    val projects: StateFlow<List<ProjectEntity>> =
        repo.projects(ledgerId).stateIn(viewModelScope, STOP_EAGER, emptyList())

    /** v6.1：可选的资产账户（这笔钱从哪个账户出/进） */
    val accounts: StateFlow<List<AccountEntity>> =
        repo.accounts(ledgerId).stateIn(viewModelScope, STOP_EAGER, emptyList())

    private val _categories = MutableStateFlow<List<CategoryEntity>>(emptyList())
    val categories: StateFlow<List<CategoryEntity>> = _categories.asStateFlow()

    /** v10.4：可选的全部账本（编辑时换账本用） */
    val allLedgers: StateFlow<List<LedgerEntity>> =
        repo.ledgers.stateIn(viewModelScope, STOP_EAGER, emptyList())

    /** v10.4：当前显示的账本 id（编辑换账本后跟 form.targetLedgerId 走） */
    val activeLedgerId: Long get() = if (form.targetLedgerId != 0L) form.targetLedgerId else ledgerId

    /**
     * v10.4：换目标账本 —— 分类跟着切到目标账本的分类列表。
     * 自动记账里记的「自动记账」分类没意义，用户换到报销账本后要选真实分类。
     */
    fun setTargetLedger(id: Long) {
        if (id == form.targetLedgerId) return
        // v16.5：换账本必须把「属于原账本的东西」摘干净，否则会串到新账本里：
        //  · projectId —— 项目是账本内的差旅/客户，搬到日常账本没有意义；
        //  · extra["state"] —— 报销状态（待提交/已提交/已到账）。
        // 用户实测：在报销账本里点开记一笔、手动切成日常账本，流水照样带着「待提交」。
        form = form.copy(
            targetLedgerId = id,
            projectId = 0L,
            extra = form.extra - "state"
        )
        viewModelScope.launch {
            val list = repo.allCategories(id).first()
            _categories.value = list
            // 分类也分账本：原来选的那个在新账本里不存在的话必须清掉，
            // 否则会存进一个「属于别的账本的 categoryId」（名字还在，但统计/编辑会对不上）
            val current = form.categoryId
            if (current != null && list.none { it.id == current }) {
                form = form.copy(categoryId = null, categoryName = "", categoryEmoji = "")
            }
        }
    }

    init {
        viewModelScope.launch {
            repo.allCategories(ledgerId).collect { list ->
                _categories.value = list
                // v5.6：首次拿到分类、且用户还没选过分类时，自动带出这个账本**最近用过的分类**。
                // 记账最高频的动作是"同一个分类再记一笔"（早饭、通勤、买菜），
                // 每次都要求点一次分类是纯粹的浪费。只自动带一次（appliedLastCategory），
                // 用户手动清空后不会被再填回来。
                if (!appliedLastCategory && form.categoryId == null) {
                    appliedLastCategory = true
                    val lastId = repo.lastCategoryId(ledgerId, form.direction)
                    val auto = list.firstOrNull { it.id == lastId && it.direction == form.direction }
                    if (auto != null) pickCategory(auto)
                }
            }
        }
    }

    /** 上次分类只自动带出一次，避免覆盖用户的选择 */
    private var appliedLastCategory = false

    // ---- 常用模板（v2.1）----
    val templates: StateFlow<List<TxnTemplate>> =
        ServiceLocator.preferences.templates.stateIn(viewModelScope, STOP_EAGER, emptyList())

    /** 点模板：把金额/分类/备注填进表单。分类按名字匹配当前账本，匹配不到就只填名称图标 */
    fun applyTemplate(t: TxnTemplate) {
        val matched = _categories.value.firstOrNull {
            it.direction == t.direction && it.name == t.categoryName
        }
        form = form.copy(
            amountText = Money.centsToPlain(t.amountCents),
            direction = t.direction,
            categoryId = matched?.id ?: form.categoryId,
            categoryName = t.categoryName,
            categoryEmoji = t.categoryEmoji,
            note = t.note
        )
    }

    /**
     * 把当前表单存成模板（要有金额 + 分类才存）。
     *
     * @return 是否真的存下了 —— 调用方要据此给回执，静默 return 会让人以为"点了没反应"。
     */
    fun saveTemplate(name: String): Boolean {
        val f = form
        val cents = f.amountCents ?: return false
        if (cents <= 0L || f.categoryName.isBlank()) return false
        viewModelScope.launch {
            val list = templates.value.toMutableList()
            list.add(
                0,
                TxnTemplate(
                    id = System.currentTimeMillis(),
                    name = name.ifBlank { f.categoryName },
                    direction = f.direction,
                    categoryName = f.categoryName,
                    categoryEmoji = f.categoryEmoji,
                    amountCents = cents,
                    note = f.note
                )
            )
            ServiceLocator.preferences.setTemplates(list)
        }
        return true
    }

    fun deleteTemplate(id: Long) {
        viewModelScope.launch {
            ServiceLocator.preferences.setTemplates(templates.value.filterNot { it.id == id })
        }
    }

    fun inputKey(key: String) {
        val cur = form.amountText
        val next = when (key) {
            "del" -> if (cur.isEmpty()) "" else cur.dropLast(1)
            "clear" -> ""
            "." -> when {
                cur.isEmpty() -> "0."
                cur.contains('.') -> cur
                else -> "$cur."
            }
            else -> when {
                cur == "0" -> key
                cur.contains('.') && cur.substringAfter('.').length >= 2 -> cur
                !cur.contains('.') && cur.count { it.isDigit() } >= 9 -> cur
                else -> cur + key
            }
        }
        form = form.copy(amountText = next)
    }

    fun setDirection(d: Int) {
        if (d == form.direction) return
        form = form.copy(direction = d, categoryId = null, categoryName = "", categoryEmoji = "📦")
    }

    fun pickCategory(c: CategoryEntity) {
        form = form.copy(categoryId = c.id, categoryName = c.name, categoryEmoji = c.emoji)
    }

    fun setExtra(key: String, value: String) {
        form = form.copy(extra = form.extra.toMutableMap().apply { put(key, value) })
    }

    fun setNote(v: String) { form = form.copy(note = v) }

    /** v5.1：选择归入的项目（0 = 不归入任何项目） */
    fun setProject(id: Long) {
        form = form.copy(projectId = id)
        ProjectContext.set(ledgerId, id)
    }

    /** v6.1：选择这笔钱走的账户（0 = 未指定） */
    fun setAccount(id: Long) { form = form.copy(accountId = id) }

    fun setOccurredAt(ts: Long) { form = form.copy(occurredAt = ts) }

    fun backdate(days: Int) {
        form = form.copy(occurredAt = System.currentTimeMillis() - days * 86_400_000L)
    }

    fun loadForEdit(txnId: Long) {
        viewModelScope.launch {
            val t = repo.getTransaction(txnId) ?: return@launch
            form = TxnFormState(
                amountText = Money.centsToPlain(t.amountCents),
                direction = t.direction,
                categoryId = t.categoryId,
                categoryName = t.categoryName,
                categoryEmoji = t.categoryEmoji,
                extra = LedgerRepository.decodeExtra(t.extraJson),
                note = t.note,
                occurredAt = t.occurredAt,
                editingId = t.id,
                projectId = t.projectId,
                accountId = t.accountId,
                targetLedgerId = t.ledgerId // v10.4：编辑时带出原账本，用户可换
            )
        }
    }

    /**
     * 清空表单。
     *
     * @param keepContext v5.6 连续记账：保留**方向 / 分类 / 日期 / 项目**，只清金额与备注。
     *   因为"一口气记几笔"通常发生在同一个场景（同一天、同一趟差旅、同类消费），
     *   把这些再选一遍是重复劳动。项目仍然从 [ProjectContext] 取最新值。
     */
    fun reset(keepContext: Boolean = false) {
        val prev = form
        // v16.5：连续记账要保留**用户选的目标账本** —— 否则在报销账本里点开、
        // 切成日常账本记完一笔，下一笔又默默记回报销账本。
        val target = if (keepContext && prev.targetLedgerId != 0L) prev.targetLedgerId else ledgerId
        // 项目和账户都挂在账本上：换了账本就不能带
        val sameLedger = target == ledgerId
        form = if (keepContext) {
            TxnFormState(
                direction = prev.direction,
                categoryId = prev.categoryId,
                categoryName = prev.categoryName,
                categoryEmoji = prev.categoryEmoji,
                occurredAt = prev.occurredAt,
                projectId = if (sameLedger) ProjectContext.get(ledgerId) else 0L,
                // v6.1：连续记账也保留账户（同一天连记几笔通常走同一个账户）
                accountId = if (sameLedger) prev.accountId else 0L,
                targetLedgerId = target
            )
        } else {
            TxnFormState(projectId = ProjectContext.get(ledgerId), targetLedgerId = ledgerId)
        }
    }

    /**
     * 保存这一笔。
     *
     * @param keepContext v5.6：保存后保留上下文（连续记账），由调用方决定是关闭弹层还是继续记
     */
    fun save(
        supportsReimbursement: Boolean,
        keepContext: Boolean = false,
        onSaved: () -> Unit
    ) {
        // ⚠️ v9.7.2 修：保存没有防连点 ——「液体保存按钮」和「键盘上的保存」是两个入口，
        // 连点两下（或退场动画的 200ms 内再点一次）会插入**两条相同流水**。
        // 用「上次保存时间」做节流，而不是布尔锁：异常时不会留下永久锁死的表单。
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastSaveAt < 800L) return
        val f = form
        val cents = f.amountCents ?: return
        if (cents <= 0L) return
        // v9.7.1：没选分类不许落库 —— 原来键盘上的「保存」不看按钮 enabled，
        // 会在界面上挂着红色「请先选择分类」的同时写进一条 categoryName = "" 的流水
        if (f.categoryName.isBlank()) return
        lastSaveAt = nowMs
        viewModelScope.launch {
            // v16.5：目标账本 = 用户在这一笔里选的那个（默认就是当前账本）
            val targetId = if (f.targetLedgerId != 0L) f.targetLedgerId else ledgerId
            // 换过账本的话，项目/账户/报销状态都属于原账本，不能跟着搬
            val ledgerChanged = targetId != ledgerId
            // 报销状态按**目标账本**的场景判定：以前用的是打开时的账本，
            // 于是「报销账本里点开 → 切成日常账本 → 保存」照样写成待提交。
            val targetSupportsReimburse = if (!ledgerChanged) {
                supportsReimbursement
            } else {
                allLedgers.value.firstOrNull { it.id == targetId }
                    ?.let { ScenePresets.byKey(it.sceneKey).supportsReimbursement } ?: false
            }
            val reimburse = reimburseStateFor(targetSupportsReimburse, f.direction, f.extra)

            val editId = f.editingId
            if (editId != null) {
                val old = repo.getTransaction(editId)
                if (old != null) {
                    // v10.4：编辑时如果换了目标账本，直接写进 ledgerId ——
                    // 省掉"先保存再批量搬"两步。分类已在换账本时切成目标账本的。
                    val newLedgerId = if (f.targetLedgerId != 0L) f.targetLedgerId else old.ledgerId
                    repo.updateTransaction(
                        old.copy(
                            ledgerId = newLedgerId,
                            categoryId = f.categoryId,
                            categoryName = f.categoryName,
                            categoryEmoji = f.categoryEmoji,
                            direction = f.direction,
                            amountCents = cents,
                            note = f.note,
                            occurredAt = f.occurredAt,
                            extraJson = LedgerRepository.encodeExtra(f.extra),
                            reimburseState = reimburse,
                            projectId = if (newLedgerId != old.ledgerId) 0L else f.projectId,
                            accountId = if (newLedgerId != old.ledgerId) 0L else f.accountId
                        )
                    )
                }
            } else {
                // v10.5：新建也可换目标账本
                repo.addTransaction(
                    ledgerId = targetId,
                    categoryId = f.categoryId,
                    categoryName = f.categoryName,
                    categoryEmoji = f.categoryEmoji,
                    direction = f.direction,
                    amountCents = cents,
                    note = f.note,
                    occurredAt = f.occurredAt,
                    extra = f.extra,
                    reimburseState = reimburse,
                    // 换过账本：项目和账户都是原账本的，不带过去（与编辑分支同一口径）
                    projectId = if (ledgerChanged) 0L else f.projectId,
                    accountId = if (ledgerChanged) 0L else f.accountId
                )
            }
            reset(keepContext)
            onSaved()
        }
    }

    companion object {
        fun factory(ledgerId: Long) = viewModelFactory {
            initializer { AddTxnViewModel(ServiceLocator.repository, ledgerId) }
        }
    }
}

// ============================================================
//  多维筛选（v5.7）
//
//  关键词只能回答「有没有提到 XX」，回答不了「上个月 200 以上的支出有哪几笔」。
//  时间范围 / 收支方向 / 金额区间 —— 记账 App 最常用的三把尺子，可与关键词叠加。
// ============================================================

/** 时间范围 */
enum class FilterTimeRange(val label: String) {
    ALL("全部时间"),
    TODAY("今天"),
    THIS_WEEK("本周"),
    THIS_MONTH("本月"),
    LAST_MONTH("上月")
}

/** 金额区间（单位：元；流水内部存分，所以这里也是分） */
enum class FilterAmount(val label: String, val minCents: Long, val maxCents: Long?) {
    ANY("不限金额", 0, null),
    LT_50("50 元以下", 0, 50_00),
    B50_200("50 ~ 200", 50_00, 200_00),
    B200_1000("200 ~ 1000", 200_00, 1000_00),
    GT_1000("1000 元以上", 1000_00, null)
}

/**
 * 多维筛选条件（v5.7）。
 *
 * 三个维度都默认「不限」，[isActive] 为 false 时整套过滤直接放行 ——
 * 未设筛选时行为与以前完全一致，零回归风险。
 */
data class TxnFilter(
    val timeRange: FilterTimeRange = FilterTimeRange.ALL,
    val direction: Int? = null,
    val amount: FilterAmount = FilterAmount.ANY
) {
    val isActive: Boolean
        get() = timeRange != FilterTimeRange.ALL || direction != null || amount != FilterAmount.ANY

    /** 顶部提示条：一句话说清现在筛的是什么 */
    fun summary(): String {
        val parts = ArrayList<String>(3)
        if (timeRange != FilterTimeRange.ALL) parts.add(timeRange.label)
        if (direction == Direction.EXPENSE) parts.add("支出")
        if (direction == Direction.INCOME) parts.add("收入")
        if (amount != FilterAmount.ANY) parts.add(amount.label)
        return parts.joinToString(" · ")
    }

    fun applyTo(list: List<TransactionEntity>, nowMillis: Long): List<TransactionEntity> {
        if (!isActive) return list
        val zone = java.time.ZoneId.systemDefault()
        val now = java.time.Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val win = timeRangeWindow(now, zone)

        return list.filter { t ->
            if (win != null && t.occurredAt !in win) return@filter false
            if (direction != null && t.direction != direction) return@filter false
            if (amount != FilterAmount.ANY) {
                if (t.amountCents < amount.minCents) return@filter false
                val max = amount.maxCents
                if (max != null && t.amountCents > max) return@filter false
            }
            true
        }
    }

    /** 时间窗：`[起, 止)` 半开区间，边界不会重复算 */
    private fun timeRangeWindow(now: java.time.LocalDate, zone: java.time.ZoneId): LongRange? {
        fun atStart(d: java.time.LocalDate) = d.atStartOfDay(zone).toInstant().toEpochMilli()
        return when (timeRange) {
            FilterTimeRange.ALL -> null
            FilterTimeRange.TODAY ->
                atStart(now) until atStart(now.plusDays(1))
            FilterTimeRange.THIS_WEEK -> {
                val mon = now.with(java.time.DayOfWeek.MONDAY)
                atStart(mon) until atStart(mon.plusDays(7))
            }
            FilterTimeRange.THIS_MONTH ->
                atStart(now.withDayOfMonth(1)) until atStart(now.plusMonths(1).withDayOfMonth(1))
            FilterTimeRange.LAST_MONTH ->
                atStart(now.minusMonths(1).withDayOfMonth(1)) until atStart(now.withDayOfMonth(1))
        }
    }
}

// ============================================================
//  垫付 / 回款（v7.1）
// ============================================================

/**
 * 报销账本的主心骨数字（v7.1）：**尚余垫付** 和 **已回款**。
 *
 * 垫付是"钱压在外面"——它不是支出统计问题，是**现金流问题**：
 * 一笔报销从垫付到回款可能跨几个月，中间最想知道的就是"还有多少钱没回来"。
 *
 * 三档状态 → 两个核心数字的映射：
 *  - 尚余垫付 = 待提交 + 已提交（已经掏了、公司还没还的）
 *  - 已回款   = 已到账（钱回到口袋了）
 */
data class ReimburseSummary(
    val pendingCents: Long = 0L,
    val pendingCount: Int = 0,
    val submittedCents: Long = 0L,
    val submittedCount: Int = 0,
    val settledCents: Long = 0L,
    val settledCount: Int = 0
) {
    /** 尚余垫付：垫了还没回来的钱 */
    val outstandingCents: Long get() = pendingCents + submittedCents

    val outstandingCount: Int get() = pendingCount + submittedCount

    /** 已回款：公司已经还回来的钱 */
    val recoveredCents: Long get() = settledCents

    val recoveredCount: Int get() = settledCount

    /** 累计垫付 = 尚余 + 已回款 */
    val advancedCents: Long get() = outstandingCents + recoveredCents

    val advancedCount: Int get() = pendingCount + submittedCount + settledCount

    /** 回款率 0f..1f（没有垫付时为 0） */
    val recoveredFraction: Float
        get() = if (advancedCents <= 0L) 0f else recoveredCents.toFloat() / advancedCents.toFloat()

    /** 垫付过且全部回来了 —— 项目卡给「已结清」徽标 */
    val allSettled: Boolean get() = advancedCents > 0L && outstandingCents == 0L

    companion object {
        fun from(list: List<ReimburseStateAgg>): ReimburseSummary {
            fun agg(s: Int) = list.firstOrNull { it.state == s }
            val p = agg(ReimburseState.PENDING)
            val s = agg(ReimburseState.SUBMITTED)
            val t = agg(ReimburseState.SETTLED)
            return ReimburseSummary(
                pendingCents = p?.totalCents ?: 0L, pendingCount = p?.count ?: 0,
                submittedCents = s?.totalCents ?: 0L, submittedCount = s?.count ?: 0,
                settledCents = t?.totalCents ?: 0L, settledCount = t?.count ?: 0
            )
        }

        /** 项目级口径（ProjectStateAgg 带 projectId，聚合逻辑同 [from]） */
        fun fromProject(list: List<ProjectStateAgg>): ReimburseSummary {
            fun agg(s: Int) = list.firstOrNull { it.state == s }
            val p = agg(ReimburseState.PENDING)
            val s = agg(ReimburseState.SUBMITTED)
            val t = agg(ReimburseState.SETTLED)
            return ReimburseSummary(
                pendingCents = p?.totalCents ?: 0L, pendingCount = p?.count ?: 0,
                submittedCents = s?.totalCents ?: 0L, submittedCount = s?.count ?: 0,
                settledCents = t?.totalCents ?: 0L, settledCount = t?.count ?: 0
            )
        }
    }
}

// ============================================================
//  预算健康度（v6.0）
// ============================================================

/**
 * 单个分类的预算状态（v6.0）。
 *
 * @property budgetCents 月预算（分）；0 表示不参与健康度
 * @property usedCents   本月已支出（分）
 */
data class BudgetHealth(
    val categoryId: Long,
    val name: String,
    val emoji: String,
    val budgetCents: Long,
    val usedCents: Long
) {
    /** 用量比例，1.0 = 刚好用完，>1 = 超支 */
    val fraction: Float
        get() = if (budgetCents > 0) usedCents.toFloat() / budgetCents.toFloat() else 0f

    val overBudget: Boolean get() = usedCents > budgetCents

    /** 快到上限（≥80%）—— 提前一步提醒比事后追悔有用 */
    val nearLimit: Boolean get() = !overBudget && fraction >= 0.8f

    val remainingCents: Long get() = budgetCents - usedCents
}

// ============================================================
//  资产账户（v6.1）
// ============================================================

/**
 * 资产账户管理（v6.1）。
 *
 * 账户管的是**存量**（我现在有多少钱），流水管的是**流量**。
 * 余额不落库、而是 `初始余额 + Σ流水` 实时推算 —— 改一笔流水余额立刻跟着变，
 * 不会出现「余额 500 但流水加起来是 620」的对账灾难。
 */
class AccountViewModel(
    private val repo: LedgerRepository,
    val ledgerId: Long
) : ViewModel() {

    val accounts: StateFlow<List<AccountEntity>> =
        repo.accounts(ledgerId).stateIn(viewModelScope, STOP_EAGER, emptyList())

    /**
     * 账户 id → 当前余额（分）。
     *
     * 依赖 transactions 是关键：记一笔流水，余额立刻跟着变，不用手动"同步余额"。
     */
    val balances: StateFlow<Map<Long, Long>> =
        combine(accounts, repo.transactions(ledgerId)) { accs, txns ->
            val sums = txns.filter { it.accountId > 0 }
                .groupBy { it.accountId }
                .mapValues { (_, v) ->
                    val inc = v.filter { it.direction == Direction.INCOME }.sumOf { it.amountCents }
                    val exp = v.filter { it.direction == Direction.EXPENSE }.sumOf { it.amountCents }
                    inc - exp
                }
            accs.associate { a -> a.id to (a.initialBalanceCents + (sums[a.id] ?: 0L)) }
        }.stateIn(viewModelScope, STOP_EAGER, emptyMap())

    /** 净资产 = 计入汇总的账户余额之和 */
    val netWorth: StateFlow<Long> =
        combine(accounts, balances) { accs, bal ->
            accs.filter { it.includeInNetWorth && !it.closed }
                .sumOf { bal[it.id] ?: 0L }
        }.stateIn(viewModelScope, STOP_EAGER, 0L)

    fun create(
        name: String,
        type: Int,
        emoji: String,
        colorHex: String,
        initialBalanceCents: Long,
        includeInNetWorth: Boolean,
        note: String
    ) = viewModelScope.launch {
        repo.createAccount(
            AccountEntity(
                ledgerId = ledgerId,
                name = name,
                type = type,
                emoji = emoji,
                colorHex = colorHex,
                initialBalanceCents = initialBalanceCents,
                includeInNetWorth = includeInNetWorth,
                note = note
            )
        )
    }

    fun update(account: AccountEntity) = viewModelScope.launch {
        repo.updateAccount(account)
    }

    /** 删账户：先把它名下的流水退回「未指定」，再删账户（绝不连带删流水） */
    fun delete(accountId: Long) = viewModelScope.launch {
        repo.deleteAccount(accountId)
    }

    companion object {
        fun factory(ledgerId: Long) = viewModelFactory {
            initializer { AccountViewModel(ServiceLocator.repository, ledgerId) }
        }
    }
}

/** 年度总结（v7.0） */
data class AnnualSummary(
    val year: Int,
    val totalExpense: Long,
    val totalIncome: Long,
    /** 12 个月的支出，索引 0 = 1 月 */
    val monthlyExpense: List<Long>
) {
    val totalNet: Long get() = totalIncome - totalExpense
    /**
     * 月均支出。
     *
     * v9.7.1 修：原来固定 ÷12 —— 今年才过到 10 月时，月均只有真实值的 5/6，
     * 1 月时更是只有 1/12，看起来像"这个月花了极少"。
     * 今年按**已过月份**算，往年按 12 个月算。
     */
    val monthlyAvg: Long
        get() {
            if (totalExpense <= 0L) return 0L
            val months = if (year == java.time.LocalDate.now().year) {
                java.time.LocalDate.now().monthValue.coerceAtLeast(1)
            } else 12
            return totalExpense / months
        }
    /** 最烧钱的那个月（1~12），0 = 没数据 */
    val peakMonth: Int
        get() {
            var best = 0
            var bestVal = -1L
            monthlyExpense.forEachIndexed { i, v ->
                if (v > bestVal) { bestVal = v; best = i + 1 }
            }
            return if (bestVal > 0) best else 0
        }
}
