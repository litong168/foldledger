package com.foldledger.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foldledger.data.ScenePresets
import com.foldledger.data.db.LedgerEntity
import com.foldledger.ui.adaptive.FoldEnv
import com.foldledger.ui.anim.StaggeredAppear
import com.foldledger.ui.anim.clickableScale
import com.foldledger.ui.components.LedgerAvatar
import com.foldledger.ui.components.PillTag
import com.foldledger.ui.theme.Radius
import com.foldledger.ui.theme.glassChip
import com.foldledger.ui.components.SectionCard
import com.foldledger.ui.components.ThinProgress
import com.foldledger.ui.components.parseHexColor
import com.foldledger.ui.theme.SceneColors
import com.foldledger.ui.theme.SurfaceCard
import com.foldledger.util.Money
import com.foldledger.ui.theme.cardSurface
import com.foldledger.ui.theme.wellSurface
import com.foldledger.ui.l10n.t

private val EMOJI_CHOICES = listOf(
    "🏠", "🛠️", "🧾", "🍼", "🚗", "🏡",
    "✈️", "🐾", "💼", "🎓", "💪", "🎁",
    "📚", "🎮", "💰", "🌟"
)

/**
 * 新建 / 编辑分账本。
 * 展开态下左右分栏：左侧填信息，右侧实时预览预置分类。
 */
@Composable
fun CreateLedgerOverlay(
    env: FoldEnv,
    initialSceneKey: String?,
    editing: LedgerEntity?,
    onDismiss: () -> Unit,
    onQuickCreate: (String) -> Unit,
    onConfirm: (
        name: String,
        sceneKey: String,
        emoji: String,
        colorHex: String,
        budgetCents: Long,
        note: String
    ) -> Unit
) {
    var sceneKey by remember { mutableStateOf(editing?.sceneKey ?: initialSceneKey ?: ScenePresets.all.first().key) }
    var name by remember { mutableStateOf(editing?.name ?: "") }
    var emoji by remember { mutableStateOf(editing?.emoji ?: ScenePresets.byKey(sceneKey).emoji) }
    var colorHex by remember { mutableStateOf(editing?.colorHex ?: ScenePresets.byKey(sceneKey).colorHex) }
    var budgetText by remember {
        mutableStateOf(
            editing?.budgetCents?.takeIf { it > 0 }?.let { Money.centsToPlain(it) }
                ?: ScenePresets.byKey(sceneKey).suggestedBudgetYuan.takeIf { it > 0 }?.toString()
                ?: ""
        )
    }
    var note by remember { mutableStateOf(editing?.note ?: "") }

    val scene = ScenePresets.byKey(sceneKey)
    val lockedScene = editing != null

    val pickScene: (String) -> Unit = { key ->
        sceneKey = key
        val s = ScenePresets.byKey(key)
        if (editing == null) {
            emoji = s.emoji
            colorHex = s.colorHex
            name = if (name.isBlank() || ScenePresets.all.any { it.name + t("账本") == name }) "${s.name}账本" else name
            budgetText = s.suggestedBudgetYuan.takeIf { it > 0 }?.toString() ?: ""
        }
    }

    val confirm = {
        val budget = Money.parseToCents(budgetText.ifBlank { "0" }) ?: 0L
        onConfirm(
            name.ifBlank { "${scene.name}账本" },
            sceneKey,
            emoji.ifBlank { scene.emoji },
            colorHex,
            budget,
            note
        )
        onDismiss()
    }

    // 确认按钮文案：创建时说明会生成几个分类，编辑时就是"保存修改"（v4.1）
    val confirmLabel = if (editing == null) {
        "创建账本（自动生成 ${ScenePresets.byKey(sceneKey).allCategories.size} 个分类）"
    } else {
        t("保存修改")
    }

    AppOverlay(env = env, onDismiss = onDismiss) {
        // 内布局在打开瞬间冻结（v3.1.1）：折叠/展开不换骨架（单列表单 ↔ 左表单右预览
        // 是两种结构，实时切换就是"开着编辑弹层折一下屏幕，内容整个重排"）。
        val dialogLayout = remember { env.useDialogOverlay }
        if (dialogLayout) {
            Row(Modifier.fillMaxSize()) {
                FormColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        // ⚠️ v4.1 修复「改完名字没有保存按钮」：展开屏的宽对话框里表单内容
                        // （名称/图标/颜色/预算/备注/场景/按钮）超过可视高度时，底部的确认按钮
                        // 会被挤出屏幕且**没有滚动容器**够不着。加上 verticalScroll 后按钮始终可达。
                        .verticalScroll(rememberScrollState()),
                    title = if (editing == null) t("新建分账本") else t("编辑账本"),
                    name = name, onName = { name = it },
                    emoji = emoji, onEmoji = { emoji = it },
                    colorHex = colorHex, onColor = { colorHex = it },
                    budgetText = budgetText, onBudget = { budgetText = it },
                    note = note, onNote = { note = it },
                    sceneKey = sceneKey, lockedScene = lockedScene, onPickScene = pickScene,
                    onQuickCreate = if (editing == null) {
                        { key -> onQuickCreate(key); onDismiss() }
                    } else null,
                    confirmLabel = confirmLabel,
                    onConfirm = confirm, onCancel = onDismiss
                )
                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                PreviewColumn(
                    sceneKey = sceneKey,
                    emoji = emoji,
                    name = name.ifBlank { "${scene.name}账本" },
                    budgetText = budgetText,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                SheetGrabber()
                FormColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    title = if (editing == null) t("新建分账本") else t("编辑账本"),
                    name = name, onName = { name = it },
                    emoji = emoji, onEmoji = { emoji = it },
                    colorHex = colorHex, onColor = { colorHex = it },
                    budgetText = budgetText, onBudget = { budgetText = it },
                    note = note, onNote = { note = it },
                    sceneKey = sceneKey, lockedScene = lockedScene, onPickScene = pickScene,
                    onQuickCreate = if (editing == null) {
                        { key -> onQuickCreate(key); onDismiss() }
                    } else null,
                    confirmLabel = confirmLabel,
                    onConfirm = null, onCancel = null
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // v13.9：取消/确定原来是"surfaceVariant 灰块 + 主色实块"。
                    // 换成两颗玻璃：中性 0.6 档，主行动 1.9 档 + 抬影。
                    Box(
                        Modifier
                            .weight(1f)
                            .glassChip(shape = RoundedCornerShape(14.dp), strength = 0.6f)
                            .clickableScale { onDismiss() }
                    ) {
                        Box(Modifier.padding(vertical = 13.dp), contentAlignment = Alignment.Center) {
                            Text(t("取消"), fontSize = 15.sp)
                        }
                    }
                    Box(
                        Modifier
                            .weight(2f)
                            .glassChip(
                                shape = RoundedCornerShape(14.dp),
                                tint = MaterialTheme.colorScheme.primary,
                                strength = 1.9f,
                                elevation = 2.dp
                            )
                            .clickableScale { confirm() }
                    ) {
                        Box(Modifier.padding(vertical = 13.dp), contentAlignment = Alignment.Center) {
                            Text(
                                if (editing == null) t("创建账本") else t("保存修改"),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 表单主体（账本页的内联创建面板也复用这一段，所以是 internal 而不是 private） */
@Composable
internal fun FormColumn(
    modifier: Modifier,
    title: String,
    name: String, onName: (String) -> Unit,
    emoji: String, onEmoji: (String) -> Unit,
    colorHex: String, onColor: (String) -> Unit,
    budgetText: String, onBudget: (String) -> Unit,
    note: String, onNote: (String) -> Unit,
    sceneKey: String, lockedScene: Boolean, onPickScene: (String) -> Unit,
    onQuickCreate: ((String) -> Unit)?,
    confirmLabel: String = t("创建账本"),
    onConfirm: (() -> Unit)?,
    onCancel: (() -> Unit)?
) {
    Column(modifier.padding(horizontal = 18.dp)) {
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            if (onCancel != null) {
                Text(
                    t("取消"),
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onCancel() }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        // ---------------- 一键创建预置账本 ----------------
        if (onQuickCreate != null) {
            Spacer(Modifier.height(14.dp))
            // v13.9：primaryContainer 55% 平涂 → 主色染膜玻璃，字回 onSurface
            Box(
                Modifier
                    .fillMaxWidth()
                    .glassChip(
                        shape = RoundedCornerShape(Radius.md),
                        tint = MaterialTheme.colorScheme.primary,
                        strength = 0.7f
                    )
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        t("⚡ 一键创建预置账本"),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        t("点一下即可按模板生成账本 + 整套分类，之后再改名字也行"),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        maxItemsInEachRow = 3
                    ) {
                        ScenePresets.quickCreate.forEachIndexed { index, s ->
                            StaggeredAppear(index = index) {
                                Surface(
                                    shape = RoundedCornerShape(Radius.sm),
                                    color = wellSurface(),
                                    border = androidx.compose.foundation.BorderStroke(
                                        1.dp, parseHexColor(s.colorHex).copy(alpha = 0.45f)
                                    ),
                                    modifier = Modifier
                                        .width(if (onCancel != null) 118.dp else 104.dp)
                                        .clickableScale(pressedScale = 0.93f) { onQuickCreate(s.key) }
                                ) {
                                    Row(
                                        Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(s.emoji, fontSize = 17.sp)
                                        Spacer(Modifier.width(6.dp))
                                        Column {
                                            Text(
                                                "${s.name}账本",
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Medium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                "${s.expenses.size} 个分类",
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .height(1.dp)
                        .weight(1f)
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )
                Text(
                    "  或手动配置  ",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Box(
                    Modifier
                        .height(1.dp)
                        .weight(1f)
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        OutlinedTextField(
            value = name,
            onValueChange = onName,
            label = { Text(t("账本名称")) },
            placeholder = { Text(t("如：小家装修 2026")) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(14.dp))
        Text(
            t("记账场景"),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (lockedScene) {
            Spacer(Modifier.height(4.dp))
            Text(
                t("已创建的账本不能更换场景（分类已按原场景生成）"),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(8.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            maxItemsInEachRow = 4
        ) {
            ScenePresets.gallery.forEach { s ->
                val active = s.key == sceneKey
                val accent = parseHexColor(s.colorHex)
                // v13.9：选中原来是"场景色 16% 平涂 + 1.5dp 实线描边"。
                // 现在是白玻璃 + 场景色内棱 —— 颜色由棱带着走，不由整块底带着走。
                Box(
                    Modifier
                        .width(84.dp)
                        .glassChip(
                            shape = RoundedCornerShape(Radius.sm),
                            strength = if (active) 1.0f else 0.5f,
                            elevation = if (active) 3.dp else 0.dp
                        )
                        .then(
                            if (active) {
                                Modifier.border(1.4.dp, accent.copy(alpha = 0.75f), RoundedCornerShape(Radius.sm))
                            } else Modifier
                        )
                        .clickableScale(enabled = !lockedScene, pressedScale = 0.93f) { onPickScene(s.key) }
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 9.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(s.emoji, fontSize = 20.sp)
                        Spacer(Modifier.height(3.dp))
                        Text(
                            s.name,
                            fontSize = 11.sp,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (active) accent
                            else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            t("图标"),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            maxItemsInEachRow = 8
        ) {
            EMOJI_CHOICES.forEach { e ->
                val active = e == emoji
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (active) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        )
                        .clickableScale(pressedScale = 0.88f) { onEmoji(e) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(e, fontSize = 17.sp)
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            t("主题色"),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SceneColors.forEach { c ->
                val active = c == parseHexColor(colorHex)
                Box(
                    Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(c)
                        .then(
                            if (active) Modifier.border(2.5.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                            else Modifier
                        )
                        .clickableScale(pressedScale = 0.85f) { onColor(hexOf(c)) }
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = budgetText,
            onValueChange = { input ->
                if (input.all { it.isDigit() || it == '.' } && input.length <= 12) onBudget(input)
            },
            label = { Text(t("预算（元，可留空）")) },
            placeholder = { Text(t("如：200000")) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = note,
            onValueChange = onNote,
            label = { Text(t("备注（可选）")) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        if (onConfirm != null) {
            Spacer(Modifier.height(20.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .glassChip(
                        shape = RoundedCornerShape(14.dp),
                        tint = MaterialTheme.colorScheme.primary,
                        strength = 1.9f,
                        elevation = 3.dp
                    )
                    .clickableScale { onConfirm() }
            ) {
                Box(Modifier.padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
                    Text(
                        confirmLabel,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** 实时预览栏（同样是内联面板复用） */
@Composable
internal fun PreviewColumn(
    sceneKey: String,
    emoji: String,
    name: String,
    budgetText: String,
    modifier: Modifier
) {
    val scene = ScenePresets.byKey(sceneKey)
    val accent = parseHexColor(scene.colorHex)
    val budgetCents = Money.parseToCents(budgetText.ifBlank { "0" }) ?: 0L

    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(18.dp)
    ) {
        Text(
            t("实时预览"),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(12.dp))

        SurfaceCard(
            shape = RoundedCornerShape(Radius.card),
            color = cardSurface(),
            elevation = 3.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LedgerAvatar(emoji.ifBlank { scene.emoji }, hexOf(accent), size = 42.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            name,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(3.dp))
                        PillTag("${scene.emoji} ${scene.name}", accent, filled = true)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(t("累计支出"), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "¥0",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = accent
                )
                if (budgetCents > 0) {
                    Spacer(Modifier.height(8.dp))
                    ThinProgress(fraction = 0f, color = accent)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "预算 ${Money.format(budgetCents)} · 已用 0%",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        SectionCard(
            title = t("将自动生成的分类"),
            subtitle = "支出 ${scene.expenses.size} 个 · 收入 ${scene.incomes.size} 个"
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                CategoryPreviewGroup(t("支出"), scene.expenses.map { it.emoji to it.name }, accent)
                CategoryPreviewGroup(
                    t("收入"),
                    scene.incomes.map { it.emoji to it.name },
                    MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (scene.fields.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            SectionCard(title = t("场景专属字段"), subtitle = t("记一笔时会出现这些输入项")) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    scene.fields.forEach { f ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(5.dp)
                                    .clip(CircleShape)
                                    .background(accent)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(f.label, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            if (f.options.isNotEmpty()) {
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    f.options.joinToString(" / ") { it.label },
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun CategoryPreviewGroup(
    label: String,
    items: List<Pair<String, String>>,
    color: Color
) {
    Column {
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = color)
        Spacer(Modifier.height(5.dp))
        Text(
            items.joinToString("  ") { "${it.first}${it.second}" },
            fontSize = 11.sp,
            lineHeight = 18.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
        )
    }
}

private fun hexOf(c: Color): String = "#%08X".format(c.toArgbLong())

private fun Color.toArgbLong(): Long {
    val a = (alpha * 255).toInt().toLong()
    val r = (red * 255).toInt().toLong()
    val g = (green * 255).toInt().toLong()
    val b = (blue * 255).toInt().toLong()
    return (a shl 24) or (r shl 16) or (g shl 8) or b
}
