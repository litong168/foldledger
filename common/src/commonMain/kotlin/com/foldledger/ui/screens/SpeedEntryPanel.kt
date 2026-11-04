package com.foldledger.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foldledger.data.db.CategoryEntity
import com.foldledger.data.db.Direction
import com.foldledger.ui.adaptive.FoldEnv
import com.foldledger.ui.anim.clickableScale
import com.foldledger.ui.components.GlassDialog
import com.foldledger.ui.components.SectionCard
import com.foldledger.ui.theme.LocalDirectionColors
import com.foldledger.ui.theme.Radius
import com.foldledger.ui.theme.glassChip
import com.foldledger.ui.vm.SpeedEntryViewModel
import com.foldledger.util.CategorySuggester
import com.foldledger.util.DateExt
import com.foldledger.util.Money
import com.foldledger.util.NLParser
import kotlinx.coroutines.delay

/**
 * 速记面板（一句话记账，v8.6 / v9.3）。
 *
 * 干的事只有一件：**把一句话变成一笔流水**。解析在 [NLParser]，
 * 分类建议在 [CategorySuggester]（从用户自己的历史里学）。
 *
 * 面板本体在 commonMain（键盘版天然可用）；语音输入是平台槽
 * [SpeedEntryVoiceInput]：Android 用系统语音识别（RecognizerIntent），
 * 桌面端暂无语音入口（槽位留空 = 纯键盘版）。
 */
@Composable
expect fun SpeedEntryPanel(
    env: FoldEnv,
    vm: SpeedEntryViewModel,
    modifier: Modifier = Modifier,
    scrollable: Boolean = true,
    onSaved: (Long) -> Unit = {}
)

/** 语音输入的共享状态：面板本体持有，平台槽读写（桌面端恒不触发，整体惰性）。 */
internal class SpeedEntryVoiceState {
    var touched by mutableStateOf(false)
    var hint by mutableStateOf<String?>(null)
    var undoText by mutableStateOf<String?>(null)
    var userEdits by mutableStateOf(0)
}

/**
 * 语音输入槽：渲染在输入框尾部的平台专属控件。
 *
 * @param onSpoken 识别到非空文字（已去掉句尾标点）。
 * @param onNoRecognition 识别界面返回空（「没听清」）。
 * @param onVoiceUnavailable 本机没有可用的语音识别服务。
 */
@Composable
internal expect fun SpeedEntryVoiceInput(
    voice: SpeedEntryVoiceState,
    onSpoken: (String) -> Unit,
    onNoRecognition: () -> Unit,
    onVoiceUnavailable: () -> Unit
)

/**
 * 速记面板（v8.6）——双端共享实现。
 *
 * 原来记一笔要三步：选分类、点日期、输金额。现在一句话说完：
 * 「昨天打车35」→ 解析出金额/日期/分类 → 确认卡上改两下 → 记下。
 *
 * 解析引擎 [NLParser] 是纯逻辑；分类优先用**历史学到的**（[CategorySuggester]），
 * 其次用关键词表；都没有就默认第一个分类，让人一眼能改。
 *
 * ⚠️ 嵌在别的可滚动页面里（V9.0 助手的 LazyColumn）必须传 [scrollable]=false ——
 * 可滚动列表不能嵌套，否则内层拿到无限高度约束直接崩（v8.0.1 事故）。
 */
@Composable
internal fun SpeedEntryPanelBody(
    env: FoldEnv,
    vm: SpeedEntryViewModel,
    modifier: Modifier = Modifier,
    scrollable: Boolean = true,
    onSaved: (Long) -> Unit = {}
) {
    val dc = LocalDirectionColors.current
    val categories by vm.categories.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val ledgers by vm.ledgers.collectAsStateWithLifecycle()
    val selectedLedger by vm.selectedLedger.collectAsStateWithLifecycle()

    // v9.7.2：改用 rememberSaveable —— 这一段会被嵌进助手的 LazyColumn，
    // 滚出屏幕即被回收，用 remember 会让已经敲好的整句话直接丢失。
    var text by rememberSaveable { mutableStateOf("") }
    var direction by rememberSaveable { mutableStateOf(Direction.EXPENSE) }
    var pickedCategoryId by rememberSaveable { mutableStateOf<Long?>(null) }
    var dateMillis by rememberSaveable { mutableStateOf(System.currentTimeMillis()) }
    var savedId by rememberSaveable { mutableStateOf<Long?>(null) }
    /** v9.8.5：撤销 = 硬删，先确认 */
    var confirmingUndo by remember { mutableStateOf(false) }

    // ===== 语音输入（平台槽）：识别出的文字填进这句话，后续流程完全不变 =====
    val voice = remember { SpeedEntryVoiceState() }
    val applySpoken: (String) -> Unit = { spoken ->
        // 只有真的"覆盖了别的文字"才留撤销；说了一遍一模一样或空场填词，
        // 不能把上一轮的旧文挂成"可撤销"（撤销会跳回更早的内容）。
        if (text.isNotBlank() && text.trim() != spoken) voice.undoText = text else voice.undoText = null
        text = spoken
        voice.hint = "已填入"
        voice.touched = true
    }
    val onNoRecognition: () -> Unit = {
        voice.hint = "没听清，再说一次"
        voice.touched = true
    }
    val onVoiceUnavailable: () -> Unit = {
        voice.hint = "没有可用的语音输入，改用键盘"
    }

    // 语音覆盖了已有文字时，留 6 秒可撤销（一句话=一笔，覆盖比追加安全）
    LaunchedEffect(voice.undoText) {
        if (voice.undoText != null) {
            delay(6000)
            voice.undoText = null
        }
    }
    // 用户手动改字 → 清掉提示（增量计数触发，避免"刚注入就被 onValueChange 的旧闭包清掉"）
    LaunchedEffect(voice.userEdits) {
        if (voice.userEdits > 0) voice.hint = null
    }

    val parsed = remember(text) { NLParser.parse(text, System.currentTimeMillis()) }

    /**
     * 解析结果同步到「方向 / 日期」。
     *
     * ⚠️ v9.7.2 修：原来 `LaunchedEffect(parsed) { direction = ...; dateMillis = ... }`，
     * 而 `parsed` 是 `remember(text)` —— **每敲一个字都是新对象**，于是
     * 「点『昨天』→ 再补两个字备注」这个动作会把日期弹回今天，用户会以为点了没生效。
     * 现在只认「解析值本身发生变化」（比如真在文本里补了日期），
     * 手选的日期/方向不会被无关的输入冲掉。
     */
    var lastParsedDate by remember { mutableStateOf<Long?>(null) }
    var lastParsedDir by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(parsed) {
        val p = parsed ?: return@LaunchedEffect
        if (p.dateMillis != lastParsedDate) {
            dateMillis = p.dateMillis
            lastParsedDate = p.dateMillis
        }
        if (p.direction != lastParsedDir) {
            direction = p.direction
            lastParsedDir = p.direction
        }
    }
    LaunchedEffect(direction) {
        pickedCategoryId = null
    }

    // 自动匹配分类：历史建议优先，其次关键词表，最后第一个分类
    val autoCategory: CategoryEntity? = remember(parsed, categories, history) {
        if (parsed == null) {
            null
        } else {
            val fromHistory = CategorySuggester.suggest(text, history)
            categories.firstOrNull { it.name == fromHistory }
                ?: categories.firstOrNull { it.name == parsed.categoryHint }
                ?: categories.firstOrNull()
        }
    }
    val effectiveCategory = categories.firstOrNull { it.id == pickedCategoryId } ?: autoCategory

    Column(
        if (scrollable) {
            modifier.verticalScroll(rememberScrollState())
        } else {
            modifier
        }.padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(8.dp))

        // v9.3：账本选择 —— 速记是全局能力，记到哪个账本当场定
        if (ledgers.size > 1) {
            LedgerPickerRow(
                ledgers = ledgers,
                selectedId = selectedLedger?.id ?: 0L,
                onPick = { vm.selectLedger(it) }
            )
            Spacer(Modifier.height(10.dp))
        }

        OutlinedTextField(
            value = text,
            // IME 组词/候选回填也会走 onValueChange 且文本可能相同 —— 只有真正改了字才算"用户编辑"
            onValueChange = { next -> if (next != text) { text = next; voice.userEdits++ } },
            placeholder = { Text("说一句：昨天打车35", fontSize = 15.sp) },
            textStyle = MaterialTheme.typography.titleMedium,
            shape = RoundedCornerShape(Radius.md),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
            ),
            // 语音输入由平台槽提供（Android 麦克风 / 桌面无槽位，保持输入框布局一致）
            trailingIcon = {
                SpeedEntryVoiceInput(
                    voice = voice,
                    onSpoken = applySpoken,
                    onNoRecognition = onNoRecognition,
                    onVoiceUnavailable = onVoiceUnavailable
                )
            },
            modifier = Modifier.fillMaxWidth()
        )

        // 语音状态位：首次交互后常驻 22dp（6 spacer + 16 行），只换文字、不改变高度。
        // 行内零 clickable（44dp 硬底线放不进 16dp）—— 撤销挂在解析卡 / 空态卡的 trailing。
        if (voice.touched) {
            Spacer(Modifier.height(6.dp))
            Box(Modifier.height(16.dp), contentAlignment = Alignment.CenterStart) {
                val status = voice.hint
                if (status != null) {
                    Text(
                        status,
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        if (parsed == null) {
            if (text.isNotBlank()) {
                HintBlock(
                    emoji = "🤔",
                    title = "没看懂金额",
                    subtitle = "换种说法试试：「打车35」「买菜56.8」「工资8000」",
                    // 空态卡也要能撤销：语音覆盖了原文字但没解析出金额时，别让"撤销"变孤儿
                    trailing = {
                        if (voice.undoText != null) UndoAction {
                            text = voice.undoText ?: ""
                            voice.undoText = null
                            voice.hint = null
                        }
                    }
                )
            } else {
                HintBlock(
                    emoji = "⚡",
                    title = "一句话就能记一笔",
                    subtitle = "支持日期（昨天/前天/10月3号）、中文数字（一百二）、方向（工资/红包）"
                )
            }
        } else {
            // 预览卡
            SectionCard(
                title = "解析结果",
                subtitle = "看着不对就直接改",
                // 槽常驻 44dp：撤销进出时标题行高度不变 → 零位移
                trailing = {
                    Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                        if (voice.undoText != null) {
                            UndoAction {
                                text = voice.undoText ?: ""
                                voice.undoText = null
                                voice.hint = null
                            }
                        }
                    }
                }
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (direction == Direction.INCOME) "收入" else "支出",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            Money.format(parsed.amountCents),
                            fontSize = 30.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = if (direction == Direction.INCOME) dc.income else dc.expense
                        )
                    }
                    // 方向切换
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        DirChip("支出", direction == Direction.EXPENSE) { direction = Direction.EXPENSE }
                        DirChip("收入", direction == Direction.INCOME) { direction = Direction.INCOME }
                    }
                }

                Spacer(Modifier.height(10.dp))

                // 日期
                Text("日期", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DateChip("今天", dateMillis) { dateMillis = startOfToday() }
                    DateChip("昨天", dateMillis) { dateMillis = startOfToday() - DAY_MS }
                    DateChip("前天", dateMillis) { dateMillis = startOfToday() - 2 * DAY_MS }
                    Text(
                        DateExt.shortDateTime(dateMillis),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                Spacer(Modifier.height(10.dp))

                // 分类
                Text("分类", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    categories.forEach { c ->
                        val active = c.id == effectiveCategory?.id
                        CategoryChip(c, active) { pickedCategoryId = c.id }
                    }
                }

                if (parsed.note.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "备注：${parsed.note}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            if (savedId == null) {
                // v13.9：主行动从主色实心底换成玻璃（1.9 档主色膜 + 抬影），
                // 字回 onSurface —— 一层淡膜上写不了 onPrimary 的白字。
                Box(
                    Modifier
                        .fillMaxWidth()
                        .glassChip(
                            shape = RoundedCornerShape(14.dp),
                            tint = MaterialTheme.colorScheme.primary,
                            strength = 1.9f,
                            elevation = 3.dp
                        )
                        .clickableScale(pressedScale = 0.96f, clipShape = RoundedCornerShape(14.dp)) {
                            // v9.7.1：没有账本时不能"点了没反应"，按钮文字会说明原因
                            if (selectedLedger != null) {
                                vm.save(
                                    amountCents = parsed.amountCents,
                                    category = effectiveCategory,
                                    direction = direction,
                                    dateMillis = dateMillis,
                                    note = parsed.note
                                ) { id ->
                                    savedId = id
                                    onSaved(id)
                                }
                            }
                        }
                ) {
                    Text(
                        if (selectedLedger != null) "记下这一笔" else "先去「账本」页建一个账本",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 13.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            } else {
                // v9.8.5：记完一笔后主按钮不该只剩"撤销"（想接着记还得先把上一笔删掉）。
                // 现在给「再记一笔」当主操作，「撤销刚才那笔」降级成次要入口，且必须二次确认
                // —— 流水是硬删、不进回收站，一次误触就真没了。
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("✅ 已记下", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    Text(
                        "撤销",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickableScale(pressedScale = 0.93f) { confirmingUndo = true }
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier
                            .glassChip(
                                shape = RoundedCornerShape(Radius.pill),
                                tint = MaterialTheme.colorScheme.primary,
                                strength = 1.9f,
                                elevation = 2.dp
                            )
                            .clickableScale(pressedScale = 0.95f, clipShape = RoundedCornerShape(Radius.pill)) {
                                text = ""
                                savedId = null
                                pickedCategoryId = null
                            }
                    ) {
                        Text(
                            "再记一笔",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                        )
                    }
                }
            }

            // 撤销 := 删掉刚记的那一笔（硬删，不进回收站）→ 必须确认
            if (confirmingUndo) {
                GlassDialog(
                    onDismissRequest = { confirmingUndo = false },
                    title = { Text("删掉刚才那一笔？") },
                    text = {
                        Text(
                            "这一笔会被直接删除，回收站里也找不回来。\n如果只是记错了金额或分类，去明细页点它能改。",
                            fontSize = 13.sp
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            savedId?.let { vm.undo(it) }
                            savedId = null
                            confirmingUndo = false
                        }) { Text("删掉", color = MaterialTheme.colorScheme.error) }
                    },
                    dismissButton = {
                        TextButton(onClick = { confirmingUndo = false }) {
                            Text("保留")
                        }
                    }
                )
            }
        }
        Spacer(Modifier.height(28.dp))
    }
}

private const val DAY_MS = 24L * 3600 * 1000

private fun startOfToday(): Long = DateExt.startOfDay(System.currentTimeMillis())

/** v9.3 账本选择条（速记用）：横向 chips，选中即切换 */
@Composable
private fun LedgerPickerRow(
    ledgers: List<com.foldledger.data.db.LedgerEntity>,
    selectedId: Long,
    onPick: (Long) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        ledgers.forEach { l ->
            val active = l.id == selectedId
            Box(
                Modifier
                    .speedChip(active)
                    .clickableScale(pressedScale = 0.93f, clipShape = RoundedCornerShape(50)) {
                        onPick(l.id)
                    }
            ) {
                Text(
                    "${l.emoji} ${l.name}",
                    fontSize = 13.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun DirChip(label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .speedChip(active)
            .clickableScale(pressedScale = 0.93f, clipShape = RoundedCornerShape(50)) { onClick() }
    ) {
        Text(
            label,
            fontSize = 13.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun DateChip(label: String, current: Long, onClick: () -> Unit) {
    val target = when (label) {
        "今天" -> startOfToday()
        "昨天" -> startOfToday() - DAY_MS
        else -> startOfToday() - 2 * DAY_MS
    }
    val active = current / DAY_MS == target / DAY_MS
    Box(
        Modifier
            .speedChip(active)
            .clickableScale(pressedScale = 0.93f, clipShape = RoundedCornerShape(50)) { onClick() }
    ) {
        Text(
            label,
            fontSize = 13.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun CategoryChip(c: CategoryEntity, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .speedChip(active)
            .clickableScale(pressedScale = 0.93f, clipShape = RoundedCornerShape(50)) { onClick() }
    ) {
        Text(
            "${c.emoji} ${c.name}",
            fontSize = 13.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
            maxLines = 1
        )
    }
}

/**
 * 速记页的选中胶囊（v13.9）。
 *
 * 原来这四组 chip（账本 / 收支 / 日期 / 分类）都是 `Surface(color = 主色)` 的
 * 实心蓝块，未选是 `onSurface 6%` 的灰块 —— 一屏里十几块平涂，正是"材质没统一"。
 * 现在收成一条规则：**选中 = 白玻璃 + 主色内棱，未选 = 薄白膜**。
 */
@Composable
private fun Modifier.speedChip(active: Boolean): Modifier = this
    .glassChip(shape = RoundedCornerShape(50), strength = if (active) 1.0f else 0.55f)
    .then(
        if (active) {
            Modifier.border(
                1.2.dp,
                MaterialTheme.colorScheme.primary.copy(alpha = 0.42f),
                RoundedCornerShape(50)
            )
        } else Modifier
    )

/**
 * 语音撤销（单一实现，两处复用）：13sp / Medium / primary / 44dp 热区。
 * 只在「语音覆盖了已有文字」时出现，所以 6 秒后 / 手改文字后都会消失。
 */
@Composable
private fun UndoAction(onClick: () -> Unit) {
    Text(
        "撤销",
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .semantics { role = Role.Button }
            .clickableScale(pressedScale = 0.94f, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp)
    )
}

@Composable
private fun HintBlock(
    emoji: String,
    title: String,
    subtitle: String,
    /** 与 SectionCard.trailing 同名同义：可选末位插槽，现有调用零改动 */
    trailing: (@Composable () -> Unit)? = null
) {
    // v13.9：surface 50% 平涂 → 一层薄玻璃托盘
    Box(
        Modifier.glassChip(shape = RoundedCornerShape(Radius.card), strength = 0.6f)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(emoji, fontSize = 24.sp)
            Spacer(Modifier.height(6.dp))
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                subtitle,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            if (trailing != null) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) { trailing() }
            }
        }
    }
}
