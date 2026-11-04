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
import com.foldledger.ui.components.SectionCard
import com.foldledger.ui.components.ThinProgress
import com.foldledger.ui.components.parseHexColor
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
import com.foldledger.ui.theme.glassSelectChip
import com.foldledger.ui.theme.rememberAnimatedPaneWidth
import kotlinx.coroutines.delay

/**
 * 拆自 LedgerDetailScreen.kt（v10.2.0）—— 把 3100 行按职责拆成 detail/ 子包。
 * 本文件保持原注释与代码不变，只调整可见性（private → internal）供同包兄弟文件调用。
 */
// ============================================================
//  多维筛选（v5.7）
// ============================================================

/**
 * 筛选面板（v5.7）。
 *
 * 三个维度都是"点一下就选中"的胶囊，不做下拉/滑块 —— 记账 App 的筛选是高频低深度操作，
 * 每一步都要点确定是反人类。应用 / 清除 / 取消 三个动作分开，误点可退回。
 */
@Composable
internal fun FilterOverlay(
    current: TxnFilter,
    onApply: (TxnFilter) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    var timeRange by remember { mutableStateOf(current.timeRange) }
    var direction by remember { mutableStateOf(current.direction) }
    var amount by remember { mutableStateOf(current.amount) }

    GlassDialog(
        onDismissRequest = onDismiss,
        title = { Text("筛选流水") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                FilterGroup("时间") {
                    FilterTimeRange.entries.forEach { r ->
                        FilterChip(r.label, r == timeRange) { timeRange = r }
                    }
                }
                FilterGroup("收支") {
                    FilterChip("不限", direction == null) { direction = null }
                    FilterChip("支出", direction == Direction.EXPENSE) { direction = Direction.EXPENSE }
                    FilterChip("收入", direction == Direction.INCOME) { direction = Direction.INCOME }
                }
                FilterGroup("金额") {
                    FilterAmount.entries.forEach { a ->
                        FilterChip(a.label, a == amount) { amount = a }
                    }
                }
            }
        },
        confirmButton = {
            Text(
                "应用",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickableScale(pressedScale = 0.92f) {
                        onApply(TxnFilter(timeRange, direction, amount))
                        onDismiss()
                    }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )
        },
        dismissButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "清除",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickableScale(pressedScale = 0.92f) {
                            onClear()
                            onDismiss()
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
                Text(
                    "取消",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickableScale(pressedScale = 0.92f, onClick = onDismiss)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }
    )
}

@Composable
private fun FilterGroup(
    title: String,
    content: @Composable androidx.compose.foundation.layout.FlowRowScope.() -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content
        )
    }
}

@Composable
private fun FilterChip(label: String, active: Boolean, onClick: () -> Unit) {
    val fg = if (active) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Box(
        Modifier
            .glassSelectChip(active, shape = RoundedCornerShape(50))
            .clickableScale(pressedScale = 0.92f) { onClick() }
    ) {
        Text(
            label,
            fontSize = 13.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            color = fg,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
        )
    }
}

/**
 * 筛选生效时的提示条（与报销状态筛选的提示条同一套观感）。
 * 明确告诉用户"现在只看这组"，并留一个一键清除的出口。
 */
@Composable
internal fun FilterActiveBar(
    filter: TxnFilter,
    count: Int,
    amount: Long,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    // v9.9.5：这块和上面的"报销筛选条"原本是两种形态（12dp 直角蓝底 vs 全圆角淡色描边），
    // 两条还能同时出现在一屏里，像两个 App 的组件。统一成：淡色底 + 1dp 细描边。
    val tint = MaterialTheme.colorScheme.primary
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .background(tint.copy(alpha = 0.10f))
            .border(
                androidx.compose.foundation.BorderStroke(1.dp, tint.copy(alpha = 0.30f)),
                RoundedCornerShape(Radius.md)
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("🔎", fontSize = 13.sp)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                filter.summary(),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                "$count 笔 · ${Money.format(amount)}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            "清除 ✕",
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .clickableScale(pressedScale = 0.9f) { onClear() }
                .padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}

