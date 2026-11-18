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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foldledger.data.backup.BackupEntry
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
import com.foldledger.ui.l10n.t

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
            title = t("备份与恢复"),
            subtitle = t("备份存放在系统目录，卸载 App 不会删掉，重装后可一键恢复")
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
                            else t("还没设置备份位置"),
                            // v16.3.1：二级页面卡主文本统一 15.sp SemiBold + 固定行盒，
                            // 与同步记录 / 全局搜索的列表主文本同层级，副文本仍 11.sp。
                            fontSize = 15.sp,
                            lineHeight = 22.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            when {
                                // 刚导出过就先给导出回执 —— 用户最想确认的就是"我刚才那次成了没"
                                s.lastExportAt > 0L -> "上次导出：${BackupViewModel.formatTime(s.lastExportAt)}"
                                s.folderReady -> "上次自动备份：${BackupViewModel.formatTime(s.lastAutoBackupAt)}"
                                else -> t("点一次「导出备份」，把目录选成「下载」即可，以后全自动")
                            },
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (s.folderReady) {
                        PillTag(t("已就绪"), CoolGreen, filled = true)
                        Spacer(Modifier.width(6.dp))
                    }
                    // v16.2.7：目录不是一次性设置 —— 选错、换盘、目录被删，都得能改
                    SmallAction(if (s.folderReady) t("更换") else t("选择")) {
                        backupVm.requestFolderChange()
                    }
                    if (s.canOpenFolder) {
                        Spacer(Modifier.width(2.dp))
                        SmallAction(t("打开")) { backupVm.openFolder() }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ---------- 两个主操作 ----------
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BigButton(
                    label = t("导出备份"),
                    emoji = "⬆️",
                    primary = true,
                    enabled = !working && hasData,
                    working = working,
                    modifier = Modifier.weight(1f),
                    onClick = { backupVm.requestExport() }
                )
                BigButton(
                    label = t("从备份恢复"),
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
                    t("当前还没有账本，先创建一些记录再导出"),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Spacer(Modifier.height(6.dp))
                Text(
                    t("「从备份恢复」会先把目录里的每一份都列出来，由你决定用哪一份"),
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
                        t("每次打开 App 自动备份一次"),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        t("保留最近 3 份自动备份，更旧的自动清理，不会堆成山"),
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
            SectionCard(title = t("备份文件"), subtitle = t("还没有备份")) {
                Text(
                    t("点上面的「立即导出」，会在你选的文件夹里生成一份 .foldledger.json；") +
                        t("导出过的文件都会列在这里，随时能用来恢复或删除。"),
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (s.backups.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            SectionCard(
                title = t("备份文件"),
                subtitle = "最近 ${s.backups.size} 份，点「恢复」可选覆盖当前数据或作为新账本加入"
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
                onReplace = { backupVm.confirmRestore(asNew = false) },
                onAsNew = { backupVm.confirmRestore(asNew = true) },
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

        // ---------- 导出加密对话框 ----------
        if (s.pendingExportPassword) {
            ExportPasswordDialog(
                onConfirm = { backupVm.confirmExport(it) },
                onDismiss = { backupVm.cancelExportPassword() }
            )
        }

        // ---------- 恢复加密备份：输入密码 ----------
        if (s.pendingRestorePassword != null) {
            RestorePasswordDialog(
                entry = s.pendingRestorePassword!!,
                onConfirm = { backupVm.confirmRestorePassword(it) },
                onDismiss = { backupVm.cancelRestorePassword() }
            )
        }
    }
}

/**
 * 卡片里那种"小号文字按钮"（更换 / 打开）。
 * 刻意不用 Button：这里只需要一个能点的字，做成按钮会在这行里显得比"已就绪"标签还重。
 */
@Composable
private fun SmallAction(label: String, onClick: () -> Unit) {
    Text(
        label,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .clickableScale { onClick() }
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
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
                if (working && primary) t("正在导出…") else label,
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
    entry: BackupEntry,
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
            Text(if (entry.encrypted) "🔒" else if (entry.isAuto) "🔄" else "📦", fontSize = 17.sp)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        BackupViewModel.formatTime(entry.savedAt),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                if (entry.encrypted) {
                    Spacer(Modifier.width(6.dp))
                    PillTag(t("加密"), pendingTone(), filled = true)
                } else if (entry.isAuto) {
                    Spacer(Modifier.width(6.dp))
                    PillTag(t("自动"), CoolGreen, filled = true)
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
                    t("恢复"),
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
                    contentDescription = t("删除这份备份"),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(17.dp)
                )
            }
        }
    }
}

@Composable
private fun DeleteConfirmDialog(
    entry: BackupEntry,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    GlassDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("删除这份备份？")) },
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
                    t("会同时删除配对的 .xlsx 文件（如果有）。删除后可在「回收站」里找回，但**不会**影响 App 里当前的数据。"),
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
                Text(t("删除"), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(t("取消")) }
        }
    )
}

@Composable
private fun ExportPasswordDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var pass by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    GlassDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("用密码保护这份备份？")) },
        text = {
            Column {
                Text(
                    t("设置密码后，备份文件会被加密，恢复时必须输入同样的密码；留空则保持明文（同时生成一份 Excel 对账表）。"),
                    fontSize = 13.sp, lineHeight = 18.sp
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = pass, onValueChange = { pass = it; error = null },
                    label = { Text(t("密码（可选）")) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = confirm, onValueChange = { confirm = it; error = null },
                    label = { Text(t("再次输入密码")) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (error != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(error!!, fontSize = 13.sp, color = MaterialTheme.colorScheme.error, lineHeight = 18.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (pass.isBlank()) { onConfirm(""); return@TextButton }
                if (pass.length < 4) { error = t("密码至少 4 位"); return@TextButton }
                if (pass != confirm) { error = t("两次输入的密码不一致"); return@TextButton }
                onConfirm(pass)
            }) { Text(if (pass.isBlank()) t("直接导出") else t("加密导出")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("取消")) } }
    )
}

@Composable
private fun RestorePasswordDialog(
    entry: BackupEntry,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var pass by remember { mutableStateOf("") }
    GlassDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("这份备份已加密")) },
        text = {
            Column {
                Text(
                    "「${BackupViewModel.formatTime(entry.savedAt)}」的备份受密码保护，输入密码后才能恢复。",
                    fontSize = 13.sp, lineHeight = 18.sp
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = pass, onValueChange = { pass = it },
                    label = { Text(t("备份密码")) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = pass.isNotBlank(),
                onClick = { onConfirm(pass) }
            ) { Text(t("确认恢复")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("取消")) } }
    )
}

@Composable
private fun RestoreConfirmDialog(
    entry: BackupEntry,
    hasData: Boolean,
    onReplace: () -> Unit,
    onAsNew: () -> Unit,
    onDismiss: () -> Unit
) {
    GlassDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("怎么恢复这份备份？")) },
        text = {
            Column {
                Text(
                    BackupViewModel.formatTime(entry.savedAt),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "${entry.ledgerCount} 个账本 · ${entry.txnCount} 笔流水 · ${BackupViewModel.formatSize(entry.sizeBytes)}",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (entry.encrypted) {
                    Text(
                        t("加密备份（密码已验证）"),
                        fontSize = 11.sp,
                        color = pendingTone()
                    )
                }
                Spacer(Modifier.height(12.dp))
                // 两条路的后果必须写清楚 —— 这正是用户最怕点错的地方
                Text(t("作为新账本"), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text(
                    t("把备份里的账本追加进来，名字后面加「（恢复）」。当前数据一条不动，两份可以摆在一起看。"),
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                Text(t("覆盖当前数据"), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text(
                    if (hasData) {
                        t("当前所有账本、分类与流水都会被替换（恢复前会自动留一份「自动备份」，万一恢复错了还能退回来）。")
                    } else {
                        t("当前是空库，恢复后即得到备份里的全部账本与记录。")
                    },
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = onAsNew) { Text(t("作为新账本")) }
                TextButton(onClick = onReplace) { Text(t("覆盖当前数据")) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(t("取消")) }
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
                    t("开启自动备份"),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    t("备份存到系统下载目录，卸载重装后也能一键找回账本"),
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
                    t("开启"),
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
