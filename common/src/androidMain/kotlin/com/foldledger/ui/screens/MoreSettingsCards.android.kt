package com.foldledger.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.foldledger.ServiceLocator
import com.foldledger.data.prefs.AppSettings
import com.foldledger.data.reminder.ReminderScheduler
import com.foldledger.data.reminder.needsNotificationPermission
import com.foldledger.ui.adaptive.FoldEnv
import com.foldledger.ui.l10n.Language
import com.foldledger.ui.l10n.LanguageCard
import com.foldledger.ui.theme.Layout
import com.foldledger.ui.vm.AppViewModel

/** Android 实现：渲染「更多设置」既有五张卡片（定义在 SettingsScreen）。 */
@Composable
actual fun MoreSettingsCards(
    settings: AppSettings,
    env: FoldEnv,
    appVm: AppViewModel,
    contentPadding: PaddingValues
) {
    val (toggleReminder, changeReminderTime) = rememberReminderActions(appVm, settings)
    LazyColumn(
        modifier = Modifier.padding(contentPadding),
        contentPadding = PaddingValues(
            start = Layout.sidePadding(env.widthDp),
            end = Layout.sidePadding(env.widthDp),
            top = 8.dp,
            bottom = 28.dp
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            ReminderCard(
                enabled = settings.reminderEnabled,
                hour = settings.reminderHour,
                minute = settings.reminderMinute,
                onToggle = toggleReminder,
                onTimeChange = changeReminderTime
            )
        }
        item { HapticsCard(settings = settings, appVm = appVm) }
        item { PreferenceCard(settings = settings, appVm = appVm) }
        item {
            LanguageCard(current = Language.fromCode(settings.language)) { lang ->
                appVm.setLanguage(lang.code)
            }
        }
        item { FoldAdaptationCard(env) }
        item { AboutCard() }
    }
}

/** Android 实现：提醒动作（通知权限 + ReminderScheduler 调度）。 */
@Composable
actual fun rememberReminderActions(
    appVm: AppViewModel,
    settings: AppSettings
): Pair<(Boolean) -> Unit, (Int, Int) -> Unit> {
    val context = ServiceLocator.appContext
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && settings.reminderEnabled) {
            ReminderScheduler.schedule(context, settings.reminderHour, settings.reminderMinute)
        }
    }

    val toggle: (Boolean) -> Unit = { enable ->
        if (enable) {
            appVm.setReminder(true, settings.reminderHour, settings.reminderMinute)
            if (needsNotificationPermission(context)) {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                ReminderScheduler.schedule(context, settings.reminderHour, settings.reminderMinute)
            }
        } else {
            appVm.setReminder(false, settings.reminderHour, settings.reminderMinute)
            ReminderScheduler.cancel(context)
        }
    }

    val changeTime: (Int, Int) -> Unit = { hour, minute ->
        appVm.setReminder(true, hour, minute)
        ReminderScheduler.schedule(context, hour, minute)
    }

    return Pair(toggle, changeTime)
}
