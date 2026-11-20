package com.foldledger.ui.screens.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foldledger.ui.anim.clickableScale
import com.foldledger.ui.l10n.t
import com.foldledger.ui.theme.LocalDirectionColors
import com.foldledger.ui.theme.Radius
import com.foldledger.ui.theme.Space
import com.foldledger.ui.theme.SurfaceCard
import com.foldledger.ui.theme.glassChip
import com.foldledger.ui.theme.glassSelectChip
import com.foldledger.ui.vm.SceneFieldSelection
import com.foldledger.ui.vm.SceneFieldStat
import com.foldledger.util.Money

/**
 * 场景字段看板（v17）—— 每个 CHOICE 字段一张卡，列出各取值的「笔数 / 金额」，
 * 点一下 chip 即筛出对应流水，再点一次取消。
 *
 * 这是报销看板的泛化：报销把 state 做成闭环，这里把家装空间 / 房屋周期 /
 * 母婴宝宝 / 日常支付方式等同样的字段用起来。视觉全程复用玻璃规范 ——
 * 选中态 `glassSelectChip`、金额走支出语义色、字号只用 13/11 两档，无新色块。
 */
@Composable
internal fun SceneFieldBoard(
    stats: List<SceneFieldStat>,
    selected: SceneFieldSelection?,
    onToggle: (fieldKey: String, value: String, label: String) -> Unit
) {
    if (stats.isEmpty()) return
    val expenseTone = LocalDirectionColors.current.expense

    Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
        stats.forEach { stat ->
            SurfaceCard(
                shape = RoundedCornerShape(Radius.card),
                elevation = 2.dp,
                softShadow = true,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(Space.xl)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            t(stat.field.label),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "${stat.totalCount} 笔 · ${Money.format(stat.totalCents)}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        stat.values.forEach { v ->
                            val active = selected?.key == stat.field.key && selected.value == v.value
                            Box(
                                Modifier
                                    .glassSelectChip(active, shape = RoundedCornerShape(Radius.pill))
                                    .clip(RoundedCornerShape(Radius.pill))
                                    .clickableScale(pressedScale = 0.92f) {
                                        onToggle(stat.field.key, v.value, v.label)
                                    }
                                    .padding(horizontal = 12.dp, vertical = 7.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        t(v.label),
                                        fontSize = 13.sp,
                                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                        color = if (active) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "${v.count} · ${Money.format(v.cents)}",
                                        fontSize = 11.sp,
                                        color = expenseTone
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 场景字段筛选提示条：与报销筛选条同一套玻璃观感。
 * 明确告诉用户「现在只看哪个字段的哪一档」，并留一键清除。
 */
@Composable
internal fun SceneFieldFilterBar(
    selection: SceneFieldSelection,
    count: Int,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tint = MaterialTheme.colorScheme.primary
    Box(
        modifier
            .fillMaxWidth()
            .glassChip(shape = RoundedCornerShape(50), tint = tint, strength = 1.0f)
    ) {
        Row(
            Modifier.padding(start = 14.dp, end = 12.dp, top = 9.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(7.dp)
                    .clip(RoundedCornerShape(50))
                    .background(tint)
            )
            Spacer(Modifier.width(7.dp))
            Text(
                "只看「${t(selection.label)}」",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = tint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "$count 笔",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            Spacer(Modifier.width(6.dp))
            Text(
                t("清除 ✕"),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = tint,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickableScale(pressedScale = 0.9f) { onClear() }
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            )
        }
    }
}
