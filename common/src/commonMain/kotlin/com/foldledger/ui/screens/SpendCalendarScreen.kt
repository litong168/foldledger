package com.foldledger.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.foldledger.data.db.TransactionEntity
import com.foldledger.ui.adaptive.AdaptiveScaffold
import com.foldledger.ui.adaptive.FoldEnv
import com.foldledger.ui.anim.clickableScale
import com.foldledger.ui.components.EmptyHint
import com.foldledger.ui.components.SectionCard
import com.foldledger.ui.theme.LocalDirectionColors
import com.foldledger.ui.theme.Radius
import com.foldledger.ui.theme.glassChip
import com.foldledger.ui.theme.Layout
import com.foldledger.ui.theme.dividerBrush
import com.foldledger.ui.theme.rememberAnimatedPaneWidth
import com.foldledger.ui.vm.CalUi
import com.foldledger.ui.vm.SpendCalendarViewModel
import com.foldledger.util.DateExt
import com.foldledger.util.Money
import com.foldledger.ui.l10n.t

/**
 * 消费日历（v7.5）。
 *
 * 列表回答不了「这个月钱烧在哪几天」——月历热力一眼看到节奏：
 * 深色的格子=烧得凶的日子。折叠屏展开后**左日历右当日明细**，是这套布局
 * 最舒服的形态；单栏时日历在上、当日明细在下。
 */
@Composable
fun SpendCalendarScreen(
    env: FoldEnv,
    ledgerId: Long,
    onBack: () -> Unit,
    /** v9.9.5：日历里看到一笔想改，点一下就能编辑（原来只能退回明细页重新找） */
    onEditTransaction: (Long) -> Unit = {}
) {
    val vm: SpendCalendarViewModel = viewModel(
        key = "spend-cal-$ledgerId",
        factory = SpendCalendarViewModel.factory(ledgerId)
    )
    val ui by vm.uiState.collectAsStateWithLifecycle()
    val monthTs by vm.monthAnchor.collectAsStateWithLifecycle()
    val selTs by vm.selectedDay.collectAsStateWithLifecycle()

    AdaptiveScaffold(
        env = env,
        title = t("消费日历"),
        subtitle = monthSubtitle(monthTs, ui),
        onBack = onBack,
        showNavigation = false
    ) { padding ->
        if (env.twoPane) {
            val masterWidth = rememberAnimatedPaneWidth(env.paneMasterWidthDp(navigationVisible = false))
            Row(
                Modifier
                    .padding(padding)
                    .fillMaxSize()
            ) {
                // 左：月历
                Column(
                    Modifier
                        .width(masterWidth)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(start = 20.dp, end = 12.dp)
                ) {
                    MonthHeader(monthTs, vm::prevMonth, vm::nextMonth, vm::goToday)
                    Spacer(Modifier.height(10.dp))
                    MonthGrid(ui, monthTs, selTs, onPick = vm::selectDay)
                    Spacer(Modifier.height(12.dp))
                    MonthFooter(ui, monthTs)
                }
                // 玻璃分隔线
                Box(
                    Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(dividerBrush())
                )
                // 右：选中日明细
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(horizontal = 16.dp)
                ) {
                    Spacer(Modifier.height(6.dp))
                    DayPanel(
                        ui,
                        selTs,
                        modifier = Modifier.weight(1f),
                        onEditTransaction = onEditTransaction
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding),
                contentPadding = PaddingValues(
                    start = Layout.sidePadding(env.widthDp),
                    end = Layout.sidePadding(env.widthDp),
                    bottom = 28.dp
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    MonthHeader(monthTs, vm::prevMonth, vm::nextMonth, vm::goToday)
                }
                item {
                    MonthGrid(ui, monthTs, selTs, onPick = vm::selectDay)
                }
                item {
                    MonthFooter(ui, monthTs)
                }
                item {
                    DayPanel(
                        ui,
                        selTs,
                        modifier = Modifier.fillMaxWidth(),
                        embedInScroll = true,
                        onEditTransaction = onEditTransaction
                    )
                }
            }
        }
    }
}

/** 单栏页边距（保持与二级页一致的 20dp 节奏） */

private fun monthSubtitle(monthTs: Long, ui: CalUi): String {
    val m = java.util.Calendar.getInstance().apply { timeInMillis = monthTs }
    return "${m.get(java.util.Calendar.YEAR)} 年 ${m.get(java.util.Calendar.MONTH) + 1} 月" +
        " · 有记录 ${ui.dayCount} 天"
}

// ================= 月历头部（月切换） =================

@Composable
private fun MonthHeader(monthTs: Long, onPrev: () -> Unit, onNext: () -> Unit, onToday: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val cal = remember(monthTs) {
            java.util.Calendar.getInstance().apply { timeInMillis = monthTs }
        }
        Text(
            "${cal.get(java.util.Calendar.YEAR)} 年 ${cal.get(java.util.Calendar.MONTH) + 1} 月",
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.weight(1f))
        MonthArrow("‹", onPrev)
        Spacer(Modifier.width(8.dp))
        MonthArrow("›", onNext)
        Spacer(Modifier.width(8.dp))
        Text(
            t("今天"),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .clickableScale(pressedScale = 0.93f) { onToday() }
                .padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}

@Composable
private fun MonthArrow(text: String, onClick: () -> Unit) {
    // v13.9：onSurface 6% 平涂 → 玻璃小胶囊
    Box(
        Modifier
            .glassChip(shape = RoundedCornerShape(50), strength = 0.6f)
            .clickableScale(pressedScale = 0.9f, clipShape = RoundedCornerShape(50)) { onClick() }
    ) {
        Text(
            text,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 3.dp)
        )
    }
}

// ================= 月历格 =================

/**
 * 月历热力格。
 *
 * 每格：日期数字 + 当日支出（元）。背景按当日支出占**当月最大值**的强度上色
 * （开方压缩动态范围，让 300 和 500 也能看出差别）——一眼回答"哪几天烧得凶"。
 */
@Composable
private fun MonthGrid(ui: CalUi, monthTs: Long, selTs: Long, onPick: (Long) -> Unit) {
    val dc = LocalDirectionColors.current
    val blanks = SpendCalendarViewModel.leadingBlanks(monthTs)
    val dayCount = SpendCalendarViewModel.daysInMonth(monthTs)
    val todayKey = DateExt.dayKey(System.currentTimeMillis())

    Column(
        Modifier
            .glassChip(
                shape = RoundedCornerShape(Radius.card),
                strength = 0.55f
            )
            .padding(10.dp)
    ) {
        // 周标题（周日开头，和日历习惯一致）
        Row(Modifier.fillMaxWidth()) {
            listOf(t("日"), t("一"), t("二"), t("三"), t("四"), t("五"), t("六")).forEach {
                Text(
                    it,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        Spacer(Modifier.height(6.dp))

        val cells = ArrayList<java.time.LocalDate?>(blanks + dayCount)
        repeat(blanks) { cells.add(null) }
        for (d in 1..dayCount) {
            val cal = java.util.Calendar.getInstance().apply {
                timeInMillis = monthTs
                set(java.util.Calendar.DAY_OF_MONTH, d)
            }
            cells.add(
                java.time.LocalDate.of(
                    cal.get(java.util.Calendar.YEAR),
                    cal.get(java.util.Calendar.MONTH) + 1,
                    d
                )
            )
        }
        val rows = cells.chunked(7)
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                repeat(7) { col ->
                    val date = row.getOrNull(col)
                    Box(Modifier.weight(1f)) {
                        if (date != null) {
                            val ts = date.atStartOfDay(java.time.ZoneId.systemDefault())
                                .toInstant().toEpochMilli()
                            val key = DateExt.dayKey(ts)
                            DayCell(
                                dayNum = date.dayOfMonth,
                                cents = ui.dayExpenseCents[key] ?: 0L,
                                heat = ui.heat(key),
                                selected = DateExt.isSameDay(ts, selTs),
                                isToday = key == todayKey,
                                expenseColor = dc.expense,
                                onClick = { onPick(ts) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    dayNum: Int,
    cents: Long,
    heat: Float,
    selected: Boolean,
    isToday: Boolean,
    expenseColor: Color,
    onClick: () -> Unit
) {
    val bg = when {
        selected -> Color.White.copy(alpha = if (com.foldledger.ui.theme.LocalDarkTheme.current) 0.10f else 0.26f)
        heat > 0f -> expenseColor.copy(alpha = 0.07f + heat * 0.32f)
        else -> Color.Transparent
    }
    val fg = when {
        selected -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface
    }
    val border = when {
        selected -> BorderStroke(1.2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.42f))
        isToday -> BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
        else -> null
    }
    Surface(
        shape = RoundedCornerShape(9.dp),
        color = bg,
        border = border,
        shadowElevation = if (selected) 3.dp else 0.dp,
        modifier = Modifier
            .padding(1.5.dp)
            .fillMaxWidth()
            .clickableScale(pressedScale = 0.92f, clipShape = RoundedCornerShape(9.dp)) { onClick() }
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(vertical = 5.dp)
        ) {
            Text(
                "$dayNum",
                fontSize = 11.sp,
                fontWeight = if (selected || isToday) FontWeight.Bold else FontWeight.Normal,
                color = fg
            )
            if (cents > 0L) {
                Text(
                    Money.formatCompact(cents).removePrefix("¥"),
                    fontSize = 11.sp,
                    color = if (selected) fg.copy(alpha = 0.85f)
                    else expenseColor.copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

// ================= 月脚注 =================

@Composable
private fun MonthFooter(ui: CalUi, monthTs: Long) {
    val dayCount = SpendCalendarViewModel.daysInMonth(monthTs)
    SectionCard(title = t("本月概览")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(t("本月支出"), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    Money.format(ui.monthExpenseCents),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
            Column(Modifier.weight(1f)) {
                Text(t("日均（按自然日）"), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    Money.format(ui.monthExpenseCents / dayCount.coerceAtLeast(1)),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Column(Modifier.weight(1f)) {
                Text(t("有记录"), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "${ui.dayCount} 天",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

// ================= 选中日明细 =================

@Composable
private fun DayPanel(
    ui: CalUi,
    selTs: Long,
    modifier: Modifier = Modifier,
    /**
     * 嵌在外层可滚动页面里（单栏）时必须 true。
     *
     * ⚠️ 可滚动列表绝不能嵌进可滚动容器 —— 内层 LazyColumn 会拿到无限高度约束，
     * Compose 直接抛 "Vertically scrollable component was measured with an infinity
     * maximum height constraints" 闪退（v8.0.0 事故）。嵌入时用普通 Column 展开；
     * 双栏右栏高度有限、需要自己滚，走 LazyColumn。
     */
    embedInScroll: Boolean = false,
    /** 点某一笔去编辑 */
    onEditTransaction: (Long) -> Unit = {}
) {
    val dc = LocalDirectionColors.current
    val dayExpense = ui.dayItems
        .filter { it.direction == 0 }
        .sumOf { it.amountCents }
    val dayIncome = ui.dayItems
        .filter { it.direction == 1 }
        .sumOf { it.amountCents }

    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                DateExt.dayHeader(selTs),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.weight(1f))
            if (dayExpense > 0) {
                Text(
                    "支出 ${Money.format(dayExpense)}",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = dc.expense
                )
            }
            if (dayIncome > 0) {
                Spacer(Modifier.width(10.dp))
                Text(
                    "收入 ${Money.format(dayIncome)}",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = dc.income
                )
            }
        }
        Spacer(Modifier.height(10.dp))

        if (ui.dayItems.isEmpty()) {
            EmptyHint(
                emoji = "🌤",
                title = t("这一天没有记录"),
                subtitle = t("用上面的「‹ ›」换个日子翻翻；补记的话回明细页点「记一笔」")
            )
        } else if (embedInScroll) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ui.dayItems.forEach { DayTxnRow(it) { onEditTransaction(it.id) } }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                items(ui.dayItems, key = { "cal-${it.id}" }) { t ->
                    DayTxnRow(t) { onEditTransaction(t.id) }
                }
            }
        }
    }
}

@Composable
private fun DayTxnRow(t: TransactionEntity, onClick: () -> Unit = {}) {
    val dc = LocalDirectionColors.current
    // v13.9：surface 60% 平涂 → 玻璃块（大面积，浓度 0.6）
    Box(
        Modifier
            .glassChip(shape = RoundedCornerShape(Radius.md), strength = 0.6f)
            .clickableScale(pressedScale = 0.985f, clipShape = RoundedCornerShape(Radius.md)) {
                onClick()
            }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)
        ) {
            Text(t.categoryEmoji, fontSize = 15.sp)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    t.categoryName,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (t.note.isNotBlank()) {
                    Text(
                        t.note,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Text(
                (if (t.direction == 0) "-" else "+") + Money.format(t.amountCents, symbol = false),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (t.direction == 0) dc.expense else dc.income
            )
        }
    }
}
