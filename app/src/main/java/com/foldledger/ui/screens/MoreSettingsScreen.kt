package com.foldledger.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foldledger.data.prefs.AppSettings
import com.foldledger.ui.adaptive.AdaptiveScaffold
import com.foldledger.ui.adaptive.FoldEnv
import com.foldledger.ui.theme.Layout
import com.foldledger.ui.vm.AppViewModel

/**
 * 更多设置（v9.5）——「我的」页里不常用、但值得调的那些。
 *
 * 归置原则：**每天会碰的留在「我的」首页，调一次就再也不动的收进这里**。
 * 触感强度、收支配色、折叠屏适配状态 —— 都是"设置一次、长期不管"的项。
 *
 * ⚠️ v13.8：**自动记账整块搬回了首页**。它被放在这里时是个误判 ——
 * 它不是"调一次不动"的设置，而是每天在后台干活、偶尔判错、判错时立刻要查的功能，
 * 而「捕获日志」又嵌在它里面，加起来四步。判据是**打开频率**，不是"看起来像不像设置"。
 */
@Composable
fun MoreSettingsScreen(
    env: FoldEnv,
    appVm: AppViewModel,
    onBack: () -> Unit
) {
    val settings by appVm.settings.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val (toggleReminder, changeReminderTime) = rememberReminderActions(context, appVm, settings)

    AdaptiveScaffold(
        env = env,
        title = "更多设置",
        subtitle = "提醒 · 手感 · 配色 · 关于",
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
            item { FoldAdaptationCard(env) }
            item { AboutCard() }
        }
    }
}
