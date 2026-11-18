package com.foldledger.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foldledger.ServiceLocator
import com.foldledger.data.sync.SyncLogEntry
import com.foldledger.data.sync.SyncLogStore
import com.foldledger.data.sync.TableNames
import com.foldledger.ui.adaptive.AdaptiveScaffold
import com.foldledger.ui.adaptive.FoldEnv
import com.foldledger.ui.anim.clickableScale
import com.foldledger.ui.components.EmptyHint
import com.foldledger.ui.components.PillTag
import com.foldledger.ui.components.SectionCard
import com.foldledger.ui.theme.CoolGreen
import com.foldledger.ui.theme.Layout
import com.foldledger.ui.theme.Radius
import com.foldledger.ui.theme.Space
import com.foldledger.ui.theme.wellSurface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.foldledger.ui.l10n.t

/**
 * 同步记录（v16.2.8）——把"同步了没有、同步了什么、为什么没同步上"摊开给人看。
 *
 * 之前同步卡只有一行字（「上行 3 行 · 下行 0 行」），失败时也只有一句错误。
 * 想弄清"我手机上改的那笔到底过去没有"，只能靠猜。
 * 这页回答三个问题：**什么时候同步的 / 收发了多少 / 哪几行冲突了**。
 *
 * 数据来自 [com.foldledger.data.sync.SyncManager] 的环形缓冲（最多 20 条，存 prefs 的一个 JSON），
 * 不落 DB —— 日志不是业务数据，不值得为它加表和迁移。
 */
@Composable
fun SyncLogScreen(
    env: FoldEnv,
    onBack: () -> Unit
) {
    val state by ServiceLocator.syncManager.state.collectAsStateWithLifecycle()
    val history = state.history
    val side = Layout.sidePadding(env.widthDp)

    AdaptiveScaffold(
        env = env,
        title = t("同步记录"),
        subtitle = if (history.isEmpty()) t("还没有同步过") else "最近 ${history.size} 次 · 点条目看冲突",
        onBack = onBack
    ) { padding ->
        if (history.isEmpty()) {
            Column(
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .padding(horizontal = side),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                EmptyHint(
                    emoji = "📡",
                    title = t("还没有同步记录"),
                    subtitle = t("在「我的 → 手机 ↔ 电脑」里点一次「立即同步」，这里就会留下这一次的收发明细。")
                )
            }
            return@AdaptiveScaffold
        }

        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(start = side, end = side, top = 4.dp, bottom = Space.huge),
            verticalArrangement = Arrangement.spacedBy(Space.gapSection)
        ) {
            item {
                SectionCard(
                    overline = t("同步"),
                    title = "最近 ${history.size} 次",
                    subtitle = "成功/失败、收发行数、耗时与冲突都在这里；只保留最近 ${SyncLogStore.LIMIT} 条"
                ) {
                    val okCount = history.count { it.ok }
                    Text(
                        "成功 $okCount 次 · 失败 ${history.size - okCount} 次",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            items(history, key = { it.at.toString() + it.trigger }) { entry ->
                SyncLogRow(entry)
            }
        }
    }
}

@Composable
private fun SyncLogRow(entry: SyncLogEntry) {
    var expanded by remember(entry.at) { mutableStateOf(false) }
    val hasDetail = entry.conflicts.isNotEmpty()

    Surface(
        shape = RoundedCornerShape(Radius.md),
        color = wellSurface(),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            Modifier
                .then(
                    if (hasDetail) {
                        Modifier.clickableScale(pressedScale = 0.995f) { expanded = !expanded }
                    } else Modifier
                )
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    formatClock(entry.at),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.width(8.dp))
                PillTag(
                    if (entry.ok) t("成功") else t("失败"),
                    if (entry.ok) CoolGreen else MaterialTheme.colorScheme.error,
                    filled = true
                )
                Spacer(Modifier.width(6.dp))
                PillTag(entry.trigger.ifBlank { t("手动") }, MaterialTheme.colorScheme.primary, filled = false)
                Spacer(Modifier.weight(1f))
                Text(
                    formatDuration(entry.durationMs),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(6.dp))
            Text(
                buildString {
                    append("上行 ${entry.upRows} 行 · 下行 ${entry.downRows} 行")
                    if (entry.ok) {
                        append(" · 新增 ${entry.inserted} / 更新 ${entry.updated}")
                        if (entry.localWins > 0) append(" · 本地胜出 ${entry.localWins}")
                        if (entry.skipped > 0) append(" · 跳过 ${entry.skipped}")
                    }
                },
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface
            )

            if (!entry.ok && entry.error != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    entry.error,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.error
                )
                SyncLogStore.hintFor(entry.error)?.let { hint ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "下一步：$hint",
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 冲突明细：默认收起 —— 平时没人想看，出事时才是关键证据
            if (hasDetail) {
                Spacer(Modifier.height(8.dp))
                ConflictToggle(
                    label = if (expanded) t("收起冲突明细") else "查看 ${entry.conflicts.size} 条冲突明细"
                )
                if (expanded) {
                    Spacer(Modifier.height(6.dp))
                    entry.conflicts.forEach { c ->
                        Text(
                            "· ${tableLabel(c.table)} #${c.id}：本地 ${formatStamp(c.localUpdatedAt)} 胜 "
                                + "远端 ${formatStamp(c.remoteUpdatedAt)}（设备 ${c.remoteByDevice}）",
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConflictToggle(label: String) {
    Text(
        label,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.primary
    )
}

/** 表名 → 中文，日志里说 `transactions` 用户看不懂 */
private fun tableLabel(table: String): String = when (table) {
    TableNames.LEDGERS -> t("账本")
    TableNames.CATEGORIES -> t("分类")
    TableNames.TRANSACTIONS -> t("流水")
    TableNames.PROJECTS -> t("项目")
    TableNames.ACCOUNTS -> t("账户")
    TableNames.RECURRING -> t("周期记账")
    TableNames.GOALS -> t("存钱目标")
    else -> table
}

private fun formatClock(ts: Long): String =
    SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date(ts))

private fun formatStamp(ts: Long): String = if (ts <= 0L) "—" else formatClock(ts)

private fun formatDuration(ms: Long): String = when {
    ms < 1000 -> "${ms}ms"
    else -> String.format(Locale.CHINA, "%.1fs", ms / 1000.0)
}
