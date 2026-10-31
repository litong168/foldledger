package com.foldledger.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.foldledger.ui.adaptive.AdaptiveScaffold
import com.foldledger.ui.adaptive.FoldEnv
import com.foldledger.ui.anim.clickableScale
import com.foldledger.ui.components.EmptyHint
import com.foldledger.ui.components.SectionCard
import com.foldledger.ui.theme.Layout
import com.foldledger.ui.theme.dividerBrush
import com.foldledger.ui.theme.rememberAnimatedPaneWidth
import com.foldledger.ui.theme.glassChip
import com.foldledger.ui.vm.AssistantViewModel
import com.foldledger.ui.vm.SpeedEntryViewModel

/**
 * 记账助手（v9.0）——今天该做什么，一页说清。
 *
 * 三块：**速记**（一句话记一笔）、**今日待办**（还没记的 / 自动记账没挪的 /
 * 垫付压太久的 / 预算快超的 / 周期要到的）、**智能建议**（环比与周期支出）。
 *
 * v9.0 的主张：记账 App 不该只被动记录，还要**主动把该做的事端上来**。
 */
@Composable
fun AssistantScreen(
    env: FoldEnv,
    onBack: () -> Unit,
    onOpenSpeedEntry: (Long) -> Unit = {},
    onOpenStats: (Long) -> Unit = {},
    onSwitchToLedger: (Long) -> Unit = {},
    onAddTransaction: (Long) -> Unit = {}
) {
    val vm: AssistantViewModel = viewModel(
        key = "assistant-global",
        factory = AssistantViewModel.factory()
    )
    // v9.7.2：每次进页面/回前台推一次时间锚点，跨天跨月后待办与环比不会停在上次打开时
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        vm.refreshPeriod()
        onPauseOrDispose { }
    }
    val speedVm: SpeedEntryViewModel = viewModel(
        key = "speed-assistant-global",
        factory = SpeedEntryViewModel.factory(0L)
    )
    val todosOrNull by vm.todos.collectAsStateWithLifecycle()
    val tips by vm.tips.collectAsStateWithLifecycle()
    val autoLedgerId by vm.autoLedgerId.collectAsStateWithLifecycle()

    // v9.3：待办动作统一在这里分派（跨账本，点一下能跳到对应账本）
    val handleTodo: (AssistantViewModel.Todo) -> Unit = { todo ->
        when (todo.action) {
            AssistantViewModel.Action.SPEED -> onOpenSpeedEntry(todo.ledgerId)
            AssistantViewModel.Action.STATS -> onOpenStats(todo.ledgerId)
            AssistantViewModel.Action.AUTO_LEDGER -> autoLedgerId?.let(onSwitchToLedger)
            AssistantViewModel.Action.ADD -> onAddTransaction(todo.ledgerId)
            AssistantViewModel.Action.LEDGER -> onSwitchToLedger(todo.ledgerId)
            AssistantViewModel.Action.NONE -> Unit
        }
    }

    AdaptiveScaffold(
        env = env,
        title = "记账助手",
        subtitle = "全部账本 · 今天该做什么",
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
                    SpeedEntryPanel(env = env, vm = speedVm, scrollable = false)
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
                    TodoCard(
                        todos = todosOrNull.orEmpty(),
                        loaded = todosOrNull != null,
                        onAction = { todo -> handleTodo(todo) }
                    )
                    Spacer(Modifier.height(12.dp))
                    TipCard(tips)
                    Spacer(Modifier.height(28.dp))
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding),
                contentPadding = PaddingValues(
                    start = Layout.sidePadding(env.widthDp),
                    end = Layout.sidePadding(env.widthDp),
                    bottom = 28.dp
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    SpeedEntryPanel(env = env, vm = speedVm, scrollable = false)
                }
                item {
                    TodoCard(
                        todos = todosOrNull.orEmpty(),
                        loaded = todosOrNull != null,
                        onAction = { todo -> handleTodo(todo) }
                    )
                }
                item { TipCard(tips) }
            }
        }
    }
}

@Composable
private fun TodoCard(
    todos: List<AssistantViewModel.Todo>,
    /** false = 还没算出来（首帧），别急着说"都办完了" */
    loaded: Boolean = true,
    onAction: (AssistantViewModel.Todo) -> Unit
) {
    SectionCard(
        title = "今日待办",
        subtitle = when {
            !loaded -> "正在看…"
            todos.isEmpty() -> "都办完了，收工 ✓"
            else -> "${todos.size} 件事等你 · 跨全部账本"
        }
    ) {
        if (todos.isEmpty()) {
            EmptyHint(emoji = "✅", title = "今天没有待办", subtitle = "该记的记了，该催的催了")
        } else {
            todos.forEach { t ->
                val tone = when (t.tone) {
                    2 -> MaterialTheme.colorScheme.error
                    1 -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 5.dp)) {
                    Text(t.emoji, fontSize = 17.sp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(t.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = tone)
                        Text(
                            t.detail,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (t.actionLabel.isNotEmpty()) {
                        Box(
                            Modifier
                                .glassChip(
                                    shape = RoundedCornerShape(50),
                                    tint = MaterialTheme.colorScheme.primary,
                                    strength = 0.9f
                                )
                                .clickableScale(
                                    pressedScale = 0.93f,
                                    clipShape = RoundedCornerShape(50)
                                ) { onAction(t) }
                        ) {
                            Text(
                                t.actionLabel,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TipCard(tips: List<AssistantViewModel.Tip>) {
    SectionCard(title = "智能建议", subtitle = "基于本机数据，不联网") {
        if (tips.isEmpty()) {
            Text(
                "暂时没有值得提醒的事 —— 数据再多一些会更准。",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            tips.forEach { tip ->
                Row(modifier = Modifier.padding(vertical = 4.dp)) {
                    Text(tip.emoji, fontSize = 15.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(tip.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}
