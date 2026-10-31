package com.foldledger.ui.screens

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.foldledger.data.db.DayTotal
import com.foldledger.data.db.RecurringInterval
import com.foldledger.ui.adaptive.AdaptiveScaffold
import com.foldledger.ui.adaptive.FoldEnv
import com.foldledger.ui.anim.clickableScale
import com.foldledger.ui.components.EmptyHint
import com.foldledger.ui.components.SectionCard
import com.foldledger.ui.components.TrendBars
import com.foldledger.ui.theme.LocalDirectionColors
import com.foldledger.ui.theme.Layout
import com.foldledger.ui.theme.dividerBrush
import com.foldledger.ui.theme.rememberAnimatedPaneWidth
import com.foldledger.ui.theme.glassChip
import com.foldledger.ui.vm.InsightViewModel
import com.foldledger.util.DateExt
import com.foldledger.util.Money
import com.foldledger.util.RecurringDetector

/**
 * 智能洞察（v8.0）——从「记录」到「懂账」。
 *
 * 三块：周期性支出识别（能偷懒）/ 净资产走势（有没有变富）/
 * 支出趋势与预测（今年要花多少）。全部本地计算，数据不出设备。
 */
@Composable
fun InsightScreen(
    env: FoldEnv,
    ledgerId: Long,
    onBack: () -> Unit
) {
    val vm: InsightViewModel = viewModel(
        key = "insight-$ledgerId",
        factory = InsightViewModel.factory(ledgerId)
    )
    val suggestions by vm.suggestions.collectAsStateWithLifecycle()
    val netWorth by vm.netWorthSeries.collectAsStateWithLifecycle()
    val trend by vm.expenseTrend.collectAsStateWithLifecycle()
    val forecast by vm.yearForecast.collectAsStateWithLifecycle()

    AdaptiveScaffold(
        env = env,
        title = "智能洞察",
        subtitle = "全部在本机算 · 数据不出设备",
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
                Column(
                    Modifier
                        .width(masterWidth)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(start = 20.dp, end = 12.dp)
                ) {
                    Spacer(Modifier.height(6.dp))
                    RecurringSection(suggestions, onAdd = vm::addSuggestion)
                }
                Box(Modifier.width(1.dp).fillMaxHeight().background(dividerBrush()))
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp)
                ) {
                    Spacer(Modifier.height(6.dp))
                    NetWorthSection(netWorth)
                    Spacer(Modifier.height(12.dp))
                    TrendSection(trend, forecast)
                    Spacer(Modifier.height(28.dp))
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
                item { RecurringSection(suggestions, onAdd = vm::addSuggestion) }
                item { NetWorthSection(netWorth) }
                item { TrendSection(trend, forecast) }
            }
        }
    }
}

// ================= 周期性支出 =================

@Composable
private fun RecurringSection(
    suggestions: List<RecurringDetector.Suggestion>,
    onAdd: (RecurringDetector.Suggestion) -> Unit
) {
    SectionCard(
        title = "周期性支出",
        subtitle = if (suggestions.isEmpty()) "扫描近 7 个月流水"
        else "发现 ${suggestions.size} 个疑似周期流 · 一键加入周期记账"
    ) {
        if (suggestions.isEmpty()) {
            EmptyHint(
                emoji = "🔍",
                title = "没发现明显的周期支出",
                subtitle = "房租、会员这类固定支出记满 3 次后再来看看"
            )
        } else {
            suggestions.forEach { s ->
                SuggestionRow(s, onAdd = { onAdd(s) })
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun SuggestionRow(s: RecurringDetector.Suggestion, onAdd: () -> Unit) {
    var added by remember { mutableStateOf(false) }
    val interval = when (s.intervalKind) {
        RecurringInterval.WEEKLY -> "每周"
        RecurringInterval.YEARLY -> "每年"
        else -> "每月"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(s.categoryEmoji, fontSize = 17.sp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                s.name,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "$interval ${Money.format(s.amountCents)} · 已出现 ${s.occurrences} 次 · " +
                    "下次约 ${DateExt.shortDay(s.predictedNextAt)}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .glassChip(
                    shape = RoundedCornerShape(50),
                    tint = if (added) null else MaterialTheme.colorScheme.primary,
                    strength = if (added) 0.55f else 1.9f
                )
                .clickableScale(
                    pressedScale = 0.93f,
                    clipShape = RoundedCornerShape(50)
                ) { if (!added) { added = true; onAdd() } }
        ) {
            Text(
                if (added) "已加入 ✓" else "加入",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (added) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}

// ================= 净资产走势 =================

@Composable
private fun NetWorthSection(series: List<com.foldledger.ui.vm.NetWorthPoint>) {
    val dc = LocalDirectionColors.current
    SectionCard(
        title = "净资产走势",
        subtitle = if (series.size < 2) "建好资产账户、记几笔后这里出现走势"
        else "近 12 个月月末净资产"
    ) {
        if (series.size < 2) {
            EmptyHint(
                emoji = "📈",
                title = "还没有足够的数据",
                subtitle = "在资产页建好现金/储蓄卡账户，走势从这里长出来"
            )
        } else {
            // TrendBars 的输入是 DayTotal（label + cents），复用同一根柱子组件
            val bars = series.map { DayTotal(DateExt.monthLabel(it.monthTs), it.cents) }
            TrendBars(days = bars, color = dc.income, height = 110.dp)
            Spacer(Modifier.height(8.dp))
            val first = series.first().cents
            val last = series.last().cents
            val delta = last - first
            Row {
                Text(
                    "一年前 ${Money.format(first)}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "现在 ${Money.format(last)}（${if (delta >= 0) "+" else "-"}${Money.format(kotlin.math.abs(delta))}）",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (delta >= 0) dc.income else dc.expense
                )
            }
        }
    }
}

// ================= 支出趋势与预测 =================

@Composable
private fun TrendSection(trend: List<com.foldledger.data.db.MonthTotal>, forecast: Long?) {
    val dc = LocalDirectionColors.current
    SectionCard(
        title = "支出趋势与预测",
        subtitle = "近 ${trend.size} 个月支出 · 按近 3 月节奏推全年"
    ) {
        if (trend.isEmpty()) {
            EmptyHint(emoji = "🧾", title = "还没有月度数据", subtitle = "记几笔后这里出现趋势")
        } else {
            val bars = trend.map { DayTotal(it.month, it.totalCents) }
            TrendBars(days = bars, color = dc.expense, height = 110.dp)
            Spacer(Modifier.height(8.dp))
            val avg = trend.map { it.totalCents }.average().toLong()
            Text(
                "月均 ${Money.format(avg)}",
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (forecast != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "按近 3 个月的节奏，全年预计支出 ${Money.format(forecast)}",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}
