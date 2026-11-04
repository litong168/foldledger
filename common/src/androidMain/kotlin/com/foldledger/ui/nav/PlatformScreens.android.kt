package com.foldledger.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.foldledger.ui.adaptive.FoldEnv
import com.foldledger.ui.screens.BillImportScreen
import com.foldledger.ui.screens.CategoryManageScreen
import com.foldledger.ui.screens.SettingsScreen
import com.foldledger.ui.screens.SpeedEntryScreen
import com.foldledger.ui.screens.SystemAccessScreen
import com.foldledger.ui.vm.AppViewModel
import com.foldledger.ui.vm.BackupViewModel

/** Android 实现：直接渲染既有屏幕（SAF/权限/语音等能力都在这一侧）。 */
private object AndroidPlatformScreens : PlatformScreens {
    @Composable
    override fun SettingsTab(
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
    ) {
        SettingsScreen(
            env = env,
            appVm = appVm,
            backupVm = backupVm,
            onOpenSystemAccess = onOpenSystemAccess,
            onOpenRecycleBin = onOpenRecycleBin,
            onOpenMoreSettings = onOpenMoreSettings,
            onOpenSpeedEntry = onOpenSpeedEntry,
            onOpenAssistant = onOpenAssistant,
            onOpenGoal = onOpenGoal,
            onOpenBillImport = onOpenBillImport,
            onOpenSyncLog = onOpenSyncLog,
            modifier = modifier
        )
    }

    @Composable
    override fun CategoryManage(env: FoldEnv, ledgerId: Long, onBack: () -> Unit) {
        CategoryManageScreen(env = env, ledgerId = ledgerId, onBack = onBack)
    }

    @Composable
    override fun SystemAccess(env: FoldEnv, onBack: () -> Unit) {
        SystemAccessScreen(env = env, onBack = onBack)
    }

    @Composable
    override fun SpeedEntry(env: FoldEnv, ledgerId: Long, onBack: () -> Unit) {
        SpeedEntryScreen(env = env, ledgerId = ledgerId, onBack = onBack)
    }

    @Composable
    override fun BillImport(env: FoldEnv, ledgerId: Long, onBack: () -> Unit) {
        BillImportScreen(env = env, ledgerId = ledgerId, onBack = onBack)
    }
}

@Composable
actual fun rememberPlatformScreens(): PlatformScreens = AndroidPlatformScreens
