package com.foldledger.ui.screens.detail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
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
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.Dp
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
import com.foldledger.ui.components.parseHexColor
import com.foldledger.ui.theme.Layout
import com.foldledger.ui.theme.LocalDirectionColors
import com.foldledger.ui.theme.Radius
import com.foldledger.ui.theme.pendingTone
import com.foldledger.ui.theme.settledTone
import com.foldledger.ui.theme.submittedTone
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
import com.foldledger.ui.theme.glassChip
import com.foldledger.ui.theme.onColorFor
import com.foldledger.ui.theme.softShadow
import com.foldledger.ui.theme.rememberAnimatedPaneWidth
import com.foldledger.ui.theme.glassSelectChip
import com.foldledger.data.CategoryIconLibrary
import androidx.compose.foundation.layout.heightIn
import kotlinx.coroutines.delay

/**
 * 拆自 LedgerDetailScreen.kt（v10.2.0）—— 把 3100 行按职责拆成 detail/ 子包。
 * 本文件保持原注释与代码不变，只调整可见性（private → internal）供同包兄弟文件调用。
 */
// ============================================================
//  项目 / 空间（v5.1）
// ============================================================

/**
 * 项目切换条。
 *
 * 报销账本的头顶多一行「空间」：全部 / 各项目 / 未归类 / ＋新建。
 * 点一下就把下面的流水过滤成那一趟差旅（或那一次招待）的账。
 */
@Composable
internal fun ProjectBar(
    projects: List<ProjectEntity>,
    selected: Long,
    onSelect: (Long) -> Unit,
    onCreate: () -> Unit,
    /**
     * v13.8：已归档的项目 id。
     *
     * 报销项目是有生命周期的：建 → 一堆待提交 → 交单 → 等回款 → 钱回来。
     * 走到「全部交出去了」之后，这趟差旅的事就完了 —— 它继续占在项目条最前面，
     * 每次看流水都要先划过三四个已经不想再碰的项目。
     *
     * 判据由调用方算（`pendingCount == 0`，或用户手动「已结束」），这里只负责
     * **把它们挪到一个可展开的抽屉里**：不删除、不丢数据、点一下还能看见。
     */
    archivedIds: Set<Long> = emptySet(),
    /**
     * v14.2：每个项目的报销状态徽标（待提交 / 已提交 / 已到账笔数）。
     * v16.5.1 从「只报待提交」扩成三档 —— 单子交出去之后项目条上什么都不显示，
     * 用户分不出「还在等打款」和「已经全到账」。
     */
    badges: Map<Long, ProjectStateBadge> = emptyMap(),
    /**
     * v16.1.1：桌面端左栏固定 368dp，LazyRow 横向滚动只露出前 2-3 个 chip，
     * 其余项目与「已归档 N」抽屉全在视口外（用户看不到其它项目）。
     * 传 true 时改用 FlowRow 换行排布、全部项目一眼可见；手机保持单行横滑。
     */
    expanded: Boolean = false
) {
    val active = projects.filter { it.id !in archivedIds }
    val archived = projects.filter { it.id in archivedIds }

    // 选中项落在归档区时自动展开 —— 否则用户点「已结清」里的某一项之后，
    // 那个 chip 收在抽屉里，屏幕上找不到"我现在看的是哪个项目"。
    var showArchived by remember { mutableStateOf(selected in archivedIds) }
    LaunchedEffect(selected, archivedIds) {
        if (selected in archivedIds) showArchived = true
    }

    if (expanded) {
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ProjectChip(
                label = "全部",
                emoji = null,
                active = selected == LedgerDetailViewModel.PROJECT_ALL
            ) { onSelect(LedgerDetailViewModel.PROJECT_ALL) }
            active.forEach { p ->
                ProjectChip(
                    label = p.name,
                    emoji = p.emoji,
                    colorHex = p.colorHex,
                    badge = badges[p.id],
                    active = selected == p.id
                ) { onSelect(p.id) }
            }
            if (projects.isNotEmpty()) {
                ProjectChip(
                    label = "未归类",
                    emoji = "🗂",
                    active = selected == LedgerDetailViewModel.PROJECT_UNASSIGNED
                ) { onSelect(LedgerDetailViewModel.PROJECT_UNASSIGNED) }
            }
            Box(
                Modifier
                    .glassChip(
                        shape = RoundedCornerShape(50),
                        tint = MaterialTheme.colorScheme.primary,
                        strength = 0.4f
                    )
                    .clickableScale(pressedScale = 0.94f) { onCreate() }
                    .padding(horizontal = 13.dp, vertical = 7.dp)
            ) {
                Text(
                    "＋ 新建项目",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (archived.isNotEmpty()) {
                ArchiveDrawerChip(count = archived.size, expanded = showArchived) {
                    showArchived = !showArchived
                }
                if (showArchived) {
                    archived.forEach { p ->
                        ProjectChip(
                            label = p.name,
                            emoji = p.emoji,
                            colorHex = p.colorHex,
                            // v16.5.1：归档的也要报状态（这一档通常是「已到账 N」）
                            badge = badges[p.id],
                            active = selected == p.id,
                            dimmed = true
                        ) { onSelect(p.id) }
                    }
                }
            }
        }
        return
    }

    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 2.dp)
    ) {
        item {
            ProjectChip(
                label = "全部",
                emoji = null,
                active = selected == LedgerDetailViewModel.PROJECT_ALL
            ) { onSelect(LedgerDetailViewModel.PROJECT_ALL) }
        }
        items(active, key = { "project-${it.id}" }) { p ->
            ProjectChip(
                label = p.name,
                emoji = p.emoji,
                colorHex = p.colorHex,
                badge = badges[p.id],
                active = selected == p.id
            ) { onSelect(p.id) }
        }
        if (projects.isNotEmpty()) {
            item {
                ProjectChip(
                    label = "未归类",
                    emoji = "🗂",
                    active = selected == LedgerDetailViewModel.PROJECT_UNASSIGNED
                ) { onSelect(LedgerDetailViewModel.PROJECT_UNASSIGNED) }
            }
        }
        item {
            // ＋新建：玻璃入口（v13.9：原来是一块主色 10% 平涂 + 1dp 描边）
            Box(
                Modifier
                    .glassChip(
                        shape = RoundedCornerShape(50),
                        tint = MaterialTheme.colorScheme.primary,
                        // ⚠️ 0.4 是"几乎只有棱"的一档：它和"选中"必须能分开 ——
                        // 选中 = 白膜 + 主色内棱 + 抬影，新建 = 只剩一圈棱。
                        strength = 0.4f
                    )
                    .clickableScale(pressedScale = 0.94f) { onCreate() }
                    .padding(horizontal = 13.dp, vertical = 7.dp)
            ) {
                Text(
                    "＋ 新建项目",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        if (archived.isNotEmpty()) {
            item {
                ArchiveDrawerChip(
                    count = archived.size,
                    expanded = showArchived
                ) { showArchived = !showArchived }
            }
            if (showArchived) {
                items(archived, key = { "archived-${it.id}" }) { p ->
                    ProjectChip(
                        label = p.name,
                        emoji = p.emoji,
                        colorHex = p.colorHex,
                        badge = badges[p.id],
                        active = selected == p.id,
                        // 归档项一律压暗一档：它和「进行中」的项目并排时，
                        // 必须一眼看出哪一组是"还在办事的"。
                        dimmed = true
                    ) { onSelect(p.id) }
                }
            }
        }
    }
}

/**
 * 「已归档 N」抽屉把手（v13.8）。
 *
 * 它不是筛选器，而是一个**会话内的开关**：默认收起，点一下把归档项目
 * 追加到横向列表末尾。收起态只占一枚 chip 的宽度 —— 这是我们能给
 * "不想再看见但也不能删"的项目找到的最小位置。
 *
 * ⚠️ 用 Canvas 画三角而不是 `▾`：10sp 的 ▾ 在 440dpi 上只有约 4px，
 * 实机上会糊成一个脏点（「待提交 ▾」踩过同一个坑）。
 */
@Composable
private fun ArchiveDrawerChip(count: Int, expanded: Boolean, onClick: () -> Unit) {
    val tone = MaterialTheme.colorScheme.onSurfaceVariant
    // v13.9：原来 surface 45% 平涂 + 1dp 描边 —— 和旁边的 ProjectChip 不是一种材质
    Box(
        Modifier
            .glassChip(shape = RoundedCornerShape(50), strength = 0.75f)
            .clickableScale(pressedScale = 0.94f) { onClick() }
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "已归档 $count",
                fontSize = 13.sp,
                color = tone.copy(alpha = 0.85f)
            )
            androidx.compose.foundation.Canvas(
                Modifier
                    .padding(start = 5.dp)
                    .size(width = 7.dp, height = 4.dp)
            ) {
                val tri = androidx.compose.ui.graphics.Path().apply {
                    if (expanded) {
                        // 展开时三角朝上：同样的三条线，只是起点反过来
                        moveTo(0f, size.height)
                        lineTo(size.width, size.height)
                        lineTo(size.width / 2f, 0f)
                    } else {
                        moveTo(0f, 0f)
                        lineTo(size.width, 0f)
                        lineTo(size.width / 2f, size.height)
                    }
                    close()
                }
                drawPath(tri, tone.copy(alpha = 0.75f))
            }
        }
    }
}

@Composable
private fun ProjectChip(
    label: String,
    emoji: String?,
    colorHex: String? = null,
    /** 报销状态徽标（null / 空 = 不显示） */
    badge: ProjectStateBadge? = null,
    active: Boolean,
    dimmed: Boolean = false,
    onClick: () -> Unit
) {
    val primary = MaterialTheme.colorScheme.primary
    // v13.9：选中态原来是「主色实色渐变 + onPrimary 白字」——
    // 在玻璃行之间就是一块贴上去的深蓝标签，边界从"棱"退化成"贴纸边"，
    // 正是用户否过的"实色选中块"。
    //
    // 现在选中 = **同一块白玻璃 + 一圈主色的棱 + 主色字 + 抬 3dp**：
    //  - 膜不做深色（试过 24% 主色，落在 (215,218,218) 的板岩灰，越浓越脏）；
    //  - 认读靠三件小事叠起来：色相（字变主色）、边界（主色 1.2dp 内棱）、
    //    高度（3dp 影）—— 任何一件单独都不够，三件一起是"这颗被选中了"。
    // ⚠️ 主色字必须留在**白膜**上：#4A6B87 在白膜 (244,242,239) 上是 4.9:1，
    //    一旦把膜染到 24% 就掉到 3.9:1，不合格。所以选中态不能既染膜又染字。
    Box(
        Modifier
            .glassChip(
                shape = RoundedCornerShape(50),
                tint = null,
                strength = if (active) 1.0f else 0.55f,
                elevation = if (active) 3.dp else 0.dp
            )
            .then(
                if (active) {
                    Modifier.border(
                        width = 1.2.dp,
                        color = primary.copy(alpha = 0.42f),
                        shape = RoundedCornerShape(50)
                    )
                } else Modifier
            )
            .clickableScale(pressedScale = 0.94f) { onClick() }
            .padding(horizontal = 13.dp, vertical = 7.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (colorHex != null) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(parseHexColor(colorHex).copy(alpha = if (dimmed) 0.45f else 1f))
                )
                Spacer(Modifier.width(5.dp))
            }
            if (emoji != null) {
                Text(emoji, fontSize = 13.sp)
                Spacer(Modifier.width(4.dp))
            }
            Text(
                label,
                fontSize = 13.sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                color = if (active) primary else MaterialTheme.colorScheme.onSurface.copy(
                    alpha = if (dimmed) 0.5f else 1f
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 140.dp)
            )
            // v14.2 / v16.5.1：报销状态 —— 纯文字，不做胶囊。
            // 每段一个颜色（待提交琥珀 / 已提交蓝 / 已到账绿），段间用「·」分隔。
            // ⚠️ 归档项的名字会压暗（dimmed），**状态字不压暗** —— 用户要的正是
            //    "归档的项目也能看到状态"，压暗等于又把它藏起来了。
            val segments = badge?.segments().orEmpty()
            if (segments.isNotEmpty()) {
                val isDarkChip = com.foldledger.ui.theme.LocalDarkTheme.current
                segments.forEachIndexed { index, seg ->
                    Spacer(Modifier.width(if (index == 0) 5.dp else 3.dp))
                    if (index > 0) {
                        Text(
                            "·",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            maxLines = 1
                        )
                        Spacer(Modifier.width(3.dp))
                    }
                    val tone = when (seg.kind) {
                        StateKind.PENDING -> if (isDarkChip) pendingTone() else lerp(pendingTone(), Color.Black, 0.12f)
                        StateKind.SUBMITTED -> submittedTone()
                        StateKind.SETTLED -> settledTone()
                    }
                    Text(
                        seg.text,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Normal,
                        color = tone,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

/**
 * 项目汇总卡：选中某个项目时出现。
 *
 * 一眼看清"这一趟花了多少、报了多少、还差多少没到账"，
 * 加上预算条和编辑入口 —— 差旅结束核对单据时不用再自己算。
 */
@Composable
internal fun ProjectSummaryCard(
    project: ProjectEntity,
    agg: ProjectAgg?,
    states: List<ProjectStateAgg>,
    /** v5.2：按报销状态筛选（点三格切换）；v7.1 起是集合（可含「未回款」组合） */
    selectedStates: Set<Int> = emptySet(),
    onSelectState: (Int) -> Unit = {},
    /** v13.8：这个项目已经在归档抽屉里 —— 卡里补一句"它去哪了"，否则像凭空消失 */
    archived: Boolean = false,
    onEdit: () -> Unit
) {
    val dc = LocalDirectionColors.current
    val accent = parseHexColor(project.colorHex)
    val total = agg?.totalCents ?: 0L
    val count = agg?.count ?: 0
    // v7.1：项目主角换成「尚余垫付 / 已回款」
    val rsum = ReimburseSummary.fromProject(states)

    fun sumOf(state: Int) = states.filter { it.state == state }.sumOf { it.totalCents }

    SurfaceCard(
        shape = RoundedCornerShape(Radius.card),
        elevation = 2.dp,
        softShadow = true,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(Space.xl)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LedgerAvatar(project.emoji, project.colorHex, size = 38.dp, emojiSize = 19)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            project.name,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (rsum.allSettled) {
                            Spacer(Modifier.width(6.dp))
                            PillTag("已结清", ReimburseSettled, filled = true)
                        }
                        if (project.closed) {
                            Spacer(Modifier.width(6.dp))
                            PillTag("已结束", MaterialTheme.colorScheme.onSurfaceVariant, filled = true)
                        }
                    }
                    Text(
                        project.note.ifBlank { "$count 笔支出" },
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    "编辑",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickableScale(pressedScale = 0.94f) { onEdit() }
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                )
            }

            Spacer(Modifier.height(14.dp))
            // v7.1 主角：尚余垫付 / 已回款（和看板同一套语义）
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("尚余垫付", fontSize = 11.sp, color = pendingTone(), fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(2.dp))
                    AnimatedCentsText(
                        cents = rsum.outstandingCents,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 24,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Spacer(Modifier.height(2.dp))
                    Text("${rsum.outstandingCount} 笔待回款", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(Modifier.weight(1f)) {
                    Text("已回款", fontSize = 11.sp, color = ReimburseSettled, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(2.dp))
                    AnimatedCentsText(
                        cents = rsum.recoveredCents,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 24,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Spacer(Modifier.height(2.dp))
                    Text("回款率 ${(rsum.recoveredFraction * 100).toInt()}%", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "累计垫付 ${Money.format(total)} · $count 笔",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (archived) {
                // 归档不是"删除"，也不该像删除。这里把规则说出来：
                // 判据（没有待提交）+ 位置（项目条末尾的抽屉）。
                Spacer(Modifier.height(4.dp))
                Text(
                    "已归档 · 全部条目均已提交，不再占用项目条（可在条末「已归档」里展开）",
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
                )
            }

            Spacer(Modifier.height(12.dp))
            // 三格：待提交 / 已提交 / 已回款 —— 点一下筛出这一档的流水
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProjectStatCell(
                    "待提交", sumOf(ReimburseState.PENDING), pendingTone(),
                    active = selectedStates == setOf(ReimburseState.PENDING),
                    modifier = Modifier.weight(1f)
                ) { onSelectState(ReimburseState.PENDING) }
                ProjectStatCell(
                    "已提交", sumOf(ReimburseState.SUBMITTED), ReimburseSubmitted,
                    active = selectedStates == setOf(ReimburseState.SUBMITTED),
                    modifier = Modifier.weight(1f)
                ) { onSelectState(ReimburseState.SUBMITTED) }
                ProjectStatCell(
                    "已回款", sumOf(ReimburseState.SETTLED), dc.income,
                    active = selectedStates == setOf(ReimburseState.SETTLED),
                    modifier = Modifier.weight(1f)
                ) { onSelectState(ReimburseState.SETTLED) }
            }

            if (project.budgetCents > 0L) {
                val used = total.toFloat() / project.budgetCents.toFloat()
                val remaining = project.budgetCents - total
                val over = remaining < 0L
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BudgetRing(used = used, color = accent, overspend = over, size = 58.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("项目预算", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "${Money.format(total)} / ${Money.format(project.budgetCents)}",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (over) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            if (over) "超支 ${Money.format(-remaining)}" else "剩余 ${Money.format(remaining)}",
                            fontSize = 11.sp,
                            color = if (over) MaterialTheme.colorScheme.error else accent
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProjectStatCell(
    label: String,
    cents: Long,
    color: Color,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    onClick: () -> Unit = {}
) {
    Surface(
        shape = RoundedCornerShape(Radius.md),
        color = color.copy(alpha = if (active) 0.18f else 0.08f),
        border = if (active) {
            androidx.compose.foundation.BorderStroke(1.2.dp, color.copy(alpha = 0.8f))
        } else null,
        modifier = modifier.clickableScale(
            pressedScale = 0.95f,
            clipShape = RoundedCornerShape(Radius.md)
        ) { onClick() }
    ) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
            Text(
                label,
                fontSize = 11.sp,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(2.dp))
            Text(
                Money.format(cents),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * 项目预算环形进度（v14.1）。
 *
 * 用一段圆弧代替原来的细条：弧长 = 已用 / 预算，环心直接标百分比，
 * 旁边补「剩余 / 超支」。超支时弧与字一起转红 —— 比一根进度条更像仪表盘，
 * 也更难忽略"这趟已经花超了"。
 */
@Composable
private fun BudgetRing(
    used: Float,
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
    overspend: Boolean = false
) {
    val animated by animateFloatAsState(
        targetValue = used.coerceIn(0f, 1f),
        animationSpec = spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessLow),
        label = "budgetRing"
    )
    val ring = size * 0.13f
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val arcColor = if (overspend) MaterialTheme.colorScheme.error else color
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = ring.toPx()
            val inset = stroke / 2f
            val arc = Size(size.toPx() - stroke, size.toPx() - stroke)
            val tl = Offset(inset, inset)
            drawArc(
                color = track,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = tl,
                size = arc,
                style = Stroke(width = stroke, cap = StrokeCap.Butt)
            )
            if (animated > 0.001f) {
                drawArc(
                    color = arcColor,
                    startAngle = -90f,
                    sweepAngle = 360f * animated,
                    useCenter = false,
                    topLeft = tl,
                    size = arc,
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
            }
        }
        Text(
            "${(used * 100).toInt()}%",
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (overspend) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
        )
    }
}

/**
 * 新建 / 编辑项目弹窗。
 *
 * 名字是必填（其它都能后补）；编辑态多一个删除按钮 —— 删项目只解除归属，
 * 流水会退回「未归类」，一分钱都不会丢。
 */
@Composable
internal fun ProjectEditorDialog(
    initial: ProjectEntity,
    ledgerId: Long,
    onDismiss: () -> Unit,
    onSave: (name: String, emoji: String, colorHex: String, budgetCents: Long, note: String) -> Unit,
    onDelete: (() -> Unit)? = null
) {
    var name by remember(initial) { mutableStateOf(initial.name) }
    var emoji by remember(initial) { mutableStateOf(initial.emoji) }
    var colorHex by remember(initial) { mutableStateOf(initial.colorHex.ifBlank { "#1D3B57" }) }
    var budget by remember(initial) {
        mutableStateOf(if (initial.budgetCents > 0) Money.centsToPlain(initial.budgetCents) else "")
    }
    var note by remember(initial) { mutableStateOf(initial.note) }
    var confirmDelete by remember { mutableStateOf(false) }
    var iconSearch by remember { mutableStateOf("") }

    val save = {
        if (name.isNotBlank()) {
            onSave(
                name.trim(),
                emoji.ifBlank { "📁" },
                colorHex.ifBlank { "#1D3B57" },
                Money.parseToCents(budget.ifBlank { "0" }) ?: 0L,
                note.trim()
            )
        }
    }

    GlassDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (initial.id == 0L) "新建项目 / 空间" else "编辑项目",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        },
        // v16.1.1：弹窗整体交给 GlassDialog 滚动（scrollable = true），
        // 这里不再自带 heightIn + verticalScroll —— 同向嵌套滚动会让外层滚不动，
        // 小屏（折叠态/横屏）下底部按钮被挤出屏幕就永远滚不到。
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    "把一次差旅、一次招待的费用装进同一个空间，整批看、整批报。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))

                // 名称 + 图标实时预览
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LedgerAvatar(emoji, colorHex, size = 46.dp, emojiSize = 22)
                    Spacer(Modifier.width(10.dp))
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("项目名称", fontSize = 11.sp) },
                        placeholder = { Text("如：6 月北京出差", fontSize = 13.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(12.dp))

                // 图标选择：复用分类图标库（约 300 个预置图标 + 中文关键词搜索）
                Text("图标", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = iconSearch,
                    onValueChange = { iconSearch = it },
                    label = { Text("搜索图标（如 加油 / 机票 / 疫苗）", fontSize = 11.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                // ⚠️ 这里必须用 FlowRow（非懒加载、不自带滚动），不能用 LazyVerticalGrid：
                // 整个弹窗内容挂在 verticalScroll 里，再嵌一个纵向可滚的网格会出现
                // 「同向嵌套滚动」——网格把点击手势吞掉，图标点了没反应（正是"图标无法修改"的根因）。
                // FlowRow 只是排版、不自带滚动，点击可以正常落到每个格子上。
                val icons = remember(iconSearch) {
                    CategoryIconLibrary.search(iconSearch)?.take(120) ?: DEFAULT_PROJECT_ICONS
                }
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    icons.forEach { icon ->
                        val active = emoji == icon.emoji
                        Box(
                            Modifier
                                .size(42.dp)
                                .glassSelectChip(active, shape = CircleShape)
                                .clickableScale(pressedScale = 0.9f, clipShape = CircleShape) { emoji = icon.emoji },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(icon.emoji, fontSize = 20.sp)
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))

                // 颜色：墨白低饱和八色，与账户 / 图表共用一套调色板
                Text("颜色", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    PROJECT_COLOR_PALETTE.forEach { hex ->
                        val active = colorHex.equals(hex, ignoreCase = true)
                        Box(
                            Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(parseHexColor(hex))
                                .then(
                                    if (active) Modifier.border(
                                        2.dp,
                                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                        CircleShape
                                    ) else Modifier
                                )
                                .clickableScale(pressedScale = 0.88f, clipShape = CircleShape) { colorHex = hex }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = colorHex,
                    onValueChange = { colorHex = it },
                    label = { Text("颜色值（也可自己填 HEX，如 #3B82F6）", fontSize = 11.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))

                OutlinedTextField(
                    value = budget,
                    onValueChange = { budget = it },
                    label = { Text("项目预算（可选）", fontSize = 11.sp) },
                    placeholder = { Text("不填就不设上限", fontSize = 13.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("备注（可选）", fontSize = 11.sp) },
                    placeholder = { Text("同行人、事由、报销单号…", fontSize = 13.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (onDelete != null) {
                    Spacer(Modifier.height(14.dp))
                    val danger = MaterialTheme.colorScheme.error
                    Text(
                        if (confirmDelete) "再点一次确认删除（流水会退回未归类）" else "删除这个项目",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = danger,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable {
                                if (confirmDelete) onDelete() else confirmDelete = true
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = save, enabled = name.isNotBlank()) {
                Text(if (initial.id == 0L) "创建" else "保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
        scrollable = true
    )
}

/**
 * 项目专用调色板：与资产账户、场景、图表共用同一套低饱和墨白八色，
 * 保证全站"选色"视觉一致（v11 起统一）。
 */
private val PROJECT_COLOR_PALETTE = listOf(
    "#1D3B57", "#2C6E5A", "#B06B3A", "#8A5A6E",
    "#A9803A", "#5E5A8C", "#3E6C82", "#9C4A3C"
)

/**
 * 项目图标默认一屏（约 40 个）：差旅 / 办公 / 财务 / 通用这些建项目最常用的。
 * 想找别的直接在上面的搜索框打关键词（图标库里约 300 个都带中文关键词）。
 */
private val DEFAULT_PROJECT_ICONS = run {
    val wanted = listOf(
        "📁", "🧳", "✈️", "🚄", "🚗", "🏨", "🎟️", "🗺️", "🎒", "🧾",
        "💼", "🏢", "📊", "📈", "🎯", "🏗️", "🚀", "💡", "🛠️", "🔧",
        "📦", "📌", "🏷️", "⭐", "✅", "⏰", "🔔", "💰", "🏦", "🛡️",
        "🎁", "🎉", "🍽️", "☕", "🏖️", "🏔️", "🏛️", "🎨", "🎓", "🩺"
    )
    val byEmoji = CategoryIconLibrary.all.associateBy { it.emoji }
    wanted.mapNotNull { byEmoji[it] }
}

