package com.foldledger.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foldledger.data.ScenePresets
import com.foldledger.ui.adaptive.FoldEnv
import com.foldledger.ui.theme.SurfaceCard
import com.foldledger.ui.theme.Radius
import com.foldledger.ui.theme.Space
import com.foldledger.ui.theme.dividerBrush
import com.foldledger.ui.theme.glassChip
import com.foldledger.util.Money

/**
 * 账本页里的**内联**创建面板。
 *
 * 和弹层版本的区别就在「位置」：不再是盖住整页的抽屉，而是直接长在账本页顶部。
 * 打开时列表先平滑滚到它那儿（`animateScrollToItem(0)`），面板再从下方上滑淡入 ——
 * 于是感觉是「页面把我带到创建的位置」，而不是「跳出一个新页面」。
 *
 * 宽屏下定高 600dp 并左右分栏（左填信息、右实时预览）。定高是必要的：
 * 这个面板被插在懒加载列表里，高度约束是无限的，里面一旦出现可滚动子节点就会崩。
 */
@Composable
fun CreateLedgerInlinePanel(
    env: FoldEnv,
    onCancel: () -> Unit,
    onConfirm: (
        name: String,
        sceneKey: String,
        emoji: String,
        colorHex: String,
        budgetCents: Long,
        note: String
    ) -> Unit,
    modifier: Modifier = Modifier
) {
    // 用 rememberSaveable 而不是 remember：
    // 面板在单栏模式下是懒列表的一项，被滚出屏幕后会被回收，普通 remember 的状态会丢。
    // 懒列表对带 key 的项有 SaveableStateHolder，用 rememberSaveable 就能跨回收保留。
    var sceneKey by rememberSaveable { mutableStateOf(ScenePresets.all.first().key) }
    var name by rememberSaveable { mutableStateOf("") }
    var emoji by rememberSaveable { mutableStateOf(ScenePresets.byKey(sceneKey).emoji) }
    var colorHex by rememberSaveable { mutableStateOf(ScenePresets.byKey(sceneKey).colorHex) }
    var budgetText by rememberSaveable {
        mutableStateOf(
            ScenePresets.byKey(sceneKey).suggestedBudgetYuan.takeIf { it > 0 }?.toString() ?: ""
        )
    }
    var note by rememberSaveable { mutableStateOf("") }

    val scene = ScenePresets.byKey(sceneKey)

    val pickScene: (String) -> Unit = { key ->
        sceneKey = key
        val s = ScenePresets.byKey(key)
        emoji = s.emoji
        colorHex = s.colorHex
        if (name.isBlank() || ScenePresets.all.any { it.name + "账本" == name }) {
            name = "${s.name}账本"
        }
        budgetText = s.suggestedBudgetYuan.takeIf { it > 0 }?.toString() ?: ""
    }

    val confirm: () -> Unit = {
        onConfirm(
            name.ifBlank { "${scene.name}账本" },
            sceneKey,
            emoji.ifBlank { scene.emoji },
            colorHex,
            Money.parseToCents(budgetText.ifBlank { "0" }) ?: 0L,
            note
        )
    }

    // 入场/退场动画由调用方（账本页）用 AnimatedVisibility 统一驱动 ——
    // 这样进场和退场是对称的，也不会和内部动画叠加出"两段式"的怪效果。

    SurfaceCard(
        shape = RoundedCornerShape(Radius.panel),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(Modifier.fillMaxWidth()) {

            // 顶部一条「新建」标识，和普通卡片区分开
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = Space.xl, end = Space.lg, top = Space.lg),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .glassChip(
                            shape = RoundedCornerShape(Radius.pill),
                            tint = MaterialTheme.colorScheme.primary,
                            strength = 0.4f
                        )
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        "＋ 新建分账本",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(Modifier.width(Space.sm))
                Text(
                    "选个场景，分类自动配好",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (env.widthDp >= 900) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(600.dp)
                ) {
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState())
                    ) {
                        FormColumn(
                            modifier = Modifier.fillMaxWidth(),
                            title = "告诉它叫什么",
                            name = name, onName = { name = it },
                            emoji = emoji, onEmoji = { emoji = it },
                            colorHex = colorHex, onColor = { colorHex = it },
                            budgetText = budgetText, onBudget = { budgetText = it },
                            note = note, onNote = { note = it },
                            sceneKey = sceneKey, lockedScene = false, onPickScene = pickScene,
                            onQuickCreate = null,
                            onConfirm = confirm, onCancel = onCancel
                        )
                    }
                    Box(
                        Modifier
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(dividerBrush())
                    )
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
                FormColumn(
                    modifier = Modifier.fillMaxWidth(),
                    title = "告诉它叫什么",
                    name = name, onName = { name = it },
                    emoji = emoji, onEmoji = { emoji = it },
                    colorHex = colorHex, onColor = { colorHex = it },
                    budgetText = budgetText, onBudget = { budgetText = it },
                    note = note, onNote = { note = it },
                    sceneKey = sceneKey, lockedScene = false, onPickScene = pickScene,
                    onQuickCreate = null,
                    onConfirm = confirm, onCancel = onCancel
                )
                Spacer(Modifier.height(Space.md))
            }
        }
    }
}
