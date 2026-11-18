package com.foldledger.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foldledger.data.update.UpdateManager
import com.foldledger.platform.loadAssetBitmap
import com.foldledger.ui.components.AccentPill
import com.foldledger.ui.components.GlassDialog
import com.foldledger.ui.l10n.t

/** 收款码资源逻辑名（两端各自放在 assets / resources） */
const val DONATION_QR_ASSET = "donation_wechat.png"

/**
 * 微信收款码弹窗：点「支持作者」后放大展示，长按/扫码即可打赏。
 * 不捆绑任何功能、关闭即走，纯自愿。
 */
@Composable
fun DonationQrDialog(onDismiss: () -> Unit) {
    var bitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(Unit) { bitmap = loadAssetBitmap(DONATION_QR_ASSET) }
    GlassDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                t("支持作者"),
                fontSize = 17.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    t("滴水记账免费开源，若对你有帮助，可自愿打赏一杯咖啡 ☕"),
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 220.dp, max = 420.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val bmp = bitmap
                    if (bmp != null) {
                        Image(
                            bitmap = bmp,
                            contentDescription = t("微信收款码"),
                            modifier = Modifier
                                .heightIn(max = 400.dp)
                                .widthIn(max = 320.dp)
                        )
                    } else {
                        CircularProgressIndicator(Modifier.size(32.dp))
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    t("用微信扫一扫 · 感谢你的支持"),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        scrollable = true
    )
}

/**
 * 发现新版本的玻璃弹窗（双端共用展示，按钮行为由平台注入）。
 * [onUpdate] 立即更新（下载+安装）、[onLater] 稍后、[onSkip] 跳过此版本。
 */
@Composable
fun UpdateAvailableDialog(
    info: UpdateManager.Available,
    progress: Pair<Long, Long>?,
    busy: Boolean,
    error: String?,
    onUpdate: () -> Unit,
    onLater: () -> Unit,
    onSkip: () -> Unit
) {
    GlassDialog(
        onDismissRequest = { if (!info.force && !busy) onLater() },
        title = {
            Text(
                "发现新版本 v${info.versionName}",
                fontSize = 17.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Column {
                Text(
                    info.notes.ifBlank { t("已发布新版本，建议更新。") },
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (busy) {
                    Spacer(Modifier.height(14.dp))
                    val (done, total) = progress ?: (0L to -1L)
                    val pct = if (total > 0) "${(done * 100 / total)}%" else "${done / 1024} KB"
                    Text("正在下载… $pct", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                }
                if (!error.isNullOrBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(error, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            AccentPill(
                text = if (busy) t("下载中…") else t("立即更新"),
                fontSize = 13,
                onClick = { if (!busy) onUpdate() }
            )
        },
        dismissButton = {
            if (!info.force && !busy) {
                TextButton(onClick = onSkip) { Text("跳过此版本") }
            }
        },
        scrollable = true
    )
}
