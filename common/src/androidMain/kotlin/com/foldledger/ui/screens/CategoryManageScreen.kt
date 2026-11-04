package com.foldledger.ui.screens

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.foldledger.data.db.CategoryEntity
import com.foldledger.data.db.Direction
import com.foldledger.ui.adaptive.AdaptiveScaffold
import com.foldledger.ui.adaptive.FoldEnv
import com.foldledger.ui.anim.Motion
import com.foldledger.ui.anim.clickableScale
import com.foldledger.ui.components.CategoryIcon
import com.foldledger.ui.components.CategoryIconBadge
import com.foldledger.ui.components.GlassDialog
import com.foldledger.ui.components.PillTag
import com.foldledger.ui.theme.CoolGreen
import com.foldledger.ui.theme.SurfaceCard
import com.foldledger.ui.theme.Layout
import com.foldledger.ui.theme.LocalDirectionColors
import com.foldledger.ui.theme.Radius
import com.foldledger.ui.theme.Space
import com.foldledger.ui.theme.cardSurface
import com.foldledger.ui.theme.cardBorder
import com.foldledger.ui.theme.dividerBrush
import com.foldledger.ui.theme.softShadow
import com.foldledger.ui.theme.glassChip
import com.foldledger.ui.theme.glassSelectChip
import com.foldledger.ui.vm.CategoryViewModel
import com.foldledger.util.IconRef
import com.foldledger.util.IconStore
import com.foldledger.util.Money
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 分类管理。
 *
 * 图标不再是「手打 Emoji」的输入框，而是点开一个选择器：预置图标库（18 组 ≈ 300 个，
 * 支持中文关键词搜索）+ 从相册上传自己的图片。
 *
 * 删除预置分类会二次确认，并在确认文案里告诉用户「可以去右上角恢复」——
 * 右上角那个按钮会把该场景模板里缺失的预置分类一键补齐。
 */
@Composable
fun CategoryManageScreen(
    env: FoldEnv,
    ledgerId: Long,
    onBack: () -> Unit
) {
    val vm: CategoryViewModel = viewModel(
        key = "cat-$ledgerId",
        factory = CategoryViewModel.factory(ledgerId)
    )
    val categories by vm.categories.collectAsStateWithLifecycle()
    // v7.0：近 3 月月均支出，作为设预算的锚点
    val budgetSuggestions by vm.budgetSuggestion.collectAsStateWithLifecycle()
    val missing by vm.missingPresetCount.collectAsStateWithLifecycle()
    val sceneName by vm.sceneName.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // ---- 新分类草稿（按方向分开，双栏时互不干扰）----
    val draftName = remember { mutableStateMapOf<Int, String>() }
    val draftIcon = remember { mutableStateMapOf<Int, String>() }

    // ---- 图标选择器的通用挂载点：谁打开谁提供「应用回调」----
    var pickerOpen by remember { mutableStateOf(false) }
    var pickerCurrent by remember { mutableStateOf<String?>(null) }
    var pickerApply by remember { mutableStateOf<((String) -> Unit)?>(null) }

    var editing by remember { mutableStateOf<CategoryEntity?>(null) }
    var deleting by remember { mutableStateOf<CategoryEntity?>(null) }
    var confirmRestore by remember { mutableStateOf(false) }

    // ---- 单栏时的方向 Tab（v3.1.1 提升到屏幕级：折叠/展开不再丢回去）----
    var tab by remember { mutableStateOf(Direction.EXPENSE) }

    // ---- 三个列表的滚动状态各自提升（v3.1.1）：折叠/展开不跳回顶部 ----
    val expenseListState = rememberLazyListState()
    val incomeListState = rememberLazyListState()
    val categoryListState = rememberLazyListState()
    var toast by remember { mutableStateOf<String?>(null) }

    // 编辑框的名称/图标提到这一层：
    // 打开图标选择器时要先把 AlertDialog 藏起来（Dialog 是独立窗口，永远盖在全屏弹层之上），
    // 状态若留在 Dialog 内部，藏一次就被重建、用户填的东西就丢了。
    var editName by remember { mutableStateOf("") }
    var editIcon by remember { mutableStateOf("") }
    var editBudget by remember { mutableStateOf("") }

    // v9.7.2：toast 用「文案 + 序号」驱动 —— 只用文案当 key 时，同一句提示连续触发
    // 两次不会重启计时（沿用第一次的剩余时间，第二句一闪而过）。
    var toastSeq by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val showToast: (String) -> Unit = { msg ->
        toast = msg
        toastSeq++
    }

    LaunchedEffect(toastSeq) {
        if (toast != null) {
            delay(2600)
            toast = null
        }
    }

    val openPicker: (String?, (String) -> Unit) -> Unit = { current, apply ->
        pickerCurrent = current
        pickerApply = apply
        pickerOpen = true
    }

    val openEditor: (CategoryEntity) -> Unit = { c ->
        editName = c.name
        editIcon = c.emoji
        editBudget = if (c.budgetCents > 0) Money.centsToPlain(c.budgetCents) else ""
        editing = c
    }

    // ⚠️ 全屏弹层必须与 AdaptiveScaffold **同级**放在最外层 Box 里。
    // 放进去（Scaffold 的 content 里）会被顶栏盖住；放进 LazyColumn 的项里更糟 ——
    // 懒列表给子项的高度约束是无限的，fillMaxSize() 会退化成"包住内容"。
    Box(Modifier.fillMaxSize()) {

        AdaptiveScaffold(
            env = env,
            title = "分类管理",
            subtitle = "共 ${categories.size} 个分类 · 删了预置分类也能一键找回",
            onBack = onBack,
            showNavigation = false,
            actions = {
                Box {
                    IconButton(onClick = { confirmRestore = true }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "恢复预置分类")
                    }
                    if (missing > 0) {
                        Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .padding(top = 6.dp, end = 4.dp)
                                .size(16.dp)
                                .clip(RoundedCornerShape(Radius.pill))
                                .background(MaterialTheme.colorScheme.error),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                if (missing > 9) "9+" else missing.toString(),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onError,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        ) { padding ->
            val side = Layout.sidePadding(env.widthDp)
            // ⚠️ 双栏时**不能**再用按整窗宽算出来的居中留白。
            // `Layout.sidePadding` 是 20 + (窗口宽 - 760) / 2：在 1366dp 上等于 323dp。
            // 而双栏下每一栏只有约半屏宽（还要先扣掉 80dp 导航栏），
            // 再左右各减 323dp 之后子项拿到的可用宽度会被压成 0 ——
            // 分类行、输入框、按钮全部塌成看不见也点不动。这里改成固定的栏内留白。
            val panePad = Space.pageH
            Box(
                Modifier
                    .padding(padding)
                    .fillMaxSize()
            ) {
                if (env.twoPane) {
                    Row(Modifier.fillMaxSize()) {
                        CategoryColumn(
                            title = "支出分类",
                            direction = Direction.EXPENSE,
                            items = categories.filter { it.direction == Direction.EXPENSE },
                            draftName = draftName[Direction.EXPENSE].orEmpty(),
                            draftIcon = draftIcon[Direction.EXPENSE],
                            onDraftName = { draftName[Direction.EXPENSE] = it },
                            onDraftIcon = { draftIcon[Direction.EXPENSE] = it },
                            onAdd = { n, icon ->
                                vm.add(n, icon, Direction.EXPENSE)
                                draftName[Direction.EXPENSE] = ""
                                draftIcon.remove(Direction.EXPENSE)
                            },
                            onOpenPicker = openPicker,
                            onEdit = openEditor,
                            onDelete = { deleting = it },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            sidePadding = panePad,
                            listState = expenseListState
                        )
                        Box(
                            Modifier
                                .width(1.dp)
                                .fillMaxHeight()
                                .background(dividerBrush())
                        )
                        CategoryColumn(
                            title = "收入分类",
                            direction = Direction.INCOME,
                            items = categories.filter { it.direction == Direction.INCOME },
                            draftName = draftName[Direction.INCOME].orEmpty(),
                            draftIcon = draftIcon[Direction.INCOME],
                            onDraftName = { draftName[Direction.INCOME] = it },
                            onDraftIcon = { draftIcon[Direction.INCOME] = it },
                            onAdd = { n, icon ->
                                vm.add(n, icon, Direction.INCOME)
                                draftName[Direction.INCOME] = ""
                                draftIcon.remove(Direction.INCOME)
                            },
                            onOpenPicker = openPicker,
                            onEdit = openEditor,
                            onDelete = { deleting = it },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            sidePadding = panePad,
                            listState = incomeListState
                        )
                    }
                } else {
                    Column(Modifier.fillMaxSize()) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(start = side, end = side, top = Space.sm, bottom = Space.xs),
                            horizontalArrangement = Arrangement.spacedBy(Space.sm)
                        ) {
                            DirectionChip(
                                "支出 ${categories.count { it.direction == Direction.EXPENSE }}",
                                tab == Direction.EXPENSE
                            ) { tab = Direction.EXPENSE }
                            DirectionChip(
                                "收入 ${categories.count { it.direction == Direction.INCOME }}",
                                tab == Direction.INCOME
                            ) { tab = Direction.INCOME }
                        }
                        CategoryColumn(
                            title = null,
                            direction = tab,
                            items = categories.filter { it.direction == tab },
                            draftName = draftName[tab].orEmpty(),
                            draftIcon = draftIcon[tab],
                            onDraftName = { draftName[tab] = it },
                            onDraftIcon = { draftIcon[tab] = it },
                            onAdd = { n, icon ->
                                vm.add(n, icon, tab)
                                draftName[tab] = ""
                                draftIcon.remove(tab)
                            },
                            onOpenPicker = openPicker,
                            onEdit = openEditor,
                            onDelete = { deleting = it },
                            modifier = Modifier.fillMaxSize(),
                            sidePadding = side,
                            listState = categoryListState
                        )
                    }
                }

                // ---- 轻提示 ----
                AnimatedVisibility(
                    visible = toast != null,
                    enter = fadeIn(tween(Motion.FAST)),
                    exit = fadeOut(tween(Motion.FAST)),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = Space.xxl)
                ) {
                    Surface(
                        shape = RoundedCornerShape(Radius.pill),
                        color = CoolGreen.copy(alpha = 0.92f)
                    ) {
                        Text(
                            toast.orEmpty(),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = Space.xl, vertical = 10.dp)
                        )
                    }
                }
            }
        }

        // ---------------- 图标选择器 ----------------
        if (pickerOpen) {
            IconPickerOverlay(
                env = env,
                current = pickerCurrent,
                onDismiss = {
                    pickerOpen = false
                    pickerApply = null
                },
                onPick = { ref ->
                    pickerApply?.invoke(ref)
                    pickerOpen = false
                    pickerApply = null
                },
                onUpload = { uri: Uri ->
                    scope.launch {
                        val fileName = IconStore.import(context, uri)
                        if (fileName != null) {
                            pickerApply?.invoke(IconRef.of(fileName))
                            showToast("已使用你上传的图片作为图标")
                        } else {
                            showToast("图片读取失败，换一张试试")
                        }
                        pickerOpen = false
                        pickerApply = null
                    }
                }
            )
        }

        // ---------------- 编辑分类（选择器打开时先让位） ----------------
        if (editing != null && !pickerOpen) {
            EditCategoryDialog(
                name = editName,
                icon = editIcon,
                budget = editBudget,
                budgetSuggestion = budgetSuggestions[editing!!.name],
                direction = editing!!.direction,
                onName = { editName = it },
                onIcon = { editIcon = it },
                onBudget = { editBudget = it },
                onRequestIcon = { current, apply -> openPicker(current, apply) },
                onSave = {
                    vm.update(editing!!, editName, editIcon)
                    vm.setBudget(editing!!, Money.parseToCents(editBudget.ifBlank { "0" }) ?: 0L)
                    editing = null
                },
                onDismiss = { editing = null }
            )
        }
    }

    // ---------------- 删除二次确认 ----------------
    deleting?.let { target ->
        GlassDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除「${target.name}」？") },
            text = {
                Column {
                    if (target.builtIn) {
                        Text(
                            "这是「$sceneName」场景的预置分类。",
                            fontSize = 13.sp,
                            lineHeight = 20.sp
                        )
                        Spacer(Modifier.height(Space.sm))
                        Text(
                            "删掉之后，可以在右上角 ↺ 里一键恢复回来，所以你随时能反悔。",
                            fontSize = 13.sp,
                            lineHeight = 20.sp,
                            color = CoolGreen
                        )
                    } else {
                        Text(
                            "这是你自己新建的分类，删除后记录里就找不回来了（需要重新创建）。",
                            fontSize = 13.sp,
                            lineHeight = 20.sp
                        )
                    }
                    Spacer(Modifier.height(Space.md))
                    Text(
                        "已经记过的流水不受影响 —— 分类名和图标是冗余存在每条流水里的，历史记录照常显示。",
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.delete(target.id)
                    deleting = null
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("取消") }
            }
        )
    }

    // ---------------- 恢复预置分类 ----------------
    if (confirmRestore) {
        GlassDialog(
            onDismissRequest = { confirmRestore = false },
            title = { Text("恢复预置分类？") },
            text = {
                Column {
                    if (missing > 0) {
                        Text(
                            "「$sceneName」场景一共该有 ${categories.size + missing} 个预置分类，现在还缺 $missing 个，这次会把它们补上。",
                            fontSize = 13.sp,
                            lineHeight = 20.sp
                        )
                        Spacer(Modifier.height(Space.sm))
                        Text(
                            "已有的分类不会被改动 —— 你改过的名字、换过的图标都会原样保留。",
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            "「$sceneName」场景的预置分类一个都不缺，不需要恢复。",
                            fontSize = 13.sp,
                            lineHeight = 20.sp
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = missing > 0,
                    onClick = {
                        confirmRestore = false
                        vm.restorePresetCategories { n ->
                            showToast(if (n > 0) "已恢复 $n 个预置分类" else "没有需要恢复的分类")
                        }
                    }
                ) { Text("恢复") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRestore = false }) { Text("取消") }
            }
        )
    }
}

// ============================================================
//  单方向的分栏
// ============================================================

@Composable
private fun CategoryColumn(
    title: String?,
    direction: Int,
    items: List<CategoryEntity>,
    draftName: String,
    draftIcon: String?,
    onDraftName: (String) -> Unit,
    onDraftIcon: (String) -> Unit,
    onAdd: (String, String) -> Unit,
    onOpenPicker: (String?, (String) -> Unit) -> Unit,
    onEdit: (CategoryEntity) -> Unit,
    onDelete: (CategoryEntity) -> Unit,
    modifier: Modifier,
    sidePadding: androidx.compose.ui.unit.Dp,
    /** v3.1.1：由屏幕级传入，折叠/展开（双栏 ↔ 单栏）不丢滚动位置 */
    listState: LazyListState
) {
    val dc = LocalDirectionColors.current
    val accent = if (direction == Direction.EXPENSE) dc.expense else dc.income

    Column(modifier) {
        if (title != null) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(
                    start = sidePadding, top = Space.md, bottom = Space.sm
                )
            )
        }

        // ---------------- 新增分类 ----------------
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = sidePadding, vertical = Space.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.sm)
        ) {
            // 图标按钮：点开选择器。已经是「选」而不是「打字」了
            CategoryIconBadge(
                ref = draftIcon,
                size = 52.dp,
                fontSize = 24,
                container = accent.copy(alpha = 0.12f),
                modifier = Modifier.clickableScale(pressedScale = 0.9f) {
                    onOpenPicker(draftIcon) { picked -> onDraftIcon(picked) }
                }
            )
            OutlinedTextField(
                value = draftName,
                onValueChange = onDraftName,
                label = { Text("新分类名称") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Box(
                Modifier
                    .glassChip(
                        shape = RoundedCornerShape(Radius.md),
                        tint = if (draftName.isBlank()) null else accent,
                        strength = if (draftName.isBlank()) 0.6f else 1.9f
                    )
                    .clickableScale(enabled = draftName.isNotBlank()) {
                        onAdd(draftName, draftIcon.orEmpty())
                    }
            ) {
                Text(
                    "添加",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (draftName.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = Space.lg, vertical = 15.dp)
                )
            }
        }

        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(
                start = sidePadding, end = sidePadding, top = Space.xs, bottom = Space.huge
            ),
            verticalArrangement = Arrangement.spacedBy(Space.sm)
        ) {
            items(items, key = { it.id }) { c ->
                CategoryRow(
                    category = c,
                    accent = accent,
                    modifier = Modifier.animateItem(),
                    onEdit = { onEdit(c) },
                    onDelete = { onDelete(c) }
                )
            }
        }
    }
}

@Composable
private fun CategoryRow(
    category: CategoryEntity,
    accent: Color,
    modifier: Modifier = Modifier,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    SurfaceCard(
        shape = RoundedCornerShape(Radius.card),
        color = cardSurface(),
        elevation = 4.dp,
        modifier = modifier
            .fillMaxWidth()
            .clickableScale(pressedScale = 0.985f) { onEdit() }
    ) {
        Row(
            Modifier.padding(
                start = Space.lg, end = Space.md, top = Space.sm, bottom = Space.sm
            ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CategoryIconBadge(
                ref = category.emoji,
                size = 40.dp,
                fontSize = 20,
                // v4.4.0：与流水行同款粉彩分类块（传主题灰即按图标哈希取粉彩）
                container = MaterialTheme.colorScheme.surfaceVariant
            )
            Spacer(Modifier.width(Space.md))
            Column(Modifier.weight(1f)) {
                Text(
                    category.name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (category.builtIn) {
                        PillTag("预置", accent, filled = true)
                        Spacer(Modifier.width(Space.sm))
                    }
                    Text(
                        when {
                            category.budgetCents > 0 -> "月预算 ${Money.format(category.budgetCents)}"
                            IconRef.isCustom(category.emoji) -> "自定义图片"
                            else -> "点一下改名、换图标或设预算"
                        },
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun DirectionChip(label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .glassSelectChip(active, shape = RoundedCornerShape(Radius.pill))
            .clickableScale(pressedScale = 0.94f) { onClick() }
    ) {
        Text(
            label,
            fontSize = 13.sp,
            lineHeight = 19.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            color = if (active) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Space.xl, vertical = 9.dp)
        )
    }
}

/**
 * 编辑分类：改名 + 换图标。
 *
 * 名称与图标的状态**由外层持有**（不在这里 `remember`）—— 因为点图标要先关掉这个
 * AlertDialog 才能让全屏选择器浮上来（Dialog 是独立窗口，永远在最上层），
 * 状态留在这里会被那次重建清空。图标选择器本身也是通过 [onRequestIcon] 借
 * 外层那个统一挂载点打开的，避免出现两个弹层打架。
 */
@Composable
private fun EditCategoryDialog(
    name: String,
    icon: String,
    budget: String,
    /** v7.0：近 3 月月均支出（分），作为设预算的锚点；null = 没有历史 */
    budgetSuggestion: Long?,
    direction: Int,
    onName: (String) -> Unit,
    onIcon: (String) -> Unit,
    onBudget: (String) -> Unit,
    onRequestIcon: (String?, (String) -> Unit) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit
) {
    val accent = if (direction == Direction.EXPENSE) {
        LocalDirectionColors.current.expense
    } else {
        LocalDirectionColors.current.income
    }

    GlassDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑分类") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CategoryIconBadge(
                        ref = icon,
                        size = 56.dp,
                        fontSize = 24,
                        container = accent.copy(alpha = 0.14f),
                        modifier = Modifier.clickableScale(pressedScale = 0.9f) {
                            onRequestIcon(icon) { picked -> onIcon(picked) }
                        }
                    )
                    Spacer(Modifier.width(Space.md))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "点左侧图标可以换",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            "预置图标库 / 上传自己的图片",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = onName,
                    label = { Text("分类名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = budget,
                    onValueChange = { input ->
                        if (input.all { it.isDigit() || it == '.' } && input.length <= 12) {
                            onBudget(input)
                        }
                    },
                    label = { Text("月度预算（元，可留空）") },
                    placeholder = { Text("如：1500") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                // v7.0：给一个历史锚点，用户不用凭空想「该设多少」
                if (budgetSuggestion != null && budgetSuggestion > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "近 3 月月均 ${Money.format(budgetSuggestion)}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "用这个值 ›",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .clickableScale(pressedScale = 0.92f) {
                                    onBudget(Money.centsToPlain(budgetSuggestion))
                                }
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSave) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
