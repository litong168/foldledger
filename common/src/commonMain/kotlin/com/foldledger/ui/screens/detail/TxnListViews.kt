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
import androidx.compose.runtime.key
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
import com.foldledger.ui.components.GlassMenu
import com.foldledger.ui.components.LedgerAvatar
import com.foldledger.ui.components.PillTag
import com.foldledger.ui.components.SectionCard
import com.foldledger.ui.components.ThinProgress
import com.foldledger.ui.components.parseHexColor
import com.foldledger.ui.theme.Layout
import com.foldledger.ui.theme.glassChip
import com.foldledger.ui.theme.glassRowColor
import com.foldledger.ui.theme.glassSurface
import com.foldledger.ui.theme.LocalDirectionColors
import com.foldledger.ui.theme.Radius
import com.foldledger.ui.theme.pendingTone
import com.foldledger.ui.theme.ReimburseSettled
import com.foldledger.ui.theme.ReimburseSubmitted
import com.foldledger.ui.theme.Space
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
import com.foldledger.ui.l10n.t

/**
 * 拆自 LedgerDetailScreen.kt（v10.2.0）—— 把 3100 行按职责拆成 detail/ 子包。
 * 本文件保持原注释与代码不变，只调整可见性（private → internal）供同包兄弟文件调用。
 */
// ============================================================
//  按日分组的流水
// ============================================================

/**
 * 一天的流水（v11 重排）。
 *
 * 构型演进：
 *  v11 之前 —— 日期头 + 一张白卡包住当天所有行 + 行间浅线。十天就是十张卡、
 *              十条阴影、十个圆角，整个列表变成一叠漂浮的卡片。
 *  v11      —— 改成"对账单"：行直接印在纸上，只有一条细线分句。
 *  v13.2    —— 按参考图 04-list-rows 改成**一行一片轻薄玻璃**：
 *              行与行之间留 5dp 空气，底色从缝里透上来。
 *              ⚠️ 不要再退回"一天一张卡"：卡一包住整天，行就没有单独的边界，
 *              长列表会读成"几块大板子"，而不是"一串玻璃片"。
 *
 * 卡片只留给真正独立的模块（看板、项目卡）。
 */
@Composable
internal fun DayGroupCard(
    group: DayGroup,
    /** 账本场景，用来把流水里存的字段值翻成中文 */
    sceneKey: String,
    /** v5.1：项目 id → 名称，流水行副标题显示归属项目 */
    projectNames: Map<Long, String> = emptyMap(),
    /** v6.1：账户 id → 名称，流水行副标题显示账户 */
    accountNames: Map<Long, String> = emptyMap(),
    /** v5.6 批量改账 */
    multiSelect: Boolean = false,
    selectedIds: Set<Long> = emptySet(),
    onToggle: (Long) -> Unit = {},
    onEdit: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onReimburse: (Long, Int) -> Unit,
    /** v15 自动记账：行级「✓ 入账」回调（非 null 时该行显示快速入账按钮） */
    quickConfirm: ((Long) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxWidth()) {
        // 日期头在卡片**外面**：它属于"章节标题"，不属于"这一天的数据"。
        // 放在卡片里会让卡片顶部长出一块空白，视觉上多出一层无意义的留白。
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, top = 2.dp, end = 52.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                group.header,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.2.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.width(6.dp))
            Text(
                group.dayKey,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
            )
            Spacer(Modifier.weight(1f))
            if (group.expenseCents > 0) {
                Text(
                    "支 ${Money.format(group.expenseCents)}",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = LocalDirectionColors.current.expense
                )
            }
            if (group.incomeCents > 0) {
                Spacer(Modifier.width(8.dp))
                Text(
                    "收 ${Money.format(group.incomeCents)}",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = LocalDirectionColors.current.income
                )
            }
        }
        // v13.2：一天不再是一整块玻璃，而是**一行一块轻薄玻璃**。
        // 参考图 04-list-rows 给的就是这个构型：行与行之间留一道空气，底色从缝里透上来，
        // 于是每一行都读成"一片独立的玻璃"，而不是"一块卡片上印了几行字"。
        //
        // ⚠️ v13.4 实测纠正：原来 elevation = 2dp 时，行内取样 #E3E0D9 比行间缝隙
        //    #E7E4DC **更暗** —— 系统阴影的 ambient/spot alpha 是常量（3.2%/4.8%），
        //    与 elevation 无关，2dp 的扩散半径足以把它透到半透明的行面上，
        //    把 15.5% 白膜提亮的那 4 个百分点又压了回去。行不但没"浮起来"，
        //    反而塌成一片比背景更暗的脏块。
        //    改成 1dp：阴影半径缩到贴着轮廓，只在边上形成一道 1dp 暗环（正好当轮廓用），
        //    不再压暗行面。膜同时提到 18% 补回浮起感。
        // ⚠️ 缝 5dp → 3dp：一屏十几行时 5dp 的亮缝会长成斑马纹，碎得不像玻璃。
        Column(Modifier.fillMaxWidth()) {
            group.items.forEachIndexed { index, txn ->
                // v16.3：给行一个稳定身份。原来一天内所有流水是普通 forEachIndexed，
                // 增删/改账时整天的行都得跟着重组；现在未变的行可被 Compose 复用。
                key(txn.id) {
                    val rowShape = RoundedCornerShape(Radius.md)
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .glassSurface(
                                shape = rowShape,
                                elevation = 1.dp,
                                fill = glassRowColor(),
                                specular = 0.55f
                            )
                            .padding(horizontal = 14.dp)
                    ) {
                        TxnRow(
                            txn = txn,
                            sceneKey = sceneKey,
                            projectName = projectNames[txn.projectId],
                            accountName = accountNames[txn.accountId],
                            multiSelect = multiSelect,
                            selected = txn.id in selectedIds,
                            onToggle = { onToggle(txn.id) },
                            onEdit = { onEdit(txn.id) },
                            onDelete = { onDelete(txn.id) },
                            onReimburse = { onReimburse(txn.id, it) },
                            quickConfirm = quickConfirm?.let { qc -> { qc(txn.id) } }
                        )
                    }
                    if (index != group.items.lastIndex) Spacer(Modifier.height(3.dp))
                }
            }
        }
    }
}

@Composable
private fun TxnRow(
    txn: TransactionEntity,
    sceneKey: String,
    projectName: String? = null,
    /** v6.1：这笔钱走的账户名 */
    accountName: String? = null,
    /** v5.6 批量改账：多选模式下点击 = 勾选，而不是进编辑 */
    multiSelect: Boolean = false,
    selected: Boolean = false,
    onToggle: () -> Unit = {},
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onReimburse: (Int) -> Unit,
    /** v15 自动记账：行级「✓ 入账」（null = 不显示） */
    quickConfirm: (() -> Unit)? = null
) {
    var menu by remember { mutableStateOf(false) }
    var reimburseMenu by remember { mutableStateOf(false) }
    val extra = remember(txn.extraJson) { LedgerRepository.decodeExtra(txn.extraJson) }

    Row(
        Modifier
            .fillMaxWidth()
            .then(
                if (selected) {
                    Modifier.background(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.07f),
                        RoundedCornerShape(Radius.sm)
                    )
                } else Modifier
            )
            .clickable {
                // 多选模式下整行就是个"勾选器"，别再把用户拽进编辑页
                if (multiSelect) onToggle() else onEdit()
            }
            .padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (multiSelect) {
            SelectCircle(selected)
            Spacer(Modifier.width(10.dp))
        }
        // 淡彩纸标（v11：无高光无内影，见 CategoryIconBadge）
        CategoryIconBadge(
            ref = txn.categoryEmoji,
            size = 36.dp,
            fontSize = 17,
            container = MaterialTheme.colorScheme.surfaceVariant
        )
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(
                txn.categoryName,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // 场景字段存的是英文 value，这里统一翻成中文 label（历史数据同样生效）
            // v9.9.9：整段副标题只在依赖变化时重算 —— 原来每行每次重组都跑一遍
            // buildString + extraSummary，滚动长列表时是纯浪费
            val sub = remember(
                txn.id, txn.categoryName, txn.note, txn.occurredAt, txn.extraJson,
                projectName, accountName, sceneKey
            ) {
                buildString {
                // v5.1：归属项目放在最前面，扫一眼就知道这笔属于哪一趟差旅
                if (!projectName.isNullOrBlank()) append("📁 $projectName")
                // v6.1：这笔钱走的账户
                if (!accountName.isNullOrBlank()) {
                    if (isNotEmpty()) append(" · ")
                    append("💳 $accountName")
                }
                if (txn.note.isNotBlank()) {
                    if (isNotEmpty()) append(" · ")
                    append(txn.note)
                }
                ScenePresets.extraSummary(sceneKey, extra).forEach { v ->
                    if (isNotEmpty()) append(" · ")
                    append(v)
                }
                if (isEmpty()) append(DateExt.fullDateTime(txn.occurredAt))
                }
            }
            Text(
                sub,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            AmountText(
                txn.amountCents,
                txn.direction,
                fontSize = 17,
                fontWeight = FontWeight.Bold
            )
            if (txn.reimburseState >= 0) {
                Spacer(Modifier.height(3.dp))
                ReimburseStateChip(
                    current = txn.reimburseState,
                    expanded = reimburseMenu,
                    onExpandChange = { reimburseMenu = it },
                    onPick = { onReimburse(it) }
                )
            }
        }
        if (quickConfirm != null) {
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier
                    .size(32.dp)
                    .glassChip(
                        shape = CircleShape,
                        tint = MaterialTheme.colorScheme.primary,
                        strength = 0.9f
                    )
                    .clickableScale(pressedScale = 0.88f, clipShape = CircleShape) { quickConfirm.invoke() },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "✓",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = t("更多"),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
            GlassMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(t("编辑这一笔")) },
                    onClick = { menu = false; onEdit() }
                )
                DropdownMenuItem(
                    text = { Text(t("删除"), color = MaterialTheme.colorScheme.error) },
                    onClick = { menu = false; onDelete() }
                )
            }
        }
    }
}

/** 报销状态 → (文案, 配色)，列表徽标与选择菜单共用一份，避免两处写差 */
@Composable
private fun reimburseLook(state: Int): Pair<String, Color> = when (state) {
    ReimburseState.PENDING -> t("待提交") to pendingTone()
    ReimburseState.SUBMITTED -> t("已提交") to ReimburseSubmitted
    else -> t("已回款") to ReimburseSettled
}

/**
 * 流水上的报销状态徽标，**点一下就能直接改状态**（待提交 / 已提交 / 已回款）。
 * 不用再翻进编辑页改那个场景字段，垫了钱、报了销，一眼一手搞定。
 */
@Composable
private fun ReimburseStateChip(
    current: Int,
    expanded: Boolean,
    onExpandChange: (Boolean) -> Unit,
    onPick: (Int) -> Unit
) {
    val (label, color) = reimburseLook(current)
    Box {
        Row(
            Modifier
                .clip(RoundedCornerShape(50))
                .clickableScale(pressedScale = 0.9f) { onExpandChange(true) }
                .padding(vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PillTag(label, color, filled = true)
            // ⚠️ v13.3：9sp 的 ▾ 在实机上糊成一个点（截图里"待提交 ·"的那颗点就是它），
            // 用户会以为是个没删干净的残留符号。抬到 10sp 并给 0.85 透明度的同色。
            // ⚠️ v13.4：10sp 仍然糊 —— ▾(U+25BE) 在 10sp 下的三角只有约 4px，
            // 440dpi 上等于看不见，反而更像"脏点"。字符大小和字形留白不可控，
            // 所以不再用字符：直接画一个 7×4dp 的实心三角，尺寸与颜色都精确可控。
            androidx.compose.foundation.Canvas(
                Modifier
                    .padding(start = 4.dp, end = 1.dp)
                    .size(width = 7.dp, height = 4.dp)
            ) {
                val tri = androidx.compose.ui.graphics.Path().apply {
                    moveTo(0f, 0f)
                    lineTo(size.width, 0f)
                    lineTo(size.width / 2f, size.height)
                    close()
                }
                drawPath(tri, color)
            }
        }

        GlassMenu(expanded = expanded, onDismissRequest = { onExpandChange(false) }) {
            Text(
                t("报销状态"),
                fontSize = 11.sp,
                letterSpacing = 0.3.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 14.dp, top = 8.dp, bottom = 2.dp)
            )
            listOf(
                ReimburseState.PENDING,
                ReimburseState.SUBMITTED,
                ReimburseState.SETTLED
            ).forEach { s ->
                val (l, c) = reimburseLook(s)
                val active = s == current
                val emoji = when (s) {
                    ReimburseState.PENDING -> "⏳"
                    ReimburseState.SUBMITTED -> "📤"
                    else -> "✅"
                }
                // v13.9：菜单里不再是一颗 8dp 圆点 + 文字（"字体加色块"）。
                // 改成「emoji + 文案 + 勾选」，选中态 = 语义色进膜的玻璃芯片（同一块玻璃的
                // 更高浓度，不是贴一张色片），文字回 onSurface，勾选承载语义色。
                Row(
                    modifier = Modifier
                        .width(188.dp)
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                        .then(
                            if (active) {
                                Modifier.glassChip(
                                    shape = RoundedCornerShape(12.dp),
                                    tint = c,
                                    strength = 1.0f,
                                    elevation = 2.dp
                                )
                            } else Modifier
                        )
                        .clickableScale(pressedScale = 0.96f) {
                            onExpandChange(false)
                            if (!active) onPick(s)
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(emoji, fontSize = 15.sp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        l,
                        fontSize = 13.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (active) 1f else 0.82f),
                        modifier = Modifier.weight(1f)
                    )
                    if (active) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            tint = c,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(2.dp))
        }
    }
}
