package com.foldledger.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import com.foldledger.ui.components.GlassDialog
import com.foldledger.ui.components.parseHexColor
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.foldledger.data.db.AccountEntity
import com.foldledger.data.db.AccountType
import com.foldledger.ui.adaptive.AdaptiveScaffold
import com.foldledger.ui.adaptive.FoldEnv
import com.foldledger.ui.anim.clickableScale
import com.foldledger.ui.components.SectionCard
import com.foldledger.ui.theme.SurfaceCard
import com.foldledger.ui.theme.glassChip
import com.foldledger.ui.theme.glassSelectChip
import com.foldledger.ui.theme.Layout
import com.foldledger.ui.theme.LocalDirectionColors
import com.foldledger.ui.theme.Radius
import com.foldledger.ui.theme.Space
import com.foldledger.ui.vm.AccountViewModel
import com.foldledger.util.Money
import com.foldledger.ui.l10n.t

/**
 * 资产账户管理（v6.1）。
 *
 * 「记账」管的是**流量**（钱怎么来的、怎么走的），「账户」管的是**存量**（我现在有多少钱）。
 * 只看流水永远不知道自己手里有多少钱 —— 这是很多记账 App 用户最终流失到资产管理 App 的原因。
 *
 * 这一页只回答两件事：**我有几个账户、每个账户现在有多少钱**。
 * 余额是 `初始余额 + Σ流水` 实时算出来的，不在库里存，所以改一笔流水余额立刻跟着变。
 */
@Composable
fun AccountManageScreen(
    env: FoldEnv,
    ledgerId: Long,
    onBack: () -> Unit
) {
    val vm: AccountViewModel = viewModel(
        key = "account-$ledgerId",
        factory = AccountViewModel.factory(ledgerId)
    )
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val balances by vm.balances.collectAsStateWithLifecycle()
    val netWorth by vm.netWorth.collectAsStateWithLifecycle()

    /** null = 关闭；id=0 表示新建 */
    var editing by remember { mutableStateOf<AccountEntity?>(null) }
    var deleting by remember { mutableStateOf<AccountEntity?>(null) }

    val side = Layout.sidePadding(env.widthDp)

    AdaptiveScaffold(
        env = env,
        title = t("资产账户"),
        subtitle = "${accounts.size} 个账户 · 余额按流水实时推算",
        onBack = onBack
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            LazyColumn(
                contentPadding = PaddingValues(
                    start = side, end = side, top = Space.sm, bottom = Space.huge
                ),
                verticalArrangement = Arrangement.spacedBy(Space.sm)
            ) {
                item(key = "net") {
                    NetWorthCard(netWorth = netWorth, accountCount = accounts.size)
                }

                item(key = "hint") {
                    Text(
                        "点账户可改名称、类型、初始余额；删账户只会把它名下的流水退回「未指定」，不会动你的钱。",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 2.dp)
                    )
                }

                if (accounts.isEmpty()) {
                    item(key = "empty") {
                        SectionCard(overline = "开始", title = "还没有资产账户") {
                            Text(
                                "建一个「现金」「支付宝」或者你的工资卡，记一笔时就能选「这笔钱从哪个账户出」，" +
                                    "这里也就有了余额。",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                items(accounts, key = { it.id }) { acc ->
                    AccountRow(
                        account = acc,
                        balanceCents = balances[acc.id] ?: acc.initialBalanceCents,
                        onClick = { editing = acc },
                        onDelete = { deleting = acc }
                    )
                }
            }

            // 右下角浮一个「新增账户」，与账本页的「记一笔」FAB 同一套位
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = side, bottom = 20.dp)
                    .glassChip(
                        shape = RoundedCornerShape(50),
                        tint = MaterialTheme.colorScheme.primary,
                        strength = 1.9f,
                        elevation = 4.dp
                    )
                    .clickableScale(pressedScale = 0.95f) {
                        editing = AccountEntity(id = 0L, ledgerId = ledgerId, name = "")
                    }
            ) {
                Text(
                    "＋ 新增账户",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp)
                )
            }
        }
    }

    editing?.let { acc ->
        AccountEditorDialog(
            initial = acc,
            onDismiss = { editing = null },
            onSave = { name, type, emoji, colorHex, initial, includeNet, note ->
                if (acc.id == 0L) {
                    vm.create(name, type, emoji, colorHex, initial, includeNet, note)
                } else {
                    vm.update(
                        acc.copy(
                            name = name,
                            type = type,
                            emoji = emoji,
                            colorHex = colorHex,
                            initialBalanceCents = initial,
                            includeInNetWorth = includeNet,
                            note = note
                        )
                    )
                }
                editing = null
            }
        )
    }

    deleting?.let { acc ->
        GlassDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除账户「${acc.name}」？") },
            text = {
                Text("它名下的流水会退回「未指定」，不会被删除。账户本身（含初始余额设置）会消失。")
            },
            confirmButton = {
                Text(
                    t("删除"),
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickableScale(pressedScale = 0.92f) {
                            vm.delete(acc.id)
                            deleting = null
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
            },
            dismissButton = {
                Text(
                    t("取消"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickableScale(pressedScale = 0.92f, onClick = { deleting = null })
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        )
    }
}

@Composable
private fun NetWorthCard(netWorth: Long, accountCount: Int) {
    val dc = LocalDirectionColors.current
    SurfaceCard(
        shape = RoundedCornerShape(Radius.card),
        elevation = 3.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(Space.lg)) {
            Text(
                t("净资产"),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Text(
                Money.format(netWorth),
                fontSize = 30.sp,
                fontWeight = FontWeight.ExtraBold,
                color = if (netWorth >= 0) dc.income else dc.expense
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "计入汇总的 $accountCount 个账户 · 余额随流水实时变化",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun AccountRow(
    account: AccountEntity,
    balanceCents: Long,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val dc = LocalDirectionColors.current
    SurfaceCard(
        shape = RoundedCornerShape(Radius.card),
        elevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .clickableScale(pressedScale = 0.98f) { onClick() }
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 账户图标：小玻璃砖（与分类图标块同一套内光影）
            Box(
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(Radius.sm))
                    .background(
                        parseHexColor(account.colorHex)
                            .copy(alpha = 0.18f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(account.emoji, fontSize = 20.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        account.name,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        AccountType.label(account.type),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                            )
                            .padding(horizontal = 7.dp, vertical = 2.dp)
                    )
                    if (!account.includeInNetWorth) {
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "不计入",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .border(
                                    0.8.dp,
                                    MaterialTheme.colorScheme.outlineVariant,
                                    RoundedCornerShape(50)
                                )
                                .padding(horizontal = 7.dp, vertical = 2.dp)
                        )
                    }
                }
                if (account.closed) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "已停用",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    Money.format(balanceCents),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (balanceCents >= 0) MaterialTheme.colorScheme.onSurface
                    else dc.expense
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    t("删除"),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.9f),
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        // v9.8.0：原来只有约 17dp 高、紧贴余额数字，几乎点不中还会误触整行（整行=编辑）。
                        // 撑到 30dp 的可点区，并和上方余额拉开 4dp。
                        .clickableScale(pressedScale = 0.9f) { onDelete() }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }
    }
}

/**
 * 新建 / 编辑账户。
 *
 * 关键字段是**初始余额**：新账户第一次录入时用它把"我现在有多少钱"锚定下来，
 * 之后所有余额都是 `初始 + Σ流水` 推算，不需要手动调。
 */
@Composable
private fun AccountEditorDialog(
    initial: AccountEntity,
    onDismiss: () -> Unit,
    onSave: (name: String, type: Int, emoji: String, colorHex: String, initial: Long, includeNet: Boolean, note: String) -> Unit
) {
    var name by remember { mutableStateOf(initial.name) }
    var type by remember { mutableStateOf(initial.type) }
    var emoji by remember { mutableStateOf(initial.emoji) }
    var colorHex by remember { mutableStateOf(initial.colorHex) }
    var balanceText by remember {
        mutableStateOf(if (initial.initialBalanceCents != 0L) Money.centsToPlain(initial.initialBalanceCents) else "")
    }
    var includeNet by remember { mutableStateOf(initial.includeInNetWorth) }
    var note by remember { mutableStateOf(initial.note) }

    GlassDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.id == 0L) "新增资产账户" else "编辑账户") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("账户名称（必填）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // 类型：五个胶囊，点一下就选
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "类型",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(
                            AccountType.CASH, AccountType.SAVINGS, AccountType.CREDIT,
                            AccountType.WALLET, AccountType.OTHER
                        ).forEach { t ->
                            val active = t == type
                            Box(
                                Modifier
                                    .glassSelectChip(active)
                                    .clickableScale(pressedScale = 0.92f) {
                                        type = t
                                        // 新建时跟着类型换图标，省一次手点
                                        if (initial.id == 0L) {
                                            emoji = AccountType.defaultEmoji(t)
                                        }
                                    }
                            ) {
                                Text(
                                    AccountType.label(t),
                                    fontSize = 13.sp,
                                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (active) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }

                // v9.9.0：原来只给一个空输入框让用户"打个 emoji 进去" ——
                // 大部分输入法打不出 emoji。给一排常用图标点选，想自定义再用下面的输入框。
                Text("图标", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("💳", "💰", "🏦", "📱", "🐷", "📈", "🪙", "🧾").forEach { e ->
                        val active = emoji == e
                        Box(
                            Modifier
                                .size(38.dp)
                                .glassSelectChip(active, shape = CircleShape)
                                .clickableScale(pressedScale = 0.9f, clipShape = CircleShape) { emoji = e },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(e, fontSize = 17.sp)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = emoji,
                    onValueChange = { if (it.length <= 2) emoji = it },
                    label = { Text("图标（也可自己输入一个 emoji）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = balanceText,
                    onValueChange = { balanceText = it },
                    label = { Text("初始余额（元，可为负，如信用卡欠款）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )

                // v9.9.0：让普通用户手打 #3B82F6 是不现实的 —— 给一排色点，选中的带描边。
                Text("颜色", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    listOf(
                        // v11：色点换成墨白体系的低饱和九色，和场景/图表共用一套
                        "#1D3B57", "#2C6E5A", "#B06B3A", "#8A5A6E",
                        "#A9803A", "#5E5A8C", "#3E6C82", "#9C4A3C"
                    ).forEach { hex ->
                        val active = colorHex.equals(hex, ignoreCase = true)
                        Box(
                            Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(parseHexColor(hex))
                                .then(
                                    if (active) Modifier.border(
                                        2.dp,
                                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                        CircleShape
                                    ) else Modifier
                                )
                                .clickableScale(pressedScale = 0.88f, clipShape = CircleShape) { colorHex = hex }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = colorHex,
                    onValueChange = { colorHex = it },
                    label = { Text("颜色值（也可自己填 HEX，如 #3B82F6）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("备注（可留空）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("计入净资产", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text(
                            "关掉后资产总览不加这个账户的余额",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = includeNet, onCheckedChange = { includeNet = it })
                }
            }
        },
        confirmButton = {
            Text(
                if (initial.id == 0L) t("创建") else t("保存"),
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickableScale(pressedScale = 0.92f, enabled = name.isNotBlank()) {
                        if (name.isNotBlank()) {
                            val cents = Money.parseToCents(balanceText.ifBlank { "0" }) ?: 0L
                            onSave(
                                name.trim(),
                                type,
                                emoji.ifBlank { AccountType.defaultEmoji(type) },
                                colorHex.ifBlank { "#3B82F6" },
                                cents,
                                includeNet,
                                note.trim()
                            )
                        }
                    }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )
        },
        dismissButton = {
            Text(
                t("取消"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickableScale(pressedScale = 0.92f, onClick = onDismiss)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
    )
}
