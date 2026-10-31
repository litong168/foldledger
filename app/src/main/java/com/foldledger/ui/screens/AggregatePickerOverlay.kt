package com.foldledger.ui.screens

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foldledger.data.ScenePresets
import com.foldledger.data.db.Direction
import com.foldledger.ui.adaptive.FoldEnv
import com.foldledger.ui.anim.Motion
import com.foldledger.ui.anim.clickableScale
import com.foldledger.ui.components.LedgerAvatar
import com.foldledger.ui.components.PillTag
import com.foldledger.ui.components.parseHexColor
import androidx.compose.foundation.border
import com.foldledger.ui.theme.LocalDirectionColors
import com.foldledger.ui.vm.LedgerUi
import com.foldledger.util.Money
import com.foldledger.ui.theme.cardSurface
import com.foldledger.ui.theme.glassChip
import com.foldledger.ui.theme.wellSurface

/**
 * 「合计范围」自定义弹层。
 *
 * 语义：
 *  - 全部勾选 == 「全部账本」模式，以后新建的账本也会自动算进合计
 *  - 只勾一部分 == 自选模式，只统计勾上的
 *  - 一个都不勾 == 合计为 0（保留这种状态，便于临时清空看板）
 */
@Composable
fun AggregatePickerOverlay(
    env: FoldEnv,
    cards: List<LedgerUi>,
    aggregateAll: Boolean,
    selectedIds: Set<Long>,
    onDismiss: () -> Unit,
    onConfirm: (all: Boolean, ids: Set<Long>) -> Unit
) {
    val allIds = remember(cards) { cards.map { it.ledger.id }.toSet() }
    var all by remember { mutableStateOf(aggregateAll) }
    var picked by remember { mutableStateOf(selectedIds) }

    val current = if (all) allIds else picked
    val expense = cards.filter { it.ledger.id in current }.sumOf { it.expenseCents }
    val income = cards.filter { it.ledger.id in current }.sumOf { it.incomeCents }

    val toggle: (Long) -> Unit = { id ->
        val next = if (id in current) current - id else current + id
        if (next == allIds && allIds.isNotEmpty()) {
            all = true
            picked = emptySet()
        } else {
            all = false
            picked = next
        }
    }

    AppOverlay(env = env, onDismiss = onDismiss) {
        Column(Modifier.fillMaxSize()) {
            SheetGrabber()
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "取消",
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickableScale { onDismiss() }
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "合计范围",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier
                        .glassChip(
                            shape = RoundedCornerShape(10.dp),
                            tint = MaterialTheme.colorScheme.primary,
                            strength = 1.9f,
                            elevation = 2.dp
                        )
                        .clickableScale {
                            onConfirm(all, if (all) emptySet() else picked)
                        }
                ) {
                    Text(
                        "完成",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 7.dp)
                    )
                }
            }

            // ---- 实时预览 ----
            Column(Modifier.padding(horizontal = 18.dp)) {
                Text(
                    if (all) "全部 ${cards.size} 个账本参与合计" else "${current.size} / ${cards.size} 个账本参与合计",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    val dc = LocalDirectionColors.current
                    PreviewMetric("支出合计", Money.format(expense), dc.expense)
                    PreviewMetric("收入合计", Money.format(income), dc.income)
                    PreviewMetric(
                        "结余",
                        Money.format(income - expense),
                        MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuickChip("全选（含以后新建）", all) {
                        all = true
                        picked = emptySet()
                    }
                    QuickChip("清空", !all && picked.isEmpty()) {
                        all = false
                        picked = emptySet()
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(cards, key = { it.ledger.id }) { ui ->
                    val checked = ui.ledger.id in current
                    LedgerCheckRow(
                        ui = ui,
                        checked = checked,
                        modifier = Modifier.animateItem(),
                        onToggle = { toggle(ui.ledger.id) }
                    )
                }
                if (cards.isEmpty()) {
                    item {
                        Text(
                            "还没有账本",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 24.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewMetric(label: String, value: String, color: Color) {
    Column {
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = color, maxLines = 1)
    }
}

@Composable
private fun QuickChip(label: String, active: Boolean, onClick: () -> Unit) {
    // v13.9：选中从 primaryContainer 实色换成玻璃（白膜 + 主色内棱）
    Box(
        Modifier
            .glassChip(
                shape = RoundedCornerShape(50),
                strength = if (active) 1.0f else 0.6f
            )
            .then(
                if (active) {
                    Modifier.border(
                        1.2.dp,
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.42f),
                        RoundedCornerShape(50)
                    )
                } else Modifier
            )
            .clickableScale { onClick() }
    ) {
        Text(
            label,
            fontSize = 13.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            color = if (active) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
        )
    }
}

@Composable
private fun LedgerCheckRow(
    ui: LedgerUi,
    checked: Boolean,
    modifier: Modifier = Modifier,
    onToggle: () -> Unit
) {
    val scene = ScenePresets.byKey(ui.ledger.sceneKey)
    val accent = parseHexColor(ui.ledger.colorHex)

    // v13.9：未选从 wellSurface 凹槽、选中从"账本色 10% 平涂 + 描边"
    // 统一到一层玻璃：未选 0.5 档中性膜，选中 0.9 档账本色染膜 + 账本色内棱。
    Box(
        modifier
            .fillMaxWidth()
            .glassChip(
                shape = RoundedCornerShape(14.dp),
                tint = if (checked) accent else null,
                strength = if (checked) 0.9f else 0.5f
            )
            .then(
                if (checked) {
                    Modifier.border(1.4.dp, accent.copy(alpha = 0.65f), RoundedCornerShape(14.dp))
                } else Modifier
            )
            .clickableScale { onToggle() }
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LedgerAvatar(ui.ledger.emoji, ui.ledger.colorHex, size = 36.dp, emojiSize = 16)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    ui.ledger.name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(3.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PillTag("${scene.emoji} ${scene.name}", accent, filled = true)
                    Text(
                        "${Money.format(ui.expenseCents)} 支出",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            // 勾选框：颜色渐变 + 打勾图标弹性缩放，跟着选中态一起动
            Surface(
                shape = RoundedCornerShape(50),
                color = if (checked) accent else Color.Transparent,
                border = if (checked) null else androidx.compose.foundation.BorderStroke(
                    1.5.dp, MaterialTheme.colorScheme.outlineVariant
                ),
                modifier = Modifier.size(24.dp)
            ) {
                val checkScale by animateFloatAsState(
                    targetValue = if (checked) 1f else 0f,
                    animationSpec = spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium),
                    label = "checkScale"
                )
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (checkScale > 0.02f) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            tint = com.foldledger.ui.theme.onColorFor(accent),
                            modifier = Modifier
                                .size(15.dp)
                                .graphicsLayer {
                                    scaleX = checkScale
                                    scaleY = checkScale
                                    alpha = checkScale.coerceIn(0f, 1f)
                                }
                        )
                    }
                }
            }
        }
    }
}
