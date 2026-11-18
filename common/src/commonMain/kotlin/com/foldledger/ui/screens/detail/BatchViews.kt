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
import androidx.compose.foundation.layout.heightIn
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
import com.foldledger.ui.theme.glassChip
import com.foldledger.ui.theme.rememberAnimatedPaneWidth
import kotlinx.coroutines.delay
import com.foldledger.ui.l10n.t

/**
 * 拆自 LedgerDetailScreen.kt（v10.2.0）—— 把 3100 行按职责拆成 detail/ 子包。
 * 本文件保持原注释与代码不变，只调整可见性（private → internal）供同包兄弟文件调用。
 */
// ============================================================
//  批量改账（v5.6）
// ============================================================

/**
 * 多选模式的勾选圆圈。
 *
 * v16.3.1：选中不再是 primary 实色块。22dp 属小尺寸玻璃，按规范只用 1dp 影，
 * 靠「主色染膜浓度 + 主色内棱」表达，✓ 回到主色、字重收成 SemiBold（玻璃上不用 Bold）。
 */
@Composable
internal fun SelectCircle(selected: Boolean) {
    val accent = MaterialTheme.colorScheme.primary
    Box(
        Modifier
            .size(22.dp)
            .clip(CircleShape)
            .then(
                if (selected) Modifier.glassChip(
                    shape = CircleShape,
                    tint = accent,
                    strength = 1.4f,
                    elevation = 1.dp
                ) else Modifier
            )
            .border(
                1.5.dp,
                if (selected) accent else MaterialTheme.colorScheme.outlineVariant,
                CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Text(
                "✓",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = accent
            )
        }
    }
}

/**
 * 批量工具条（v5.6）。
 *
 * 两种形态：
 *  - **未多选**：只露出一个「多选」入口，不打扰日常记账；
 *  - **多选中**：一条常驻的操作面板，显示已选 N 笔与合计，四个批量动作一排排开。
 *
 * 放在搜索栏下方而不是悬浮在底部 —— 悬浮条会和「记一笔」FAB 抢位置，
 * 而且在折叠屏的双栏布局里底部对不齐。
 */
@Composable
internal fun BatchToolbar(
    multiSelect: Boolean,
    selectedCount: Int,
    selectedAmount: Long,
    hasReimburse: Boolean,
    visibleIds: List<Long>,
    filterActive: Boolean,
    onOpenFilter: () -> Unit,
    onEnter: () -> Unit,
    onExit: () -> Unit,
    onSelectAll: () -> Unit,
    onInvert: () -> Unit,
    onBatchCategory: () -> Unit,
    onBatchProject: () -> Unit,
    onBatchReimburse: () -> Unit,
    onBatchMove: () -> Unit,
    onBatchDelete: () -> Unit,
    /** v15：自动账本里「移动到账本」语义化为主行动「确认入账」 */
    moveLabel: String = t("移动到账本"),
    modifier: Modifier = Modifier
) {
    if (!multiSelect) {
        Row(
            modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 筛选入口：有激活条件时给个主色小圆点，一眼知道"当前不是全部流水"
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(
                        MaterialTheme.colorScheme.onSurface.copy(
                            alpha = if (com.foldledger.ui.theme.LocalDarkTheme.current) 0.10f else 0.045f
                        )
                    )
                    .clickableScale(pressedScale = 0.94f) { onOpenFilter() }
                    .padding(horizontal = 14.dp, vertical = 7.dp)
            ) {
                if (filterActive) {
                    Box(
                        Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                    Spacer(Modifier.width(5.dp))
                }
                Text(
                    // v9.9.5：原来写"⛃ 筛选"，⛃ 是生僻符号，部分机型字体会渲染成方框
                    t("筛选"),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (filterActive) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.weight(1f))
            // 批量入口做成低调的文字按钮：批量是低频操作，不该和「搜索」「记一笔」抢注意力
            Text(
                t("☑ 多选"),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(
                        MaterialTheme.colorScheme.onSurface.copy(
                            alpha = if (com.foldledger.ui.theme.LocalDarkTheme.current) 0.10f else 0.045f
                        )
                    )
                    .clickableScale(pressedScale = 0.94f, enabled = visibleIds.isNotEmpty()) { onEnter() }
                    .padding(horizontal = 14.dp, vertical = 7.dp)
            )
        }
        return
    }

    SurfaceCard(
        shape = RoundedCornerShape(Radius.md),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
        elevation = 2.dp,
        showBorder = true,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "✕",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickableScale(pressedScale = 0.85f) { onExit() }
                        .padding(8.dp)
                )
                Spacer(Modifier.width(4.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        // v9.9.5：筛选生效时「全选」只选可见的那些，
                        // 不写清楚会让人以为选了全部流水（改完发现别处没变）
                        if (filterActive) "已选 $selectedCount / ${visibleIds.size} 笔（筛选后）"
                        else "已选 $selectedCount 笔",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (selectedCount > 0) {
                        Text(
                            "合计 ${Money.format(selectedAmount)}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Text(
                    t("全选"),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickableScale(pressedScale = 0.9f) { onSelectAll() }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
                Text(
                    t("反选"),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickableScale(pressedScale = 0.9f) { onInvert() }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BatchActionButton(t("改分类"), enabled = selectedCount > 0, onClick = onBatchCategory)
                BatchActionButton(t("改项目"), enabled = selectedCount > 0, onClick = onBatchProject)
                if (hasReimburse) {
                    BatchActionButton(t("报销状态"), enabled = selectedCount > 0, onClick = onBatchReimburse)
                }
                // v8.1：自动记账确认后挪去正式账本（分类/场景字段自动适配）
                BatchActionButton(moveLabel, enabled = selectedCount > 0, onClick = onBatchMove)
                BatchActionButton(
                    t("删除"),
                    enabled = selectedCount > 0,
                    danger = true,
                    onClick = onBatchDelete
                )
            }
        }
    }
}

@Composable
private fun BatchActionButton(
    label: String,
    enabled: Boolean,
    danger: Boolean = false,
    onClick: () -> Unit
) {
    val fg = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        danger -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    Box(
        Modifier
            .glassChip(
                shape = RoundedCornerShape(10.dp),
                tint = if (danger && enabled) MaterialTheme.colorScheme.error else null,
                strength = if (danger && enabled) 0.9f else 0.55f
            )
            .clickableScale(pressedScale = 0.92f, enabled = enabled) { onClick() }
    ) {
        Text(
            label,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = fg,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}

/** 批量操作的弹窗种类 */
internal enum class BatchMode { CATEGORY, PROJECT, REIMBURSE, MOVE, DELETE }

/**
 * 批量操作的二次选择（v5.6）。
 *
 * 分类/项目/报销状态都是「选完立刻生效」，不需要再点确定 —— 批量操作的痛点就是"要多点一下"。
 * 只有删除是不可撤销的，才留一次确认。
 */
@Composable
internal fun BatchActionDialog(
    mode: BatchMode,
    selectedCount: Int,
    categories: List<CategoryEntity>,
    projects: List<ProjectEntity>,
    moveTargets: List<LedgerEntity> = emptyList(),
    onPickCategory: (CategoryEntity) -> Unit,
    onPickProject: (Long) -> Unit,
    onPickReimburse: (Int) -> Unit,
    onPickLedger: (Long) -> Unit = {},
    onConfirmDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    when (mode) {
        BatchMode.DELETE -> GlassDialog(
            onDismissRequest = onDismiss,
            title = { Text("删除 $selectedCount 笔流水？") },
            text = { Text("删除后无法恢复。确认要删掉这 $selectedCount 笔吗？") },
            confirmButton = {
                Text(
                    t("删除"),
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickableScale(pressedScale = 0.92f) {
                            onConfirmDelete()
                            onDismiss()
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
            },
            dismissButton = {
                Text(
                    t("取消"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickableScale(pressedScale = 0.92f, onClick = onDismiss)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        )

        else -> BatchPickerSheet(
            mode = mode,
            categories = categories,
            projects = projects,
            moveTargets = moveTargets,
            onPickCategory = { onPickCategory(it); onDismiss() },
            onPickProject = { onPickProject(it); onDismiss() },
            onPickReimburse = { onPickReimburse(it); onDismiss() },
            onPickLedger = { onPickLedger(it); onDismiss() },
            onDismiss = onDismiss
        )
    }
}

/** 分类 / 项目 / 报销状态 / 目标账本的选择列表（共用一个壳） */
@Composable
internal fun BatchPickerSheet(
    mode: BatchMode,
    categories: List<CategoryEntity>,
    projects: List<ProjectEntity>,
    moveTargets: List<LedgerEntity> = emptyList(),
    onPickCategory: (CategoryEntity) -> Unit,
    onPickProject: (Long) -> Unit,
    onPickReimburse: (Int) -> Unit,
    onPickLedger: (Long) -> Unit = {},
    onDismiss: () -> Unit
) {
    val title = when (mode) {
        BatchMode.CATEGORY -> t("改分类")
        BatchMode.PROJECT -> t("改项目")
        BatchMode.REIMBURSE -> t("改报销状态")
        BatchMode.MOVE -> t("移动到账本")
        BatchMode.DELETE -> ""
    }
    GlassDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            // v10.3：分类/项目多时给滚动上限，否则对话框内容被裁切不可达
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                when (mode) {
                    BatchMode.CATEGORY -> {
                        if (categories.isEmpty()) {
                            Text(
                                t("这个账本还没有分类"),
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        categories.forEach { c ->
                            BatchPickRow(
                                emoji = c.emoji,
                                label = c.name,
                                onClick = { onPickCategory(c) }
                            )
                        }
                    }

                    BatchMode.PROJECT -> {
                        // 「未归类」放在第一个：批量把流水撤出项目是常见操作
                        BatchPickRow(emoji = "🗂", label = t("不归入项目"), onClick = { onPickProject(0L) })
                        projects.filter { !it.closed }.forEach { p ->
                            BatchPickRow(
                                emoji = p.emoji,
                                label = p.name,
                                onClick = { onPickProject(p.id) }
                            )
                        }
                    }

                    BatchMode.REIMBURSE -> {
                        BatchPickRow("⏳", t("待提交"), onClick = { onPickReimburse(ReimburseState.PENDING) })
                        BatchPickRow("📤", t("已提交"), onClick = { onPickReimburse(ReimburseState.SUBMITTED) })
                        BatchPickRow("✅", t("已回款"), onClick = { onPickReimburse(ReimburseState.SETTLED) })
                    }

                    BatchMode.MOVE -> {
                        if (moveTargets.isEmpty()) {
                            Text(
                                t("还没有别的账本可以移入"),
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        moveTargets.forEach { l ->
                            BatchPickRow(
                                emoji = l.emoji,
                                label = "${l.name}（${ScenePresets.byKey(l.sceneKey).name}）",
                                onClick = { onPickLedger(l.id) }
                            )
                        }
                    }

                    else -> Unit
                }
            }
        },
        confirmButton = {
            Text(
                t("取消"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickableScale(pressedScale = 0.92f, onClick = onDismiss)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
    )
}

@Composable
private fun BatchPickRow(emoji: String, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickableScale(pressedScale = 0.96f) { onClick() }
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(emoji, fontSize = 17.sp)
        Spacer(Modifier.width(10.dp))
        Text(label, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

