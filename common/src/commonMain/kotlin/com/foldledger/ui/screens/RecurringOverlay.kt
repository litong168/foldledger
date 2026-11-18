package com.foldledger.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foldledger.data.RecurringSchedule
import com.foldledger.data.db.CategoryEntity
import com.foldledger.data.db.Direction
import com.foldledger.data.db.RecurringEntity
import com.foldledger.data.db.RecurringInterval
import com.foldledger.ui.adaptive.FoldEnv
import com.foldledger.ui.anim.Haptic
import com.foldledger.ui.anim.clickableScale
import com.foldledger.ui.components.CategoryIcon
import com.foldledger.ui.theme.LiquidButton
import com.foldledger.ui.theme.LocalDirectionColors
import com.foldledger.ui.theme.Radius
import com.foldledger.ui.theme.Space
import com.foldledger.ui.theme.SurfaceCard
import com.foldledger.ui.theme.cardSurface
import com.foldledger.util.DateExt
import com.foldledger.util.Money
import com.foldledger.ui.l10n.t

/**
 * 明细页顶部的「固定支出待入账」提示条（v5.7）。
 *
 * 为什么不做成自动入账：固定支出的金额经常变（水电、房租涨价），静默写进流水反而要人
 * 回头去翻改。这里只提醒，由用户确认一次 —— 成本很低，账目可信度高得多。
 */
@Composable
fun RecurringDueBar(
    due: List<RecurringEntity>,
    onPostAll: () -> Unit,
    onManage: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (due.isEmpty()) return
    val total = due.sumOf { it.amountCents }
    val dc = LocalDirectionColors.current
    SurfaceCard(
        shape = RoundedCornerShape(Radius.card),
        color = cardSurface(),
        elevation = 4.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(34.dp)
                        .background(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                            RoundedCornerShape(10.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) { Text("🔁", fontSize = 17.sp) }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "${due.size} 笔固定支出待入账",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        due.take(3).joinToString(" · ") { it.name } +
                            if (due.size > 3) " 等" else "",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    Money.format(total),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = dc.expense
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) {
                    LiquidButton(
                        text = t("全部入账"),
                        onClick = onPostAll,
                        height = 40.dp,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Box(
                    Modifier
                        .height(40.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                            RoundedCornerShape(50)
                        )
                        .clickableScale(pressedScale = 0.94f, haptic = Haptic.LIGHT) { onManage() }
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        t("管理"),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

/** 表单草稿（id = 0 表示新增） */
private data class RecurringDraft(
    val id: Long = 0L,
    val name: String = "",
    val amountText: String = "",
    val categoryId: Long? = null,
    val categoryName: String = "",
    val categoryEmoji: String = "📦",
    val direction: Int = Direction.EXPENSE,
    val intervalKind: Int = RecurringInterval.MONTHLY,
    val nextDueAt: Long = RecurringSchedule.advance(System.currentTimeMillis(), RecurringInterval.MONTHLY),
    val note: String = ""
) {
    val cents: Long? get() = Money.parseToCents(amountText)
    val canSave: Boolean get() = name.isNotBlank() && (cents ?: 0L) > 0L && categoryName.isNotBlank()

    fun toEntity(ledgerId: Long) = RecurringEntity(
        id = id,
        ledgerId = ledgerId,
        name = name.trim(),
        categoryId = categoryId,
        categoryName = categoryName,
        categoryEmoji = categoryEmoji,
        direction = direction,
        amountCents = cents ?: 0L,
        note = note.trim(),
        intervalKind = intervalKind,
        nextDueAt = nextDueAt,
        enabled = true
    )
}

/**
 * 固定支出管理弹层（v5.7）。
 *
 * 两种视图：列表（默认）/ 新建或编辑表单。表单不弹新窗口，而是在同一层里切换 ——
 * 手机上连续叠两层弹层很容易点错返回。
 */
@Composable
fun RecurringOverlay(
    env: FoldEnv,
    ledgerId: Long,
    items: List<RecurringEntity>,
    categories: List<CategoryEntity>,
    onCreate: (RecurringEntity) -> Unit,
    onUpdate: (RecurringEntity) -> Unit,
    onDelete: (Long) -> Unit,
    onToggle: (Long, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var draft by remember { mutableStateOf<RecurringDraft?>(null) }
    var datePickerOpen by remember { mutableStateOf(false) }
    val now = remember { System.currentTimeMillis() }

    AppOverlay(env = env, onDismiss = onDismiss) {
        Column(Modifier.fillMaxSize()) {
            if (!env.isTabletop && !env.isBook) SheetGrabber()
            val editing = draft
            if (editing == null) {
                RecurringList(
                    items = items,
                    now = now,
                    onNew = { draft = RecurringDraft() },
                    onEdit = { e ->
                        draft = RecurringDraft(
                            id = e.id,
                            name = e.name,
                            amountText = Money.centsToPlain(e.amountCents),
                            categoryId = e.categoryId,
                            categoryName = e.categoryName,
                            categoryEmoji = e.categoryEmoji,
                            direction = e.direction,
                            intervalKind = e.intervalKind,
                            nextDueAt = e.nextDueAt,
                            note = e.note
                        )
                    },
                    onToggle = onToggle,
                    onDelete = onDelete,
                    onClose = onDismiss
                )
            } else {
                RecurringForm(
                    draft = editing,
                    categories = categories.filter { it.direction == editing.direction },
                    onChange = { draft = it },
                    onPickDate = { datePickerOpen = true },
                    onCancel = { draft = null },
                    onDelete = if (editing.id != 0L) {
                        {
                            onDelete(editing.id)
                            draft = null
                        }
                    } else null,
                    onSave = {
                        if (editing.id == 0L) onCreate(editing.toEntity(ledgerId))
                        else onUpdate(editing.toEntity(ledgerId))
                        draft = null
                    }
                )
            }
        }
    }

    if (datePickerOpen) {
        val cur = draft ?: return
        DateTimePickerOverlay(
            env = env,
            initial = cur.nextDueAt,
            onConfirm = { ts ->
                draft = cur.copy(nextDueAt = ts)
                datePickerOpen = false
            },
            onDismiss = { datePickerOpen = false }
        )
    }
}

@Composable
private fun RecurringList(
    items: List<RecurringEntity>,
    now: Long,
    onNew: () -> Unit,
    onEdit: (RecurringEntity) -> Unit,
    onToggle: (Long, Boolean) -> Unit,
    onDelete: (Long) -> Unit,
    onClose: () -> Unit
) {
    val dc = LocalDirectionColors.current
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    t("固定支出"),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    t("房租、会员、房贷这类周期性支出，到点提醒你入账"),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                t("关闭"),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickableScale(pressedScale = 0.94f, haptic = Haptic.LIGHT) { onClose() }
                    .padding(8.dp)
            )
        }

        if (items.isEmpty()) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("🔁", fontSize = 44.sp)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        t("还没有固定支出"),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        t("把每月都要付的那几笔加进来，以后一键入账"),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(items, key = { it.id }) { item ->
                    val overdue = item.enabled && item.nextDueAt <= now
                    SurfaceCard(
                        shape = RoundedCornerShape(Radius.md),
                        color = cardSurface(),
                        elevation = 3.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickableScale(pressedScale = 0.985f) { onEdit(item) }
                    ) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CategoryIcon(ref = item.categoryEmoji, size = 34.dp, fontSize = 17)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        item.name,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    if (overdue) {
                                        Spacer(Modifier.width(6.dp))
                                        Box(
                                            Modifier.background(
                                                dc.expense.copy(alpha = 0.14f),
                                                RoundedCornerShape(50)
                                            )
                                        ) {
                                            Text(
                                                t("待入账"),
                                                fontSize = 11.sp,
                                                color = dc.expense,
                                                modifier = Modifier.padding(
                                                    horizontal = 6.dp,
                                                    vertical = 2.dp
                                                )
                                            )
                                        }
                                    }
                                }
                                Text(
                                    "${RecurringInterval.label(item.intervalKind)} · 下次 ${
                                        DateExt.shortDay(item.nextDueAt)
                                    }",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(
                                (if (item.direction == Direction.EXPENSE) "-" else "+") +
                                    Money.format(item.amountCents),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (item.direction == Direction.EXPENSE) dc.expense
                                else dc.income
                            )
                            Switch(
                                checked = item.enabled,
                                onCheckedChange = { onToggle(item.id, it) },
                                modifier = Modifier.padding(start = 4.dp)
                            )
                        }
                    }
                }
            }
        }

        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 12.dp)
        ) {
            LiquidButton(
                text = t("＋ 新增固定支出"),
                onClick = onNew,
                height = 50.dp,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun RecurringForm(
    draft: RecurringDraft,
    categories: List<CategoryEntity>,
    onChange: (RecurringDraft) -> Unit,
    onPickDate: () -> Unit,
    onCancel: () -> Unit,
    onDelete: (() -> Unit)?,
    onSave: () -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (draft.id == 0L) t("新增固定支出") else t("编辑固定支出"),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            Text(
                t("取消"),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickableScale(pressedScale = 0.94f) { onCancel() }
                    .padding(8.dp)
            )
        }

        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = { onChange(draft.copy(name = it)) },
                    label = { Text(t("名称，如「房租」")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                OutlinedTextField(
                    value = draft.amountText,
                    onValueChange = { onChange(draft.copy(amountText = it)) },
                    label = { Text(t("金额")) },
                    prefix = { Text("¥") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                LabeledChips(t("收支")) {
                    listOf(Direction.EXPENSE to t("支出"), Direction.INCOME to t("收入")).forEach { (d, label) ->
                        SelectChip(
                            label = label,
                            selected = draft.direction == d,
                            onClick = {
                                onChange(
                                    draft.copy(
                                        direction = d,
                                        categoryId = null,
                                        categoryName = "",
                                        categoryEmoji = "📦"
                                    )
                                )
                            }
                        )
                    }
                }
            }
            item {
                LabeledChips(t("分类")) {
                    if (categories.isEmpty()) {
                        Text(
                            t("这个方向还没有分类"),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    categories.forEach { c ->
                        SelectChip(
                            label = "${c.emoji} ${c.name}",
                            selected = draft.categoryId == c.id,
                            onClick = {
                                onChange(
                                    draft.copy(
                                        categoryId = c.id,
                                        categoryName = c.name,
                                        categoryEmoji = c.emoji
                                    )
                                )
                            }
                        )
                    }
                }
            }
            item {
                LabeledChips(t("周期")) {
                    listOf(
                        RecurringInterval.MONTHLY,
                        RecurringInterval.WEEKLY,
                        RecurringInterval.YEARLY
                    ).forEach { k ->
                        SelectChip(
                            label = RecurringInterval.label(k),
                            selected = draft.intervalKind == k,
                            onClick = { onChange(draft.copy(intervalKind = k)) }
                        )
                    }
                }
            }
            item {
                LabeledChips(t("下次入账日")) {
                    Box(
                        Modifier
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                                RoundedCornerShape(50)
                            )
                            .clickableScale(pressedScale = 0.94f) { onPickDate() }
                            .padding(horizontal = 14.dp, vertical = 7.dp)
                    ) {
                        Text(
                            "🗓 " + DateExt.shortDay(draft.nextDueAt),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Text(
                        t("到这一天，它就会出现在「待入账」里"),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            item {
                OutlinedTextField(
                    value = draft.note,
                    onValueChange = { onChange(draft.copy(note = it)) },
                    label = { Text(t("备注（留空则用名称）")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                LiquidButton(
                    text = if (draft.id == 0L) t("添加") else t("保存修改"),
                    onClick = onSave,
                    enabled = draft.canSave,
                    height = 50.dp,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (onDelete != null) {
                item {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clickableScale(pressedScale = 0.97f, haptic = Haptic.MEDIUM) { onDelete() }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            t("删除这一项"),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(Space.lg)) }
        }
    }
}

@Composable
private fun LabeledChips(label: String, content: @Composable () -> Unit) {
    Column {
        Text(
            label,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) { content() }
    }
}

@Composable
private fun SelectChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .background(
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                RoundedCornerShape(50)
            )
            .clickableScale(pressedScale = 0.93f, haptic = Haptic.LIGHT) { onClick() }
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Text(
            label,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
        )
    }
}
