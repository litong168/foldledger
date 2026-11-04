package com.foldledger.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foldledger.data.sync.SyncManager
import com.foldledger.ui.anim.clickableScale
import com.foldledger.ui.components.SectionCard
import com.foldledger.ui.theme.Radius
import kotlinx.coroutines.launch

/**
 * M4.5 双端共享同步卡：开关 / 服务器地址 / 注册 / 手动与自动同步 / 状态。
 *
 * 桌面「我的」与 Android 设置页都挂它，行为完全一致；
 * 状态来自 [SyncManager]，不各自造轮子。
 */
@Composable
fun SyncSectionCard(
    syncManager: SyncManager,
    /** v16.2.8：打开「同步记录」二级页（宿主负责导航） */
    onOpenLog: () -> Unit = {}
) {
    val state by syncManager.state.collectAsStateWithLifecycle()
    val history = state.history
    val scope = rememberCoroutineScope()

    SectionCard(
        overline = "同步",
        title = "手机 ↔ 电脑",
        subtitle = "自托管服务，双端账本自动保持一致"
    ) {
        // 总开关
        Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("启用双端同步", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text(
                    if (state.registered) "本机设备 ID：${state.deviceId}" else "把账本同步到你自己的服务器",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = state.enabled,
                onCheckedChange = { scope.launch { syncManager.setEnabled(it) } }
            )
        }

        if (state.enabled) {
            Spacer(Modifier.height(8.dp))
            Text(
                "服务器地址",
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = state.serverUrl,
                onValueChange = { scope.launch { syncManager.setServerUrl(it) } },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("http://192.168.1.10:8701", fontSize = 13.sp) },
                singleLine = true,
                enabled = !state.syncing,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                shape = RoundedCornerShape(Radius.sm)
            )

            Spacer(Modifier.height(8.dp))
            Text(
                "访问密钥（可选）",
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = state.syncToken,
                onValueChange = { scope.launch { syncManager.setToken(it) } },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("服务端 --token 密钥；未启用可留空", fontSize = 13.sp) },
                singleLine = true,
                enabled = !state.syncing,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                shape = RoundedCornerShape(Radius.sm)
            )

            if (!state.registered) {
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = { scope.launch { syncManager.register() } },
                    enabled = !state.syncing,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (state.syncing) "注册中…" else "注册此设备")
                }
            } else {
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("自动同步（每 5 分钟）", fontSize = 13.sp)
                    Switch(
                        checked = state.autoSync,
                        onCheckedChange = { scope.launch { syncManager.setAutoSync(it) } }
                    )
                }
                Button(
                    onClick = { scope.launch { syncManager.syncNow() } },
                    enabled = !state.syncing,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (state.syncing) "同步中…" else "立即同步")
                }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(
                    onClick = { scope.launch { syncManager.resetIdentity() } },
                    enabled = !state.syncing,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("重置同步身份（换服务器 / 身份冲突时用）", fontSize = 13.sp)
                }
            }

            state.lastResult?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    it,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            state.lastError?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    it,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.error,
                    // v16.3.1：与上方 lastResult 用同一对齐口径（两者在同一位置轮换）
                    textAlign = TextAlign.Start,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        Text(
            "同步会把账本数据发送到你填写的服务器（建议内网或自配 HTTPS）；" +
                "「自动记账」账本不参与同步。",
            fontSize = 11.sp,
            lineHeight = 16.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // v16.2.8：同步记录入口 —— 以前"同步了什么"只有一行字，出事只能猜
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radius.sm))
                .clickableScale { onOpenLog() }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "同步记录",
                // v16.3.1：入口行主文本与同页其它二级卡统一 15.sp + 固定行盒
                fontSize = 15.sp,
                lineHeight = 22.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (history.isEmpty()) "还没有记录" else "最近 ${history.size} 次 · 冲突可展开",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.weight(1f))
            Text("›", fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
