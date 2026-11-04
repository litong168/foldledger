package com.foldledger.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.foldledger.ui.adaptive.FoldEnv
import com.foldledger.ui.vm.AppViewModel
import com.foldledger.ui.vm.BackupViewModel

/**
 * Android 专属二级页/设置页的双端桥。
 *
 * 这些屏幕依赖 SAF / 系统权限 / 语音识别等 Android 能力，留在 androidMain；
 * 桌面端（desktopMain actual）提供等效的桌面实现，占位页仅限 Android 专属能力页。
 */
interface PlatformScreens {
    @Composable
    fun SettingsTab(
        env: FoldEnv,
        appVm: AppViewModel,
        backupVm: BackupViewModel,
        onOpenSystemAccess: () -> Unit,
        onOpenRecycleBin: () -> Unit,
        onOpenMoreSettings: () -> Unit,
        onOpenSpeedEntry: (Long) -> Unit,
        onOpenAssistant: () -> Unit,
        onOpenGoal: (Long) -> Unit,
        onOpenBillImport: (Long) -> Unit,
        onOpenSyncLog: () -> Unit,
        modifier: Modifier
    )

    @Composable
    fun CategoryManage(env: FoldEnv, ledgerId: Long, onBack: () -> Unit)

    @Composable
    fun SystemAccess(env: FoldEnv, onBack: () -> Unit)

    @Composable
    fun SpeedEntry(env: FoldEnv, ledgerId: Long, onBack: () -> Unit)

    @Composable
    fun BillImport(env: FoldEnv, ledgerId: Long, onBack: () -> Unit)
}

@Composable
expect fun rememberPlatformScreens(): PlatformScreens
