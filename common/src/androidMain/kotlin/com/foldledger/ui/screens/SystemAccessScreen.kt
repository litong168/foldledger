package com.foldledger.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.foldledger.ui.adaptive.AdaptiveScaffold
import com.foldledger.ui.adaptive.FoldEnv
import com.foldledger.ui.anim.clickableScale
import com.foldledger.ui.components.SectionCard
import com.foldledger.ui.theme.glassChip
import com.foldledger.ui.theme.Layout
import com.foldledger.ui.theme.dividerBrush
import com.foldledger.ui.theme.rememberAnimatedPaneWidth
import com.foldledger.util.MiuiSettings

/**
 * 系统权限引导（v8.1.1）——给小米 HyperOS 的管控开路。
 *
 * 自动记账在小米上跑不起来，几乎都不是代码问题，而是三道系统闸门没开：
 *  1. **安装**被「未知来源 / 纯净模式」拦（装都装不上）；
 *  2. **自启动**没给（短信广播收不到、通知监听被系统杀）；
 *  3. **省电策略**没给无限制（进程被冻结，捕获全停）。
 *
 * 这页把每道闸门做成一行：**看得出开没开 + 一键跳到小米对应的设置页**。
 * 各机型设置入口不一，按「机型专用页 → 通用入口 → 应用详情」三级降级，总有一个打得开。
 */
@Composable
fun SystemAccessScreen(
    env: FoldEnv,
    onBack: () -> Unit
) {
    val context = LocalContext.current

    var notifGranted by remember { mutableStateOf(false) }
    var smsGranted by remember { mutableStateOf(false) }
    var batteryOk by remember { mutableStateOf(false) }

    val smsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> smsGranted = granted }

    // 从系统设置回来时刷新开关状态（关键：授权完成回来立刻看到✅）
    LifecycleResumeEffect(Unit) {
        notifGranted = NotificationManagerCompat
            .getEnabledListenerPackages(context)
            .contains(context.packageName)
        smsGranted = runCatching {
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, Manifest.permission.RECEIVE_SMS
            ) == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        batteryOk = MiuiSettings.ignoringBatteryOptimizations(context)
        onPauseOrDispose { }
    }

    AdaptiveScaffold(
        env = env,
        title = "系统权限引导",
        subtitle = "小米 HyperOS 三道闸门 · 逐项解锁",
        onBack = onBack,
        showNavigation = false
    ) { padding ->
        if (env.twoPane) {
            val masterWidth = rememberAnimatedPaneWidth(env.paneMasterWidthDp(navigationVisible = false))
            Row(
                Modifier
                    .padding(padding)
                    .fillMaxSize()
            ) {
                Column(
                    Modifier
                        .width(masterWidth)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(start = 20.dp, end = 12.dp)
                ) {
                    Spacer(Modifier.height(6.dp))
                    InstallBlockedCard()
                }
                Box(Modifier.width(1.dp).fillMaxHeight().background(dividerBrush()))
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp)
                ) {
                    Spacer(Modifier.height(6.dp))
                    AccessList(
                        notifGranted = notifGranted,
                        smsGranted = smsGranted,
                        batteryOk = batteryOk,
                        onSmsRequest = { smsLauncher.launch(Manifest.permission.RECEIVE_SMS) }
                    )
                    Spacer(Modifier.height(28.dp))
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding),
                contentPadding = PaddingValues(
                    start = Layout.sidePadding(env.widthDp),
                    end = Layout.sidePadding(env.widthDp),
                    bottom = 28.dp
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item { InstallBlockedCard() }
                item {
                    AccessList(
                        notifGranted = notifGranted,
                        smsGranted = smsGranted,
                        batteryOk = batteryOk,
                        onSmsRequest = { smsLauncher.launch(Manifest.permission.RECEIVE_SMS) }
                    )
                }
            }
        }
    }
}

// ================= 安装被拦 =================

@Composable
private fun InstallBlockedCard() {
    val context = LocalContext.current
    SectionCard(
        title = "安装时被拦？",
        subtitle = "「未知来源应用」/「纯净模式」是 HyperOS 对侧载安装的默认拦截"
    ) {
        StepLine("1", "系统弹「未知来源应用」→ 点提示进设置，给**文件管理/浏览器**（你用来打开 APK 的那个 App）开「允许安装应用」")
        StepLine("2", "提示「已被纯净模式/安全守护拦截」→ 打开**手机管家**，搜「纯净模式」（或安全守护）→ 关闭，或选「仍要安装」")
        StepLine("3", "以上都没有 → 设置 › 隐私保护 › 特殊权限 › 安装未知应用，手动打开来源开关")
        Spacer(Modifier.height(4.dp))
        Text(
            "同签名更新（8.x 覆盖安装）不会再弹这个提示，只第一次装 / 换来源装会拦。",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        GuideButton(label = "打开「安装未知应用」设置") {
            MiuiSettings.openInstallUnknownApps(context)
        }
    }
}

// ================= 自动记账权限清单 =================

@Composable
private fun AccessList(
    notifGranted: Boolean,
    smsGranted: Boolean,
    batteryOk: Boolean,
    onSmsRequest: () -> Unit
) {
    val context = LocalContext.current
    SectionCard(
        title = "自动记账需要的权限",
        subtitle = "前三项是硬性前提：缺任何一项都收不到账"
    ) {
        AccessRow(
            emoji = "🔔",
            label = "通知使用权",
            hint = "读取微信/支付宝/云闪付的支付通知",
            ok = notifGranted,
            actionLabel = "去授权"
        ) { MiuiSettings.openNotificationAccess(context) }

        AccessRow(
            emoji = "✉️",
            label = "短信权限",
            hint = "接收银行动账短信（系统弹窗点「允许」）",
            ok = smsGranted,
            actionLabel = "授予权限",
            onAction = onSmsRequest
        )

        AccessRow(
            emoji = "🚀",
            label = "自启动",
            hint = "小米管控重点：不给自启动，通知监听被杀、短信广播收不到",
            ok = null, // 无法可靠探测，只给入口
            actionLabel = "去开启"
        ) { MiuiSettings.openAutostart(context) }

        AccessRow(
            emoji = "🔋",
            label = "省电策略 · 无限制",
            hint = "被省电策略管住的 App 会被冻结，捕获全停。设为「无限制」",
            ok = null,
            actionLabel = "去设置"
        ) { MiuiSettings.openBatteryPolicy(context) }

        AccessRow(
            emoji = "⚡",
            label = "电池优化白名单",
            hint = "标准安卓通道，多一道保险（会弹系统确认框）",
            ok = batteryOk,
            actionLabel = "加入白名单"
        ) { MiuiSettings.requestIgnoreBatteryOptimizations(context) }

        Spacer(Modifier.height(10.dp))
        Text(
            "各机型入口略有不同 —— 打不开的会落到「应用详情」页，" +
                "自启动 / 耗电策略 / 权限都在那里面。全部打 ✅ 后，" +
                "付一笔款就会出现在「自动记账」账本里。",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun AccessRow(
    emoji: String,
    label: String,
    hint: String,
    ok: Boolean?,
    actionLabel: String,
    onAction: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 5.dp)) {
        Text(emoji, fontSize = 17.sp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(6.dp))
                when (ok) {
                    true -> Text("✅ 已开启", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                    false -> Text("❌ 未开启", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                    null -> Text("去确认", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(hint, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        GuideButton(label = actionLabel, onClick = onAction)
    }
}

@Composable
private fun GuideButton(label: String, onClick: () -> Unit) {
    // v13.9：整颗主色实心 → 主色染膜玻璃（字回 onSurface）
    Box(
        Modifier
            .glassChip(
                shape = RoundedCornerShape(50),
                tint = MaterialTheme.colorScheme.primary,
                strength = 1.9f,
                elevation = 2.dp
            )
            .clickableScale(pressedScale = 0.93f, clipShape = RoundedCornerShape(50)) { onClick() }
    ) {
        Text(
            label,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
        )
    }
}

@Composable
private fun StepLine(no: String, text: String) {
    Row(modifier = Modifier.padding(vertical = 4.dp)) {
        Box(Modifier.glassChip(shape = RoundedCornerShape(50), tint = MaterialTheme.colorScheme.primary, strength = 0.9f)) {
            Text(
                no,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurface,
            lineHeight = 16.sp,
            modifier = Modifier.weight(1f)
        )
    }
}
