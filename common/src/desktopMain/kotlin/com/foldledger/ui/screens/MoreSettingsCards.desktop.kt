package com.foldledger.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
        SectionCard(overline = "关于", title = "滴水记账") {
            Text("Windows 桌面版 · 版本 $APP_VERSION_NAME")
        }
        SectionCard(overline = "说明", title = "其余设置") {
            Text("手感 / 配色 / 折叠屏适配等设置将在桌面版后续版本提供")
        }
    }
}

@Composable
private fun Row_Reminder(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    androidx.compose.foundation.layout.Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text("每日提醒")
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
