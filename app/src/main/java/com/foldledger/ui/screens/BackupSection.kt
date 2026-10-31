package com.foldledger.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foldledger.data.backup.BackupManager
import com.foldledger.ui.anim.clickableScale
import com.foldledger.ui.components.GlassDialog
import com.foldledger.ui.components.PillTag
import com.foldledger.ui.theme.Radius
import com.foldledger.ui.components.SectionCard
import com.foldledger.ui.theme.CoolGreen
import com.foldledger.ui.theme.pendingTone
import com.foldledger.ui.vm.BackupPhase
import com.foldledger.ui.vm.BackupViewModel
import com.foldledger.ui.theme.cardSurface
import com.foldledger.ui.theme.glassChip
import com.foldledger.ui.theme.wellSurface

/**
 * 「备份与恢复」区块。
 *
 * 交互设计目标是傻瓜式：
 *  - 点「导出备份」→ 首次会弹一次系统目录选择（默认定位到「下载」）→ 之后永远是一键
 *  - 点「从备份恢复」→ 自动找备份目录里最新的一份 → 确认一下 → 恢复完成
 *  - 卸载重装后，目录授权会失效，此时再点任意一个按钮即会重新弹一次选择器
 */
@Composable
fun BackupSection(
    backupVm: BackupViewModel,
    hasData: Boolean,
    modifier: Modifier = Modifier
) {
    val s by backupVm.state.collectAsStateWithLifecycle()
    val working = s.phase == BackupPhase.WORKING

    Column(modifier) {
        SectionCard(
            title = "备份与恢复",
            subtitle = "备份存放在系统目录，卸载 App 不会删掉，重装后可一键恢复"
        ) {
            // ---------- 备份位置状态 ----------
            Box(
                Modifier
                    .fillMaxWidth()
                    .glassChip(
                        shape = RoundedCornerShape(Radius.sm),
                        tint = if (s.folderReady) CoolGreen else pendingTone(),
                        strength = 0.6f
                    )
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(if (s.folderReady) "📍" else "⚠️", fontSize = 15.sp)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (s.folderReady) "备份位置：${s.folderLabel ?: "已授权目录"}"
                            else "还没设置备份位置",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            if (s.folderReady) {
                                "上次自动备份：${BackupViewModel.formatTime(s.lastAutoBackupAt)}"
                            } else {
                                "点一次「导出备份」，把目录选成「下载」即可，以后全自动"
                            },
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (s.folderReady) {
                        PillTag("已就绪", CoolGreen, filled = true)
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ---------- 两个主操作 ----------
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BigButton(
                    label = "导出备份",
                    emoji = "⬆️",
                    primary = true,
                    enabled = !working && hasData,
                    working = working,
                    modifier = Modifier.weight(1f),
                    onClick = { backupVm.requestExport() }
                )
                BigButton(
                    label = "从备份恢复",
                    emoji = "⬇️",
                    primary = false,
                    enabled = !working,
                    working = false,
                    modifier = Modifier.weight(1f),
                    onClick = { backupVm.requestImport() }
                )
            }
            if (!hasData) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "当前还没有账本，先创建一些记录再导出",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Spacer(Modifier.height(6.dp))
                Text(
                    "「从备份恢复」会先把目录里的每一份都列出来，由你决定用哪一份",
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(14.dp))

            // ---------- 自动备份 ----------
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "每次打开 App 自动备份一次",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        "保留最近 3 份自动备份，更旧的自动清理，不会堆成山",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = s.autoBackupEnabled,
                    onCheckedChange = { backupVm.setAutoBackupEnabled(it) },
                    enabled = !working
                )
            }

            // ---------- 执行结果 ----------
            if (s.message.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    s.message,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = when (s.phase) {
                        BackupPhase.DONE -> CoolGreen
                        BackupPhase.FAILED -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }

        // ---------- 备份文件列表 ----------
        // v9.9.0：一份都没有时原来是整块不渲染 —— 用户不知道"备份文件"会长在这里，
        // 也就不会想到去点「立即导出」。空态给一句去处。
        if (s.backups.isEmpty()) {
            Spacer(Modifier.height(12.dp))
            SectionCard(title = "备份文件", subtitle = "还没有备份") {
                Text(
                    "点上面的「立即导出」，会在你选的文件夹里生成一份 .foldledger.json；" +
                        "导出过的文件都会列在这里，随时能用来恢复或删除。",
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (s.backups.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            SectionCard(
                title = "备份文件",
                subtitle = "最近 ${s.backups.size} 份，点「恢复」即用该份覆盖当前数据"
            ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            s.backups.take(10).forEach { entry ->
                                BackupRow(
                                    entry = entry,
                                    onRestore = { backupVm.requestRestoreFrom(entry) },
                                    onDelete = { backupVm.requestDelete(entry) }
                                )
                            }
                        }
            }
        }

        // ---------- 二次确认：恢复 ----------
        if (s.pendingRestore != null) {
            RestoreConfirmDialog(
                entry = s.pendingRestore!!,
                hasData = hasData,
                onConfirm = { backupVm.confirmRestore() },
                onDismiss = { backupVm.cancelPendingRestore() }
            )
        }

        // ---------- 二次确认：删除备份 ----------
        if (s.pendingDelete != null) {
            DeleteConfirmDialog(
                entry = s.pendingDelete!!,
                onConfirm = { backupVm.confirmDelete() },
                onDismiss = { backupVm.cancelDelete() }
            )
        }
    }
}

@Composable
private fun BigButton(
    label: String,
    emoji: String,
    primary: Boolean,
    enabled: Boolean,
    working: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val primaryFill = primary && enabled
    Box(
        modifier
            .glassChip(
                shape = RoundedCornerShape(14.dp),
                tint = if (primaryFill) MaterialTheme.colorScheme.primary else null,
                strength = if (primaryFill) 1.9f else 0.6f
            )
            .clickableScale(enabled = enabled) { onClick() }
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (working && primary) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(8.dp))
            } else {
                Text(emoji, fontSize = 15.sp)
                Spacer(Modifier.width(6.dp))
            }
            Text(
                if (working && primary) "正在导出…" else label,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = when {
                    primaryFill -> MaterialTheme.colorScheme.onSurface
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}

@Composable
private fun BackupRow(
    entry: BackupManager.BackupEntry,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(Radius.sm),
        color = wellSurface(),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(if (entry.isAuto) "🔄" else "📦", fontSize = 17.sp)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        BackupViewModel.formatTime(entry.savedAt),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                    if (entry.isAuto) {
                        Spacer(Modifier.width(6.dp))
                        PillTag("自动", CoolGreen, filled = true)
                    }
                }
                Text(
                    "${entry.ledgerCount} 个账本 · ${entry.txnCount} 笔流水 · ${
                        BackupViewModel.formatSize(entry.sizeBytes)
                    }",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Box(
                Modifier
                    .glassChip(
                        shape = RoundedCornerShape(50),
                        tint = MaterialTheme.colorScheme.primary,
                        strength = 0.9f
                    )
                    .clickableScale { onRestore() }
            ) {
                Text(
                    "恢复",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                )
            }
            Spacer(Modifier.width(4.dp))
            // 删除这份备份（会二次确认，且只删本 App 命名的文件）
            Box(
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(50))
                    .clickableScale(pressedScale = 0.88f) { onDelete() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "删除这份备份",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(17.dp)
                )
            }
        }
    }
}

@Composable
private fun DeleteConfirmDialog(
    entry: BackupManager.BackupEntry,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    GlassDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除这份备份？") },
        text = {
            Column {
                Text(
                    BackupViewModel.formatTime(entry.savedAt),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "${entry.ledgerCount} 个账本 · ${entry.txnCount} 笔流水 · ${
                        BackupViewModel.formatSize(entry.sizeBytes)
                    }",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "会同时删除配对的 .xlsx 文件（如果有）。此操作不可撤销，但**不会**影响 App 里当前的数据。",
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    entry.fileName,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("删除", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun RestoreConfirmDialog(
    entry: BackupManager.BackupEntry,
    hasData: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    GlassDialog(
        onDismissRequest = onDismiss,
        title = { Text("用这份备份覆盖当前数据？") },
        text = {
            Column {
                Text(
                    BackupViewModel.formatTime(entry.savedAt),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "${entry.ledgerCount} 个账本 · ${entry.txnCount} 笔流水",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                if (hasData) {
                    Text(
                        "当前所有账本、分类与流水都会被替换。恢复前会自动把当前数据另存为一份「自动备份」，万一恢复错了还能退回来。",
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                } else {
                    Text(
                        "当前是空库，恢复后即得到备份里的全部账本与记录。",
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("确认恢复") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/** 首页顶部那条「开启自动备份」提示条 */
@Composable
fun BackupPromptBanner(
    onEnable: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier
            .fillMaxWidth()
            .glassChip(
                shape = RoundedCornerShape(14.dp),
                tint = MaterialTheme.colorScheme.primary,
                strength = 0.6f
            )
    ) {
        Row(
            Modifier.padding(start = 12.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("🛟", fontSize = 17.sp)
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "开启自动备份",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    "备份存到系统下载目录，卸载重装后也能一键找回账本",
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Box(
                Modifier
                    .glassChip(
                        shape = RoundedCornerShape(50),
                        tint = MaterialTheme.colorScheme.primary,
                        strength = 1.9f
                    )
                    .clickableScale { onEnable() }
            ) {
                Text(
                    "开启",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                )
            }
            Box(
                Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(50))
                    .clickableScale(pressedScale = 0.88f) { onDismiss() },
                contentAlignment = Alignment.Center
            ) {
                Text("✕", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
