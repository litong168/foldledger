package com.foldledger.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.foldledger.data.db.GoalEntity
import com.foldledger.ui.adaptive.AdaptiveScaffold
import com.foldledger.ui.adaptive.FoldEnv
import com.foldledger.ui.anim.clickableScale
import com.foldledger.ui.components.EmptyHint
import com.foldledger.ui.components.GlassDialog
import com.foldledger.ui.components.SectionCard
import com.foldledger.ui.theme.Layout
import com.foldledger.ui.theme.glassChip
import com.foldledger.ui.theme.glassSelectChip
import com.foldledger.ui.theme.dividerBrush
import com.foldledger.ui.theme.rememberAnimatedPaneWidth
import com.foldledger.ui.vm.GoalViewModel
import com.foldledger.util.Money
import com.foldledger.util.SavingsGoal
import java.util.concurrent.TimeUnit

/**
 * 存钱目标（v9.2）——心愿罐。
 *
 * 记账之外的那一半：为将来攒钱。每个目标一个进度条，攒够就达成。
 * 目标**不写进流水**（攒钱不是支出），所以不影响预算与月度结余的统计。
 */
@Composable
fun GoalScreen(
    env: FoldEnv,
    /** 新建目标时默认归属的账本（0 = 让用户选） */
    defaultLedgerId: Long = 0L,
    onBack: () -> Unit
) {
    val vm: GoalViewModel = viewModel(
        key = "goal-global",
        factory = GoalViewModel.factory(defaultLedgerId)
    )
    val goalsOrNull by vm.goals.collectAsStateWithLifecycle()
    val goals = goalsOrNull.orEmpty()
    val ledgers by vm.ledgers.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }
    var depositing by remember { mutableStateOf<GoalEntity?>(null) }
    // v9.4：删除 → 二次确认 → 进回收站
    var deleting by remember { mutableStateOf<GoalEntity?>(null) }
    // v9.7：编辑目标
    var editing by remember { mutableStateOf<GoalEntity?>(null) }

    // 账本 id → 名称（卡片上标出目标属于哪个账本）
    // v9.7：null = 独立目标（不显示账本名）
    val ledgerNameOf: (Long?) -> String = { lid ->
        if (lid == null) "" else ledgers.firstOrNull { it.id == lid }?.name.orEmpty()
    }

    AdaptiveScaffold(
        env = env,
        title = "存钱目标",
        subtitle = "全部账本 · 为将来攒下的每一笔",
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
                    GoalCreateCard { creating = true }
                    Spacer(Modifier.height(12.dp))
                    goals.forEach { g ->
                        GoalCard(
                            g,
                            ledgerName = ledgerNameOf(g.ledgerId),
                            onDeposit = { depositing = g },
                            onDelete = { deleting = g },
                            onEdit = { editing = g }
                        )
                    }
                    if (goalsOrNull != null && goals.isEmpty()) {
                        Spacer(Modifier.height(20.dp))
                        EmptyHint(
                            emoji = "🎯",
                            title = "还没有存钱目标",
                            subtitle = "定一个目标：换手机、旅行、应急金…"
                        )
                    }
                    Spacer(Modifier.height(28.dp))
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
                    GoalTipsCard(goals)
                    Spacer(Modifier.height(28.dp))
                }
            }
        } else {
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
                item { GoalCreateCard { creating = true } }
                if (goalsOrNull != null && goals.isEmpty()) {
                    item {
                        EmptyHint(
                            emoji = "🎯",
                            title = "还没有存钱目标",
                            subtitle = "定一个目标：换手机、旅行、应急金…"
                        )
                    }
                } else {
                    // v9.7.1 修：原来用 items(count) + goals[i] 按下标取，删除目标后列表变短
                    // 会 IndexOutOfBounds；改成按对象 + key
                    items(goals, key = { "goal-${it.id}" }) { g ->
                        GoalCard(
                            g,
                            ledgerName = ledgerNameOf(g.ledgerId),
                            onDeposit = { depositing = g },
                            onDelete = { deleting = g },
                            onEdit = { editing = g }
                        )
                    }
                }
            }
        }
    }

    if (creating) {
        GoalEditDialog(
            goal = null,
            ledgers = ledgers,
            onDismiss = { creating = false },
            onSubmit = { name, emoji, cents, target, lid ->
                vm.create(name, emoji, cents, target, lid)
                creating = false
            }
        )
    }

    // v9.7：编辑目标
    editing?.let { g ->
        GoalEditDialog(
            goal = g,
            ledgers = ledgers,
            onDismiss = { editing = null },
            onSubmit = { name, emoji, cents, target, lid ->
                vm.update(g, name, emoji, cents, target, lid)
                editing = null
            }
        )
    }

    deleting?.let { goal ->
        GlassDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除「${goal.name}」？") },
            text = {
                Text(
                    "目标会先进**回收站**，随时能还原；\n" +
                        "在回收站里再彻底删除，就真的没了。",
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.trash(goal)
                    deleting = null
                }) { Text("移到回收站", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("再想想") }
            }
        )
    }

    depositing?.let { goal ->
        DepositDialog(
            goal = goal,
            onDismiss = { depositing = null },
            onDeposit = { cents ->
                vm.deposit(goal, cents)
                depositing = null
            },
            onWithdraw = { cents ->
                vm.withdraw(goal, cents)
                depositing = null
            }
        )
    }
}

@Composable
private fun GoalCreateCard(onClick: () -> Unit) {
    // v13.9：主色 8% 平涂 → 主色染膜的玻璃入口块（字回 onSurface）
    Box(
        Modifier
            .fillMaxWidth()
            .glassChip(
                shape = RoundedCornerShape(14.dp),
                tint = MaterialTheme.colorScheme.primary,
                strength = 0.7f
            )
            .clip(RoundedCornerShape(14.dp))
            .clickableScale(pressedScale = 0.97f, clipShape = RoundedCornerShape(14.dp)) { onClick() }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Text("🎯", fontSize = 17.sp)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "新建存钱目标",
                    // v16.3.1：入口卡主文本统一 15.sp + 固定行盒
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    "定个金额和期限，慢慢填满",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text("›", fontSize = 15.sp, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun GoalCard(
    goal: GoalEntity,
    ledgerName: String,
    onDeposit: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit
) {
    val pct = SavingsGoal.percent(goal.savedCents, goal.targetCents)
    val done = SavingsGoal.reached(goal.savedCents, goal.targetCents)
    val left = SavingsGoal.remaining(goal.savedCents, goal.targetCents)
    val days = SavingsGoal.daysLeft(goal.targetDate, System.currentTimeMillis())
    val monthly = SavingsGoal.monthlyNeeded(
        goal.savedCents, goal.targetCents, goal.targetDate, System.currentTimeMillis()
    )

    // v16.3.1：原来传空串给 title/subtitle，SectionHeader 仍会渲染两个空 Text + 约 40dp 空白；
    // 现在不传标题，卡内自造的 15.sp 主文本就是唯一层级，头部不再留一段假空白。
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(goal.emoji, fontSize = 24.sp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    goal.name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    buildString {
                        if (ledgerName.isNotBlank()) append("【$ledgerName】")
                        append(Money.format(goal.savedCents))
                        append(" / ")
                        append(Money.format(goal.targetCents))
                        if (goal.targetDate > 0) {
                            append(if (days >= 0) " · 还剩 $days 天" else " · 已过期")
                        }
                    },
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (done) {
                Text(
                    "✅ 已达成",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                Text(
                    "$pct%",
                    fontSize = 17.sp,
                    lineHeight = 24.sp,
                    // 规范：玻璃上不用 ExtraBold，层级靠字号差 + 墨色深浅
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        // 进度条
        Box(
            Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
        ) {
            Box(
                Modifier
                    .fillMaxWidth(SavingsGoal.progress(goal.savedCents, goal.targetCents))
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!done) {
                Text(
                    if (monthly > 0) "还差 ${Money.format(left)} · 每月存 ${Money.format(monthly)}"
                    else "还差 ${Money.format(left)}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
            } else {
                Text(
                    "已攒够，达成目标 🎉",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
            }
            SmallButton("存入") { onDeposit() }
            Spacer(Modifier.width(6.dp))
            // v9.7：编辑（改名 / 改金额 / 改期限 / 改归属）
            // v13.9：onSurface 7% 平涂 → 玻璃小胶囊
            Box(
                Modifier
                    .glassChip(shape = RoundedCornerShape(50), strength = 0.6f)
                    .clickableScale(pressedScale = 0.93f, clipShape = RoundedCornerShape(50)) {
                        onEdit()
                    }
            ) {
                Text(
                    "✏️",
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
            // v9.9.5：删除离「存入」远一点 —— 三个小胶囊并排时最容易把删除当成编辑点到
            Spacer(Modifier.width(14.dp))
            // v9.4：删除（先进回收站，可还原）
            Box(
                Modifier
                    .glassChip(shape = RoundedCornerShape(50), strength = 0.6f)
                    .clickableScale(pressedScale = 0.93f, clipShape = RoundedCornerShape(50)) {
                        onDelete()
                    }
            ) {
                Text(
                    "🗑",
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun GoalTipsCard(goals: List<GoalEntity>) {
    SectionCard(title = "关于存钱目标", subtitle = "只记目标，不动流水") {
        Text(
            "攒钱不是支出，所以**不写进流水** —— 你的预算、月度结余、统计口径都不会被它影响。\n\n" +
                "目标默认**独立于账本**：「换台相机」「存个应急金」本来就跟哪个账本无关。\n\n" +
                "想按账本分类时，可以给它打个归属标签（可选），纯属方便筛选；" +
                "账本删了，目标也只会解绑，不会跟着没。\n\n" +
                "设了期限就会算出「每月要存多少」，没达成的目标会一直提醒你。",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SmallButton(label: String, onClick: () -> Unit) {
    // v13.9：主色 12% 平涂 → 主色染膜玻璃
    Box(
        Modifier
            .glassChip(
                shape = RoundedCornerShape(50),
                tint = MaterialTheme.colorScheme.primary,
                strength = 0.9f
            )
            .clickableScale(pressedScale = 0.93f, clipShape = RoundedCornerShape(50)) { onClick() }
    ) {
        Text(
            label,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun GoalEditDialog(
    /** null = 新建；非 null = 编辑已有目标 */
    goal: GoalEntity?,
    ledgers: List<com.foldledger.data.db.LedgerEntity>,
    onDismiss: () -> Unit,
    onSubmit: (name: String, emoji: String, cents: Long, targetDate: Long, ledgerId: Long?) -> Unit
) {
    // ⚠️ v9.7.2：全部 remember 都带上 goal 作 key —— 这个弹窗在同一个组合槽位里
    // 会被"新建"和"编辑不同目标"复用，不带 key 的话打开 B 目标看到的是 A 的名字和金额。
    var name by remember(goal) { mutableStateOf(goal?.name ?: "") }
    var amount by remember(goal) { mutableStateOf(goal?.let { Money.centsToPlain(it.targetCents) } ?: "") }
    var emoji by remember(goal) { mutableStateOf(goal?.emoji ?: "🎯") }
    var months by remember(goal) { mutableStateOf(0) }
    // v9.6：期限给自由度 —— 预设之外还能自选具体日期
    var customDate by remember(goal) { mutableStateOf(goal?.targetDate?.takeIf { it > 0L }) }
    var showDatePicker by remember { mutableStateOf(false) }
    // v9.7：归属账本是**可选标签**，默认不关联（null = 独立目标）
    var selectedLedger by remember(goal) { mutableStateOf<Long?>(goal?.ledgerId) }

    val emojis = listOf("🎯", "📱", "✈️", "🏠", "🚗", "💻", "🎓", "🎁", "🛟")

    val now = System.currentTimeMillis()
    val targetDate: Long = SavingsGoal.targetDateFrom(now, months, customDate)
    val centsNow = Money.parseToCents(amount) ?: 0L
    val perMonth = SavingsGoal.monthlyNeeded(0L, centsNow, targetDate, now)

    GlassDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (goal == null) "新建存钱目标" else "编辑目标") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("目标名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it.filter { ch -> ch.isDigit() || ch == '.' } },
                    label = { Text("目标金额（元）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                Text("图标", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    emojis.forEach { e ->
                        Box(
                            Modifier
                                .glassSelectChip(active = emoji == e)
                                .clickableScale(pressedScale = 0.9f) { emoji = e }
                        ) {
                            Text(
                                e,
                                fontSize = 15.sp,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                // v9.7：归属可选，默认"不关联账本"
                Text("归属账本（可选）", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(2.dp))
                Text(
                    "目标默认独立：攒的钱不写进流水，关联只是为了筛选方便",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    PickChip("不关联", selectedLedger == null) { selectedLedger = null }
                    ledgers.forEach { l ->
                        PickChip("${l.emoji} ${l.name}", selectedLedger == l.id) { selectedLedger = l.id }
                    }
                }
                Spacer(Modifier.height(12.dp))
                // v9.6：期限 —— 选中态一眼可见 + 预设之外可自选日期
                Text("期限", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        0 to "不设",
                        1 to "1个月",
                        3 to "3个月",
                        6 to "6个月",
                        12 to "1年",
                        24 to "2年",
                        36 to "3年"
                    ).forEach { (m, label) ->
                        val active = customDate == null && months == m
                        PickChip(label, active) {
                            months = m
                            customDate = null
                        }
                    }
                    PickChip("自定义日期", customDate != null) { showDatePicker = true }
                }
                if (name.isBlank() || centsNow <= 0L) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "填好名称和金额就能" + if (goal == null) "创建" else "保存",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (targetDate > 0L) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "截止 ${formatDate(targetDate)}" +
                            if (centsNow > 0L) " · 每月要存 ${Money.format(perMonth)}"
                            else " · 填了金额就算每月要存多少",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else if (centsNow > 0L) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "不设期限 · 按自己的节奏存",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            // v9.7.1：条件不满足时禁用按钮（原来点了毫无反应，用户不知道缺什么）
            val canSubmit = name.isNotBlank() && centsNow > 0L
            TextButton(
                enabled = canSubmit,
                onClick = {
                    if (canSubmit) {
                        onSubmit(name.trim(), emoji, centsNow, targetDate, selectedLedger)
                    }
                }
            ) { Text(if (goal == null) "创建" else "保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )

    // v9.6：自选截止日期（Material3 日期选择器，叠在弹窗之上）
    if (showDatePicker) {
        GoalDatePickerDialog(
            initial = customDate ?: (now + TimeUnit.DAYS.toMillis(90L)),
            onPick = { picked ->
                customDate = normalizeToLocalNoon(picked)
                months = 0
            },
            onDismiss = { showDatePicker = false }
        )
    }
}

/** v9.6 日期选择器（Material3，实验性 API） */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun GoalDatePickerDialog(
    initial: Long,
    onPick: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    val state = androidx.compose.material3.rememberDatePickerState(
        initialSelectedDateMillis = initial
    )
    androidx.compose.material3.DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let(onPick)
                onDismiss()
            }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    ) {
        androidx.compose.material3.DatePicker(state = state)
    }
}

/**
 * Material3 日期选择器给的是 UTC 零点，直接存会让东八区以外的时区差一天。
 * 统一换算到**本地中午**，显示与比较都不跑偏。
 */
private fun normalizeToLocalNoon(millis: Long): Long {
    val zone = java.time.ZoneId.systemDefault()
    val date = java.time.Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
    return date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
}

@Composable
private fun DepositDialog(
    goal: GoalEntity,
    onDismiss: () -> Unit,
    onDeposit: (Long) -> Unit,
    onWithdraw: (Long) -> Unit
) {
    // v9.7.2：带 goal.id 作 key —— 从「存 A」切到「存 B」时金额输入框要清空
    var amount by remember(goal.id) { mutableStateOf("") }
    val presets = listOf(100L, 500L, 1000L, 5000L)

    GlassDialog(
        onDismissRequest = onDismiss,
        title = { Text("${goal.emoji} ${goal.name}") },
        text = {
            Column {
                Text(
                    "已存 ${Money.format(goal.savedCents)} / 目标 ${Money.format(goal.targetCents)}",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it.filter { ch -> ch.isDigit() || ch == '.' } },
                    label = { Text("金额（元）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    presets.forEach { p ->
                        SmallButton(Money.format(p * 100).removePrefix("¥")) { amount = p.toString() }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val cents = Money.parseToCents(amount) ?: 0L
                if (cents > 0) onDeposit(cents)
            }) { Text("存入") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    val cents = Money.parseToCents(amount) ?: 0L
                    if (cents > 0) onWithdraw(cents)
                }) { Text("取出", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    )
}

/** 可选中的 chip（v13.9：选中 = 白玻璃 + 主色内棱 + 主色字，不再是实心主色） */
@Composable
private fun PickChip(label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .glassSelectChip(active = active)
            .clickableScale(pressedScale = 0.9f, clipShape = RoundedCornerShape(50)) { onClick() }
    ) {
        Text(
            label,
            fontSize = 13.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
            maxLines = 1
        )
    }
}

/** 时间戳 → yyyy-MM-dd */
private fun formatDate(millis: Long): String =
    java.time.Instant.ofEpochMilli(millis)
        .atZone(java.time.ZoneId.systemDefault())
        .toLocalDate()
        .toString()
