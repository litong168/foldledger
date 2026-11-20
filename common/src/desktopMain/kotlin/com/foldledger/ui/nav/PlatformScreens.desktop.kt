package com.foldledger.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.foldledger.APP_VERSION_CODE
import com.foldledger.APP_VERSION_NAME
import com.foldledger.ServiceLocator
import com.foldledger.data.prefs.AppSettings
import com.foldledger.data.prefs.DarkMode
import com.foldledger.desktop.DesktopAutoUpdater
import com.foldledger.ui.adaptive.AdaptiveScaffold
import com.foldledger.ui.adaptive.FoldEnv
import com.foldledger.ui.anim.clickableScale
import com.foldledger.ui.components.SectionCard
import com.foldledger.ui.screens.BackupSection
import com.foldledger.ui.screens.BillImportScreen
import com.foldledger.ui.screens.DesktopCategoryManageScreen
import com.foldledger.ui.screens.DonationQrDialog
import com.foldledger.ui.screens.SpeedEntryPanel
import com.foldledger.ui.screens.SyncSectionCard
import com.foldledger.ui.screens.UpdateAvailableDialog
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import com.foldledger.ui.theme.Layout
import com.foldledger.ui.theme.Radius
import com.foldledger.ui.theme.Space
import com.foldledger.ui.theme.glassChip
import com.foldledger.ui.theme.wellSurface
import com.foldledger.ui.vm.AppViewModel
import com.foldledger.ui.vm.BackupViewModel
import com.foldledger.ui.vm.SpeedEntryViewModel
import com.foldledger.ui.l10n.t

/**
 * 桌面端平台页。
 *
 * 「我的」Tab 是桌面完整设置页（备份卡走共享 BackupSection + 桌面文件备份实现；
 * 外观/提醒/同步/关于为桌面专属卡）；依赖 Android 能力（系统权限 / 语音）的页面保持占位。
 */
private object DesktopPlatformScreens : PlatformScreens {
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
        val settings by appVm.settings.collectAsStateWithLifecycle()
        val cards by appVm.ledgerCards.collectAsStateWithLifecycle()

        // v16.4：宽屏双列。
        // 原来所有卡片排成一列、宽度锁 760dp 居中 —— 1700dp 的窗口里左右各空 470dp，
        // 「外观」这种两行就完的卡被拉成一条 760dp 的横带，屏幕利用率不到一半。
        // 现在 ≥1180dp 拆两列：左列是"每天要用的工具"，右列是"设置与数据"，
        // 一屏能看全，也不用把滚动条拖到底去找「关于」。
        BoxWithConstraints(modifier.fillMaxSize()) {
            val wide = maxWidth >= 1180.dp
            val side = Layout.sidePaddingIn(maxWidth.value.toInt())

            if (wide) {
                Row(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(start = side, end = side, top = Space.sm, bottom = Space.huge),
                    horizontalArrangement = Arrangement.spacedBy(Space.gapSection),
                    verticalAlignment = Alignment.Top
                ) {
                    Column(
                        Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(Space.gapSection)
                    ) {
                        ToolsCard(
                            settings = settings,
                            onOpenSpeedEntry = onOpenSpeedEntry,
                            onOpenAssistant = onOpenAssistant,
                            onOpenGoal = onOpenGoal,
                            onOpenBillImport = onOpenBillImport
                        )
                        AppearanceCard(settings = settings, appVm = appVm)
                        RecycleCard(onOpenRecycleBin = onOpenRecycleBin)
                        SyncSectionCard(
                            syncManager = ServiceLocator.syncManager,
                            onOpenLog = onOpenSyncLog
                        )
                    }
                    Column(
                        Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(Space.gapSection)
                    ) {
                        BackupSection(backupVm = backupVm, hasData = cards.isNotEmpty())
                        ReminderCard(settings = settings, appVm = appVm)
                        StartupCard(settings = settings, appVm = appVm)
                        AboutCard()
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = side, end = side, top = Space.sm, bottom = Space.huge
                    ),
                    verticalArrangement = Arrangement.spacedBy(Space.gapSection)
                ) {
                    item {
                        ToolsCard(
                            settings = settings,
                            onOpenSpeedEntry = onOpenSpeedEntry,
                            onOpenAssistant = onOpenAssistant,
                            onOpenGoal = onOpenGoal,
                            onOpenBillImport = onOpenBillImport
                        )
                    }
                    item { AppearanceCard(settings = settings, appVm = appVm) }
                    item { BackupSection(backupVm = backupVm, hasData = cards.isNotEmpty()) }
                    item { RecycleCard(onOpenRecycleBin = onOpenRecycleBin) }
                    item { ReminderCard(settings = settings, appVm = appVm) }
                    item { StartupCard(settings = settings, appVm = appVm) }
                    item {
                        SyncSectionCard(
                            syncManager = ServiceLocator.syncManager,
                            onOpenLog = onOpenSyncLog
                        )
                    }
                    item { AboutCard() }
                }
            }
        }
    }

    @Composable
    override fun CategoryManage(env: FoldEnv, ledgerId: Long, onBack: () -> Unit) {
        DesktopCategoryManageScreen(env = env, ledgerId = ledgerId, onBack = onBack)
    }

    @Composable
    override fun SystemAccess(env: FoldEnv, onBack: () -> Unit) {
        Placeholder(t("系统权限页仅 Android 需要，桌面无需此页"))
    }

    @Composable
    override fun SpeedEntry(env: FoldEnv, ledgerId: Long, onBack: () -> Unit) {
        val vm: SpeedEntryViewModel = viewModel(
            key = "speed-$ledgerId",
            factory = SpeedEntryViewModel.factory(ledgerId)
        )
        AdaptiveScaffold(
            env = env,
            title = t("一句话记账"),
            subtitle = "打一句「昨天打车35」就记完",
            onBack = onBack,
            showNavigation = false
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                SpeedEntryPanel(env = env, vm = vm, modifier = Modifier.fillMaxSize())
            }
        }
    }

    @Composable
    override fun BillImport(env: FoldEnv, ledgerId: Long, onBack: () -> Unit) {
        BillImportScreen(env = env, ledgerId = ledgerId, onBack = onBack)
    }

    @Composable
    private fun Placeholder(text: String) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text, modifier = Modifier.padding(24.dp))
        }
    }
}

// ============================================================
//  各张设置卡（拆成独立 composable，单列 / 双列两种排版共用同一份内容）
// ============================================================

@Composable
private fun ToolsCard(
    settings: AppSettings,
    onOpenSpeedEntry: (Long) -> Unit,
    onOpenAssistant: () -> Unit,
    onOpenGoal: (Long) -> Unit,
    onOpenBillImport: (Long) -> Unit
) {
    SectionCard(overline = t("全局"), title = t("速记 · 助手 · 目标")) {
        DesktopToolRow(
            emoji = "⚡",
            title = t("一句话记账"),
            subtitle = "打一句「昨天打车35」就记完",
            onClick = { onOpenSpeedEntry(settings.lastLedgerId) }
        )
        DesktopDivider()
        DesktopToolRow(
            emoji = "💡",
            title = t("记账助手"),
            subtitle = t("全部账本的待办，一页说清"),
            onClick = onOpenAssistant
        )
        DesktopDivider()
        DesktopToolRow(
            emoji = "🎯",
            title = t("存钱目标"),
            subtitle = "所有账本的钱罐，攒钱不混进流水",
            onClick = { onOpenGoal(settings.lastLedgerId) }
        )
        DesktopDivider()
        DesktopToolRow(
            emoji = "📥",
            title = t("导入账单"),
            subtitle = t("微信 / 支付宝导出的 Excel 或 CSV"),
            onClick = { onOpenBillImport(settings.lastLedgerId) }
        )
    }
}

@Composable
private fun AppearanceCard(settings: AppSettings, appVm: AppViewModel) {
    SectionCard(overline = t("主题"), title = t("外观")) {
        Text(
            t("深色模式"),
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(vertical = 4.dp)
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                DarkMode.FOLLOW_SYSTEM to t("跟随系统"),
                DarkMode.LIGHT to t("浅色"),
                DarkMode.DARK to t("深色")
            ).forEach { (mode, label) ->
                val selected = settings.darkMode == mode
                // v16.3.1：与 Android ChipRow 同款 —— 选中 = 主色染浓的玻璃，
                // 未选中 = 墨色凹槽。不再用 primary 实色块（用户明确否过）。
                Box(
                    Modifier
                        .then(
                            if (selected) Modifier.glassChip(
                                shape = RoundedCornerShape(50),
                                tint = MaterialTheme.colorScheme.primary,
                                strength = 2.6f,
                                elevation = 2.dp
                            ) else Modifier
                                .clip(RoundedCornerShape(50))
                                .background(wellSurface())
                        )
                        .clip(RoundedCornerShape(50))
                        .clickableScale { appVm.setDarkMode(mode) }
                        .padding(horizontal = 14.dp, vertical = 7.dp)
                ) {
                    Text(
                        label,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selected) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun RecycleCard(onOpenRecycleBin: () -> Unit) {
    // v16.3：桌面以前收了 onOpenRecycleBin 却没接，回收站页面根本进不去
    // （虽然共享的 RecycleBinScreen 与 OverlayTarget 早就注册好了）。
    SectionCard(overline = t("数据"), title = t("回收站")) {
        DesktopToolRow(
            emoji = "🗑️",
            title = t("查看回收站"),
            subtitle = t("删掉的账本、存钱目标和备份文件，都能在这里找回"),
            onClick = onOpenRecycleBin
        )
    }
}

@Composable
private fun ReminderCard(settings: AppSettings, appVm: AppViewModel) {
    SectionCard(
        overline = t("提醒"),
        title = t("记账提醒"),
        subtitle = t("每天定时提醒你记一笔")
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(t("每日提醒"), fontSize = 15.sp)
            Switch(
                checked = settings.reminderEnabled,
                onCheckedChange = { enable ->
                    appVm.setReminder(enable, settings.reminderHour, settings.reminderMinute)
                }
            )
        }
        if (settings.reminderEnabled) {
            Spacer(Modifier.height(10.dp))
            Text(t("提醒时间"), fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = settings.reminderHour.toString(),
                    onValueChange = { v ->
                        val h = v.filter { it.isDigit() }.toIntOrNull()
                        if (h != null && h in 0..23) {
                            appVm.setReminder(true, h, settings.reminderMinute)
                        }
                    },
                    label = { Text(t("时")) },
                    singleLine = true,
                    modifier = Modifier.width(80.dp)
                )
                Text(":", fontSize = 20.sp, modifier = Modifier.padding(horizontal = 8.dp))
                OutlinedTextField(
                    value = settings.reminderMinute.toString(),
                    onValueChange = { v ->
                        val m = v.filter { it.isDigit() }.toIntOrNull()
                        if (m != null && m in 0..59) {
                            appVm.setReminder(true, settings.reminderHour, m)
                        }
                    },
                    label = { Text(t("分")) },
                    singleLine = true,
                    modifier = Modifier.width(80.dp)
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                t("到点会从系统托盘弹出气泡提醒；App 需在后台运行（最小化到托盘即可）"),
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StartupCard(settings: AppSettings, appVm: AppViewModel) {
    // M5.15：开机自启与自动更新卡，桌面专属。v16.6 起更新走 GitHub，本地目录作兜底。
    var updateDir by remember { mutableStateOf(settings.desktopUpdateDir ?: "") }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var updateJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(settings.desktopUpdateDir) {
        updateDir = settings.desktopUpdateDir ?: ""
    }
    // 后台检查发现新版本时自动弹窗（GitHub 路径）
    LaunchedEffect(DesktopAutoUpdater.updateInfo.value) {
        if (DesktopAutoUpdater.updateInfo.value?.fromGithub == true) showUpdateDialog = true
    }

    SectionCard(overline = t("启动"), title = t("开机自启与更新")) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(t("开机自启"), fontSize = 15.sp)
            Switch(
                checked = settings.desktopAutoStart,
                onCheckedChange = { appVm.setDesktopAutoStart(it) }
            )
        }
        Text(
            t("开机后后台启动（不弹窗），从托盘打开窗口"),
            fontSize = 11.sp, lineHeight = 16.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        DesktopDivider()
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(t("自动检查更新"), fontSize = 15.sp)
            Switch(
                checked = settings.autoCheckUpdate,
                onCheckedChange = { appVm.setAutoCheckUpdate(it) }
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = { DesktopAutoUpdater.checkNow() },
                enabled = !DesktopAutoUpdater.busy.value
            ) {
                Text(if (DesktopAutoUpdater.busy.value) t("检查中…") else t("检查更新"))
            }
            // 本地兜底包：直接给安装/定位按钮（GitHub 包走统一弹窗）
            DesktopAutoUpdater.updateInfo.value?.let { info ->
                if (!info.fromGithub) {
                    if (info.isInstaller) {
                        Button(onClick = { DesktopAutoUpdater.installUpdate() }) {
                            Text("安装 v${info.versionName}")
                        }
                    } else {
                        Button(onClick = { DesktopAutoUpdater.openUpdateLocation() }) {
                            Text(t("打开位置"))
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            DesktopAutoUpdater.updateInfo.value?.let {
                if (it.fromGithub) "发现新版本 v${it.versionName}，已弹出更新提示"
                else if (it.isInstaller) "发现本地安装包 v${it.versionName}，点上方安装"
                else "发现旧版绿色包 v${it.versionName}"
            } ?: "当前版本 v$APP_VERSION_NAME（$APP_VERSION_CODE）；优先从 GitHub 检查",
            fontSize = 11.sp, lineHeight = 16.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Text(t("本地更新来源目录（GitHub 不可用时兜底）"), fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = updateDir,
            onValueChange = { updateDir = it; appVm.setDesktopUpdateDir(it) },
            placeholder = { Text(t("留空 = 系统「下载」文件夹")) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }

    if (showUpdateDialog) {
        val full = DesktopAutoUpdater.managerAvailable
        if (full != null) {
            UpdateAvailableDialog(
                info = full,
                progress = DesktopAutoUpdater.progress.value.let { if (it.second > 0) it else null },
                busy = DesktopAutoUpdater.busy.value,
                error = DesktopAutoUpdater.lastError.value,
                onUpdate = {
                    updateJob = scope.launch {
                        val ok = DesktopAutoUpdater.downloadGithubUpdate()
                        if (ok) DesktopAutoUpdater.installUpdate()
                    }
                },
                onLater = { showUpdateDialog = false },
                onSkip = {
                    scope.launch { DesktopAutoUpdater.skipThisVersion() }
                    showUpdateDialog = false
                },
                onCancel = {
                    updateJob?.cancel()
                    updateJob = null
                },
                usingMirror = DesktopAutoUpdater.usingMirror
            )
        }
    }
}

@Composable
private fun AboutCard() {
    var showDonation by remember { mutableStateOf(false) }
    SectionCard(overline = t("关于"), title = t("滴水记账")) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "Windows 桌面版 · 版本 $APP_VERSION_NAME（$APP_VERSION_CODE）",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                t("数据保存在本机 ~/.foldledger/；启用同步后会上传到自托管服务器"),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            // 捐赠入口：关于页底部一行，不打扰、不捆绑功能
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickableScale { showDonation = true }
                    .padding(vertical = 10.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("💝", fontSize = 20.sp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(t("支持作者"), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                    Text(t("滴水记账免费开源，自愿打赏"), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("›", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (showDonation) DonationQrDialog(onDismiss = { showDonation = false })
}

@Composable
private fun DesktopToolRow(
    emoji: String,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    // v16.3.1：与 Android GlobalToolRow 用同一套层级（emoji 20 / 标题 13 SemiBold /
    // 说明 11 / 尾部箭头 / 6dp 行距），并给每级文字固定行盒，两端行高不再随字体度量漂移。
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.sm))
            .clickableScale { onClick() }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(emoji, fontSize = 20.sp, lineHeight = 24.sp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                subtitle,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text("›", fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DesktopDivider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f),
        modifier = Modifier.padding(vertical = 2.dp)
    )
}

@Composable
actual fun rememberPlatformScreens(): PlatformScreens = DesktopPlatformScreens
