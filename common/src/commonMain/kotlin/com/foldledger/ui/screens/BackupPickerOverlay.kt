package com.foldledger.ui.screens

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
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
import com.foldledger.data.backup.BackupEntry
import com.foldledger.ui.adaptive.FoldEnv
import com.foldledger.ui.anim.clickableScale
import com.foldledger.ui.components.PillTag
import com.foldledger.ui.theme.CoolGreen
import com.foldledger.ui.theme.Radius
import com.foldledger.ui.theme.Space
import com.foldledger.ui.theme.cardSurface
import com.foldledger.ui.theme.glassChip
import com.foldledger.ui.theme.wellSurface
import com.foldledger.ui.vm.BackupViewModel

/**
 * 「从备份恢复」的选份器。
 *
 * 之前是默默取最新那份再确认 —— 一旦自动备份把最新那份覆盖成了「坏数据」，
 * 用户就没有退路了。现在把目录里所有备份摆出来，谁想恢复哪一份自己挑。
 */
@Composable
fun BackupPickerOverlay(
    env: FoldEnv,
    backups: List<BackupEntry>,
    onPick: (BackupEntry) -> Unit,
    onPickLatest: () -> Unit,
    onDismiss: () -> Unit
) {
    AppOverlay(env = env, onDismiss = onDismiss) {
        Column(Modifier.fillMaxSize()) {

            // ---------------- 头部 ----------------
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = Space.xl, end = Space.md, top = Space.lg, bottom = Space.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "选择要恢复的备份",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "备份目录里共 ${backups.size} 份 · 越靠上越新",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(Radius.pill))
                        .clickableScale(pressedScale = 0.88f) { onDismiss() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "关闭",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // ---------------- 快捷：用最新的一份 ----------------
            if (backups.isNotEmpty()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Space.xl)
                        .glassChip(
                            shape = RoundedCornerShape(Radius.card),
                            tint = MaterialTheme.colorScheme.primary,
                            strength = 1.9f,
                            elevation = 3.dp
                        )
                        .clickableScale(pressedScale = 0.98f) { onPickLatest() }
                        .padding(Space.lg)
                ) {
                    Text(
                        "⚡ 用最新的一份",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "${BackupViewModel.formatTime(backups.first().savedAt)} · " +
                            "${backups.first().ledgerCount} 个账本 · " +
                            "${backups.first().txnCount} 笔流水",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(Space.md))
                Text(
                    "或者指定下面任意一份",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = Space.xl, bottom = Space.sm)
                )
            }

            // ---------------- 全部备份 ----------------
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(
                    start = Space.xl, end = Space.xl, top = Space.xs, bottom = Space.xxl
                ),
                verticalArrangement = Arrangement.spacedBy(Space.sm)
            ) {
                itemsIndexed(backups, key = { _, e -> e.fileName }) { index, entry ->
                    BackupPickRow(
                        entry = entry,
                        newest = index == 0,
                        modifier = Modifier.animateItem(),
                        onPick = { onPick(entry) }
                    )
                }
            }
        }
    }
}

@Composable
private fun BackupPickRow(
    entry: BackupEntry,
    newest: Boolean,
    modifier: Modifier = Modifier,
    onPick: () -> Unit
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.card))
            .background(wellSurface())
            .clickableScale(pressedScale = 0.98f) { onPick() }
            .padding(Space.lg),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(if (entry.isAuto) "🔄" else "📦", fontSize = 20.sp)
        Spacer(Modifier.width(Space.md))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    BackupViewModel.formatTime(entry.savedAt),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.width(Space.sm))
                if (newest) {
                    PillTag("最新", MaterialTheme.colorScheme.primary, filled = true)
                    Spacer(Modifier.width(Space.xs))
                }
                if (entry.isAuto) PillTag("自动", CoolGreen, filled = true)
            }
            Spacer(Modifier.height(4.dp))
            if (entry.corrupt) {
                Text(
                    "⚠️ 文件已损坏，无法读取 · 恢复会被安全拦截",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            } else {
                Text(
                    "${entry.ledgerCount} 个账本 · ${entry.txnCount} 笔流水 · " +
                        BackupViewModel.formatSize(entry.sizeBytes),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.width(Space.sm))
        Text(
            "选它",
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}
