package com.foldledger.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import com.foldledger.data.prefs.AppSettings
import com.foldledger.ui.adaptive.FoldEnv
import com.foldledger.ui.vm.AppViewModel

/**
 * 「更多设置」页的卡片区。
 * Android 渲染完整的提醒/手感/偏好/折叠/关于卡片；桌面先提供对应功能的占位卡。
 */
@Composable
expect fun MoreSettingsCards(
    settings: AppSettings,
    env: FoldEnv,
    appVm: AppViewModel,
    contentPadding: PaddingValues
)

/**
 * 记账提醒动作（Android 需通知权限 + WorkManager 调度；桌面暂只写偏好）。
 */
@Composable
expect fun rememberReminderActions(
    appVm: AppViewModel,
    settings: AppSettings
): Pair<(Boolean) -> Unit, (Int, Int) -> Unit>
