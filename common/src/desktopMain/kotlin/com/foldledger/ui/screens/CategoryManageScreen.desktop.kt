package com.foldledger.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.foldledger.data.CategoryIconLibrary
import com.foldledger.data.db.CategoryEntity
import com.foldledger.data.db.Direction
import com.foldledger.ui.adaptive.AdaptiveScaffold
import com.foldledger.ui.adaptive.FoldEnv
import com.foldledger.ui.anim.clickableScale
import com.foldledger.ui.components.CategoryIcon
import com.foldledger.ui.components.GlassDialog
import com.foldledger.ui.components.PillTag
import com.foldledger.ui.theme.CoolGreen
import com.foldledger.ui.theme.LocalDirectionColors
import com.foldledger.ui.theme.Radius
import com.foldledger.ui.theme.glassChip
import com.foldledger.ui.theme.glassSelectChip
import com.foldledger.ui.theme.pendingTone
import com.foldledger.ui.vm.CategoryViewModel
import com.foldledger.util.DesktopIconStore
import com.foldledger.util.IconRef
import com.foldledger.util.Money
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter
import kotlin.coroutines.resume
import com.foldledger.ui.l10n.t

/**
 * 分类管理（桌面版，M3）。
 *
 * 覆盖核心交互：收支切换 / 增删改 / 图标选择器（预置库可搜索 + 上传自定义图片）/
 * 支出预算（近三月月均作锚点）/ 一键恢复场景预置分类。
 *
 * v16.3 起自定义图片与 Android 共用 `img:<fileName>` 引用，显示走共享 `CategoryIcon`。
 */
@Composable
fun DesktopCategoryManageScreen(
    env: FoldEnv,
    ledgerId: Long,
    onBack: () -> Unit
) {
    val vm: CategoryViewModel = viewModel(
        key = "cat-$ledgerId",
        factory = CategoryViewModel.factory(ledgerId)
    )
    val categories by vm.categories.collectAsStateWithLifecycle()
    val sceneName by vm.sceneName.collectAsStateWithLifecycle()
    val missing by vm.missingPresetCount.collectAsStateWithLifecycle()
    val suggestions by vm.budgetSuggestion.collectAsStateWithLifecycle()

    var direction by remember { mutableStateOf(Direction.EXPENSE) }
    var editing by remember { mutableStateOf<CategoryEntity?>(null) }
    var adding by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf<CategoryEntity?>(null) }
    var confirmingRestore by remember { mutableStateOf(false) }

    AdaptiveScaffold(
        env = env,
        title = t("分类管理"),
        subtitle = sceneName,
        onBack = onBack,
        showNavigation = false
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(
                Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                DirectionChip(t("支出"), direction == Direction.EXPENSE) { direction = Direction.EXPENSE }
                DirectionChip(t("收入"), direction == Direction.INCOME) { direction = Direction.INCOME }
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val list = categories.filter { it.direction == direction }
                if (list.isEmpty()) {
                    item {
                        Text(
                            "还没有${if (direction == Direction.EXPENSE) "支出" else "收入"}分类，点下面新建一个",
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            // v16.3.1：textAlign 需要撑满宽度才生效，原来 wrap-content 仍靠左
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp),
                            textAlign = TextAlign.Center
                        )
                    }
                }
                items(list, key = { it.id }) { c ->
                    val dc = LocalDirectionColors.current
                    // v16.3：走共享 CategoryIcon，自定义图片与 Android 一样显示真实图标，
                    // 图片文件缺失时由共享组件降级成 🖼️（不再一律画成 🏷️）
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .glassChip(shape = RoundedCornerShape(Radius.card), strength = 0.55f)
                            .clickableScale(pressedScale = 0.97f, clipShape = RoundedCornerShape(Radius.card)) {
                                editing = c
                            }
                            .padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CategoryIcon(ref = c.emoji, size = 24.dp, fontSize = 20)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                c.name,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (c.budgetCents > 0) {
                                Text(
                                    "预算 ${Money.format(c.budgetCents)}",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (c.budgetCents > 0) {
                            PillTag(t("已设预算"), CoolGreen, filled = false)
                            Spacer(Modifier.width(6.dp))
                        }
                        IconButton(onClick = { confirmingDelete = c }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = t("删除分类"),
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
                item {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .glassChip(
                                shape = RoundedCornerShape(Radius.card),
                                tint = MaterialTheme.colorScheme.primary,
                                strength = 1.2f
                            )
                            .clickableScale(pressedScale = 0.97f, clipShape = RoundedCornerShape(Radius.card)) {
                                adding = true
                            }
                            .padding(vertical = 13.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "＋ 新建${if (direction == Direction.EXPENSE) "支出" else "收入"}分类",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                if (missing > 0) {
                    item {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .glassChip(shape = RoundedCornerShape(Radius.card), strength = 0.55f)
                                .clickableScale(pressedScale = 0.97f, clipShape = RoundedCornerShape(Radius.card)) {
                                    confirmingRestore = true
                                }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("♻️", fontSize = 17.sp)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text("恢复场景预置分类", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                                Text(
                                    "还缺 $missing 个「$sceneName」模板里的分类",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            PillTag("$missing", pendingTone(), filled = true)
                        }
                    }
                }
            }
        }
    }

    if (adding || editing != null) {
        CategoryEditDialog(
            vm = vm,
            category = editing,
            direction = direction,
            suggestionCents = suggestions[editing?.name] ?: 0L,
            onDismiss = { adding = false; editing = null }
        )
    }
    confirmingDelete?.let { c ->
        GlassDialog(
            onDismissRequest = { confirmingDelete = null },
            title = { Text("删除分类「${c.name}」？") },
            text = {
                Text(
                    "已有流水会保留，但会变成「未分类」。\n删掉的分类在回收站里也找不回来。",
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.delete(c.id)
                    confirmingDelete = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = null }) { Text("取消") }
            }
        )
    }
    if (confirmingRestore) {
        GlassDialog(
            onDismissRequest = { confirmingRestore = false },
            title = { Text("恢复预置分类？") },
            text = {
                Text(
                    "会把「$sceneName」模板里缺失的 $missing 个预置分类补回来。已有分类不受影响。",
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.restorePresetCategories()
                    confirmingRestore = false
                }) { Text("恢复") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingRestore = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun DirectionChip(label: String, active: Boolean, onClick: () -> Unit) {
    // v16.3.1：与 Android DirectionChip 同款 —— 选中靠「浓度 + 内棱 + 抬影」三件事，
    // 不再只有浓度一件；层级（字重 / 颜色 / 行高）也逐项对齐。
    Box(
        Modifier
            .glassSelectChip(active, shape = RoundedCornerShape(50))
            .clickableScale(pressedScale = 0.93f, clipShape = RoundedCornerShape(50)) { onClick() }
    ) {
        Text(
            label,
            fontSize = 13.sp,
            lineHeight = 19.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            color = if (active) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}

/** 新建/编辑分类对话框：名称 + 可搜索图标选择器 + （支出）预算滑块。 */
@Composable
private fun CategoryEditDialog(
    vm: CategoryViewModel,
    category: CategoryEntity?,
    direction: Int,
    suggestionCents: Long,
    onDismiss: () -> Unit
) {
    val isEdit = category != null
    var name by remember(category) { mutableStateOf(category?.name ?: "") }
    // v16.3：桌面也能保留 / 编辑自定义图片引用（以前一律降级成 🏷️）
    var icon by remember(category) { mutableStateOf(category?.emoji ?: "🏷️") }
    var budgetCents by remember(category) { mutableStateOf(category?.budgetCents ?: 0L) }
    var search by remember { mutableStateOf("") }
    var groupIndex by remember { mutableStateOf(0) }
    val uploadScope = rememberCoroutineScope()
    var uploadHint by remember { mutableStateOf<String?>(null) }

    val groups = CategoryIconLibrary.groups
    val query = search.trim()
    val filtered = remember(query, groupIndex) {
        if (query.isNotBlank()) {
            groups.flatMap { it.icons }.filter {
                it.emoji.contains(query) || it.keywords.contains(query)
            }
        } else {
            groups[groupIndex.coerceIn(0, groups.lastIndex)].icons
        }
    }
    // 预算滑块上限：近三月月均 ×2（至少 500 元档）
    val budgetMax = ((suggestionCents.coerceAtLeast(0L)) * 2).coerceAtLeast(50_000L)
    val dc = LocalDirectionColors.current

    GlassDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isEdit) t("编辑分类") else "新建${if (direction == Direction.EXPENSE) "支出" else "收入"}分类") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it; if (it.isNotBlank()) groupIndex = 0 },
                    label = { Text("搜图标（如：吃饭 / 交通）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                if (query.isBlank()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(groups.size) { gi ->
                            Box(
                                Modifier
                                    .glassChip(shape = RoundedCornerShape(50), strength = if (gi == groupIndex) 1.0f else 0.4f)
                                    .clickableScale(pressedScale = 0.93f, clipShape = RoundedCornerShape(50)) {
                                        groupIndex = gi
                                    }
                            ) {
                                Text(
                                    "${groups[gi].emoji} ${groups[gi].name}",
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                // 图标网格（搜索命中时收起分组只显示结果）
                val shown = filtered.take(30)
                if (shown.isEmpty()) {
                    Text("没有匹配的图标", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        shown.forEach { preset ->
                            val active = preset.emoji == icon
                            Box(
                                Modifier
                                    .size(42.dp)
                                    .clip(CircleShape)
                                    .glassChip(
                                        shape = CircleShape,
                                        tint = if (active) MaterialTheme.colorScheme.primary else null,
                                        strength = if (active) 1.1f else 0.4f
                                    )
                                    .clickableScale(pressedScale = 0.90f, clipShape = CircleShape) {
                                        icon = preset.emoji
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(preset.emoji, fontSize = 20.sp)
                            }
                        }
                    }
                }
                // v16.3：桌面补齐「上传自定义图标」，与 Android 的 Photo Picker 对齐。
                // 仍走共享的 `img:<fileName>` 引用，不改 schema / 备份格式。
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        uploadScope.launch {
                            val picked = pickIconFile()
                            if (picked == null) {
                                uploadHint = t("已取消选择图片")
                                return@launch
                            }
                            val name = DesktopIconStore.import(picked)
                            if (name == null) {
                                uploadHint = t("这张图片读不出来，换一张试试")
                            } else {
                                icon = IconRef.of(name)
                                uploadHint = null
                            }
                        }
                    }) { Text("上传图片", fontSize = 13.sp) }
                    if (IconRef.isCustom(icon)) {
                        Spacer(Modifier.width(8.dp))
                        CategoryIcon(ref = icon, size = 24.dp, fontSize = 20)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            t("已选自定义图标"),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                uploadHint?.let {
                    Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                if (direction == Direction.EXPENSE && isEdit) {
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("预算", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            Money.format(budgetCents),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = if (budgetCents > 0) dc.expense else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.weight(1f))
                        if (suggestionCents > 0) {
                            Text(
                                "参考月均 ${Money.format(suggestionCents)}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Slider(
                        value = budgetCents.toFloat().coerceIn(0f, budgetMax.toFloat()),
                        onValueChange = { budgetCents = it.toLong() },
                        valueRange = 0f..budgetMax.toFloat()
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("¥0", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            "月均的 2 倍（¥${budgetMax / 100}）",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isBlank()) return@TextButton
                if (isEdit) {
                    vm.update(category!!, name, icon)
                    if (direction == Direction.EXPENSE) vm.setBudget(category!!, budgetCents)
                } else {
                    vm.add(name, icon, direction)
                }
                onDismiss()
            }) { Text(if (isEdit) t("保存") else t("新建")) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/**
 * 选一张本地图片做分类图标。
 *
 * ⚠️ 与备份目录选择同款防吞机制（v16.2.5 修「点了导出没反应」）：JFileChooser 必须在
 * EDT 上、用 `invokeLater` 排到本次鼠标释放之后，并挂到主窗口做模态，绝不能在
 * 事件派发栈里同步 `showOpenDialog(null)`。
 */
private suspend fun pickIconFile(): File? = suspendCancellableCoroutine { cont ->
    SwingUtilities.invokeLater {
        val picked = runCatching {
            val chooser = JFileChooser().apply {
                dialogTitle = t("选一张分类图标")
                isAcceptAllFileFilterUsed = false
                fileFilter = FileNameExtensionFilter(
                    t("图片 (PNG / JPG / WEBP / BMP)"), "png", "jpg", "jpeg", "webp", "bmp"
                )
                File(System.getProperty("user.home"), "Pictures").takeIf { it.isDirectory }
                    ?.let { currentDirectory = it }
            }
            if (chooser.showOpenDialog(appWindow()) == JFileChooser.APPROVE_OPTION) {
                chooser.selectedFile
            } else null
        }.getOrNull()
        if (cont.isActive) cont.resume(picked)
    }
}

private fun appWindow(): java.awt.Window? {
    val windows = java.awt.Window.getWindows()
    return windows.firstOrNull { it is java.awt.Frame && it.isVisible && it.title == "滴水记账" }
        ?: windows.firstOrNull { it is java.awt.Frame && it.isVisible }
}
