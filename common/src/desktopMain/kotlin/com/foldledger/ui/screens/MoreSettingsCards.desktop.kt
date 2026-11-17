package com.foldledger.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foldledger.APP_VERSION_NAME
import com.foldledger.data.prefs.AppSettings
import com.foldledger.ui.adaptive.FoldEnv
import com.foldledger.ui.components.SectionCard
import com.foldledger.ui.theme.Layout
import com.foldledger.ui.vm.AppViewModel

/** 桌面端「更多设置」卡：提醒偏好可写，关于卡显示版本。 */
@Composable
actual fun MoreSettingsCards(
    settings: AppSettings,
    env: FoldEnv,
    appVm: AppViewModel,
    contentPadding: PaddingValues
) {
    val (toggleReminder, changeReminderTime) = rememberReminderActions(appVm, settings)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(contentPadding)
            .padding(horizontal = Layout.sidePadding(env.widthDp)),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SectionCard(overline = "提醒", title = "记账提醒", subtitle = "每天定时提醒你记一笔") {
            Row_Reminder(enabled = settings.reminderEnabled, onToggle = toggleReminder)
        }
        // v16.6：关于卡加捐赠入口（不打扰、不捆绑功能）
        val showDonation = remember { mutableStateOf(false) }
        SectionCard(overline = "关于", title = "滴水记账") {
            Text(
                "Windows 桌面版 · 版本 $APP_VERSION_NAME",
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { showDonation.value = true }
                    .padding(vertical = 10.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("💝", fontSize = 20.sp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("支持作者", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text("滴水记账免费开源，自愿打赏", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("›", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (showDonation.value) DonationQrDialog(onDismiss = { showDonation.value = false })
        SectionCard(overline = "说明", title = "其余设置") {
            Text(
                "手感 / 配色 / 折叠屏适配等设置将在桌面版后续版本提供",
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun Row_Reminder(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    // v16.3.1：Switch 约 32dp、Text 行盒 22sp，默认 Top 对齐会明显错位
    androidx.compose.foundation.layout.Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "每日提醒",
            fontSize = 15.sp,
            lineHeight = 22.sp,
            color = MaterialTheme.colorScheme.onSurface
        )
        Switch(checked = enabled, onCheckedChange = onToggle)
    }
}

/** 桌面端：暂只写偏好，不做系统通知调度（M3 接入 Windows 通知）。 */
@Composable
actual fun rememberReminderActions(
    appVm: AppViewModel,
    settings: AppSettings
): Pair<(Boolean) -> Unit, (Int, Int) -> Unit> {
    val toggle: (Boolean) -> Unit = { enable ->
        appVm.setReminder(enable, settings.reminderHour, settings.reminderMinute)
    }
    val changeTime: (Int, Int) -> Unit = { hour, minute ->
        appVm.setReminder(true, hour, minute)
    }
    return Pair(toggle, changeTime)
}
