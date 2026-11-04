package com.foldledger.ui.screens

import android.Manifest
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foldledger.APP_VERSION_CODE
import com.foldledger.APP_VERSION_NAME
import com.foldledger.ServiceLocator
import com.foldledger.auto.AutoDiag
import com.foldledger.core.DripCore
import com.foldledger.data.db.Direction
import com.foldledger.data.prefs.AppSettings
import com.foldledger.data.prefs.ColorConvention
import com.foldledger.ui.haptic.AppHaptics
import com.foldledger.ui.theme.GlassButton
import com.foldledger.data.prefs.DarkMode
import com.foldledger.data.reminder.ReminderScheduler
import com.foldledger.data.reminder.needsNotificationPermission
import com.foldledger.ui.adaptive.AdaptiveScaffold
import com.foldledger.ui.adaptive.AppTab
import com.foldledger.ui.adaptive.FoldEnv
import com.foldledger.ui.components.GlassDialog
import com.foldledger.ui.components.AccentPill
import com.foldledger.ui.components.SectionCard
import com.foldledger.ui.theme.CoolGreen
import com.foldledger.ui.theme.Layout
import com.foldledger.ui.theme.LocalDirectionColors
import com.foldledger.ui.theme.pendingTone
import com.foldledger.ui.theme.Radius
import com.foldledger.ui.theme.Space
import com.foldledger.ui.theme.WarmRed
import com.foldledger.ui.theme.glassChip
import com.foldledger.ui.theme.glassSurface
import com.foldledger.ui.theme.wellSurface
import com.foldledger.util.Money
import com.foldledger.ui.vm.AppViewModel
import com.foldledger.ui.vm.BackupViewModel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    env: FoldEnv,
    appVm: AppViewModel,
    backupVm: BackupViewModel,
    /** v8.1.1 系统权限引导（小米管控：自启动/省电/通知使用权） */
    onOpenSystemAccess: () -> Unit = {},
    /** v9.1 账单导入（微信/支付宝 Excel/CSV → 流水） */
    onOpenBillImport: (Long) -> Unit = {},
    /** v9.4 全局回收站 */
    onOpenRecycleBin: () -> Unit = {},
    /** v9.5 更多设置（低频项收进二级页） */
    onOpenMoreSettings: () -> Unit = {},
    /** v9.3 全局能力入口（速记/助手/存钱目标）—— 这些能力跨账本，不该只在明细页里有 */
    onOpenSpeedEntry: (Long) -> Unit = {},
    onOpenAssistant: () -> Unit = {},
    onOpenGoal: (Long) -> Unit = {},
    /** v16.2.8 同步记录（收发明细 / 冲突） */
    onOpenSyncLog: () -> Unit = {},
    /** v3.2.0：外壳（顶栏 / 导航栏）已上提到 AppRoot，本页只画内容 */
    modifier: Modifier = Modifier
) {
    val settings by appVm.settings.collectAsStateWithLifecycle()
    val cards by appVm.ledgerCards.collectAsStateWithLifecycle()

    var confirmWipeRecords by remember { mutableStateOf(false) }
    var confirmWipeAll by remember { mutableStateOf(false) }
    // v8.1 回收站
    var confirmPurgeAll by remember { mutableStateOf(false) }

    val context = LocalContext.current

    // v3.2.0：外壳已上提到 AppRoot，这里直接画内容。`modifier` 已含顶栏与导航栏内边距。
    Box(modifier.fillMaxSize()) {
        val side = Layout.sidePadding(env.widthDp)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = side, end = side, top = Space.sm, bottom = Space.huge
            ),
            verticalArrangement = Arrangement.spacedBy(Space.gapSection)
        ) {
            // v9.3：全局能力入口 —— 一句话记账 / 记账助手 / 存钱目标 都跨账本，
            // 放在「我的」里作为全局入口，明细页的快捷行只是顺手的位置。
            item {
                SectionCard(overline = "全局", title = "随手记 · 助手 · 目标") {
                    GlobalToolRow(
                        emoji = "⚡",
                        title = "一句话记账",
                        subtitle = "说一句话记一笔，账本当场选",
                        onClick = { onOpenSpeedEntry(settings.lastLedgerId) }
                    )
                    SettingDivider()
                    GlobalToolRow(
                        emoji = "💡",
                        title = "记账助手",
                        subtitle = "全部账本的待办，一页说清",
                        onClick = { onOpenAssistant() }
                    )
                    SettingDivider()
                    GlobalToolRow(
                        emoji = "🎯",
                        title = "存钱目标",
                        subtitle = "所有账本的钱罐，攒钱不混进流水",
                        onClick = { onOpenGoal(settings.lastLedgerId) }
                    )
                }
            }


            // v13.8：自动记账从「更多设置」搬上来。
            // 归置原则一直是「每天会碰的留在首页，调一次不动的收进二级页」，
            // 自动记账属于前者 —— 它每天在后台自己记账，判错的时候要立刻能查。
            // 原来藏在 我的 → 更多设置 → 自动记账 → 捕获日志，四步。
            item {
                AutoBookkeepingCard(
                    context = context,
                    settings = settings,
                    appVm = appVm,
                    onOpenSystemAccess = onOpenSystemAccess
                )
            }

            item {
            SectionCard(overline = "主题", title = "外观") {
                LabeledRow("深色模式") {
                    ChipRow(
                        options = listOf(
                            DarkMode.FOLLOW_SYSTEM to "跟随系统",
                            DarkMode.LIGHT to "浅色",
                            DarkMode.DARK to "深色"
                        ),
                        selected = settings.darkMode,
                        onSelect = appVm::setDarkMode
                    )
                }
                Spacer(Modifier.height(14.dp))
                LabeledRow(
                    "动态取色（Material You）",
                    hint = "只换按钮/进度条的强调色；底色、语义色和玻璃材质不跟随 —— " +
                        "那三层是按固定色温标定过对比度的"
                ) {
                    Switch(
                        checked = settings.dynamicColor,
                        onCheckedChange = appVm::setDynamicColor
                    )
                }
                Spacer(Modifier.height(14.dp))
                // ⚠️ v13.9：「环境光晕」这一项**整项删除**（原本叫「纸纹质感」）。
                //
                // v13.8 把它改名后 hint 写的是"四团色光缓慢漂移，关掉就静止"——
                // 问题就在这句话本身：它只改"动不动"，底色、颗粒、明度一模一样，
                // 关掉之后用户看到的是"一片完全没变的屏幕"，只会怀疑自己没点中。
                // 而背景那四团色光也在 v13.9 整层退役了（理由见 AuroraBackdrop），
                // 所以这个开关已经没有任何可控制的对象。
                //
                // 设置页的判据始终是「一项设置必须改变一个用户看得见的东西」——
                // 不满足的就去掉，而不是留着占一行。
                Text(
                    "背景是静态的三段色温底色 + 一层极细颗粒（用来消除渐变色带），" +
                        "没有可调项。玻璃的透明度、棱边与投影都按固定值标定过对比度。",
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ---- v5.0 触感反馈 ----



            item { BackupSection(backupVm = backupVm, hasData = cards.isNotEmpty()) }

        // ---- M4.5 双端同步（自托管；与桌面端同一共享卡） ----
        item {
            SyncSectionCard(
                syncManager = ServiceLocator.syncManager,
                onOpenLog = onOpenSyncLog
            )
        }


        item {
            SectionCard(
                overline = "安全",
                title = "数据",
                subtitle = "账本默认仅保存在本机 SQLite；启用同步后上传到你自填的服务器"
            ) {
                // v9.4：升级成全局回收站（账本 + 存钱目标 + 备份文件）
                val trashCount by appVm.trashTotalCount.collectAsStateWithLifecycle()
                TrashRow(count = trashCount) { onOpenRecycleBin() }
                SettingDivider()
                // v9.1 账单导入：把微信/支付宝账单搬进来
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Radius.sm))
                        .clickable { onOpenBillImport(settings.lastLedgerId) }
                        .padding(vertical = 14.dp)
                ) {
                    Text("📥", fontSize = 15.sp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("导入账单", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "微信 / 支付宝导出的 Excel 或 CSV，一键变流水",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text("›", fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                SettingDivider()
                DangerRow("清空所有流水记录") { confirmWipeRecords = true }
                SettingDivider()
                DangerRow("清空全部数据（含账本与分类）") { confirmWipeAll = true }
                Spacer(Modifier.height(12.dp))
                Text(
                    "清空前建议先「导出备份」；恢复时把备份带回同一个目录即可一键找回。",
                    fontSize = 11.sp,
                    lineHeight = 17.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

            item {
                MoreSettingsRow { onOpenMoreSettings() }
            }


        }
    }

    if (confirmWipeRecords) {
        GlassDialog(
            onDismissRequest = { confirmWipeRecords = false },
            title = { Text("清空所有流水？") },
            text = { Text("账本与分类会保留，所有记账记录将被删除，此操作不可撤销。建议先导出备份。") },
            confirmButton = {
                TextButton(onClick = {
                    appVm.wipeRecords()
                    confirmWipeRecords = false
                }) { Text("确认清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmWipeRecords = false }) { Text("取消") }
            }
        )
    }

    if (confirmWipeAll) {
        GlassDialog(
            onDismissRequest = { confirmWipeAll = false },
            title = { Text("清空全部数据？") },
            text = { Text("所有账本、分类和流水都会被删除，应用回到空白状态（预置场景模板仍在，可随时重新创建）。此操作不可撤销，建议先导出备份。") },
            confirmButton = {
                TextButton(onClick = {
                    appVm.wipeAllData()
                    confirmWipeAll = false
                }) { Text("确认清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmWipeAll = false }) { Text("取消") }
            }
        )
    }

    // ================= v8.1 账本回收站 =================


    if (confirmPurgeAll) {
        GlassDialog(
            onDismissRequest = { confirmPurgeAll = false },
            title = { Text("清空回收站？") },
            text = { Text("回收站里的所有账本和它们的流水会**永久删除**，无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    appVm.purgeTrashed()
                    confirmPurgeAll = false
                }) { Text("全部删掉", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmPurgeAll = false }) { Text("取消") }
            }
        )
    }
}

// ============================================================


// ================= 低频设置（v9.5 移到「更多设置」二级页） =================

/** 触感反馈 */
@Composable
internal fun HapticsCard(settings: AppSettings, appVm: AppViewModel) {
    val ctx = LocalContext.current
    SectionCard(
        overline = "手感",
        title = "触感反馈",
        subtitle = "数字键盘每按一下都会给一次短震动，力度按你的习惯调"
    ) {
        LabeledRow("震动强度") {
            ChipRow(
                options = listOf(
                    com.foldledger.data.prefs.HapticStrength.OFF to "关",
                    com.foldledger.data.prefs.HapticStrength.LIGHT to "轻",
                    com.foldledger.data.prefs.HapticStrength.MEDIUM to "中",
                    com.foldledger.data.prefs.HapticStrength.STRONG to "强"
                ),
                selected = settings.hapticStrength,
                onSelect = appVm::setHapticStrength
            )
        }
        Spacer(Modifier.height(14.dp))
        LabeledRow("数字键盘震动", hint = "记账输金额时的按键反馈") {
            Switch(
                checked = settings.hapticKeypad,
                onCheckedChange = appVm::setHapticKeypad
            )
        }
        Spacer(Modifier.height(14.dp))
        GlassButton(
            text = "试一下手感",
            onClick = { AppHaptics.preview() },
            enabled = settings.hapticStrength != com.foldledger.data.prefs.HapticStrength.OFF,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** 记账偏好（收支配色） */
@Composable
internal fun PreferenceCard(settings: AppSettings, appVm: AppViewModel) {
    SectionCard(
        overline = "偏好",
        title = "记账偏好",
        subtitle = "中国用户有两种收支配色习惯，按你的手感来"
    ) {
        LabeledRow("收支配色") {
            ChipRow(
                options = listOf(
                    ColorConvention.LEDGER to "支出红 · 收入绿",
                    ColorConvention.MARKET to "涨红跌绿"
                ),
                selected = settings.colorConvention,
                onSelect = appVm::setColorConvention
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ColorSample(
                "支出", "-",
                if (settings.colorConvention == ColorConvention.LEDGER) WarmRed else CoolGreen
            )
            ColorSample(
                "收入", "+",
                if (settings.colorConvention == ColorConvention.LEDGER) CoolGreen else WarmRed
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "展开态的分栏宽度不再手动设置：已按 15 档展开宽度（653~1366dp）离线搜索比对，" +
                "定为「左栏 min(368dp, 可分栏宽度 × 48%)」，并把 800dp 以下的侧栏换成底部导航。" +
                "理由与数据见 README。",
            fontSize = 11.sp,
            lineHeight = 16.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 折叠屏适配实时状态 */
@Composable
internal fun FoldAdaptationCard(env: FoldEnv) {

    SectionCard(
        overline = "设备",
        title = "折叠屏适配状态（实时）",
        subtitle = "改变折叠形态，这里的数值会立即刷新"
    ) {
        InfoRow("窗口尺寸", "${env.widthDp} × ${env.heightDp} dp")
        InfoRow("宽度断点", env.widthSizeClass.toString())
        InfoRow("高度断点", env.heightSizeClass.toString())
        InfoRow("设备姿态", env.postureLabel)
        InfoRow("可折叠设备", if (env.isFoldable) "是" else "否")
        InfoRow("铰链分割窗口", if (env.isSeparating) "是" else "否")
        InfoRow("铰链占比", "${(env.hingeFraction * 100).roundToInt()}%")
        InfoRow("布局策略", layoutStrategy(env))
    }
}

private fun layoutStrategy(env: FoldEnv): String = when {
    env.isTabletop -> "上下分屏：内容区 / 操作区隔离，控件不跨铰链"
    env.isBook -> "左右分栏：避开垂直铰链"
    // v12.1.4：内外屏统一 —— 导航恒为底部悬浮胶囊，双栏只发生在展开态内容区
    env.twoPane -> "Master-Detail 双栏 + 底部悬浮胶囊（内外屏统一）"
    else -> "单栏 + 底部悬浮胶囊"
}

@Composable
private fun LabeledRow(label: String, hint: String? = null, content: @Composable () -> Unit) {
    Column {
        Text(
            label,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (hint != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                hint,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun ChipRow(
    options: List<Pair<Int, String>>,
    selected: Int,
    onSelect: (Int) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            val active = value == selected
            // v13.9：选中态不再是 `primaryContainer` 那块**实色**（用户明确否过"实色选中块"）。
            // 现在选中 = 被主色染浓一档的玻璃（2.6×），未选中 = 一层墨色凹槽。
            // 两者的区别是"浓度 + 投影"，不是"有色 / 没色"。
            Box(
                Modifier
                    .then(
                        if (active) Modifier.glassChip(
                            shape = RoundedCornerShape(50),
                            tint = MaterialTheme.colorScheme.primary,
                            strength = 2.6f,
                            elevation = 2.dp
                        ) else Modifier
                            .clip(RoundedCornerShape(50))
                            .background(wellSurface())
                    )
                    // v5.3：先裁剪再挂点击，涟漪才不会露出方角
                    .clip(RoundedCornerShape(50))
                    .clickable { onSelect(value) }
            ) {
                Text(
                    label,
                    fontSize = 13.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    // v13.9：选中态是"主色染膜的玻璃"，不是 primaryContainer 实色块，
                    // 所以字回到 onSurface —— onPrimaryContainer 是给实色底用的。
                    color = if (active) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                )
            }
        }
    }
}

@Composable
private fun ColorSample(label: String, sign: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(50))
                .background(color)
        )
        Spacer(Modifier.width(6.dp))
        Text("$label $sign¥128.00", fontSize = 13.sp, color = color, fontWeight = FontWeight.Medium)
    }
}

/**
 * Rust 核心运行时自检（v4.3.0）。
 *
 * 拿几个已知答案的样本过一遍 `DripCore`，确认金额格式化 / 搜索匹配 / 备份校验三条链路
 * 都在正常工作 —— 同时也就验证了 `.so` 是否真的装载成功、JNI 符号是否对得上。
 * 结果直接显示在「关于」里，用户和开发者都能一眼确认核心引擎的健康状况。
 */
private fun rustSelfCheck(): String {
    val moneyOk = DripCore.formatCents(12_345_678L, true) == "¥123,456.78" &&
        DripCore.toPlain(1_234L) == "12.34"
    val searchOk = DripCore.matchesQuery("餐饮", "午饭", 3_500L, "午饭") &&
        DripCore.matchesQuery("餐饮", "午饭", 3_500L, "35") &&
        !DripCore.matchesQuery("餐饮", "午饭", 3_500L, "打车")
    val parseOk = DripCore.parseToCents("12.345") == 1235L
    val allOk = moneyOk && searchOk && parseOk
    return if (allOk) {
        "通过（金额 / 搜索 / 解析）"
    } else {
        "异常（金额=${moneyOk} 搜索=${searchOk} 解析=${parseOk}）"
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            label,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(104.dp)
        )
        Text(
            value,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}

/** v9.5「更多设置」入口行 */
/**
 * 设置项之间的分割线（v11）。
 * 卡片里的一串设置项用"细线"分成行，而不是每行垫一块浅色底 ——
 * 前者是印刷品的表格，后者是每隔一行刷一道灰漆。
 */
@Composable
private fun SettingDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant)
    )
}

@Composable
private fun MoreSettingsRow(onClick: () -> Unit) {
    // v11：设置项不再是"卡里再套一层色块"，就是一行字 + 一个箭头
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.sm))
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 16.dp)
    ) {
        Text("⚙️", fontSize = 15.sp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("更多设置", fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(2.dp))
            Text(
                "提醒 · 自动记账 · 手感 · 配色 · 关于",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text("›", fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** v9.3 全局工具入口行 */
@Composable
private fun GlobalToolRow(
    emoji: String,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.sm))
            .clickable { onClick() }
            .padding(vertical = 6.dp)
    ) {
        Text(emoji, fontSize = 20.sp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(
                subtitle,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text("›", fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** v8.1 自动记账的授权状态行 */
@Composable
private fun PermissionRow(
    label: String,
    hint: String,
    granted: Boolean,
    actionLabel: String,
    onAction: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(if (granted) "✅" else "⬜", fontSize = 13.sp)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(hint, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (granted) {
            Text("已授权", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
        } else {
            // v13.9：实色主色胶囊 → 主色染色的玻璃胶囊
            AccentPill(text = actionLabel, onClick = onAction, fontSize = 11)
        }
    }
}

/** v8.1 回收站入口行 */
@Composable
private fun TrashRow(count: Int, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.sm))
            .clickable { onClick() }
            .padding(vertical = 14.dp)
    ) {
        Text("🗑", fontSize = 15.sp)
        Spacer(Modifier.width(10.dp))
        Text(
            "回收站",
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
        Text(
            if (count > 0) "$count 个待恢复 ›" else "空 ›",
            fontSize = 13.sp,
            color = if (count > 0) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun DangerRow(label: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.sm))
            .clickable { onClick() }
            .padding(vertical = 13.dp)
    ) {
        Text(
            label,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.error,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
        Text("›", fontSize = 17.sp, color = MaterialTheme.colorScheme.error.copy(alpha = 0.6f))
    }
}

/** 每日记账提醒（v2.1）：开关 + 时间 */
@Composable
internal fun ReminderCard(
    enabled: Boolean,
    hour: Int,
    minute: Int,
    onToggle: (Boolean) -> Unit,
    onTimeChange: (Int, Int) -> Unit
) {
    SectionCard(
        overline = "提醒",
        title = "记账提醒",
        subtitle = "每天定时提醒你记一笔"
    ) {
        LabeledRow("每日提醒") {
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
        if (enabled) {
            Spacer(Modifier.height(12.dp))
            Text("提醒时间", fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = hour.toString(),
                    onValueChange = { v ->
                        val h = v.filter { it.isDigit() }.toIntOrNull()
                        if (h != null && h in 0..23) onTimeChange(h, minute)
                    },
                    label = { Text("时") },
                    singleLine = true,
                    modifier = Modifier.width(80.dp)
                )
                Text(":", fontSize = 20.sp, modifier = Modifier.padding(horizontal = 8.dp))
                OutlinedTextField(
                    value = minute.toString(),
                    onValueChange = { v ->
                        val m = v.filter { it.isDigit() }.toIntOrNull()
                        if (m != null && m in 0..59) onTimeChange(hour, m)
                    },
                    label = { Text("分") },
                    singleLine = true,
                    modifier = Modifier.width(80.dp)
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Android 13+ 首次开启会请求通知权限；提醒由系统调度，可能在设定时间附近略有偏差。",
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 自动记账（v8.1 起；v13.8 从「更多设置」搬到「我的」首页）。
 *
 * ### 为什么搬家
 * 这一块原本在二级页（我的 → 更多设置 → 自动记账），而「捕获日志」又在这张卡里 ——
 * 加起来是**四步**。但自动记账是"每天在后台自己干活、偶尔判错一次"的功能：
 * 判错的时候用户第一反应是"它刚才为什么记了那一笔"，这时候要他翻四个页面，
 * 他就不看了，下次还是不知道为什么。
 *
 * 归置原则本来写的是「每天会碰的留在首页，调一次就不动的收进二级页」——
 * 自动记账属于前者，只是当初被当成"设置"了。
 *
 * ### 为什么长这样
 * 首页的卡要短。所以这里做了三件减法：
 *   1. 两段说明压成一句（细则留在二级页的旧位置没必要，用户读一次就够）；
 *   2. 「系统权限引导」「捕获日志」并排成两枚方块，各占一半宽；
 *   3. 加一行**最近一条捕获**：大多数"为什么没记/为什么记了"看一眼这行就够，
 *      不用进日志。日志是给"这行还是解释不了"的情况留的。
 */
@Composable
internal fun AutoBookkeepingCard(
    context: Context,
    settings: AppSettings,
    appVm: AppViewModel,
    onOpenSystemAccess: () -> Unit
) {

    // v9.7.2：短信权限改成「回前台重查」—— 原来用 remember 冻在首次组合，
    // 用户去系统设置里授完权回来，这里仍显示「授予权限」，只能杀掉 App 才刷新。
    var smsState by remember { mutableStateOf(false) }
    val smsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> smsState = granted }
    var notifGranted by remember { mutableStateOf(false) }
    // v13.8：最近一条捕获。同样在回前台时重查 —— 用户从通知栏点回 App 时
    // 最想看到的正是"刚才那笔到底记了没有"。
    var latest by remember { mutableStateOf<AutoDiag.Entry?>(null) }
    var diagCount by remember { mutableStateOf(0) }
    var diagOpen by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        smsState = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.RECEIVE_SMS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        notifGranted = NotificationManagerCompat
            .getEnabledListenerPackages(context)
            .contains(context.packageName)
        val recent = runCatching { AutoDiag.recent(context, 40) }.getOrElse { emptyList() }
        latest = recent.firstOrNull()
        diagCount = recent.size
        onPauseOrDispose { }
    }

    SectionCard(
        overline = "自动化",
        title = "自动记账",
        subtitle = "支付通知 / 银行动账短信自动进「自动记账」账本，确认后移去正式账本"
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("自动捕获", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text(
                    "关掉后不再自动进账，已记的条目保留",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = settings.autoBookkeepingEnabled,
                onCheckedChange = appVm::setAutoBookkeepingEnabled
            )
        }
        Spacer(Modifier.height(8.dp))
        PermissionRow(
            label = "通知使用权",
            hint = "读取微信/支付宝/云闪付的支付通知",
            granted = notifGranted,
            actionLabel = "去授权"
        ) {
            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        Spacer(Modifier.height(6.dp))
        PermissionRow(
            label = "短信权限",
            hint = "接收银行动账短信",
            granted = smsState,
            actionLabel = "授予权限"
        ) {
            smsLauncher.launch(android.Manifest.permission.RECEIVE_SMS)
        }

        // ---------- 最近一条 ----------
        // 这一行是这张卡的重点：绝大多数判定问题看一眼就明白，
        // 不用进日志逐条翻。
        val last = latest
        if (last != null) {
            val dc = LocalDirectionColors.current
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (last.accepted) "最近一次 · 已入账" else "最近一次 · 未入账",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (last.accepted) dc.income else pendingTone()
                )
                Spacer(Modifier.weight(1f))
                Text(
                    AutoDiag.formatTime(last.at),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                last.title.ifBlank { last.text }.ifBlank { last.channel },
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(1.dp))
            Text(
                buildString {
                    val amt = last.amountCents
                    if (last.accepted && amt != null) {
                        append(if (last.direction == Direction.INCOME) "+" else "−")
                        append(Money.format(amt, symbol = false))
                        append(" · ")
                    }
                    // 「已入账·微信」这种 note 对"为什么记了"是有信息的，照原样透出
                    append(last.note)
                },
                fontSize = 11.sp,
                lineHeight = 15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Spacer(Modifier.height(12.dp))
            Text(
                if (settings.autoBookkeepingEnabled) {
                    "还没有捕获记录。付一笔、或等一条银行短信，这里就会出现。"
                } else {
                    "自动捕获是关的 —— 打开之后支付通知才会进账。"
                },
                fontSize = 11.sp,
                lineHeight = 15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // ---------- 两个入口并排 ----------
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CaptureActionTile(
                emoji = "🧾",
                title = "捕获日志",
                hint = if (diagCount > 0) "最近 $diagCount 条判定" else "看每次为什么",
                modifier = Modifier.weight(1f)
            ) { diagOpen = true }
            CaptureActionTile(
                emoji = "🧭",
                title = "权限排查",
                hint = "小米管控逐项解锁",
                modifier = Modifier.weight(1f)
            ) { onOpenSystemAccess() }
        }

        // ---------- 一句说明 ----------
        Spacer(Modifier.height(10.dp))
        Text(
            "全部解析在本机完成，不上传任何数据。想停用请用上面的开关 —— " +
                "删掉「自动记账」账本不会停用，下次支付会自动重建。",
            fontSize = 11.sp,
            lineHeight = 16.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    if (diagOpen) {
        AutoDiagDialog(context = context, onDismiss = { diagOpen = false })
    }
}

/**
 * 首页卡里的半宽入口块（v13.8）。
 *
 * 原来的两个入口是两条通栏的 tinted surface（🧭 系统权限引导、🧾 捕获日志），
 * 各占 56dp，加起来 112dp —— 在二级页里没问题，搬到首页就成了"卡比内容长"。
 * 并排之后高度减半，而且两个动作在语义上是并列的（都是"出问题时该点哪"）。
 */
@Composable
private fun CaptureActionTile(
    emoji: String,
    title: String,
    hint: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    // v13.9：入口块从"主色 9% 平涂"改成玻璃。
    // ⚠️ 这里**不能**用 glassChip：它给的是白膜，而这块是"卡内的次级控件"。
    // 白膜铺在卡里 = 比卡更白的一块补丁（实测和卡底只差 1 级，等于没有）；
    // 卡内的次级控件必须**凹下去**（wellSurface 墨 6.5%）再挂棱，才是"玻璃槽"。
    // 面积越大浓度越低这条也适用：9% → 6.5%，让它退回"次级"。
    Box(
        modifier
            .glassSurface(
                shape = RoundedCornerShape(Radius.sm),
                elevation = 0.dp,
                fill = wellSurface(),
                specular = 0.55f
            )
            .clickable { onClick() }
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 9.dp)) {
            Text(emoji, fontSize = 13.sp)
            Spacer(Modifier.height(3.dp))
            Text(
                title,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1
            )
            Text(
                hint,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** v10.6 捕获日志弹窗：最近 100 条通知/短信的判定结果 */
@Composable
private fun AutoDiagDialog(context: Context, onDismiss: () -> Unit) {
    var entries by remember { mutableStateOf(AutoDiag.recent(context, 100)) }
    val scope = rememberCoroutineScope()

    GlassDialog(
        onDismissRequest = onDismiss,
        title = { Text("捕获日志") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                // v12.1：日志降噪后这里只会有"和钱有关"的条目
                Text(
                    "只记录与金额相关的通知 / 短信。已入账的那条如果记错了，点「撤销」直接删掉。",
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 10.dp)
                )
                if (entries.isEmpty()) {
                    Text(
                        "还没有捕获记录。\n\n" +
                            "开着自动记账，随便付一笔或等一条银行短信，这里就会出现记录。" +
                            "如果一笔支付没记上，来这里看它被拒的原因。",
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        entries.forEach { e ->
                            Surface(
                                shape = RoundedCornerShape(Radius.sm),
                                color = if (e.accepted)
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            ) {
                                Column(Modifier.padding(10.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            if (e.accepted) "已记账" else "未记账",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (e.accepted)
                                                MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            "${e.channel} · ${AutoDiag.formatTime(e.at)}",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(Modifier.weight(1f))
                                        if (e.amountCents != null) {
                                            // 金额放在最右边：一眼看出"这条记了多少钱、什么方向"
                                            // 颜色跟着用户的「收支配色」偏好走（涨红跌绿的人不该在这里看到绿收）
                                            val dc = LocalDirectionColors.current
                                            Text(
                                                (if (e.direction == Direction.INCOME) "+" else "−") +
                                                    Money.format(e.amountCents, symbol = false),
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = if (e.direction == Direction.INCOME) dc.income else dc.expense
                                            )
                                        } else {
                                            Text(
                                                e.note,
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                    if (e.title.isNotBlank()) {
                                        Text(
                                            e.title,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.padding(top = 3.dp)
                                        )
                                    }
                                    // v13.8：**已入账也要显示 note**。
                                    // 原来只有"未入账"才显示原因，于是一个最重要的分支
                                    // 完全看不见：「重复已跳过」—— 用户明明付了钱、日志里
                                    // 也有一条"已记账"，但金额那栏是空的（amountCents 有值
                                    // 但 txnId 为 null），看起来像记了又像没记。
                                    if (e.accepted && e.note.isNotBlank()) {
                                        Text(
                                            e.note,
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    if (e.text.isNotBlank()) {
                                        Text(
                                            e.text,
                                            fontSize = 11.sp,
                                            lineHeight = 15.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 3,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    // v12.1：记错了当场撤销 —— 不用再到明细页里翻出那一条删掉
                                    if (e.accepted && e.txnId != null) {
                                        Row(
                                            Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.End
                                        ) {
                                            Text(
                                                "撤销这笔",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = MaterialTheme.colorScheme.error,
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(Radius.sm))
                                                    .clickable {
                                                        val id = e.txnId ?: return@clickable
                                                        scope.launch {
                                                            runCatching {
                                                                ServiceLocator.repository
                                                                    .deleteTransaction(id)
                                                            }
                                                            AutoDiag.remove(context, e)
                                                            entries = entries.filterNot {
                                                                it.at == e.at && it.pkg == e.pkg
                                                            }
                                                        }
                                                    }
                                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                AutoDiag.clear(context)
                entries = emptyList()
            }) { Text("清空") }
            TextButton(onClick = {
                entries = AutoDiag.recent(context, 100)
            }) { Text("刷新") }
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

/** 关于 */
@Composable
internal fun AboutCard() {
    SectionCard(overline = "信息", title = "关于") {
        InfoRow("应用名称", "滴水记账")
        InfoRow("版本", "$APP_VERSION_NAME ($APP_VERSION_CODE)")
        InfoRow("技术栈", "Kotlin 2.0 · Jetpack Compose · Material 3 · Room")
        // v4.3.0：Rust 核心真实状态 + 运行时自检（一眼看出 .so 是否生效）
        InfoRow("核心引擎", if (DripCore.nativeAvailable) "Rust 原生（JNI）" else "Kotlin 回退")
        InfoRow("核心自检", rustSelfCheck())
        InfoRow("自适应", "WindowManager FoldingFeature + WindowSizeClass")
        InfoRow("备份格式", ".foldledger.json 可恢复 + .xlsx 可阅读")
        InfoRow("最低版本", "Android 8.0 (API 26) · 目标 Android 15 (API 35)")
    }
}

