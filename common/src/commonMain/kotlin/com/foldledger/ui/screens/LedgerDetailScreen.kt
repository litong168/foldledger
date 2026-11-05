package com.foldledger.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.launch
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.foldledger.data.ScenePresets
import com.foldledger.data.db.AccountEntity
import com.foldledger.data.db.CategoryEntity
import com.foldledger.data.db.Direction
import com.foldledger.data.db.LedgerEntity
import com.foldledger.data.db.ProjectAgg
import com.foldledger.data.db.ProjectEntity
import com.foldledger.data.db.ProjectStateAgg
import com.foldledger.data.db.ReimburseState
import com.foldledger.data.db.TransactionEntity
import com.foldledger.data.repo.LedgerRepository
import com.foldledger.ui.adaptive.AdaptiveScaffold
import com.foldledger.ui.adaptive.AppTab
import com.foldledger.ui.adaptive.FoldEnv
import com.foldledger.ui.anim.Motion
import com.foldledger.ui.anim.clickableScale
import com.foldledger.ui.anim.valuePulse
import com.foldledger.ui.components.AmountText
import com.foldledger.ui.components.AnimatedCentsText
import com.foldledger.ui.components.CategoryIconBadge
import com.foldledger.ui.components.EmptyHint
import com.foldledger.ui.components.GlassDialog
import com.foldledger.ui.components.LedgerAvatar
import com.foldledger.ui.components.PillTag
import com.foldledger.ui.components.ThinProgress
import com.foldledger.ui.components.parseHexColor
import com.foldledger.ui.theme.GlassGlowDriver
import com.foldledger.ui.theme.Hairline
import com.foldledger.ui.theme.glassSurface
import com.foldledger.ui.theme.Layout
import com.foldledger.ui.theme.LocalDirectionColors
import com.foldledger.ui.theme.Radius
import com.foldledger.ui.theme.ReimbursePending
import com.foldledger.ui.theme.ReimburseSettled
import com.foldledger.ui.theme.ReimburseSubmitted
import com.foldledger.ui.theme.Space
import com.foldledger.ui.theme.SurfaceCard
import com.foldledger.ui.vm.DayGroup
import com.foldledger.ui.vm.FilterAmount
import com.foldledger.ui.vm.FilterTimeRange
import com.foldledger.ui.vm.TxnFilter
import com.foldledger.ui.vm.LedgerDetailViewModel
import com.foldledger.ui.vm.LedgerUi
import com.foldledger.ui.vm.ReimburseSummary
import com.foldledger.util.DateExt
import com.foldledger.util.Money
import com.foldledger.util.ReimburseAging
import com.foldledger.ui.theme.cardSurface
import com.foldledger.ui.theme.cardBorder
import com.foldledger.ui.theme.dividerBrush
import com.foldledger.ui.theme.onColorFor
import com.foldledger.ui.theme.softShadow
import com.foldledger.ui.theme.rememberAnimatedPaneWidth
import kotlinx.coroutines.delay
import com.foldledger.ui.screens.detail.AssetBar
import com.foldledger.ui.screens.detail.LedgerOverviewCard
import com.foldledger.ui.screens.detail.FilteredEmptyHint
import com.foldledger.ui.screens.detail.ReimburseFilterBar
import com.foldledger.ui.screens.detail.ReimburseBoard
import com.foldledger.ui.screens.detail.SettledArchiveBar
import com.foldledger.ui.screens.detail.ChaseListCard
import com.foldledger.ui.screens.detail.ProjectBar
import com.foldledger.ui.screens.detail.ProjectSummaryCard
import com.foldledger.ui.screens.detail.ProjectEditorDialog
import com.foldledger.ui.screens.detail.FilterOverlay
import com.foldledger.ui.screens.detail.FilterActiveBar
import com.foldledger.ui.screens.detail.BatchToolbar
import com.foldledger.ui.screens.detail.BatchActionDialog
import com.foldledger.ui.screens.detail.BatchPickerSheet
import com.foldledger.ui.screens.detail.CategoryBreakdownPage
import com.foldledger.ui.screens.detail.DetailSegment
import com.foldledger.ui.screens.detail.GlassSegmentBar
import com.foldledger.ui.screens.detail.MonthBreakdownPage
import com.foldledger.ui.screens.detail.BatchMode
import com.foldledger.ui.screens.detail.DayGroupCard

/**
 * 明细页 —— 显示「账本页」当前选中的那个分账本的流水。
 * 顶部有账本切换条，不用回账本页也能换。
 */
@Composable
fun LedgerDetailTabScreen(
    env: FoldEnv,
    cards: List<LedgerUi>,
    currentLedgerId: Long?,
    onGoBooks: () -> Unit,
    onOpenStats: (Long) -> Unit,
    onOpenCategories: (Long) -> Unit,
    /** v6.2：打开资产账户管理 */
    onOpenAccounts: (Long) -> Unit,
    onAddTransaction: (Long) -> Unit,
    onEditTransaction: (Long, Long) -> Unit,
    /** v10.1.0：概览卡是否收起（只留主角大数字） */
    overviewCompact: Boolean = false,
    onToggleOverviewCompact: () -> Unit = {},
    /** v3.2.0：外壳（顶栏 / 导航栏 / FAB）已上提到 AppRoot，本页只画内容 */
    modifier: Modifier = Modifier
) {
    val current = cards.firstOrNull { it.ledger.id == currentLedgerId } ?: cards.firstOrNull()

    // v3.2.0：外壳已上提，这里直接画内容。`modifier` 已含顶栏与导航栏内边距。
    if (current == null) {
        Box(
            modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            EmptyHint(
                emoji = "📒",
                title = "还没有账本",
                subtitle = "去「账本」页创建一个分账本，明细会显示在这里",
                actionLabel = "去账本页",
                onAction = onGoBooks
            )
        }
    } else {
        val vm: LedgerDetailViewModel = viewModel(
            key = "detail-${current.ledger.id}",
            factory = LedgerDetailViewModel.factory(current.ledger.id)
        )
        // v9.7.1：回到前台重算"本月"区间（跨月不重启也不会停在上个月）
        androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
            vm.refreshPeriod()
            onPauseOrDispose { }
        }


        Column(
            Modifier
                .then(modifier)
                .fillMaxSize()
        ) {
            // v13.7：账本 chips 行退役。
            // 它的内容（账本名 + 结余）现在在**顶栏大标题的下拉**里：
            // 大标题本身就是当前账本名，点右侧箭头换 —— 信息一条不少，
            // 但不再为了"偶尔切一次"常年占掉 48dp，也不再和标题重复同一句话。
            LedgerDetailBody(
                env = env,
                vm = vm,
                modifier = Modifier.fillMaxSize(),
                overviewCompact = overviewCompact,
                onToggleOverviewCompact = onToggleOverviewCompact,
                onAddTransaction = { onAddTransaction(current.ledger.id) },
                onEditTransaction = { onEditTransaction(current.ledger.id, it) },
                onOpenAccounts = { onOpenAccounts(current.ledger.id) }
            )
        }
    }
}

// ============================================================
//  明细主体：窄屏单栏 / 展开态左右分栏
// ============================================================

@Composable
private fun LedgerDetailBody(
    env: FoldEnv,
    vm: LedgerDetailViewModel,
    modifier: Modifier,
    overviewCompact: Boolean = false,
    onToggleOverviewCompact: () -> Unit = {},
    onAddTransaction: () -> Unit,
    onEditTransaction: (Long) -> Unit,
    /** v6.2：打开资产账户管理 */
    onOpenAccounts: () -> Unit,
) {

    // v13.9.2：列表底部留白必须**跟随系统导航栏高度**，而不是写死 72dp。
    // 浮动底栏（玻璃胶囊 52dp + 下方 12dp 外边距 + 系统导航栏 inset）从屏幕底占去
    // 12 + inset + 52 dp；列表末项必须清过这一高度，否则最后一条会被浮在上面的
    // 玻璃胶囊盖住（v13.9.2 实测：归档条下半截被底栏遮掉）。再多留 12dp 当作与
    // 胶囊之间的呼吸缝 —— 既不贴脸，也不再在末条与胶囊间留一大段空背景带。
    // 与 AdaptiveScaffold 里算 bar 的 inset 用同一来源（WindowInsets.navigationBars），
    // 这样不同设备 / 不同导航模式（手势条 vs 三键）下都刚好顶到胶囊上方。
    val density = androidx.compose.ui.platform.LocalDensity.current
    val navBottomInset = with(density) {
        WindowInsets.navigationBars.getBottom(density).toDp()
    }
    val listBottomPad = 12.dp + navBottomInset + 52.dp + 12.dp

    // v9.7.1：单笔删除要先确认 —— 流水是真删（不进回收站），
    // 原来点一下就没了，隔壁批量删除反而有确认框
    var pendingDeleteTxnId by remember { mutableStateOf<Long?>(null) }
    pendingDeleteTxnId?.let { id ->
        GlassDialog(
            onDismissRequest = { pendingDeleteTxnId = null },
            title = { Text("删除这一笔？") },
            text = {
                Text(
                    "流水删除后不进回收站，没法还原。\n" +
                        "如果只是记错了分类或金额，建议直接编辑它。",
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    vm.deleteTransaction(id)
                    pendingDeleteTxnId = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { pendingDeleteTxnId = null }) {
                    Text("取消")
                }
            }
        )
    }
    val ledger by vm.ledger.collectAsStateWithLifecycle()
    val groups by vm.grouped.collectAsStateWithLifecycle()
    val searchQuery by vm.searchQuery.collectAsStateWithLifecycle()
    val filteredGroups by vm.filteredGrouped.collectAsStateWithLifecycle()
    val totalExpense by vm.totalExpense.collectAsStateWithLifecycle()
    val totalIncome by vm.totalIncome.collectAsStateWithLifecycle()
    val month by vm.monthTotals.collectAsStateWithLifecycle()
    val supportsReimburse by vm.supportsReimbursement.collectAsStateWithLifecycle()
    // v7.1 垫付/回款：账本级给概览卡，跟项目的给看板（看板数字必须和列表对得上）
    val reimburseGlobal by vm.reimburseSummary.collectAsStateWithLifecycle()
    val board by vm.reimburseSummaryScoped.collectAsStateWithLifecycle()
    // v7.2 未回款账龄 / 催办清单
    val aging by vm.reimburseAging.collectAsStateWithLifecycle()
    val outstanding by vm.outstandingItems.collectAsStateWithLifecycle()
    // v8.1 批量移动到账本的目标列表
    val otherLedgers by vm.otherLedgers.collectAsStateWithLifecycle()

    // ---- 项目 / 空间（v5.1）----
    val projects by vm.projects.collectAsStateWithLifecycle()
    val projectFilter by vm.projectFilter.collectAsStateWithLifecycle()
    val projectTotals by vm.projectTotals.collectAsStateWithLifecycle()
    val projectStateTotals by vm.projectStateTotals.collectAsStateWithLifecycle()
    val projectNames by vm.projectNames.collectAsStateWithLifecycle()
    // v14.2：每个项目「待提交」笔数 —— 项目 chip 上的待办徽标（零新查询，复用已有汇总）
    val pendingByProject = remember(projectStateTotals) {
        projectStateTotals.mapValues { (_, list) ->
            list.filter { it.state == ReimburseState.PENDING }.sumOf { it.count }
        }
    }
    /**
     * v13.8：哪些项目已经"不需要再盯着"了。
     *
     * 判据是 **`pendingCount == 0`**，也就是**这个项目里没有任何一条还停在「待提交」**。
     * 为什么不是"已回款"：报销流程里真正需要你动手的只有"待提交"这一步 ——
     * 单子交出去之后，剩下的是等公司打款，那件事在「尚余垫付 / 催收列表」里统一看，
     * 不需要在项目条上一个个占位。用户的原话也是"条目状态全部变为已提交之后"。
     *
     * 手动「已结束」（`closed`）的项目同样归档 —— 它和"提交完了"是同一个意思：
     * 这趟事办完了。归档**不删数据**，流水、归属、金额全都还在，只是从项目条挪到抽屉里。
     */
    val archivedProjectIds = remember(projects, projectStateTotals) {
        projects.filter { p ->
            if (p.closed) return@filter true
            val states = projectStateTotals[p.id].orEmpty()
            // 一个还没记过支出的项目不算归档：它只是空壳，用户建了还没用
            if (states.isEmpty()) return@filter false
            val pending = states.firstOrNull { it.state == ReimburseState.PENDING }
            (pending?.count ?: 0) == 0
        }.map { it.id }.toSet()
    }
    val accountNames by vm.accountNames.collectAsStateWithLifecycle()
    // v6.2：资产总览
    val accountBalances by vm.accountBalances.collectAsStateWithLifecycle()
    val netWorth by vm.netWorth.collectAsStateWithLifecycle()
    val hasAccounts = accountBalances.isNotEmpty()
    val reimburseFilter by vm.reimburseFilter.collectAsStateWithLifecycle()

    // v13.9.2：**「已到账」默认收起来**。
    // 报销账本里钱已经回来了，这一档不用一直占着列表位置（用户原话：
    // "已到账的条目也隐藏或归档，只显示待提交和已提交就行了"）。
    // 收起来不等于没了 —— 列表末尾给一扇门（[SettledArchiveBar]）把它们放回来。
    //
    // 判据：账本支持报销 + 确实有已到账的条目 + 当前筛选没有把「已到账」选进来。
    // 用 `board`（跟随项目筛选的汇总）而不是全局汇总，否则项目筛选下数字会打架。
    val settledHidden = supportsReimburse &&
        ReimburseState.SETTLED !in reimburseFilter &&
        board.settledCount > 0

    // 项目编辑弹窗：null = 关闭；新建时传一个 id=0 的空项目
    var projectEditor by remember { mutableStateOf<ProjectEntity?>(null) }

    // ---- 固定支出（v5.7）----
    val recurringDue by vm.recurringDue.collectAsStateWithLifecycle()
    val recurringAll by vm.recurringAll.collectAsStateWithLifecycle()
    val allCategories by vm.categories.collectAsStateWithLifecycle()
    var recurringOpen by remember { mutableStateOf(false) }

    // ---- 批量改账（v5.6）----
    val multiSelect by vm.multiSelect.collectAsStateWithLifecycle()
    val selectedIds by vm.selectedIds.collectAsStateWithLifecycle()
    val selectionSummary by vm.selectionSummary.collectAsStateWithLifecycle()
    /** 当前弹出的批量操作类型；null = 没在弹 */
    var batchMode by remember { mutableStateOf<BatchMode?>(null) }

    // v15 自动记账：自动账本的行级「✓ 入账」—— 一键入账到上次确认的账本；
    // 还没记过目标账本时，退化为"这一笔的移动到账本"（弹现有选择器）
    val isAutoLedger = ledger?.sceneKey == ScenePresets.AUTO_KEY
    fun confirmInto(txnId: Long) {
        if (!vm.quickConfirm(txnId)) {
            vm.enterMultiSelect(txnId)
            batchMode = BatchMode.MOVE
        }
    }
    // ---- 多维筛选（v5.7）----
    val txnFilter by vm.filter.collectAsStateWithLifecycle()
    var filterOpen by remember { mutableStateOf(false) }
    /** 当前过滤后可见的流水 id（全选/反选按这个范围走，别把筛掉的也算进去） */
    val visibleIds = remember(filteredGroups) {
        filteredGroups.flatMap { g -> g.items.map { it.id } }
    }
    /**
     * v16.3：筛选提示条的「笔数 / 合计」只随 filteredGroups 变化算一次。
     * 原来双栏 / 单栏两处分支每次重组都 `flatMap` 出一份中间 List 再 `sumOf`，
     * 滚动光相或任意状态刷新都会把全表重扫一遍。口径不变，仍是过滤后全部流水之和。
     */
    val filterSummary = remember(filteredGroups) {
        var count = 0
        var amount = 0L
        filteredGroups.forEach { g ->
            count += g.items.size
            g.items.forEach { amount += it.amountCents }
        }
        count to amount
    }

    // v5.2：看板数字要跟随当前项目 —— 否则"看板 3000、点进去只有 800"会让人以为数据错了
    // v7.1：scoping 已收进 VM 的 reimburseSummaryScoped（board），这里不再手算

    // 双栏 ↔ 单栏共享滚动状态（v3.1.1）：折叠/展开时不再"跳回第一笔"。
    // 两栏分支的前导项不同（双栏左列是「流水记录」标题 + 搜索框，单栏是概览卡 + 看板 + 搜索框），
    // 下标只是近似对齐，但比起每次展开都跳回顶部，这一点点偏移可以忽略。
    val listState = rememberLazyListState()
    val overviewScroll = rememberScrollState()

    // v13.5：**滚动 → 顶栏折叠**（iOS 大标题的手感：不是切换，是被推上去）。
    //
    // 进度只取「第一个可见项」这一对值：
    //  - index 仍是 0 时，offset 在概览卡内部连续增长 —— 正好铺满 0→1 的斜坡；
    //  - 一旦滚过第一项（index > 0）就直接到底，不必再算。
    // 斜坡取 72dp（≈ 概览卡标题区的高度）：再短会"啪"地收起，再长则半屏都在变形。
    //
    // ⚠️ 这个写入**不会**反过来改变 listState：折叠的只是顶栏栏高与 chips 行高，
    //    LazyColumn 的首项锚点不变，所以不会出现"收起→布局变→再触发收起"的抖动。
    val titleCollapse = com.foldledger.ui.adaptive.LocalTitleCollapse.current
    val collapseRampPx = with(LocalDensity.current) { 72.dp.toPx() }
    DisposableEffect(Unit) {
        onDispose { titleCollapse?.reset() }
    }
    // v13.5 的顶栏折叠：随主列表滚动把大标题推上去。
    // ⚠️ 必须与 Glass 2.0 的光相驱动**分开** —— 两者都要监听同一份滚动状态，
    // 但一个改顶栏高度、一个改玻璃高光，混在一个 collect 里会让"折叠"跟着
    // "光相"一起被跳过（比如折叠状态为 null 时光相也一起不工作了）。
    LaunchedEffect(listState, titleCollapse) {
        if (titleCollapse == null) return@LaunchedEffect
        snapshotFlow {
            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }.collect { (index, offset) ->
            titleCollapse.set(if (index > 0) 1f else offset / collapseRampPx)
        }
    }
    // Glass 2.0：把主列表的滚动接到底栏玻璃的光相上。
    //
    // 旧写法（v14.0.0 草稿）用「index*1000 + offset」当连续量折成 0..1 相位，两个问题：
    //  ① 位置为 0 时相位也是 0 → 折出的扫掠量是 -1 而非 0 → 顶部静置时底栏高光
    //     被推到 0.16 宽度处，与 v13.9 的 0.28 对不上（**回归**，不是效果）；
    //  ② 假设每行 1000px，而真实流水行约 140px → 每换一行连续量猛跳 860px、
    //     相位跳 1.4 圈 → 高光闪一下。
    //
    // 现在改成**速度驱动**：静止 = 0（逐像素回到 v13.9），换行精确补偿不跳变，
    // 停手后高光自己荡回原位。详见 GlassGlowEngine。
    GlassGlowDriver(listState)

    // v13：明细 / 分类 / 月份 的横滑分页。
    // 用真正的 Pager 而不是"手势 + 换内容"：只有 Pager 是**跟手**的，
    // 也只有它能吃到平台的 overscroll（Android 12+ 的拉伸+弹簧回位），
    // 这才是 iOS 那种"滑到头会顶一下再弹回来"的来源。
    val pageState = rememberPagerState(pageCount = { DetailSegment.entries.size })

    // v13.2：翻页**落位**时给一次轻微触感。
    // 注意是 settledPage 而不是 currentPage —— currentPage 在拖动过程中会随手指反复变，
    // 用它会在一次滑动里连震好几下（那是"故障感"，不是"手感"）。
    // 用 TextHandleMove 而不是 MEDIUM：它短、轻，像指尖划过一格；MEDIUM 是"按了个按钮"。
    // 首帧也会触发一次 settledPage 的 LaunchedEffect，所以要用 armed 标记挡掉打开页面那一下。
    val haptics = LocalHapticFeedback.current
    var hapticArmed by remember { mutableStateOf(false) }
    LaunchedEffect(pageState.settledPage) {
        if (hapticArmed) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        hapticArmed = true
        // v13.5：翻页时把顶栏折叠对齐到"当前这一页"的状态。
        // 分类/月份页没有可滚动的长列表，顶栏若还停在上一次的收起态，
        // 用户会发现"换账本的 chips 忽然不见了"——那里恰恰还需要它。
        val c = titleCollapse
        if (c != null) {
            if (pageState.settledPage == 0) {
                val idx = listState.firstVisibleItemIndex
                c.set(if (idx > 0) 1f else listState.firstVisibleItemScrollOffset / collapseRampPx)
            } else {
                c.reset()
            }
        }
    }
    val scope = rememberCoroutineScope()
    // 分类/月份两个视图要看**整本账**的流水，不能用过滤后的 filteredGroups
    // （那会把"当前搜索/筛选"带进去，切到分类页会莫名其妙只剩几条）
    val allTxns by vm.transactions.collectAsStateWithLifecycle()

    // v5.2：点看板/项目卡切了报销状态筛选，把列表带回顶部（否则筛选结果可能在视野外）
    LaunchedEffect(reimburseFilter) {
        if (reimburseFilter.isNotEmpty() && groups.isNotEmpty()) {
            listState.animateScrollToItem(0)
        }
    }

    if (env.twoPane) {
        // 左栏宽度由 FoldEnv 的离线搜索结果给出（不再让用户调）
        val masterWidth = rememberAnimatedPaneWidth(env.paneMasterWidthDp(navigationVisible = true))
        Row(modifier) {
            // ---- 左：流水 / 分类 / 月份（可横滑） ----
            // v13：窄栏宽 368dp 也足以承载分类/月份视图，所以三条腿都放在左栏里，
            // 右栏始终留给"概览"——展开态一眼能看到的是"钱花在哪 + 还剩多少"。
            Column(Modifier.width(masterWidth).fillMaxHeight()) {
                GlassSegmentBar(
                    selected = pageState.currentPage,
                    onSelect = { k -> scope.launch { pageState.animateScrollToPage(k) } },
                    modifier = Modifier.padding(start = 20.dp, end = 16.dp, top = 10.dp, bottom = 8.dp)
                )
                HorizontalPager(
                    state = pageState,
                    modifier = Modifier.weight(1f),
                    beyondViewportPageCount = 0
                ) { page ->
                    when (page) {
                        0 -> LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 20.dp, end = 16.dp, top = 6.dp, bottom = listBottomPad),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        item { SectionTitle("流水记录") }
                        // v5.1：项目/空间切换条 —— 一次差旅、一次招待各归一块
                        if (supportsReimburse) {
                            item {
                                ProjectBar(
                                    projects = projects,
                                    selected = projectFilter,
                                    onSelect = vm::setProjectFilter,
                                    onCreate = { projectEditor = blankProject(ledger?.id ?: 0L) },
                                    archivedIds = archivedProjectIds, pendingCounts = pendingByProject,
                                    // v16.1.1：桌面宽屏左栏窄，LazyRow 只露前几个 chip → FlowRow 全展开
                                    expanded = env.twoPane && !env.isFoldable
                                )
                            }
                            val current = projects.firstOrNull { it.id == projectFilter }
                            if (current != null) {
                                item {
                                    ProjectSummaryCard(
                                        project = current,
                                        agg = projectTotals[current.id],
                                        states = projectStateTotals[current.id].orEmpty(),
                                        selectedStates = reimburseFilter,
                                        onSelectState = vm::toggleReimburseFilter,
                                        archived = current.id in archivedProjectIds,
                                        onEdit = { projectEditor = current }
                                    )
                                }
                            }
                        }
                        // v5.2：按报销状态筛选时，顶部挂一条"当前只看什么"的提示
                        if (reimburseFilter.isNotEmpty()) {
                            item {
                                ReimburseFilterBar(
                                    states = reimburseFilter,
                                    groups = filteredGroups,
                                    onClear = vm::clearReimburseFilter
                                )
                            }
                        }
                        if (groups.isNotEmpty()) {
                            // v13.4：与单栏同步 —— 非多选态把筛选 / 多选并进搜索行
                            if (multiSelect) {
                                item {
                                    BatchToolbar(
                                        multiSelect = true,
                                        selectedCount = selectionSummary.first,
                                        selectedAmount = selectionSummary.second,
                                        hasReimburse = supportsReimburse,
                                        visibleIds = visibleIds,
                                        filterActive = txnFilter.isActive,
                                        onOpenFilter = { filterOpen = true },
                                        onEnter = { vm.enterMultiSelect() },
                                        onExit = vm::exitMultiSelect,
                                        onSelectAll = { vm.selectAll(visibleIds) },
                                        onInvert = { vm.invertSelection(visibleIds) },
                                        onBatchCategory = { batchMode = BatchMode.CATEGORY },
                                        onBatchProject = { batchMode = BatchMode.PROJECT },
                                        onBatchReimburse = { batchMode = BatchMode.REIMBURSE },
                                        onBatchMove = { batchMode = BatchMode.MOVE },
                                        onBatchDelete = { batchMode = BatchMode.DELETE },
                                        moveLabel = if (isAutoLedger) "✓ 确认入账" else "移动到账本"
                                    )
                                }
                                item { SearchBar(searchQuery, vm::setSearchQuery) }
                            } else {
                                item {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(Modifier.weight(1f)) {
                                            SearchBar(searchQuery, vm::setSearchQuery)
                                        }
                                        Spacer(Modifier.width(6.dp))
                                        ToolPill(
                                            "筛选",
                                            active = txnFilter.isActive
                                        ) { filterOpen = true }
                                        Spacer(Modifier.width(6.dp))
                                        ToolPill(
                                            "多选",
                                            enabled = visibleIds.isNotEmpty()
                                        ) { vm.enterMultiSelect() }
                                    }
                                }
                            }
                            // v5.7：筛选生效时，搜索栏下方挂一条"现在只看什么"的提示
                            if (txnFilter.isActive) {
                                item {
                                    FilterActiveBar(
                                        filter = txnFilter,
                                        count = filterSummary.first,
                                        amount = filterSummary.second,
                                        onClear = vm::clearFilter
                                    )
                                }
                            }
                        }
                        when {
                            groups.isEmpty() -> item {
                                EmptyHint(
                                    emoji = "🧾",
                                    title = "还没有记账记录",
                                    subtitle = "点右下角那颗「＋」，给这个账本添上第一笔",
                                    // ⚠️ v13.9：**空态里不再放「记一笔」按钮**。
                                    // 右下角那颗玻璃圆键是常驻的，空态再放一颗一模一样的按钮，
                                    // 用户看到的是"两个记一笔"，读不出"这里空着"。
                                    // 空态的职责只有一个：解释为什么空。动作交给唯一的那颗键。
                                    highlight = true
                                )
                            }
                            filteredGroups.isEmpty() && !settledHidden -> item {
                                FilteredEmptyHint(searchQuery, projectFilter, reimburseFilter)
                            }
                            else -> items(
                                filteredGroups,
                                key = { it.dayKey },
                                contentType = { "dayGroup" }
                            ) { g ->
                                DayGroupCard(
                                    group = g,
                                    sceneKey = ledger?.sceneKey ?: ScenePresets.CUSTOM_KEY,
                                    projectNames = projectNames,
                                    accountNames = accountNames,
                                    multiSelect = multiSelect,
                                    selectedIds = selectedIds,
                                    onToggle = vm::toggleSelect,
                                    modifier = Modifier.animateItem(
                                        // v15：入账/删除/改账后这一行不是瞬间消失 —— 淡出 220ms，
                                        // 让人眼追上"刚才那笔去哪了"
                                        fadeOutSpec = tween(220, easing = androidx.compose.animation.core.LinearEasing)
                                    ),
                                    onEdit = onEditTransaction,
                                    onDelete = { pendingDeleteTxnId = it },
                                    onReimburse = { id, state -> vm.setReimburseState(listOf(id), state) },
                                    quickConfirm = if (isAutoLedger) ({ id -> confirmInto(id) }) else null
                                )
                            }
                        }
                        // v13.9.2：已到账的条目默认收起来，列表末尾给一扇门把它们放回来
                        if (settledHidden) {
                            item {
                                SettledArchiveBar(
                                    count = board.settledCount,
                                    cents = board.settledCents,
                                    onClick = { vm.setReimburseFilter(setOf(ReimburseState.SETTLED)) }
                                )
                            }
                        }
                    }
                        1 -> CategoryBreakdownPage(
                            transactions = allTxns,
                            contentPadding = PaddingValues(start = 20.dp, end = 16.dp, top = 6.dp, bottom = listBottomPad)
                        )
                        2 -> MonthBreakdownPage(
                            transactions = allTxns,
                            contentPadding = PaddingValues(start = 20.dp, end = 16.dp, top = 6.dp, bottom = listBottomPad)
                        )
                    }
                }
            }
            // 玻璃分隔线
            Box(
                Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(dividerBrush())
            )
            // ---- 右：概览 ----
            // v16.4：右栏封顶 720dp 并居中。原来只有 20dp 内边距，1700dp 窗口下这块
            // 有 1300dp 宽 —— 报销看板的三块金额被 SpaceBetween 顶到屏幕两端，
            // 中间空出一条河，"已回款"和"待回款"读起来像两张不相干的卡。
            // v16.4：右栏封顶 + 宽屏拆两列。
            // 原来只有 20dp 内边距，1700dp 窗口下这块有 1300dp 宽 —— 报销看板的三块金额
            // 被 SpaceBetween 顶到屏幕两端，中间空出一条河；而「尚余垫付 ¥2,056」这种
            // 一个数字的卡被拉到 800dp 宽，右边全是空气。单纯"限宽居中"只是把空白挪到边上，
            // 真正的大屏做法是**并排两列**：左列"我还有多少钱"（概览 / 资产 / 固定支出），
            // 右列"钱卡在谁那儿"（报销看板 / 催办清单）。每列回到 520dp 上下，卡片本身
            // 也重新成立 —— 这才是桌面版而不是被拉长的手机版。
            val detailPaneWidth = env.paneTrailingWidthDp(navigationVisible = true) - 1
            val wideOverview = detailPaneWidth >= 860
            val overviewBlocks: @Composable () -> Unit = {
                Spacer(Modifier.height(6.dp))
                LedgerOverviewCard(
                    emoji = ledger?.emoji ?: "📒",
                    colorHex = ledger?.colorHex ?: "#1D3B57",
                    sceneKey = ledger?.sceneKey ?: ScenePresets.CUSTOM_KEY,
                    totalExpense = totalExpense,
                    totalIncome = totalIncome,
                    monthExpense = month.first,
                    monthIncome = month.second,
                    budgetCents = ledger?.budgetCents ?: 0L,
                    reimburse = if (supportsReimburse) reimburseGlobal else null,
                    compact = overviewCompact,
                    onToggleCompact = onToggleOverviewCompact
                )
                // v13.7：**没有账户就不占这一行**。
                // 没建账户时净资产恒为 0，这行只是在说「净资产 ¥0 · 建账户」——
                // 40dp 的横幅广告。入口在顶栏「更多」→「资产账户」里还在，
                // 所以发现性不受影响；一旦真建了账户，这行照旧出现。
                if (hasAccounts) {
                    Spacer(Modifier.height(6.dp))
                    AssetBar(
                        netWorth = netWorth,
                        balances = accountBalances,
                        onManage = onOpenAccounts
                    )
                }

                // v5.7：固定支出到期提醒（概览卡下方，进页面就能看到）
                if (recurringDue.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    RecurringDueBar(
                        due = recurringDue,
                        onPostAll = { vm.postAllRecurring() },
                        onManage = { recurringOpen = true }
                    )
                }
                // v16.4：看板跟概览走同一列 —— 它讲的是"我有多少钱压在外面"，
                // 和上面的余额是同一句话；催办清单是明细，单独站右列。
                // 这样两列的高度也不会一边倒（收起概览时左列只剩一张 100dp 的卡）。
                if (supportsReimburse) {
                    Spacer(Modifier.height(12.dp))
                    ReimburseBoard(
                        summary = board,
                        aging = aging,
                        selected = reimburseFilter,
                        onToggleState = vm::toggleReimburseFilter,
                        onPickOutstanding = { vm.setReimburseFilter(setOf(ReimburseState.PENDING, ReimburseState.SUBMITTED)) },
                        onPickRecovered = { vm.setReimburseFilter(setOf(ReimburseState.SETTLED)) }
                    )
                }
            }
            val chaseBlocks: @Composable () -> Unit = {
                if (supportsReimburse && outstanding.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    ChaseListCard(
                        items = outstanding,
                        projectNames = projectNames,
                        ledgerName = ledger?.name ?: "",
                        onFilterAll = { vm.setReimburseFilter(setOf(ReimburseState.PENDING, ReimburseState.SUBMITTED)) }
                    )
                }
            }
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScroll(overviewScroll)
                    .padding(
                        horizontal = Layout.sidePaddingIn(
                            detailPaneWidth,
                            Layout.wideContentMaxWidth
                        )
                    )
            ) {
                if (wideOverview) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Column(Modifier.weight(1f)) { overviewBlocks() }
                        Column(Modifier.weight(1f)) { chaseBlocks() }
                    }
                } else {
                    overviewBlocks()
                    chaseBlocks()
                }
                // ⚠️ v12.2.0：这里原来在收起态插了一块「记账节奏」卡
                // （记账天数 / 流水笔数 / 日均笔数 / 最近记账）。
                // 结果是**收起概览等于没收**：看板收了，又换一块玻璃顶上，
                // 首屏照样看不到流水 —— 而这三项是运营指标，不是记账时的决策依据，
                // 要看习惯有统计页。收起的语义就是"让流水上来"，不再拿别的东西填这个位置。
                Spacer(Modifier.height(Space.xl))
            }
        }
    } else {
        Column(modifier.fillMaxSize()) {
            // v13：明细 / 分类 / 月份 —— 同一份数据的三种看法。
            // 段控件在凹槽里不动，内容在下面横滑；滑到头尾时由平台弹性回弹给出"到底了"。
            GlassSegmentBar(
                selected = pageState.currentPage,
                onSelect = { i -> scope.launch { pageState.animateScrollToPage(i) } },
                // v13.7：顶部留白从 4dp 抬到 10dp。
                // 原来那 4dp 是给账本 chips 行"垫"出来的呼吸；chips 退役后，
                // 段控件直接顶在顶栏的分隔线下面，读起来像"贴在标题上"。
                modifier = Modifier.padding(
                    start = Layout.sidePadding(env.widthDp),
                    end = Layout.sidePadding(env.widthDp),
                    top = 10.dp,
                    bottom = Space.sm
                )
            )
            HorizontalPager(
                state = pageState,
                modifier = Modifier.weight(1f),
                beyondViewportPageCount = 0,
                // 页面贴着走、不叠加缩放：iOS 的分页就是 1:1 位移，
                // 加缩放会变成"卡片轮播"，那是另一种语言。
                pageSpacing = 0.dp
            ) { page ->
                when (page) {
                    0 -> LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = Layout.sidePadding(env.widthDp),
                        end = Layout.sidePadding(env.widthDp),
                        top = Space.sm,
                        bottom = listBottomPad
                    ),
                    verticalArrangement = Arrangement.spacedBy(Space.gapList)
                ) {
                    item {
                        LedgerOverviewCard(
                            emoji = ledger?.emoji ?: "📒",
                            colorHex = ledger?.colorHex ?: "#1D3B57",
                            sceneKey = ledger?.sceneKey ?: ScenePresets.CUSTOM_KEY,
                            totalExpense = totalExpense,
                            totalIncome = totalIncome,
                            monthExpense = month.first,
                            monthIncome = month.second,
                            budgetCents = ledger?.budgetCents ?: 0L,
                            reimburse = if (supportsReimburse) reimburseGlobal else null,
                            compact = overviewCompact,
                            onToggleCompact = onToggleOverviewCompact
                        )
                        // v13.7：同左栏 —— 没有账户时这行不出现（净资产恒为 0，读了等于没读）
                        if (hasAccounts) {
                            Spacer(Modifier.height(6.dp))
                            AssetBar(
                                netWorth = netWorth,
                                balances = accountBalances,
                                onManage = onOpenAccounts
                            )
                        }
                    }
                    // v9.8.5：工具条 + 搜索框提到概览卡下方 —— 原来排在第 7 项，
                    // 想搜一笔要先滚两屏（概览/报销看板/催办/项目条都在它前面）
                    if (groups.isNotEmpty()) {
                        // v10.1.0：工具条 + 搜索**常驻**（sticky）——
                        // 原来它们跟着列表滚走，滚到流水中间就看不出"列表被筛过"，只会觉得账少了。
                        stickyHeader(key = "detail-tools", contentType = "tools") {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    // v12：吸顶的不再是一条"横贯全屏的色带"，而是一张**浮空的玻璃工具卡**。
                                    // 色带的两个毛病很明显：它把版式和列表拦腰截断，而且方角在圆角卡片之间
                                    // 显得像没做完。浮卡则和底部导航同一种形态 —— 界面里所有"常驻功能"
                                    // 都以玻璃片的形式浮在内容之上，这是液态玻璃的一贯语法。
                                    // v13.5：**不留上下空气** —— 原来这里 padding(vertical = 4dp)，
                                    // 那 4dp 缝正好露出上一行流水被切掉的下半截字，
                                    // 读起来像"渲染错了"。卡的玻璃本来就够实（floatingBarColor + filmDepth=1），
                                    // 让卡自己从视口顶端开始盖住内容，缝就没了。
                                    // v13：吸顶工具卡走**真材质**，而不是"clip + 平涂一个色"。
                                    // filmDepth = 1 是必须的：下面有流水在滑，底部太透会透出文字鬼影。
                                    .glassSurface(
                                        shape = RoundedCornerShape(Radius.card),
                                        elevation = 6.dp,
                                        fill = com.foldledger.ui.theme.floatingBarColor(),
                                        specular = 0.9f,
                                        filmDepth = 1f
                                    )
                                    .padding(horizontal = 10.dp, vertical = 8.dp)
                            ) {
                                if (multiSelect) {
                                    // 多选是"整宽操作面板"（全选 / 反选 / 批量改），塞不进 44dp
                                    // 一行，这时搜索行让位，保持原来的上下堆叠
                                    BatchToolbar(
                                        multiSelect = true,
                                        selectedCount = selectionSummary.first,
                                        selectedAmount = selectionSummary.second,
                                        hasReimburse = supportsReimburse,
                                        visibleIds = visibleIds,
                                        filterActive = txnFilter.isActive,
                                        onOpenFilter = { filterOpen = true },
                                        onEnter = { vm.enterMultiSelect() },
                                        onExit = vm::exitMultiSelect,
                                        onSelectAll = { vm.selectAll(visibleIds) },
                                        onInvert = { vm.invertSelection(visibleIds) },
                                        onBatchCategory = { batchMode = BatchMode.CATEGORY },
                                        onBatchProject = { batchMode = BatchMode.PROJECT },
                                        onBatchReimburse = { batchMode = BatchMode.REIMBURSE },
                                        onBatchMove = { batchMode = BatchMode.MOVE },
                                        onBatchDelete = { batchMode = BatchMode.DELETE },
                                        moveLabel = if (isAutoLedger) "✓ 确认入账" else "移动到账本"
                                    )
                                    Spacer(Modifier.height(Space.sm))
                                    SearchBar(searchQuery, vm::setSearchQuery)
                                } else {
                                    // v13.4：筛选 / 多选并进搜索行 —— 三者同属"怎么找"这一层，
                                    // 拆成两行时合计 98dp，比"日期分组 + 一条流水"还高。
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(Modifier.weight(1f)) {
                                            SearchBar(searchQuery, vm::setSearchQuery)
                                        }
                                        Spacer(Modifier.width(6.dp))
                                        ToolPill(
                                            "筛选",
                                            active = txnFilter.isActive
                                        ) { filterOpen = true }
                                        Spacer(Modifier.width(6.dp))
                                        ToolPill(
                                            "多选",
                                            enabled = visibleIds.isNotEmpty()
                                        ) { vm.enterMultiSelect() }
                                    }
                                }
                                if (txnFilter.isActive) {
                                    Spacer(Modifier.height(Space.sm))
                                    FilterActiveBar(
                                        filter = txnFilter,
                                        count = filterSummary.first,
                                        amount = filterSummary.second,
                                        onClear = vm::clearFilter
                                    )
                                }
                            }
                        }
                    }
                    // v5.7：固定支出到期提醒
                    if (recurringDue.isNotEmpty()) {
                        item {
                            RecurringDueBar(
                                due = recurringDue,
                                onPostAll = { vm.postAllRecurring() },
                                onManage = { recurringOpen = true }
                            )
                        }
                    }
                    // v10.1.0：概览收起时，看板也一起收 —— 它和卡里的摘要讲的是同一组数字，
                    // 全展开时首屏就再也看不到流水了。
                    // ⚠️ v10.2.0：这里原来把「项目/空间」也一起包进了 !overviewCompact，
                    // 收起概览后项目筛选条直接消失 —— 筛选是操作入口，不是摘要，不能跟着收。
                    if (supportsReimburse && !overviewCompact) {
                        item {
                            ReimburseBoard(
                                summary = board,
                                aging = aging,
                                selected = reimburseFilter,
                                onToggleState = vm::toggleReimburseFilter,
                                onPickOutstanding = { vm.setReimburseFilter(setOf(ReimburseState.PENDING, ReimburseState.SUBMITTED)) },
                                onPickRecovered = { vm.setReimburseFilter(setOf(ReimburseState.SETTLED)) }
                            )
                        }
                        if (outstanding.isNotEmpty()) {
                            item {
                                ChaseListCard(
                                    items = outstanding,
                                    projectNames = projectNames,
                                    ledgerName = ledger?.name ?: "",
                                    onFilterAll = { vm.setReimburseFilter(setOf(ReimburseState.PENDING, ReimburseState.SUBMITTED)) }
                                )
                            }
                        }
                    }
                    if (supportsReimburse) {
                        // v5.1：项目/空间
                        item {
                            ProjectBar(
                                projects = projects,
                                selected = projectFilter,
                                onSelect = vm::setProjectFilter,
                                onCreate = { projectEditor = blankProject(ledger?.id ?: 0L) },
                                archivedIds = archivedProjectIds, pendingCounts = pendingByProject,
                                // v16.1.1：桌面宽屏左栏窄，LazyRow 只露前几个 chip → FlowRow 全展开
                                expanded = env.twoPane && !env.isFoldable
                            )
                        }
                        val current = projects.firstOrNull { it.id == projectFilter }
                        if (current != null) {
                            item {
                                ProjectSummaryCard(
                                    project = current,
                                    agg = projectTotals[current.id],
                                    states = projectStateTotals[current.id].orEmpty(),
                                    selectedStates = reimburseFilter,
                                    onSelectState = vm::toggleReimburseFilter,
                                    archived = current.id in archivedProjectIds,
                                    onEdit = { projectEditor = current }
                                )
                            }
                        }
                    }
                    if (reimburseFilter.isNotEmpty()) {
                        item {
                            ReimburseFilterBar(
                                states = reimburseFilter,
                                groups = filteredGroups,
                                onClear = vm::clearReimburseFilter
                            )
                        }
                    }
                    when {
                        groups.isEmpty() -> item {
                            EmptyHint(
                                emoji = "🧾",
                                title = "还没有记账记录",
                                subtitle = "点右下角那颗「＋」，给这个账本添上第一笔",
                                // ⚠️ v13.9：同上 —— 双栏版本的空态也不再自带按钮
                                highlight = true
                            )
                        }
                        filteredGroups.isEmpty() && !settledHidden -> item {
                            FilteredEmptyHint(searchQuery, projectFilter, reimburseFilter)
                        }
                        else -> items(
                            filteredGroups,
                            key = { it.dayKey },
                            contentType = { "dayGroup" }
                        ) { g ->
                            DayGroupCard(
                                group = g,
                                sceneKey = ledger?.sceneKey ?: ScenePresets.CUSTOM_KEY,
                                projectNames = projectNames,
                                accountNames = accountNames,
                                multiSelect = multiSelect,
                                selectedIds = selectedIds,
                                onToggle = vm::toggleSelect,
                                modifier = Modifier.animateItem(
                                    fadeOutSpec = tween(220, easing = androidx.compose.animation.core.LinearEasing)
                                ),
                                onEdit = onEditTransaction,
                                onDelete = { pendingDeleteTxnId = it },
                                onReimburse = { id, state -> vm.setReimburseState(listOf(id), state) },
                                quickConfirm = if (isAutoLedger) ({ id -> confirmInto(id) }) else null
                            )
                        }
                    }
                    // v13.9.2：已到账的条目默认收起来，列表末尾给一扇门把它们放回来
                    if (settledHidden) {
                        item {
                            SettledArchiveBar(
                                count = board.settledCount,
                                cents = board.settledCents,
                                onClick = { vm.setReimburseFilter(setOf(ReimburseState.SETTLED)) }
                            )
                        }
                    }
                }
                    1 -> CategoryBreakdownPage(
                        transactions = allTxns,
                        contentPadding = PaddingValues(
                            start = Layout.sidePadding(env.widthDp),
                            end = Layout.sidePadding(env.widthDp),
                            top = Space.sm,
                            bottom = listBottomPad
                        )
                    )
                    2 -> MonthBreakdownPage(
                        transactions = allTxns,
                        contentPadding = PaddingValues(
                            start = Layout.sidePadding(env.widthDp),
                            end = Layout.sidePadding(env.widthDp),
                            top = Space.sm,
                            bottom = listBottomPad
                        )
                    )
                }
            }
        }
    }

    // 多维筛选面板（v5.7）
    if (filterOpen) {
        FilterOverlay(
            current = txnFilter,
            onApply = vm::setFilter,
            onClear = vm::clearFilter,
            onDismiss = { filterOpen = false }
        )
    }

    // 批量改账的二次选择（v5.6）：分类 / 项目 / 报销状态选完即生效，删除留一次确认
    batchMode?.let { mode ->
        BatchActionDialog(
            mode = mode,
            selectedCount = selectionSummary.first,
            categories = allCategories,
            projects = projects,
            moveTargets = otherLedgers,
            onPickCategory = { vm.batchSetCategory(it.id, it.name, it.emoji) },
            onPickProject = { vm.batchSetProject(it) },
            onPickReimburse = { vm.batchSetReimburseState(it) },
            onPickLedger = { id ->
                // v15：自动账本里「确认入账」记住目标账本并留撤销；普通账本保持原批量移动
                if (isAutoLedger) vm.batchConfirm(selectedIds.toList(), id)
                else vm.moveSelectedTo(id)
            },
            onConfirmDelete = { vm.batchDelete() },
            onDismiss = { batchMode = null }
        )
    }

    // 新建 / 编辑项目弹窗
    projectEditor?.let { editing ->
        ProjectEditorDialog(
            initial = editing,
            ledgerId = ledger?.id ?: 0L,
            onDismiss = { projectEditor = null },
            onSave = { name, emoji, colorHex, budgetCents, note ->
                if (editing.id == 0L) {
                    vm.createProject(name, emoji, colorHex, budgetCents, note)
                } else {
                    vm.updateProject(
                        editing.copy(
                            name = name,
                            emoji = emoji,
                            colorHex = colorHex,
                            budgetCents = budgetCents,
                            note = note
                        )
                    )
                }
                projectEditor = null
            },
            onDelete = if (editing.id != 0L) {
                {
                    vm.deleteProject(editing.id)
                    projectEditor = null
                }
            } else null
        )
    }

    // 固定支出管理弹层（v5.7）
    if (recurringOpen) {
        RecurringOverlay(
            env = env,
            ledgerId = ledger?.id ?: 0L,
            items = recurringAll,
            categories = allCategories,
            onCreate = vm::createRecurring,
            onUpdate = vm::updateRecurring,
            onDelete = vm::deleteRecurring,
            onToggle = vm::setRecurringEnabled,
            onDismiss = { recurringOpen = false }
        )
    }
}

/** 新建项目的空壳（id=0 表示还没入库） */
private fun blankProject(ledgerId: Long) = ProjectEntity(
    id = 0L,
    ledgerId = ledgerId,
    name = "",
    emoji = "📁",
    colorHex = "#1D3B57"
)

@Composable
private fun SectionTitle(text: String) {
    // v11：栏目标签用"加宽字距的小字"，而不是又一层加粗标题 ——
    // 页面里已经有顶栏大标题和版头大数字，第三个大字号只会互相打架
    Text(
        text,
        style = com.foldledger.ui.theme.OverlineLabel,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
    )
}

/**
 * 流水搜索框（v12：玻璃胶囊）。
 *
 * 原来是 `OutlinedTextField` —— M3 的描边输入框在玻璃上有两个硬伤：
 * 描边是一条实线（玻璃没有实线）、聚焦态会跳一层 Material 的容器色。
 * 现在换成「一层半透明白膜 + 上沿高光」的行内输入，和搜索这件事的体量相称。
 */
/**
 * 搜索行（v13.4：不再独占一行）。
 *
 * 原来「筛选」和「多选」各占一行胶囊，搜索框再占一行 —— 单栏里这两行
 * 合计 98dp，比一整个日期分组 + 一条流水还高。现在调用方用 Row 把搜索框
 * （weight）和两个同高的 [ToolPill] 并排，整块压到 44dp。
 */
@Composable
private fun SearchBar(
    query: String,
    onChange: (String) -> Unit
) {
    val dark = com.foldledger.ui.theme.LocalDarkTheme.current
    val shape = RoundedCornerShape(Radius.md)
    Row(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .glassSurface(
                shape = shape,
                elevation = 2.dp,
                // 卡片内部的输入框要"凹"下去：叠白只会在白卡里消失，淡墨才读得出层次
                fill = MaterialTheme.colorScheme.onSurface.copy(alpha = if (dark) 0.10f else 0.045f),
                specular = 0.30f
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Spacer(Modifier.width(12.dp))
        Icon(
            Icons.Filled.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(17.dp)
        )
        Spacer(Modifier.width(9.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(
                    "搜分类 / 备注 / 金额",
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                )
            }
            androidx.compose.foundation.text.BasicTextField(
                value = query,
                onValueChange = onChange,
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface
                ),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (query.isNotEmpty()) {
            Box(
                Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(50))
                    .clickableScale(pressedScale = 0.9f) { onChange("") },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "清除",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(15.dp)
                )
            }
        }
        Spacer(Modifier.width(8.dp))
    }
}

/**
 * 与搜索框同高的凹槽胶囊（v13.4）。
 *
 * 用途只有两个：筛选入口、进入多选。两者都是「对列表的操作」，不是数据本身 ——
 * 所以它们贴着搜索框，和搜索一样属于"怎么找"这一层，而不是独立一条工具栏。
 */
@Composable
private fun ToolPill(
    label: String,
    active: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val dark = com.foldledger.ui.theme.LocalDarkTheme.current
    val tint = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        active -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        Modifier
            .height(44.dp)
            .clip(RoundedCornerShape(Radius.md))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = if (dark) 0.10f else 0.045f))
            .clickableScale(pressedScale = 0.94f, enabled = enabled) { onClick() }
            .padding(horizontal = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (active) {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
            )
            Spacer(Modifier.width(5.dp))
        }
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = tint)
    }
}

