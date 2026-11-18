package com.foldledger.ui.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.foldledger.ServiceLocator
import com.foldledger.data.ScenePresets
import com.foldledger.data.db.Direction
import com.foldledger.data.db.LedgerEntity
import com.foldledger.data.db.ReimburseState
import com.foldledger.data.repo.LedgerRepository
import com.foldledger.util.DateExt
import com.foldledger.util.Money
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import com.foldledger.ui.l10n.t

/**
 * 记账助手（v9.0 / v9.3）——**今天该做什么，一页说清**。
 *
 * **v9.3：不再绑死单个账本。** 助手本来就是全局视角的事：自动记账的待确认
 * 挂在"自动记账"账本、垫付散在各个报销账本、预算红线是每个账本自己的事。
 * 绑在当前账本上，就等于"在报销账本里看不到日常账本的预算"——
 * 所以改成**跨账本聚合**：每条待办都带账本名，点一下能跳到那个账本。
 */
class AssistantViewModel(
    private val repo: LedgerRepository
) : ViewModel() {

    /** 待办动作类型（决定点一下跳哪儿） */
    enum class Action { NONE, SPEED, STATS, AUTO_LEDGER, ADD, LEDGER }

    /** 一条待办 */
    data class Todo(
        val emoji: String,
        val title: String,
        val detail: String,
        val action: Action = Action.NONE,
        val actionLabel: String = "",
        /** 0 提醒 / 1 需注意 / 2 紧急 */
        val tone: Int = 0,
        /** 关联账本（0 = 全局） */
        val ledgerId: Long = 0L,
        val ledgerName: String = ""
    )

    /** 一条建议 */
    data class Tip(val emoji: String, val text: String)

    val ledgers: StateFlow<List<LedgerEntity>> =
        repo.ledgers.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * 时间锚点（v9.7.2）。
     *
     * 原来 `now / monthFrom / prevFrom` 都在构造时算死 —— 助手页的 VM 挂在 Activity 上，
     * App 常驻不重启时，跨天/跨月后打开助手看到的还是"打开那一刻"的待办和环比。
     * 现在统一从锚点派生，回到前台调 [refreshPeriod] 推一次即可。
     */
    private val periodAnchor = MutableStateFlow(System.currentTimeMillis())

    fun refreshPeriod() {
        periodAnchor.value = System.currentTimeMillis()
    }

    /** 当前锚点的毫秒值（算「还有几天到期」这类相对时间用） */
    private val now: Long get() = periodAnchor.value

    /** 自动记账账本 id（t("待确认")那件事的落点） */
    val autoLedgerId: StateFlow<Long?> =
        ledgers.map { list -> list.firstOrNull { it.sceneKey == ScenePresets.AUTO_KEY }?.id }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val monthTotals = periodAnchor.flatMapLatest { n ->
        repo.ledgerTotalsInRange(DateExt.startOfMonth(n), DateExt.endOfMonth(n))
    }
    private val prevTotals = periodAnchor.flatMapLatest { n ->
        // 上月区间：取"本月月初 - 1 毫秒"再套 startOfMonth/endOfMonth，
        // 这样在 1 号、31 号这些边界上也不会算错
        val anchor = DateExt.startOfMonth(n) - 1L
        repo.ledgerTotalsInRange(DateExt.startOfMonth(anchor), DateExt.endOfMonth(anchor))
    }
    private val reimburseAggs = repo.allReimburseStateTotals()
    private val dueAll = periodAnchor.flatMapLatest { n -> repo.allRecurringDue(n) }
    private val todayLedgerCount = periodAnchor.flatMapLatest { n ->
        repo.ledgerCountInRange(DateExt.startOfDay(n), DateExt.endOfDay(n))
    }

    /**
     * 今日待办（跨账本聚合）。
     *
     * v9.7.1：初值 null = "还没算出来"，避免首帧闪一下「都办完了，收工 ✓」。
     */
    val todos: StateFlow<List<Todo>?> =
        // kotlinx 的 typed combine 最多 5 个流，这里分两组再合
        combine(
            combine(ledgers, monthTotals, reimburseAggs) { l, m, r -> Triple(l, m, r) },
            combine(
                dueAll,
                repo.autoPendingCount(),
                todayLedgerCount
            ) { d, a, t -> Triple(d, a, t) }
        ) { first, second ->
            val ledgers = first.first
            val monthAggs = first.second
            val reimbAggs = first.third
            val due = second.first
            val autoPending = second.second
            val todayLedgers = second.third
            val out = ArrayList<Todo>()
            val nameOf = HashMap<Long, String>()
            ledgers.forEach { nameOf[it.id] = it.name }

            // 1. 今天还没记账（全局：所有账本今天都没有流水）
            if (todayLedgers == 0) {
                out += Todo(
                    emoji = "⚡",
                    title = t("今天还没记账"),
                    detail = t("一句话就记完：昨天打车35"),
                    action = Action.SPEED,
                    actionLabel = t("去速记"),
                    tone = 1
                )
            }

            // 2. 自动记账待确认（全局）
            if (autoPending > 0) {
                out += Todo(
                    emoji = "🤖",
                    title = "自动记账有 $autoPending 笔待确认",
                    detail = t("确认后移去正式账本，分类会自动适配"),
                    action = Action.AUTO_LEDGER,
                    actionLabel = t("去处理"),
                    tone = 1
                )
            }

            // 3. 尚余垫付（按账本，报销场景的账本才算）
            val outstandingByLedger = HashMap<Long, Pair<Long, Int>>()
            reimbAggs.forEach { a ->
                if (a.state == ReimburseState.PENDING || a.state == ReimburseState.SUBMITTED) {
                    val cur = outstandingByLedger[a.ledgerId] ?: (0L to 0)
                    outstandingByLedger[a.ledgerId] = (cur.first + a.totalCents) to (cur.second + a.count)
                }
            }
            ledgers.filter { ScenePresets.byKey(it.sceneKey).supportsReimbursement }
                .forEach { l ->
                    val v = outstandingByLedger[l.id] ?: return@forEach
                    if (v.first <= 0L) return@forEach
                    out += Todo(
                        emoji = "⏳",
                        title = "${l.name} · 尚余垫付 ${Money.format(v.first)}",
                        detail = "${v.second} 笔待回款",
                        action = Action.LEDGER,
                        actionLabel = t("去账本"),
                        tone = 1,
                        ledgerId = l.id,
                        ledgerName = l.name
                    )
                }

            // 4. 预算红线（按账本）
            val expenseByLedger = HashMap<Long, Long>()
            monthAggs.forEach { a ->
                if (a.direction == Direction.EXPENSE) expenseByLedger[a.ledgerId] = a.totalCents
            }
            ledgers.filter { it.budgetCents > 0L }.forEach { l ->
                val used = expenseByLedger[l.id] ?: 0L
                if (used <= 0L) return@forEach
                val pct = used * 100 / l.budgetCents
                if (pct >= 85) {
                    out += Todo(
                        emoji = "🔋",
                        title = "${l.name} · 预算已用 $pct%",
                        detail = "${Money.format(used)} / ${Money.format(l.budgetCents)}",
                        action = Action.STATS,
                        actionLabel = t("看统计"),
                        tone = if (pct >= 100) 2 else 1,
                        ledgerId = l.id,
                        ledgerName = l.name
                    )
                }
            }

            // 5. 周期记账即将到期（按账本归并）
            val dueByLedger = due.groupBy { it.ledgerId }
            dueByLedger.forEach { (lid, items) ->
                val soon = items.filter { it.nextDueAt - now <= 3 * 24L * 3600 * 1000 }
                if (soon.isEmpty()) return@forEach
                val lname = nameOf[lid] ?: t("账本")
                out += Todo(
                    emoji = "🔁",
                    title = "$lname · ${soon.size} 笔周期记账即将到期",
                    detail = soon.take(2).joinToString("、") { it.name },
                    action = Action.ADD,
                    actionLabel = t("去记账"),
                    tone = 1,
                    ledgerId = lid,
                    ledgerName = lname
                )
            }
            out
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 智能建议（全局环比） */
    val tips: StateFlow<List<Tip>> =
        combine(monthTotals, prevTotals) { curAggs, prevAggs ->
            val out = ArrayList<Tip>()
            val cur = curAggs.filter { it.direction == Direction.EXPENSE }.sumOf { it.totalCents }
            val prev = prevAggs.filter { it.direction == Direction.EXPENSE }.sumOf { it.totalCents }
            if (prev > 0L && cur > 0L) {
                val diff = cur - prev
                val pct = Math.abs(diff) * 100 / prev
                if (pct >= 10) {
                    out += Tip(
                        emoji = if (diff > 0) "📈" else "📉",
                        text = if (diff > 0) "本月比上月多花 ${Money.format(diff)}（+$pct%）"
                        else "本月比上月少花 ${Money.format(-diff)}（-$pct%）"
                    )
                }
            }
            out
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    companion object {
        fun factory() = viewModelFactory {
            initializer { AssistantViewModel(ServiceLocator.repository) }
        }
    }
}
