package com.foldledger.ui.screens

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.foldledger.ServiceLocator
import com.foldledger.data.backup.RecycledBackup
import com.foldledger.data.db.GoalEntity
import com.foldledger.data.db.LedgerEntity
import com.foldledger.ui.adaptive.AdaptiveScaffold
import com.foldledger.ui.adaptive.FoldEnv
import com.foldledger.ui.theme.Layout
import com.foldledger.ui.theme.glassChip
import com.foldledger.ui.anim.clickableScale
import com.foldledger.ui.components.EmptyHint
import com.foldledger.ui.components.GlassDialog
import com.foldledger.ui.components.SectionCard
import com.foldledger.ui.vm.RecycleBinViewModel
import com.foldledger.ui.vm.rememberBackupRecycler
import com.foldledger.util.DateExt
import com.foldledger.util.Money

/**
 * 全局回收站（v9.4）——**最终后悔药**。
 *
 * 账本、存钱目标、备份文件三类都在这儿，每类都能还原或彻底铲除。
 * "彻底铲除"要二次确认 —— 这是不可逆的一步，值得多点一下。
 */
@Composable
fun RecycleBinScreen(
    env: FoldEnv,
    onBack: () -> Unit
) {
    val recycler = rememberBackupRecycler()
    val vm: RecycleBinViewModel = viewModel(key = "recycle-bin") {
        RecycleBinViewModel(ServiceLocator.repository, recycler)
    }
    val ledgers by vm.ledgers.collectAsStateWithLifecycle()
    val goals by vm.goals.collectAsStateWithLifecycle()
    val backups by vm.backups.collectAsStateWithLifecycle()
    val treeUri by vm.backupTreeUri.collectAsStateWithLifecycle()

    // 二次确认：待确认的对象
    var confirmPurge by remember { mutableStateOf<PurgeTarget?>(null) }

    AdaptiveScaffold(
        env = env,
        title = "回收站",
        // v9.9.0：原来写"删掉的东西都在这儿 · 随时能反悔"，但**流水是硬删**、不进回收站 ——
        // 用户删完一笔来这儿找不到，会以为数据丢了。把范围写清楚。
        subtitle = "账本 / 存钱目标 / 备份文件 · 流水删除不在这里",
        onBack = onBack,
        showNavigation = false
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(
                start = Layout.sidePadding(env.widthDp),
                end = Layout.sidePadding(env.widthDp),
                top = 8.dp,
                bottom = 28.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (ledgers.isEmpty() && goals.isEmpty() && backups.isEmpty()) {
                item {
                    EmptyHint(
                        emoji = "🗑",
                        title = "回收站是空的",
                        subtitle = "删掉的账本、存钱目标、备份文件都会先放这儿"
                    )
                }
            }

            if (ledgers.isNotEmpty()) {
                item {
                    SectionCard(
                        title = "账本",
                        subtitle = "${ledgers.size} 个 · 还原后流水和分类原样回来"
                    ) {
                        ledgers.forEach { l ->
                            TrashRow(
                                emoji = l.emoji,
                                title = l.name,
                                subtitle = "删于 ${DateExt.shortDateTime(l.deletedAt)}",
                                onRestore = { vm.restoreLedger(l.id) },
                                onPurge = { confirmPurge = PurgeTarget.Ledger(l) }
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        SmallDangerButton("清空账本回收站") {
                            confirmPurge = PurgeTarget.AllLedgers
                        }
                    }
                }
            }

            if (goals.isNotEmpty()) {
                item {
                    SectionCard(
                        title = "存钱目标",
                        subtitle = "${goals.size} 个 · 还原后进度照旧"
                    ) {
                        goals.forEach { g ->
                            TrashRow(
                                emoji = g.emoji,
                                title = g.name,
                                subtitle = "已存 ${Money.format(g.savedCents)} / 目标 ${Money.format(g.targetCents)}",
                                onRestore = { vm.restoreGoal(g.id) },
                                onPurge = { confirmPurge = PurgeTarget.Goal(g) }
                            )
                        }
                    }
                }
            }

            if (backups.isNotEmpty()) {
                item {
                    SectionCard(
                        title = "备份文件",
                        subtitle = "${backups.size} 份 · 还原会放回备份目录"
                    ) {
                        backups.forEach { b ->
                            TrashRow(
                                emoji = "💾",
                                title = b.fileName,
                                subtitle = "删于 ${DateExt.shortDateTime(b.deletedAt)} · ${b.sizeBytes / 1024} KB",
                                onRestore = { vm.restoreBackup(b, treeUri) },
                                onPurge = { confirmPurge = PurgeTarget.Backup(b) }
                            )
                        }
                    }
                }
            }
        }
    }

    // 彻底铲除的二次确认
    confirmPurge?.let { target ->
        val title = when (target) {
            is PurgeTarget.Ledger -> "彻底删除「${target.ledger.name}」？"
            is PurgeTarget.Goal -> "彻底删除「${target.goal.name}」？"
            is PurgeTarget.Backup -> "彻底铲除「${target.backup.fileName}」？"
            PurgeTarget.AllLedgers -> "清空账本回收站？"
        }
        GlassDialog(
            onDismissRequest = { confirmPurge = null },
            title = { Text(title) },
            text = {
                Text(
                    "这一步不可逆，删掉就真的没了。\n" +
                        "如果只是想让它从回收站消失、回到正常使用，请点「还原」。",
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    when (target) {
                        is PurgeTarget.Ledger -> vm.purgeLedger(target.ledger.id)
                        is PurgeTarget.Goal -> vm.purgeGoal(target.goal.id)
                        is PurgeTarget.Backup -> vm.purgeBackup(target.backup)
                        PurgeTarget.AllLedgers -> vm.purgeAllLedgers()
                    }
                    confirmPurge = null
                }) {
                    Text("彻底删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmPurge = null }) { Text("再想想") }
            }
        )
    }
}

/** 待确认的彻底删除对象 */
private sealed interface PurgeTarget {
    data class Ledger(val ledger: LedgerEntity) : PurgeTarget
    data class Goal(val goal: GoalEntity) : PurgeTarget
    data class Backup(val backup: RecycledBackup) : PurgeTarget
    data object AllLedgers : PurgeTarget
}

@Composable
private fun TrashRow(
    emoji: String,
    title: String,
    subtitle: String,
    onRestore: () -> Unit,
    onPurge: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
    ) {
        Text(emoji, fontSize = 17.sp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                // v16.3.1：与备份 / 同步记录等二级页面卡的列表主文本统一层级
                fontSize = 15.sp,
                lineHeight = 22.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                subtitle,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        SmallActionButton("还原", primary = true, onClick = onRestore)
        // v9.8.0：两个胶囊同尺寸、只隔 6dp，手指一偏就从"还原"滑到"彻底删除"（不可逆）
        Spacer(Modifier.width(10.dp))
        SmallActionButton("彻底删除", primary = false, onClick = onPurge)
    }
}

@Composable
private fun SmallActionButton(label: String, primary: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .glassChip(
                shape = RoundedCornerShape(50),
                tint = if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                strength = 0.9f
            )
            .clickableScale(pressedScale = 0.93f, clipShape = RoundedCornerShape(50)) { onClick() }
    ) {
        Text(
            label,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun SmallDangerButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .glassChip(
                shape = RoundedCornerShape(50),
                tint = MaterialTheme.colorScheme.error,
                strength = 0.9f
            )
            .clickableScale(pressedScale = 0.93f, clipShape = RoundedCornerShape(50)) { onClick() }
    ) {
        Text(
            label,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}
