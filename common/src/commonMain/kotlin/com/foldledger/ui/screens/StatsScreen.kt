package com.foldledger.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.foldledger.data.ScenePresets
import com.foldledger.data.db.CategoryTotal
import com.foldledger.data.db.Direction
import com.foldledger.data.db.MonthTotal
import com.foldledger.ui.adaptive.AdaptiveScaffold
import com.foldledger.ui.adaptive.FoldEnv
import com.foldledger.ui.anim.Motion
import com.foldledger.ui.anim.valuePulse
import com.foldledger.ui.components.AmountText
import com.foldledger.ui.components.AnimatedCentsText
import com.foldledger.ui.components.CategoryIcon
import com.foldledger.ui.anim.clickableScale
import com.foldledger.ui.components.CategoryIconBadge
import com.foldledger.ui.components.DonutChart
import com.foldledger.ui.components.EmptyHint
import com.foldledger.ui.components.SectionCard
import com.foldledger.ui.components.ThinProgress
import com.foldledger.ui.components.TrendBars
import com.foldledger.ui.theme.Layout
import com.foldledger.ui.theme.SurfaceCard
import com.foldledger.ui.theme.LocalDirectionColors
import com.foldledger.ui.theme.Radius
import com.foldledger.ui.theme.SceneColors
import com.foldledger.ui.theme.Space
import com.foldledger.ui.vm.AnnualSummary
import com.foldledger.ui.vm.BudgetHealth
import com.foldledger.ui.vm.CheckupLine
import com.foldledger.ui.vm.StatsViewModel
import com.foldledger.util.Money
import com.foldledger.ui.theme.cardSurface
import com.foldledger.ui.theme.wellSurface
import com.foldledger.ui.theme.cardBorder
import com.foldledger.ui.theme.softShadow
import com.foldledger.ui.theme.dividerBrush
import com.foldledger.ui.theme.rememberAnimatedPaneWidth
import java.util.Calendar
import kotlinx.coroutines.delay
import com.foldledger.ui.l10n.t

@Composable
fun StatsScreen(
    env: FoldEnv,
    ledgerId: Long,
    onBack: () -> Unit,
    /** v6.0 预算健康度：没有预算时引导去分类管理设置 */
    onOpenCategories: () -> Unit,
    /** v8.0 智能洞察入口 */
    onOpenInsight: () -> Unit = {}
) {
    val vm: StatsViewModel = viewModel(
        key = "stats-$ledgerId",
        factory = StatsViewModel.factory(ledgerId)
    )
    val ledger by vm.ledger.collectAsStateWithLifecycle()
    val monthLabel by vm.monthLabel.collectAsStateWithLifecycle()
    val direction by vm.direction.collectAsStateWithLifecycle()
    val totals by vm.categoryTotals.collectAsStateWithLifecycle()
    val daily by vm.dailyTotals.collectAsStateWithLifecycle()
    val monthSum by vm.monthSum.collectAsStateWithLifecycle()
    val monthExpense by vm.monthExpense.collectAsStateWithLifecycle()
    val monthIncome by vm.monthIncome.collectAsStateWithLifecycle()
    val trend by vm.monthlyTrend.collectAsStateWithLifecycle()
    val count by vm.monthTxnCount.collectAsStateWithLifecycle()
    val expenseCategories by vm.expenseCategoryTotals.collectAsStateWithLifecycle()
    // v6.0 预算健康度
    val budgetHealth by vm.budgetHealth.collectAsStateWithLifecycle()
    // v7.0 年度总结
    val annual by vm.annual.collectAsStateWithLifecycle()
    val prevMonthExpense by vm.prevMonthExpense.collectAsStateWithLifecycle()
    // v5.8 本月体检（环比 / 分类涨幅 / 月末预测 / 预算余额）
    val checkup by vm.checkup.collectAsStateWithLifecycle()

    val scene = ScenePresets.byKey(ledger?.sceneKey ?: ScenePresets.CUSTOM_KEY)

    // 图表生长进度提升到**屏幕级**驱动（v3.1.1）：切月/换年照旧重播，
    // 但折叠/展开（双栏分支重挂）不再从 0 重长一遍 —— 那也是"闪一下"。
    val donutSweep = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        donutSweep.animateTo(1f, tween(Motion.SLOW + 200, easing = Motion.fluid))
    }
    val trendGrow = remember { Animatable(0f) }
    LaunchedEffect(daily) {
        trendGrow.snapTo(0f)
        trendGrow.animateTo(1f, tween(Motion.SLOW, easing = Motion.fluid))
    }
    val yearGrow = remember { Animatable(0f) }
    LaunchedEffect(trend) {
        yearGrow.snapTo(0f)
        yearGrow.animateTo(1f, tween(Motion.SLOW, easing = Motion.fluid))
    }

    // 双栏 ↔ 单栏共享滚动状态（v3.1.1）：折叠/展开不跳回顶部
    val statsLeftScroll = rememberScrollState()
    val statsRightScroll = rememberScrollState()
    val statsListState = rememberLazyListState()

    AdaptiveScaffold(
        env = env,
        title = "统计 · ${ledger?.name ?: ""}",
        subtitle = "${scene.emoji} ${scene.name} · $monthLabel · $count 笔",
        onBack = onBack,
        showNavigation = false
    ) { padding ->
        if (env.twoPane) {
            // 双栏：上方共享月份/方向切换，左 = 占比，右 = 排行 + 趋势
            Column(
                Modifier
                    .padding(padding)
                    .fillMaxSize()
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.weight(1f)) {
                        MonthSwitcher(
                            monthLabel,
                            vm::prevMonth,
                            vm::nextMonth,
                            canGoNext = vm.canGoNext.collectAsStateWithLifecycle().value
                        )
                    }
                    Spacer(Modifier.width(16.dp))
                    DirectionToggleRow(direction, vm::setDirection)
                }
                Spacer(Modifier.height(14.dp))
                // 左栏宽度由 FoldEnv 的离线搜索结果给出（不再让用户调）
                val masterWidth = rememberAnimatedPaneWidth(env.paneMasterWidthDp(navigationVisible = false))
                Row(Modifier.weight(1f)) {
                    Column(
                        Modifier
                            .width(masterWidth)
                            .fillMaxHeight()
                            .verticalScroll(statsLeftScroll)
                            .padding(start = 20.dp)
                    ) {
                        MonthSummaryCard(monthExpense, monthIncome, monthLabel)
                        Spacer(Modifier.height(12.dp))
                        InsightEntryCard(onOpenInsight)
                        Spacer(Modifier.height(12.dp))
                        InsightsCard(
                            monthExpense = monthExpense,
                            monthIncome = monthIncome,
                            expenseCategories = expenseCategories,
                            monthLabel = monthLabel,
                            checkup = checkup
                        )
                        Spacer(Modifier.height(12.dp))
                        BudgetHealthCard(
                            items = budgetHealth,
                            monthLabel = monthLabel,
                            onGoCategories = onOpenCategories
                        )
                        Spacer(Modifier.height(12.dp))
                        AnnualCard(annual)
                        Spacer(Modifier.height(12.dp))
                        CategoryPanel(
                            direction = direction,
                            monthSum = monthSum,
                            totals = totals,
                            daily = daily,
                            showTrend = false,
                            donutSweep = donutSweep.value,
                            trendGrow = trendGrow.value
                        )
                    }
                    // 玻璃分隔线
                    Box(
                        Modifier
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(dividerBrush())
                    )
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .verticalScroll(statsRightScroll)
                            // v16.3.1：双栏右栏左右留白对称（原 16/20 让内容整体偏左 4dp）
                            .padding(start = 20.dp, end = 20.dp)
                    ) {
                        SectionCard(overline = t("结构"), title = t("分类排行")) {
                            if (totals.isEmpty()) {
                                Text(
                                    t("本月暂无数据"),
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    // v16.3：只在 totals 变化时算一次，别让动画每帧重扫整张分类表
                                    val sum = remember(totals) { totals.sumOf { it.totalCents } }
                                    totals.take(10).forEachIndexed { i, t ->
                                        RankingRow(i, t, sum, direction)
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        MonthlyTrendCard(trend, direction)
                        Spacer(Modifier.height(12.dp))
                        YearOverviewCard(trend, yearGrow = yearGrow.value)
                    }
                }
            }
        } else {
            LazyColumn(
                state = statsListState,
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
                contentPadding = PaddingValues(
                    start = Layout.sidePadding(env.widthDp),
                    end = Layout.sidePadding(env.widthDp),
                    top = Space.sm,
                    bottom = Space.huge
                ),
                verticalArrangement = Arrangement.spacedBy(Space.gapList)
            ) {
                item { MonthSwitcher(
                            monthLabel,
                            vm::prevMonth,
                            vm::nextMonth,
                            canGoNext = vm.canGoNext.collectAsStateWithLifecycle().value
                        ) }
                item { MonthSummaryCard(monthExpense, monthIncome, monthLabel) }
                item { InsightEntryCard(onOpenInsight) }
                item {
                    InsightsCard(
                        monthExpense = monthExpense,
                        monthIncome = monthIncome,
                        expenseCategories = expenseCategories,
                        monthLabel = monthLabel,
                        checkup = checkup
                    )
                    Spacer(Modifier.height(12.dp))
                    BudgetHealthCard(
                        items = budgetHealth,
                        monthLabel = monthLabel,
                        onGoCategories = onOpenCategories
                    )
                    Spacer(Modifier.height(12.dp))
                    AnnualCard(annual)
                }
                item { DirectionToggleRow(direction, vm::setDirection) }
                item {
                    CategoryPanel(
                        direction = direction,
                        monthSum = monthSum,
                        totals = totals,
                        daily = daily,
                        showTrend = true,
                        donutSweep = donutSweep.value,
                        trendGrow = trendGrow.value
                    )
                }
                item { MonthlyTrendCard(trend, direction) }
                item { YearOverviewCard(trend, yearGrow = yearGrow.value) }
            }
        }
    }
}

/** v8.0 智能洞察入口卡（统计 → 洞察） */
@Composable
private fun InsightEntryCard(onOpenInsight: () -> Unit) {
    SurfaceCard(
        shape = RoundedCornerShape(Radius.card),
        elevation = 1.dp,
        softShadow = true,
        modifier = Modifier
            .fillMaxWidth()
            .clickableScale(pressedScale = 0.97f, clipShape = RoundedCornerShape(Radius.card)) { onOpenInsight() }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Text("🧠", fontSize = 20.sp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(t("智能洞察"), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    t("周期支出识别 · 净资产走势 · 年度支出预测"),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text("›", fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * 本月收支概览：支出 / 收入 / **结余**。
 *
 * 结余 = 收入 − 支出，是用户最关心的那个数，所以给它最大的字号和最强的对比。
 * 结余为正用"收入色"，为负用"支出色"（跟随用户的收支配色习惯），
 * 数值变化时用 [valuePulse] 跳一下 —— 切换月份时眼睛会被自然带过去。
 */
@Composable
private fun MonthSummaryCard(expense: Long, income: Long, monthLabel: String) {
    val dc = LocalDirectionColors.current
    val net = income - expense
    val netColor = if (net >= 0) dc.income else dc.expense

    SurfaceCard(
        shape = RoundedCornerShape(Radius.card),
        color = cardSurface(),
        elevation = 4.dp,
        modifier = Modifier
            .fillMaxWidth()
    ) {
        Column(Modifier.padding(Space.lg)) {
            Text(
                "${monthLabel.ifBlank { "本月" }} 结余",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (net >= 0) "+" else "−",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = netColor
                )
                Spacer(Modifier.width(3.dp))
                AnimatedCentsText(
                    cents = kotlin.math.abs(net),
                    color = netColor,
                    fontSize = 30,
                    // v9.7.3：与明细页的结余（34sp ExtraBold）保持同一档数字质感，
                    // 否则从明细切到统计，同一个"结余"看着轻了一档
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.valuePulse(net)
                )
            }
            Text(
                when {
                    // v9.9.0：零数据时两张卡都是 0，唯一的空态文案藏在下面的分类面板里，
                    // 新用户不知道下一步做什么 —— 这里直接给一句话
                    expense == 0L && income == 0L -> t("这个月还没有记录，回明细页点「记一笔」就有数了")
                    net >= 0 -> t("收入扣掉支出后还剩这么多")
                    else -> t("这个月支出超过了收入")
                },
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(Space.lg))
            // v4.4.0：与明细页概览卡一致 —— ▲收 / ▼支 两个粉彩格对称排列
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SummaryMetric(t("本月支出"), "▼", expense, dc.expense, Modifier.weight(1f))
                SummaryMetric(t("本月收入"), "▲", income, dc.income, Modifier.weight(1f))
            }
        }
    }
}

/**
 * 本月体检（v5.8，替换旧的「消费洞察」）。
 *
 * 旧版三条是「结余 / 最大支出项 / 环比」，前两条只是把上面的数字重念一遍，价值低。
 * 新版保留结余，其余换成**能指导行动**的判断：
 *  - 比上月多花还是少花、差多少；
 *  - 涨得最凶的那个分类（用户通常猜不到是哪个）；
 *  - 按当前日均推算月末会到哪、预算还够不够（这是真正会改变行为的一条）。
 *
 * 所有结论来自 `StatsViewModel.checkup`，本地计算、不联网。
 */
@Composable
private fun InsightsCard(
    monthExpense: Long,
    monthIncome: Long,
    expenseCategories: List<CategoryTotal>,
    monthLabel: String,
    checkup: List<CheckupLine>
) {
    val dc = LocalDirectionColors.current
    val net = monthIncome - monthExpense

    SectionCard(
        overline = t("体检"),
        title = t("本月体检"),
        subtitle = (monthLabel.ifBlank { t("本月") }) + t(" · 自动生成")
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            InsightRow("💰", t("本月结余")) {
                val color = if (net >= 0) dc.income else dc.expense
                Text(
                    if (net >= 0) "结余 ${Money.format(net)}，收入覆盖支出"
                    else "超支 ${Money.format(-net)}，支出超过了收入",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = color
                )
            }

            val top = expenseCategories.firstOrNull()
            if (top != null) {
                val total = expenseCategories.sumOf { it.totalCents }
                val pct = if (total > 0) (top.totalCents * 100 / total).toInt() else 0
                InsightRow("🔝", t("最大支出项")) {
                    Text(
                        "「${top.name}」占支出的 $pct%",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            checkup.forEach { line ->
                InsightRow(line.emoji, line.title) {
                    Text(
                        line.detail.ifBlank { line.title },
                        fontSize = 13.sp,
                        fontWeight = if (line.detail.isBlank()) FontWeight.SemiBold
                        else FontWeight.Normal,
                        color = when (line.tone) {
                            2 -> dc.expense
                            1 -> dc.income
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
        }
    }
}

// ============================================================
//  预算健康度（v6.0）
// ============================================================

/** 快到上限时的琥珀色（M3 的 tertiary 在自定义色板里不可控，直接用常量） */
private val WarnAmber = Color(0xFFB07A22)

/**
 * 预算健康度（v6.0）。
 *
 * 本月体检回答的是「这个月我花得怎么样」，这张卡回答的是**「哪些分类快爆了」**——
 * 后者才是会改变下一次消费决策的信息。超支的排前、接近上限的用琥珀色预警、
 * 底部一条用量进度条比文字更快被看到。
 *
 * 没有任何分类设过预算时给一句引导，而不是留个空卡片。
 */
@Composable
private fun BudgetHealthCard(
    items: List<BudgetHealth>,
    monthLabel: String,
    onGoCategories: () -> Unit
) {
    val dc = LocalDirectionColors.current
    SectionCard(
        overline = t("预算"),
        title = t("预算健康度"),
        subtitle = (monthLabel.ifBlank { t("本月") }) + t(" · 用量从高到低")
    ) {
        if (items.isEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    t("还没有给分类设预算。"),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    t("到「分类管理」给常花的几个月度预算，这里就能看到谁快爆了、还剩多少可花。"),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    t("去设置预算 ›"),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickableScale(pressedScale = 0.92f) { onGoCategories() }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
            return@SectionCard
        }

        val totalBudget = items.sumOf { it.budgetCents }
        val totalUsed = items.sumOf { it.usedCents }
        val left = totalBudget - totalUsed

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "总预算 ${Money.format(totalBudget)}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "已用 ${Money.format(totalUsed)}",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                if (left >= 0) "剩余 ${Money.format(left)}" else "超支 ${Money.format(-left)}",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = if (left >= 0) dc.income else dc.expense
            )
        }
        Spacer(Modifier.height(12.dp))

        items.forEach { item ->
            BudgetHealthRow(item)
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun BudgetHealthRow(item: BudgetHealth) {
    val dc = LocalDirectionColors.current
    // 超支 = 红、快到上限 = 琥珀、正常 = 主色
    val color = when {
        item.overBudget -> dc.expense
        item.nearLimit -> WarnAmber
        else -> MaterialTheme.colorScheme.primary
    }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(item.emoji, fontSize = 13.sp)
            Spacer(Modifier.width(6.dp))
            Text(
                item.name,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                "${Money.format(item.usedCents)} / ${Money.format(item.budgetCents)}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 148.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                when {
                    item.overBudget -> "超 ${Money.format(-item.remainingCents)}"
                    item.nearLimit -> t("快到上限")
                    else -> "剩 ${Money.format(item.remainingCents)}"
                },
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = color
            )
        }
        Spacer(Modifier.height(5.dp))
        ThinProgress(
            fraction = item.fraction.coerceIn(0f, 1f),
            color = color,
            height = 5.dp
        )
    }
}

// ============================================================
//  年度总结（v7.0）
// ============================================================

/**
 * 年度总结（v7.0）。
 *
 * 月度体检回答「这个月怎么样」，这张卡回答**「这一年怎么样」**——
 * 前者管节流，后者管复盘。三个数 + 12 个月的走势 + 最烧钱的那个月，
 * 一眼看清这一年的钱是怎么走的。
 */
@Composable
private fun AnnualCard(summary: AnnualSummary) {
    val dc = LocalDirectionColors.current
    val net = summary.totalNet
    val maxMonth = remember(summary) {
        (summary.monthlyExpense.maxOrNull() ?: 0L).coerceAtLeast(1L)
    }

    SectionCard(
        overline = t("年度"),
        title = "${summary.year} 年总结",
        subtitle = t("1 月至今 · 自动汇总")
    ) {
        // 三个数：总支出 / 总收入 / 结余
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AnnualMetric(t("支出"), summary.totalExpense, dc.expense, Modifier.weight(1f))
            AnnualMetric(t("收入"), summary.totalIncome, dc.income, Modifier.weight(1f))
            AnnualMetric(t("结余"), net, if (net >= 0) dc.income else dc.expense, Modifier.weight(1f))
        }

        Spacer(Modifier.height(14.dp))
        Text(
            t("月度支出走势"),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))

        // 12 根极简柱：不用图表组件，只要能一眼看出哪个月高
        Row(
            Modifier.fillMaxWidth().height(56.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            summary.monthlyExpense.forEachIndexed { i, v ->
                Column(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.Bottom,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height((48.dp * (v.toFloat() / maxMonth)).coerceAtLeast(1.dp))
                            .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                            .background(
                                if (v == maxMonth && v > 0) dc.expense
                                else dc.expense.copy(alpha = 0.35f)
                            )
                    )
                }
            }
        }
        // v16.3.1：月份刻度与上方柱图共用基线，显式垂直居中 + 固定行盒
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf("1", "4", "7", "10", "12").forEach {
                Text(
                    it,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        // 一句话结论
        val peak = summary.peakMonth
        val msg = buildString {
            if (peak > 0) append("${peak} 月最烧钱")
            if (summary.monthlyAvg > 0) {
                if (isNotEmpty()) append(" · ")
                append("月均 ${Money.format(summary.monthlyAvg)}")
            }
        }
        if (msg.isNotEmpty()) {
            Text(
                msg,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun AnnualMetric(label: String, cents: Long, color: Color, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(2.dp))
        Text(
            Money.format(cents),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun InsightRow(icon: String, label: String, content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(icon, fontSize = 17.sp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(2.dp))
            content()
        }
    }
}

@Composable
private fun SummaryMetric(
    label: String,
    arrow: String,
    cents: Long,
    color: Color,
    modifier: Modifier = Modifier
) {
    // v11：统计格不再垫粉彩底色 —— 一个标签 + 一个数字，靠语义色区分方向就够了
    Column(modifier.padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(arrow, fontSize = 11.sp, color = color)
            Spacer(Modifier.width(4.dp))
            Text(
                label,
                fontSize = 11.sp,
                letterSpacing = 0.8.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(4.dp))
        AnimatedCentsText(
            cents = cents,
            color = color,
            fontSize = 17,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.valuePulse(cents)
        )
    }
}

@Composable
private fun CategoryPanel(
    direction: Int,
    monthSum: Long,
    totals: List<CategoryTotal>,
    daily: List<com.foldledger.data.db.DayTotal>,
    showTrend: Boolean,
    /** v3.1.1：屏幕级驱动的图表进度，折叠/展开不重播 */
    donutSweep: Float,
    trendGrow: Float
) {
    val dc = LocalDirectionColors.current
    val accent = if (direction == Direction.EXPENSE) dc.expense else dc.income

    SurfaceCard(
        shape = RoundedCornerShape(Radius.card),
        color = cardSurface(),
        elevation = 4.dp,
        modifier = Modifier
            .fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                if (direction == Direction.EXPENSE) t("本月支出合计") else t("本月收入合计"),
                style = com.foldledger.ui.theme.OverlineLabel,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            // 统计页的主数字用同一套"版头数字"样式，与明细页形成一致的读数记忆
            AmountText(
                monthSum,
                direction,
                // v16.3.1：HeroNumber 本身标定 44/50；原来 copy 成 44/44 把行盒余量压成 0，
                // 中文 + tnum 数字下沿会被裁掉一道。直接用主题样式。
                style = com.foldledger.ui.theme.HeroNumber
            )

            Spacer(Modifier.height(16.dp))
            // v16.3：饼图数据与占比分母只随 totals 重算一次。
            // 原来排行里每一行都 `totals.sumOf{}` 重新扫一遍全表。
            val slices = remember(totals) { totals.map { it.name to it.totalCents } }
            val totalSum = remember(totals) { totals.sumOf { it.totalCents } }
            Row(verticalAlignment = Alignment.CenterVertically) {
                DonutChart(
                    slices = slices,
                    colors = SceneColors,
                    modifier = Modifier.size(132.dp),
                    strokeWidth = 16.dp,
                    centerTitle = t("分类数"),
                    centerValue = totals.size.toString(),
                    // 屏幕级驱动的扫入进度：折叠展开不重播（v3.1.1）
                    sweep = donutSweep
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (totals.isEmpty()) {
                        Text(
                            t("本月还没有记录"),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        totals.take(5).forEachIndexed { i, t ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier
                                        .size(8.dp)
                                        .clip(RoundedCornerShape(50))
                                        .background(SceneColors[i % SceneColors.size])
                                )
                                Spacer(Modifier.width(6.dp))
                                Row(
                                    Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    CategoryIcon(ref = t.emoji, size = 20.dp, fontSize = 15)
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        t.name,
                                        fontSize = 13.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Text(
                                    Money.percent(t.totalCents, totalSum),
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (totals.size > 5) {
                            Text(
                                "还有 ${totals.size - 5} 个分类…",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            if (showTrend) {
                Spacer(Modifier.height(18.dp))
                Text(
                    t("本月日趋势"),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                TrendBars(days = daily, color = accent, height = 88.dp, grow = trendGrow)
            }
        }
    }
}

@Composable
private fun MonthSwitcher(
    label: String,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    canGoNext: Boolean = true
) {
    val onVariant = MaterialTheme.colorScheme.onSurfaceVariant
    // v13.9：surfaceVariant 60% 平涂 → 标准凹槽（墨 6.5%）。
    // 这一条是"月份切换条"的槽，里面的左右箭头按下去才有力。
    Surface(
        shape = RoundedCornerShape(50),
        color = wellSurface(),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(50))
                    .clickable { onPrev() },
                contentAlignment = Alignment.Center
            ) { Text("‹", fontSize = 20.sp) }
            Text(
                label.ifBlank { "—" },
                modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Box(
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(50))
                    .clickable(enabled = canGoNext) { onNext() },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "›",
                    fontSize = 20.sp,
                    // v9.8.0：已经是最新月份时置灰并停用 —— 原来还能点但什么都不发生，像卡住
                    color = if (canGoNext) Color.Unspecified
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                )
            }
        }
    }
}

@Composable
private fun DirectionToggleRow(direction: Int, onChange: (Int) -> Unit) {
    val dc = LocalDirectionColors.current
    // 小米钱包式分段控件：浅色轨道 + 选中项白底/语义底 + 细描边
    Surface(
        shape = RoundedCornerShape(50),
        color = wellSurface(),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(3.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(Direction.EXPENSE to t("支出"), Direction.INCOME to t("收入")).forEach { (d, label) ->
                val active = d == direction
                val c = if (d == Direction.EXPENSE) dc.expense else dc.income
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (active) cardSurface() else Color.Transparent,
                    border = if (active) {
                        androidx.compose.foundation.BorderStroke(1.dp, c.copy(alpha = 0.35f))
                    } else null,
                    shadowElevation = if (active) 1.dp else 0.dp,
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onChange(d) }
                ) {
                    Box(
                        Modifier.padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            label,
                            fontSize = 13.sp,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (active) c else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RankingRow(index: Int, t: CategoryTotal, total: Long, direction: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // 前三名用彩色徽标，其余用序号（小米钱包排行的层级处理）
        if (index < 3) {
            Box(
                Modifier
                    .size(18.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(SceneColors[index % SceneColors.size].copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "${index + 1}",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = SceneColors[index % SceneColors.size]
                )
            }
        } else {
            Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                Text(
                    "${index + 1}",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Row(
            Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // v4.4.0：与流水行同款粉彩分类块
            CategoryIconBadge(
                ref = t.emoji,
                size = 30.dp,
                fontSize = 15,
                container = MaterialTheme.colorScheme.surfaceVariant
            )
            Spacer(Modifier.width(9.dp))
            Text(
                t.name,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            AmountText(t.totalCents, direction, fontSize = 13, fontWeight = FontWeight.Bold)
            Text(
                "${t.count} 笔 · ${Money.percent(t.totalCents, total)}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    Spacer(Modifier.height(5.dp))
    ThinProgress(
        fraction = if (total > 0) t.totalCents.toFloat() / total.toFloat() else 0f,
        color = SceneColors[index % SceneColors.size],
        height = 4.dp
    )
}

@Composable
private fun MonthlyTrendCard(trend: List<MonthTotal>, direction: Int) {
    val dc = LocalDirectionColors.current
    val accent = if (direction == Direction.EXPENSE) dc.expense else dc.income
    val filtered = trend.filter { it.direction == direction }.takeLast(6)
    val max = (filtered.maxOfOrNull { it.totalCents } ?: 0L).coerceAtLeast(1L)

    SectionCard(overline = t("趋势"), title = t("近 6 个月趋势")) {
        if (filtered.isEmpty()) {
            EmptyHint(emoji = "📉", title = t("暂无历史数据"))
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                filtered.forEach { m ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            m.month.substringAfter('-') + "月",
                            fontSize = 13.sp,
                            modifier = Modifier.width(40.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Box(Modifier.weight(1f)) {
                            ThinProgress(
                                fraction = m.totalCents.toFloat() / max.toFloat(),
                                color = accent,
                                height = 8.dp
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            Money.format(m.totalCents),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.width(88.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.End
                        )
                    }
                }
            }
        }
    }
}

/**
 * 年度回顾（v2.1）：当前自然年 12 个月的收支双柱图 + 全年汇总。
 * 数据直接来自 [trend]（全量月度趋势）按年过滤，不联网、不改库。
 */
@Composable
private fun YearOverviewCard(
    trend: List<MonthTotal>,
    /** v3.1.1：屏幕级驱动的生长进度，折叠/展开不重播 */
    yearGrow: Float
) {
    val dc = LocalDirectionColors.current
    val year = Calendar.getInstance().get(Calendar.YEAR)
    // v16.3：原来 12 个月各做 2 次线性查找（24 次全表扫）+ 两次 sumOf，
    // 且在动画每帧重组时全跑一遍。改成按 trend 建一次索引，只在数据/年份变化时重算。
    val months = remember(trend, year) {
        val byKey = HashMap<String, Pair<Long, Long>>()
        trend.forEach { m ->
            val (exp, inc) = byKey[m.month] ?: (0L to 0L)
            byKey[m.month] = if (m.direction == Direction.EXPENSE) (m.totalCents to inc) else (exp to m.totalCents)
        }
        (1..12).map { m ->
            val pair = byKey["%04d-%02d".format(year, m)] ?: (0L to 0L)
            Triple(m, pair.first, pair.second)
        }
    }
    val totalExp = remember(months) { months.sumOf { it.second } }
    val totalInc = remember(months) { months.sumOf { it.third } }

    SectionCard(
        overline = t("回顾"),
        title = "$year 年度回顾",
        subtitle = "支出 ${Money.format(totalExp)} · 收入 ${Money.format(totalInc)} · 结余 ${
            Money.format(totalInc - totalExp)
        }"
    ) {
        if (totalExp == 0L && totalInc == 0L) {
            EmptyHint(emoji = "📅", title = t("今年还没有记录"))
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LegendDot(t("支出"), dc.expense)
                LegendDot(t("收入"), dc.income)
            }
            Spacer(Modifier.height(10.dp))
            YearBars(months, grow = yearGrow)
        }
    }
}

@Composable
private fun LegendDot(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(50))
                .background(color)
        )
        Spacer(Modifier.width(5.dp))
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun YearBars(
    months: List<Triple<Int, Long, Long>>,
    modifier: Modifier = Modifier,
    /** v3.1.1：外部（屏幕级）驱动的生长进度。null = 组件自持（旧行为） */
    grow: Float? = null
) {
    val dc = LocalDirectionColors.current
    val height = 140.dp
    val max = (months.maxOfOrNull { maxOf(it.second, it.third) } ?: 0L).coerceAtLeast(1L)
    // 柱子从底部弹性长出（对齐 TrendBars 的生长动画）。
    // 外部传入进度时（屏幕级驱动），折叠展开不重播；换年仍照旧重播。
    val selfGrow = remember { Animatable(0f) }
    LaunchedEffect(months, grow == null) {
        if (grow == null) {
            selfGrow.snapTo(0f)
            selfGrow.animateTo(1f, tween(Motion.SLOW, easing = Motion.fluid))
        }
    }
    val growValue = grow ?: selfGrow.value
    Row(
        modifier = modifier.fillMaxWidth().height(height),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        months.forEach { (m, exp, inc) ->
            Column(
                Modifier.weight(1f).height(height),
                verticalArrangement = Arrangement.Bottom,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    Modifier.fillMaxWidth().weight(1f),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Box(
                        Modifier
                            .width(5.dp)
                            .fillMaxHeight(
                                (exp.toFloat() / max.toFloat()).coerceIn(0f, 1f) * growValue
                            )
                            .clip(RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
                            .background(dc.expense.copy(alpha = 0.85f))
                    )
                    Spacer(Modifier.width(3.dp))
                    Box(
                        Modifier
                            .width(5.dp)
                            .fillMaxHeight(
                                (inc.toFloat() / max.toFloat()).coerceIn(0f, 1f) * growValue
                            )
                            .clip(RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
                            .background(dc.income.copy(alpha = 0.85f))
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text("$m", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
