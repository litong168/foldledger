package com.foldledger.ui.screens.detail

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
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
import com.foldledger.ui.components.LedgerAvatar
import com.foldledger.ui.components.PillTag
import com.foldledger.ui.components.SectionCard
import com.foldledger.ui.components.parseHexColor
import com.foldledger.ui.theme.glassChip
import com.foldledger.ui.theme.glassSurface
import com.foldledger.ui.theme.LocalDarkTheme
import com.foldledger.ui.theme.Layout
import com.foldledger.ui.theme.LocalDirectionColors
import com.foldledger.ui.theme.Radius
import com.foldledger.ui.theme.pendingTone
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

/**
 * 拆自 LedgerDetailScreen.kt（v10.2.0）—— 把 3100 行按职责拆成 detail/ 子包。
 * 本文件保持原注释与代码不变，只调整可见性（private → internal）供同包兄弟文件调用。
 */
// ============================================================
//  报销看板
// ============================================================

/**
 * 过滤后没有结果时的空态（v5.2）。
 *
 * 三种原因说三种话：按报销状态筛空了 / 按项目筛空了 / 关键词没搜到 ——
 * 一律写"没有匹配的记录"会让用户以为数据丢了。
 */
@Composable
internal fun FilteredEmptyHint(searchQuery: String, projectFilter: Long, reimburseFilter: Set<Int>) {
    when {
        reimburseFilter.isNotEmpty() -> {
            val label = when {
                ReimburseState.PENDING in reimburseFilter && ReimburseState.SUBMITTED in reimburseFilter -> "未回款"
                ReimburseState.SETTLED in reimburseFilter -> "已回款"
                ReimburseState.PENDING in reimburseFilter -> "待提交"
                ReimburseState.SUBMITTED in reimburseFilter -> "已提交"
                else -> "已回款"
            }
            EmptyHint(
                emoji = "📭",
                title = "没有「$label」的流水",
                subtitle = "点上方的「清除 ✕」或再点一次那一格，就能回到全部"
            )
        }
        searchQuery.isNotBlank() -> EmptyHint(
            emoji = "🔍",
            title = "没有匹配的记录",
            subtitle = "换个关键词试试"
        )
        projectFilter > LedgerDetailViewModel.PROJECT_UNASSIGNED -> EmptyHint(
            emoji = "📁",
            title = "这个项目还没有记录",
            subtitle = "记一笔时选上它，或在流水里把其他笔归进来"
        )
        else -> EmptyHint(
            emoji = "🔍",
            title = "没有匹配的记录",
            subtitle = "换个关键词试试"
        )
    }
}

/**
 * 「当前只看某个报销状态」的提示条（v5.2）。
 *
 * 点看板/项目卡上的某一格后出现：告诉用户现在正在筛什么、一共几笔多少钱，
 * 并提供一键清除 —— 筛选是"临时视图"，必须随时能退出去。
 */
@Composable
internal fun ReimburseFilterBar(
    states: Set<Int>,
    groups: List<DayGroup>,
    onClear: () -> Unit
) {
    val (label, color) = when {
        states.contains(ReimburseState.PENDING) && states.contains(ReimburseState.SUBMITTED) ->
            "未回款" to pendingTone()
        states.contains(ReimburseState.SETTLED) -> "已回款" to ReimburseSettled
        states.contains(ReimburseState.PENDING) -> "待提交" to pendingTone()
        states.contains(ReimburseState.SUBMITTED) -> "已提交" to ReimburseSubmitted
        else -> "已回款" to ReimburseSettled
    }
    val count = groups.sumOf { it.items.size }
    val total = groups.sumOf { it.expenseCents }

    // v13.9：这条"现在只看什么"的提示条也是玻璃 —— 旧版是"10% 淡色底 + 1dp 描边"，
    // 描边不是玻璃的语言（玻璃的边界是折出来的棱，不是画出来的线）。
    Box(
        Modifier
            .fillMaxWidth()
            .glassChip(shape = RoundedCornerShape(50), tint = color, strength = 1.0f)
    ) {
        Row(
            Modifier.padding(start = 14.dp, end = 12.dp, top = 9.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(7.dp)
                    .clip(RoundedCornerShape(50))
                    .background(color)
            )
            Spacer(Modifier.width(7.dp))
            Text(
                "只看「$label」",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = color
            )
            Spacer(Modifier.weight(1f))
            Text(
                "$count 笔 · ${Money.format(total)}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "清除 ✕",
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = color,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickableScale(pressedScale = 0.92f) { onClear() }
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            )
        }
    }
}

/**
 * 垫付 / 回款看板（v7.1）。
 *
 * 报销账本的核心不是"花了多少"，而是**「公司还欠我多少、已经回来多少」**——
 * 所以两个主角是「尚余垫付」（待提交+已提交）和「已回款」（已到账），
 * 三档明细退居第二行做拆解。点主角 = 按档位筛流水，点三格 = 按单档筛。
 */
@Composable
internal fun ReimburseBoard(
    summary: ReimburseSummary,
    aging: ReimburseAging.Summary = ReimburseAging.Summary.EMPTY,
    selected: Set<Int> = emptySet(),
    onToggleState: (Int) -> Unit = {},
    onPickOutstanding: () -> Unit = {},
    onPickRecovered: () -> Unit = {}
) {
    val outstandingActive = selected == setOf(ReimburseState.PENDING, ReimburseState.SUBMITTED)
    val recoveredActive = selected == setOf(ReimburseState.SETTLED)

    SectionCard(
        title = "垫付 · 回款",
        subtitle = when {
            summary.allSettled -> "全部垫付都已回款 ✓"
            summary.advancedCents == 0L -> "记一笔垫付后，这里显示公司还欠你多少"
            aging.oldestDays >= 30 -> "最久一笔已压 ${aging.oldestDays} 天，该催了"
            else -> "点大数字或任意一格筛出相关流水"
        }
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ReimburseHeroCell(
                label = "尚余垫付",
                cents = summary.outstandingCents,
                color = pendingTone(),
                sub = if (aging.oldestDays > 0) "${summary.outstandingCount} 笔 · 最久 ${aging.oldestDays} 天"
                else "${summary.outstandingCount} 笔待回款",
                active = outstandingActive,
                modifier = Modifier.weight(1f),
                onClick = onPickOutstanding
            )
            ReimburseHeroCell(
                label = "已回款",
                cents = summary.recoveredCents,
                color = ReimburseSettled,
                sub = "回款率 ${(summary.recoveredFraction * 100).toInt()}%",
                active = recoveredActive,
                modifier = Modifier.weight(1f),
                onClick = onPickRecovered
            )
        }

        if (summary.advancedCents > 0L) {
            Spacer(Modifier.height(12.dp))
            ReimburseCompositionBar(summary = summary)
        }

        // v7.2 账龄分档：钱压在哪里一眼看清，30 天以上的档标红=该催了
        if (summary.outstandingCents > 0L) {
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                aging.buckets.filter { it.count > 0 }.forEach { b ->
                    val tone = when {
                        b.daysFrom >= 91 -> MaterialTheme.colorScheme.error
                        b.daysFrom >= 31 -> pendingTone()
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Box(
                        Modifier.glassChip(
                            shape = RoundedCornerShape(50),
                            tint = tone,
                            strength = if (b.isOverdue) 1.2f else 0.8f
                        )
                    ) {
                        Text(
                            "${b.label} ${Money.format(b.cents)}",
                            fontSize = 11.sp,
                            fontWeight = if (b.isOverdue) FontWeight.SemiBold else FontWeight.Normal,
                            color = tone,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ReimburseTile(
                "待提交", summary.pendingCents, pendingTone(),
                active = selected.contains(ReimburseState.PENDING) && selected.size == 1,
                modifier = Modifier.weight(1f)
            ) { onToggleState(ReimburseState.PENDING) }
            ReimburseTile(
                "已提交", summary.submittedCents, ReimburseSubmitted,
                active = selected.contains(ReimburseState.SUBMITTED) && selected.size == 1,
                modifier = Modifier.weight(1f)
            ) { onToggleState(ReimburseState.SUBMITTED) }
            ReimburseTile(
                "已回款", summary.settledCents, ReimburseSettled,
                active = selected.contains(ReimburseState.SETTLED) && selected.size == 1,
                modifier = Modifier.weight(1f)
            ) { onToggleState(ReimburseState.SETTLED) }
        }
    }
}

/**
 * 看板主角格（v7.1）：尚余垫付 / 已回款。
 *
 * 大数字 + 一行小注（笔数 / 回款率）——比三格均权更直接地回答
 * "我还有多少钱压在外面"。
 */
@Composable
private fun ReimburseHeroCell(
    label: String,
    cents: Long,
    color: Color,
    sub: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    // v13.9：语义色**只走 tint**，不再垫一层"白 34% 的膜 + 语义色洗色"。
    // 那个组合在真机上合成出来是一块偏灰的实心片（白膜把卡片的底盖掉了，
    // 洗色又太淡），三格并排读起来像积分卡片。现在 fill 本身就是语义色的
    // 极淡着色玻璃，膜的厚度交给引擎，选中态靠浓度（2.0×）+ 投影区分。
    val shape = RoundedCornerShape(Radius.md)
    val fg = MaterialTheme.colorScheme.onSurface
    Box(
        modifier
            .glassChip(
                shape = shape,
                tint = color,
                strength = if (active) 2.0f else 0.9f,
                elevation = if (active) 8.dp else 3.dp
            )
            .clickableScale(pressedScale = 0.96f, clipShape = shape) { onClick() }
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    label,
                    fontSize = 11.sp,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold,
                    color = color
                )
                Spacer(Modifier.weight(1f))
                Text(
                    if (active) "●" else "›",
                    fontSize = if (active) 8.sp else 12.sp,
                    color = (if (active) fg else color).copy(alpha = 0.8f)
                )
            }
            Spacer(Modifier.height(3.dp))
            AnimatedCentsText(
                cents = cents,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 20,
                fontWeight = FontWeight.ExtraBold
            )
            Spacer(Modifier.height(2.dp))
            Text(
                sub,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 催办清单（v7.2）——"该催的账"。
 *
 * 未回款流水按垫付天数**从久到近**排：压得最久的排最上面，一眼看到先催哪笔。
 * 「复制催办摘要」把对账文本写进剪贴板，直接粘到微信/钉钉发给财务——
 * 催报销最烦的就是整理"到底哪几笔、各多少钱"，这一步必须一键。
 */
@Composable
internal fun ChaseListCard(
    items: List<TransactionEntity>,
    projectNames: Map<Long, String>,
    ledgerName: String,
    onFilterAll: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    val now = remember(items) { System.currentTimeMillis() }
    var copied by remember { mutableStateOf(false) }

    SectionCard(
        title = "催办清单",
        subtitle = "未回款按垫付天数从久到近 · 共 ${items.size} 笔"
    ) {
        items.take(6).forEach { t ->
            val days = ReimburseAging.ageDays(t.occurredAt, now)
            val tone = when {
                days >= 91 -> MaterialTheme.colorScheme.error
                days >= 31 -> pendingTone()
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 4.dp)
            ) {
                Text(
                    DateExt.shortDay(t.occurredAt),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(52.dp)
                )
                Text(t.categoryEmoji, fontSize = 13.sp)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        t.categoryName,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val pn = projectNames[t.projectId]?.takeIf { it.isNotBlank() }
                    if (pn != null) {
                        Text(pn, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
                Text(
                    Money.format(t.amountCents),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
                Box(Modifier.glassChip(shape = RoundedCornerShape(50), tint = tone, strength = 1.0f)) {
                    Text(
                        "压了 $days 天",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = tone,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                    )
                }
            }
        }
        if (items.size > 6) {
            Text(
                "…共 ${items.size} 笔，点「看全部未回款」查看",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .weight(1f)
                    .clickableScale(
                        pressedScale = 0.95f,
                        clipShape = RoundedCornerShape(50)
                    ) {
                        clipboard.setText(
                            AnnotatedString(
                                ReimburseAging.chaseSummary(ledgerName, items, projectNames)
                            )
                        )
                        copied = true
                    }
            ) {
                Text(
                    if (copied) "已复制 ✓ 粘贴发给财务" else "复制催办摘要",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 9.dp)
                )
            }
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
                modifier = Modifier.clickableScale(
                    pressedScale = 0.95f,
                    clipShape = RoundedCornerShape(50)
                ) { onFilterAll() }
            ) {
                Text(
                    "看全部未回款",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)
                )
            }
        }
    }
}

/**
 * 报销看板的一格（v5.2 可点）。
 *
 * 点一下让明细列表只剩这个状态的流水，再点一次取消 —— 比"知道总数"更进一步，
 * 直接看到"这几笔到底是哪些"。
 */
@Composable
private fun ReimburseTile(
    label: String,
    cents: Long,
    color: Color,
    active: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    // v13.9：三格是**同一块玻璃的三种染色**。
    // 旧版选中态直接铺 `color.copy(alpha = 0.92)` —— 那是一块实色（用户明确否过
    // "实色选中块"）：在玻璃卡里贴一块不透明的色片，边界立刻从"棱"变成"贴纸边"。
    // 现在两种状态都是染色玻璃，只差浓度（2.4× / 0.9×）与是否挂投影。
    // 金额色一律用 onSurface：语义色已经在膜上了，数字再染色会掉到 4.5:1 以下。
    val shape = RoundedCornerShape(Radius.md)
    val fg = MaterialTheme.colorScheme.onSurface
    Box(
        modifier
            .glassChip(
                shape = shape,
                tint = color,
                strength = if (active) 2.4f else 0.9f,
                elevation = if (active) 8.dp else 3.dp
            )
            .clickableScale(pressedScale = 0.95f, clipShape = shape) { onClick() }
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    label,
                    fontSize = 11.sp,
                    color = if (active) fg.copy(alpha = 0.9f) else color,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium
                )
                Spacer(Modifier.weight(1f))
                // 可点的提示：一个小箭头，选中时变成"筛选中"的实心点
                Text(
                    if (active) "●" else "›",
                    fontSize = if (active) 8.sp else 12.sp,
                    color = (if (active) fg else color).copy(alpha = 0.8f)
                )
            }
            Spacer(Modifier.height(2.dp))
            AnimatedCentsText(
                cents = cents,
                color = fg,
                fontSize = 15,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}


/**
 * 「已回款」归档条（v13.9.2）。
 *
 * 默认列表里看不到已到账的条目 —— 报销账本里钱已经回来了，这一档不用一直
 * 占着位置（用户原话："已到账的条目也隐藏或归档，只显示待提交和已提交"）。
 *
 * 但"看不见"和"没了"是两回事，所以这一行就是那扇门：写清楚有多少笔被收起来、
 * 多少钱在里面，点一下把它们放回列表。位置在列表最末 —— 收起来的东西就该待在末尾，
 * 塞在中间只会挡住还在等你处理的那几笔。
 */
/**
 * 垫付构成三段条（v13.9.3）。
 *
 * 把「总垫付」按 待提交 / 已提交 / 已回款 拆成三段同色带，一眼看清钱压在哪一步——
 * 比孤立的「回款进度」细线 + 三块 tile 更连贯：细线只说"回了多少"，
 * 三段条同时说"没回的部分里，多少还卡在待提交、多少已提交在等批"。
 * 带子按金额比例分配（weight），金额为 0 的档直接不画。
 *
 * 纯展示，点筛交给下方三格 tile（热区更大、视觉反馈更明确）。
 */
@Composable
private fun ReimburseCompositionBar(summary: ReimburseSummary) {
    val total = summary.advancedCents
    if (total <= 0L) return
    val dark = com.foldledger.ui.theme.LocalDarkTheme.current
    val faint = MaterialTheme.colorScheme.onSurfaceVariant
    val trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = if (dark) 0.10f else 0.08f)
    val segs = listOf(
        Triple(summary.pendingCents, pendingTone(), "待提交"),
        Triple(summary.submittedCents, ReimburseSubmitted, "已提交"),
        Triple(summary.settledCents, ReimburseSettled, "已回款"),
    ).filter { it.first > 0L }

    Column {
        Text("垫付构成", fontSize = 11.sp, color = faint)
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(16.dp)
                .clip(RoundedCornerShape(50))
                .background(trackColor)
        ) {
            Row(Modifier.fillMaxSize()) {
                segs.forEach { (cents, color, _) ->
                    val w = (cents.toFloat() / total).coerceIn(0f, 1f)
                    Box(Modifier.weight(w).fillMaxHeight().background(color))
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // 图例始终列出三态（含 0%），让"钱压在哪一步"一眼完整；
            // 金额为 0 的档在条里只是 0 宽不可见段，但图例要交代清楚。
            listOf(
                Triple(summary.pendingCents, pendingTone(), "待提交"),
                Triple(summary.submittedCents, ReimburseSubmitted, "已提交"),
                Triple(summary.settledCents, ReimburseSettled, "已回款"),
            ).forEach { (cents, color, label) ->
                val pct = ((cents * 100 + total / 2) / total).toInt()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(color))
                    Spacer(Modifier.width(4.dp))
                    Text("$label $pct%", fontSize = 11.sp, color = faint)
                }
            }
        }
    }
}

@Composable
internal fun SettledArchiveBar(
    count: Int,
    cents: Long,
    onClick: () -> Unit
) {
    if (count <= 0) return
    val tone = ReimburseSettled
    Row(
        Modifier
            .fillMaxWidth()
            .glassChip(
                shape = RoundedCornerShape(Radius.md),
                tint = tone,
                strength = 0.9f
            )
            .clickableScale(pressedScale = 0.97f) { onClick() }
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("🗄", fontSize = 15.sp)
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "已回款 $count 笔已归档",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                "${Money.format(cents)} · 点此放回列表",
                fontSize = 11.sp,
                lineHeight = 15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        // 尖角：画线而不是用字符 ▸（11sp 的字符三角在 440dpi 上会糊成一个点）
        androidx.compose.foundation.Canvas(
            Modifier.size(width = 8.dp, height = 12.dp)
        ) {
            val sw = 1.6.dp.toPx()
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(0f, 0f)
                lineTo(size.width, size.height / 2f)
                lineTo(0f, size.height)
                close()
            }
            drawPath(
                path = path,
                color = tone.copy(alpha = 0.85f),
                style = Stroke(
                    width = sw,
                    join = StrokeJoin.Round
                )
            )
        }
    }
}
