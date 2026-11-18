package com.foldledger.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.foldledger.data.FieldKind
import com.foldledger.data.SceneField
import com.foldledger.data.ScenePreset
import com.foldledger.data.ScenePresets
import com.foldledger.data.TxnTemplate
import com.foldledger.data.db.AccountEntity
import com.foldledger.data.db.CategoryEntity
import com.foldledger.data.db.Direction
import com.foldledger.data.db.LedgerEntity
import com.foldledger.data.db.ProjectEntity
import com.foldledger.ui.adaptive.FoldEnv
import com.foldledger.ui.anim.Haptic
import com.foldledger.ui.anim.Motion
import com.foldledger.ui.anim.clickableScale
import com.foldledger.ui.components.AppToastBus
import com.foldledger.ui.components.CategoryIcon
import com.foldledger.ui.haptic.AppHaptics
import com.foldledger.ui.theme.LiquidButton
import com.foldledger.ui.theme.LocalDirectionColors
import com.foldledger.ui.theme.Radius
import com.foldledger.ui.theme.Space
import com.foldledger.ui.theme.TactileKey
import com.foldledger.ui.theme.cardSurface
import com.foldledger.ui.theme.glassChip
import com.foldledger.ui.theme.pressPulse
import com.foldledger.ui.theme.wellSurface
import com.foldledger.ui.vm.AddTxnUiState
import com.foldledger.ui.vm.AddTxnViewModel
import com.foldledger.ui.vm.TxnFormState
import com.foldledger.util.DateExt
import com.foldledger.util.Money
import com.foldledger.ui.l10n.t

/**
 * 记一笔 / 编辑一笔（外壳）。
 *
 * 只负责把两个全屏弹层排好顺序：**先记账弹层，再日期时间选择弹层**。
 * 两者都往父级 Box 里发射，排在后面的自然盖在上面。
 */
@Composable
fun AddTxnOverlay(
    env: FoldEnv,
    ledgerId: Long,
    editTxnId: Long?,
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    val vm: AddTxnViewModel = viewModel(
        key = "add-$ledgerId",
        factory = AddTxnViewModel.factory(ledgerId)
    )
    var datePickerOpen by remember { mutableStateOf(false) }

    // 每打开一次记账弹层，把连续记账的笔数归零（开关本身保留，属于会话偏好）
    LaunchedEffect(Unit) { AddTxnUiState.resetSession() }

    TxnEditor(
        env = env,
        vm = vm,
        editTxnId = editTxnId,
        onOpenDatePicker = { datePickerOpen = true },
        onDismiss = onDismiss,
        onSaved = onSaved
    )

    if (datePickerOpen) {
        DateTimePickerOverlay(
            env = env,
            initial = vm.form.occurredAt,
            onConfirm = { ts ->
                vm.setOccurredAt(ts)
                datePickerOpen = false
            },
            onDismiss = { datePickerOpen = false }
        )
    }
}

/** 分类区至少要占的高度：宁可键盘矮一点，也不能让分类被挤到要二次滑 */
private val PICK_AREA_MIN = 300.dp

/** 金额展示块的高度估算 */
private val AMOUNT_BLOCK = 104.dp

/** 键盘高度上下限 */
private val PAD_MIN = 148.dp
private val PAD_MAX = 252.dp

/**
 * 记账主体：分类 / 场景字段 / 备注 / 日期入口 / 金额键盘。
 *
 * ### 折叠态为什么会"键盘太大、分类要二次滑"
 * 两个原因，v1.8.0 都修了：
 *  1. **底部抽屉高度被写死**：`heightIn(max = 640.dp).fillMaxHeight(0.92f)` 的修饰符顺序
 *     会让 0.92 作用在 **640** 上 → 抽屉实际只有 **589dp**，分类区被压到不足 200dp。
 *     现在改为 `fillMaxHeight(0.94f)`（外屏实测约 810dp）。见 AppOverlay。
 *  2. **键盘高度是常量**：`PAD_HEIGHT = 232.dp` 不管屏幕多矮都占 232dp。
 *     现在按"所在区域剩余高度"分配，并给分类区预留 [PICK_AREA_MIN]；
 *     另加「收起键盘」开关，想改分类先收键盘，一步到位。
 */
@Composable
private fun TxnEditor(
    env: FoldEnv,
    vm: AddTxnViewModel,
    editTxnId: Long?,
    onOpenDatePicker: () -> Unit,
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    val ledger by vm.ledger.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val templates by vm.templates.collectAsStateWithLifecycle()
    // v5.1：可归入的项目 / 空间
    val projects by vm.projects.collectAsStateWithLifecycle()
    // v6.1：可选的资产账户
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    // v10.4：可换的目标账本
    val allLedgers by vm.allLedgers.collectAsStateWithLifecycle()
    val form = vm.form

    val scene = ScenePresets.byKey(ledger?.sceneKey ?: ScenePresets.CUSTOM_KEY)
    val supportsReimburse = scene.supportsReimbursement

    // v16.5：这一笔最终落在哪个账本上，UI 与写入都以它为准。
    // 以前这里只看"打开时的账本"，于是从报销账本点开、手动切成日常账本后，
    // 项目行照旧显示、流水还带「待提交」——两个账本的数据串了。
    val switched: LedgerEntity? = allLedgers.firstOrNull {
        it.id == form.targetLedgerId && it.id != vm.ledgerId
    }
    val targetScene = switched?.let { ScenePresets.byKey(it.sceneKey) } ?: scene
    val targetSupportsReimburse = targetScene.supportsReimbursement
    // 项目是账本内的（这趟差旅/这个客户），换到别的账本就没有意义了
    val showProjectRow = supportsReimburse && switched == null

    // ⚠️ v9.7.2 修：这个 VM 挂在 Activity 上（key = "add-$ledgerId"），会被多次打开复用。
    // 原来只在「编辑」时 loadForEdit，新建时**什么都不做**，于是：
    //   ① 取消后再点「记一笔」→ 上次填的金额/备注/分类/日期全都还在；
    //   ② 更危险：先「编辑某笔」再取消 → form.editingId 残留，
    //      接下来新记的一笔会走 updateTransaction 分支，**把那笔旧流水覆盖掉**。
    // 现在每次打开都明确复位：编辑就载入，新建就清空。
    LaunchedEffect(editTxnId) {
        if (editTxnId != null) vm.loadForEdit(editTxnId) else vm.reset()
    }

    val visibleCategories = categories.filter { it.direction == form.direction }
    val onPick: (CategoryEntity) -> Unit = { vm.pickCategory(it) }
    // v5.6「记账提速」：连续记账。保存后**不关闭弹层**，保留方向/分类/日期/项目，
    // 只清掉金额与备注 —— 一口气记几笔（同一天的多笔开销）时不用反复开关弹层。
    // 编辑已有流水时必须关闭（那是一次性的修改，不该连着改下一笔）。
    val save = {
        val continuous = AddTxnUiState.continuous && editTxnId == null
        // 传目标账本的报销能力，不要传打开时的那个（见上面的 switched 说明）
        vm.save(targetSupportsReimburse, keepContext = continuous) {
            if (continuous) {
                AddTxnUiState.savedCount++
            } else {
                onSaved()
                onDismiss()
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val fullH = maxHeight
        // 键盘所在区域：笔记本姿态只占铰链下方那一半
        val padRegion =
            if (env.isTabletop) fullH * env.trailingFraction.coerceAtLeast(0.3f) else fullH
        val compact = padRegion < 780.dp

        // 内布局模式：v15 起**跟随实时姿态**。
        //
        // v3.1.1 曾用 remember 在打开瞬间冻结骨架，理由是"折叠/展开时不换骨架，
        // 避免抽屉外壳与分栏内容分裂闪帧"。但用户实测：开着记一笔时半折/全展开，
        // 布局完全不跟随 —— 冻结把"防一帧闪"变成了"永远不切"。物理折叠是慢动作，
        // 一次重组可接受；折叠瞬间的配置竞态已由 FoldEnv 的宽度兜底（everFoldable
        // 记忆 + TWO_PANE_MIN_WIDTH_DP）吸收。
        val tabletopLayout = env.isTabletop
        val dialogLayout = env.useDialogOverlay

        when {
            // ============ 半折叠 · 笔记本姿态：上屏选分类，下屏键盘 ============
            tabletopLayout -> {
                AppOverlay(
                    env = env,
                    onDismiss = onDismiss,
                    leadingContent = {
                        PickArea(
                            title = if (editTxnId == null) t("选择分类") else t("编辑这一笔"),
                            subtitle = "${ledger?.emoji ?: "📒"} ${ledger?.name ?: ""} · ${scene.name}",
                            form = form,
                            scene = scene,
                            categories = visibleCategories,
                            templates = templates,
                            projects = projects,
                            onProject = vm::setProject,
                            showProjects = showProjectRow,
                            accounts = accounts,
                            onAccount = vm::setAccount,
                            allLedgers = allLedgers,
                            currentLedgerName = ledger?.name ?: "",
                            currentLedgerEmoji = ledger?.emoji ?: "📒",
                            showLedgerPicker = true, // v10.5：新建也可换账本，不限编辑
                            onSetTargetLedger = vm::setTargetLedger,
                            onApplyTemplate = vm::applyTemplate,
                            onSaveTemplate = {
                                val ok = vm.saveTemplate("")
                                AppToastBus.show(if (ok) t("已存为模板，下次一点就用") else t("先填金额、选好分类再存模板"))
                            },
                            columns = 6,
                            compact = false,
                            onDirection = vm::setDirection,
                            onPick = onPick,
                            onExtra = vm::setExtra,
                            onNote = vm::setNote,
                            onBackdate = vm::backdate,
                            onOpenPicker = onOpenDatePicker,
                            scrollable = true,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                ) {
                    Column(
                        Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Bottom
                    ) {
                        KeyArea(
                            form = form,
                            onKey = vm::inputKey,
                            onSave = save,
                            padHeight = (padRegion - AMOUNT_BLOCK).coerceIn(PAD_MIN, PAD_MAX),
                            compact = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // ============ 半折叠 · 书本姿态：左屏选分类，右屏键盘 ============
            // v15.0.3：旋转到横屏半折（BOOK，铰链竖直分割左右）时，内容必须跟外壳走 ——
            // 旧实现切到 dialogLayout 分支在 BOOK 半屏里排 320dp 键盘，把 PickArea 挤成 0 宽，
            // 只剩键盘"半屏"。这里显式用 leadingContent 结构：左屏选择、右屏操作。
            env.isBook -> {
                AppOverlay(
                    env = env,
                    onDismiss = onDismiss,
                    leadingContent = {
                        PickArea(
                            title = if (editTxnId == null) t("选择分类") else t("编辑这一笔"),
                            subtitle = "${ledger?.emoji ?: "📒"} ${ledger?.name ?: ""} · ${scene.name}",
                            form = form,
                            scene = scene,
                            categories = visibleCategories,
                            templates = templates,
                            projects = projects,
                            onProject = vm::setProject,
                            showProjects = showProjectRow,
                            accounts = accounts,
                            onAccount = vm::setAccount,
                            allLedgers = allLedgers,
                            currentLedgerName = ledger?.name ?: "",
                            currentLedgerEmoji = ledger?.emoji ?: "📒",
                            showLedgerPicker = true,
                            onSetTargetLedger = vm::setTargetLedger,
                            onApplyTemplate = vm::applyTemplate,
                            onSaveTemplate = {
                                val ok = vm.saveTemplate("")
                                AppToastBus.show(if (ok) t("已存为模板，下次一点就用") else t("先填金额、选好分类再存模板"))
                            },
                            columns = if (env.widthDp >= 1000) 6 else 5,
                            compact = false,
                            onDirection = vm::setDirection,
                            onPick = onPick,
                            onExtra = vm::setExtra,
                            onNote = vm::setNote,
                            onBackdate = vm::backdate,
                            onOpenPicker = onOpenDatePicker,
                            scrollable = true,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                ) {
                    Column(
                        Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Bottom
                    ) {
                        KeyArea(
                            form = form,
                            onKey = vm::inputKey,
                            onSave = save,
                            padHeight = (fullH - AMOUNT_BLOCK).coerceIn(PAD_MIN, PAD_MAX),
                            compact = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // ============ 内屏展开 / 桌面窗口：居中浮窗 ============
            dialogLayout -> {
                // 卡片够宽就左右分栏；窄屏（折叠屏展开**竖屏** 608dp）在**同一张浮窗**里
                // 上下叠 —— 分类铺满整宽 4 列，键盘贴底。
                //
                // ⚠️ 用户要的是"浮窗样式 + 更大信息量"，不是把屏幕铺满。
                // v16.5 曾把门槛提到 900dp，窄屏退回贴底抽屉（fillMaxHeight 0.92，
                // 视觉上就是全屏），被明确否掉。现在摆位与排版分开判：
                // 摆位看 useDialogOverlay(≥600)，内部是否分栏看这里。
                //
                // 阈值 840（v16.5.3）：折叠屏展开**横屏**是 860×608 —— 高度只有 608，
                // 上下叠会把分类压得只剩一两行，而横向明明有 860 的余量。
                // 840 之上卡片能到 812，扣掉键盘栏 360 仍有 450 给分类（4 列刚好）。
                val twoColumn = env.widthDp >= 840
                // v16.4：键盘栏固定在 340~380dp，其余全给分类区；
                // v16.5：键盘栏随窗口降档 —— 840dp 窗口下卡片 812，分类区仍有 472dp。
                // 横屏（860×608）高度只有 ~497dp，左栏得同时收紧：键盘栏压到 340，
                // 让「账本」四个 chip 排成一行（多一排就是 -55dp）。
                val padColumn = when {
                    env.widthDp >= 1100 -> 380.dp
                    env.widthDp >= 840 -> 340.dp
                    else -> 360.dp
                }
                val pickColumns = if (env.widthDp >= 1000) 5 else 4
                AppOverlay(env = env, onDismiss = onDismiss) {
                    if (twoColumn) {
                        Row(Modifier.fillMaxSize()) {
                            PickArea(
                                title = if (editTxnId == null) t("记一笔") else t("编辑这一笔"),
                                subtitle = "${ledger?.emoji ?: "📒"} ${ledger?.name ?: ""} · ${scene.emoji}${scene.name}",
                                form = form,
                                scene = scene,
                                categories = visibleCategories,
                                templates = templates,
                                projects = projects,
                                onProject = vm::setProject,
                                showProjects = showProjectRow,
                                accounts = accounts,
                                onAccount = vm::setAccount,
                                allLedgers = allLedgers,
                                currentLedgerName = ledger?.name ?: "",
                                currentLedgerEmoji = ledger?.emoji ?: "📒",
                                showLedgerPicker = true, // v10.5：新建也可换账本，不限编辑
                                onSetTargetLedger = vm::setTargetLedger,
                                onApplyTemplate = vm::applyTemplate,
                                onSaveTemplate = {
                                    val ok = vm.saveTemplate("")
                                    AppToastBus.show(if (ok) t("已存为模板，下次一点就用") else t("先填金额、选好分类再存模板"))
                                },
                                columns = pickColumns,
                                // 矮屏（横屏展开：608dp 高）用紧凑芯片 + 省掉小标题，
                                // 否则「账本/模板/方向/项目」四段吃掉整栏，分类一条都看不见
                                compact = compact,
                                dense = compact,
                                onDirection = vm::setDirection,
                                onPick = onPick,
                                onExtra = vm::setExtra,
                                onNote = vm::setNote,
                                onBackdate = vm::backdate,
                                onOpenPicker = onOpenDatePicker,
                                scrollable = true,
                                modifier = Modifier.weight(1f).fillMaxHeight()
                            )
                            VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Column(
                                Modifier
                                    .width(padColumn)
                                    .fillMaxHeight(),
                                verticalArrangement = Arrangement.Bottom
                            ) {
                                KeyArea(
                                    form = form,
                                    onKey = vm::inputKey,
                                    onSave = save,
                                    padHeight = (fullH - AMOUNT_BLOCK - 48.dp).coerceIn(PAD_MIN, PAD_MAX),
                                    compact = false,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    } else {
                        // 窄卡片：上下叠。没有拖拽把手（那是抽屉的手势语言）
                        TxnStackedBody(
                            vm = vm, form = form, scene = scene, ledger = ledger,
                            editTxnId = editTxnId, categories = visibleCategories,
                            templates = templates, projects = projects, accounts = accounts,
                            allLedgers = allLedgers, showProjectRow = showProjectRow,
                            columns = env.categoryColumns, compact = compact, fullH = fullH,
                            save = save, onPick = onPick, onDismiss = onDismiss,
                            onOpenDatePicker = onOpenDatePicker, showGrabber = false,
                            // 窄屏：键盘压到 190dp + 省掉三个小标题，多出来的全给分类区
                            padMax = 190.dp, dense = true
                        )
                    }
                }
            }

            // ============ 外屏 / 直板机：底部抽屉 ============
            else -> {
                AppOverlay(env = env, onDismiss = onDismiss) {
                    TxnStackedBody(
                        vm = vm, form = form, scene = scene, ledger = ledger,
                        editTxnId = editTxnId, categories = visibleCategories,
                        templates = templates, projects = projects, accounts = accounts,
                        allLedgers = allLedgers, showProjectRow = showProjectRow,
                        columns = env.categoryColumns, compact = compact, fullH = fullH,
                        save = save, onPick = onPick, onDismiss = onDismiss,
                        onOpenDatePicker = onOpenDatePicker, showGrabber = true
                    )
                }
            }
        }
    }
}

/**
 * 「上下叠」版记账主体：头部（取消 / 标题 / 账本）+ 分类区 + 键盘。
 *
 * v16.5.1：从底部抽屉里抽出来，给**窄屏浮窗**复用 —— 手机与折叠屏展开竖屏
 * （<900dp）的卡片里就是这一个块，只是没有 [showGrabber]（拖拽把手是抽屉的
 * 手势语言，浮窗上画一根横条会让人想往下拖）。
 *
 * 布局意图：分类区优先。键盘高度按"扣掉分类区下限后剩多少"算，
 * 所以窄屏上也是先保证看得见分类，再决定键盘多高。
 */
@Composable
private fun TxnStackedBody(
    vm: AddTxnViewModel,
    form: TxnFormState,
    scene: ScenePreset,
    ledger: LedgerEntity?,
    editTxnId: Long?,
    categories: List<CategoryEntity>,
    templates: List<TxnTemplate>,
    projects: List<ProjectEntity>,
    accounts: List<AccountEntity>,
    allLedgers: List<LedgerEntity>,
    showProjectRow: Boolean,
    columns: Int,
    compact: Boolean,
    fullH: Dp,
    save: () -> Unit,
    onPick: (CategoryEntity) -> Unit,
    onDismiss: () -> Unit,
    onOpenDatePicker: () -> Unit,
    showGrabber: Boolean,
    /** 键盘最大高度。窄屏浮窗压到 200dp，把高度让给分类区 */
    padMax: Dp = PAD_MAX,
    /** 紧凑头部（省掉「账本/常用模板/归入项目」三个小标题） */
    dense: Boolean = false
) {
    Column(Modifier.fillMaxSize()) {
        if (showGrabber) SheetGrabber()
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.md, vertical = Space.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                t("取消"),
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(Radius.sm))
                    .clickableScale(pressedScale = 0.94f) { onDismiss() }
                    .padding(horizontal = Space.sm, vertical = 6.dp)
            )
            Spacer(Modifier.weight(1f))
            Text(
                if (editTxnId == null) t("记一笔") else t("编辑"),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.weight(1f))
            Text(
                "${ledger?.emoji ?: "📒"} ${ledger?.name ?: ""}",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(96.dp)
            )
        }
        // 先把扣除键盘后剩下的空间全给分类区（保证 PICK_AREA_MIN），
        // 键盘再吃掉剩下的那部分 —— "分类优先"就是这一段在保证
        PickArea(
            title = null,
            subtitle = null,
            form = form,
            scene = scene,
            categories = categories,
            templates = templates,
            projects = projects,
            onProject = vm::setProject,
            showProjects = showProjectRow,
            accounts = accounts,
            onAccount = vm::setAccount,
            allLedgers = allLedgers,
            currentLedgerName = ledger?.name ?: "",
            currentLedgerEmoji = ledger?.emoji ?: "📒",
            showLedgerPicker = true, // v10.5：新建也可换账本，不限编辑
            onSetTargetLedger = vm::setTargetLedger,
            onApplyTemplate = vm::applyTemplate,
            onSaveTemplate = {
                val ok = vm.saveTemplate("")
                AppToastBus.show(if (ok) t("已存为模板，下次一点就用") else t("先填金额、选好分类再存模板"))
            },
            columns = columns,
            compact = compact,
            dense = dense,
            onDirection = vm::setDirection,
            onPick = onPick,
            onExtra = vm::setExtra,
            onNote = vm::setNote,
            onBackdate = vm::backdate,
            onOpenPicker = onOpenDatePicker,
            scrollable = true,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = PICK_AREA_MIN)
                .fillMaxWidth()
        )
        KeyArea(
            form = form,
            onKey = vm::inputKey,
            onSave = save,
            padHeight = (fullH - PICK_AREA_MIN - AMOUNT_BLOCK - 72.dp).coerceIn(PAD_MIN, padMax),
            compact = compact,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

// ============================================================
//  分类 + 场景字段 + 备注
// ============================================================

@Composable
private fun PickArea(
    title: String?,
    subtitle: String?,
    form: TxnFormState,
    scene: ScenePreset,
    categories: List<CategoryEntity>,
    templates: List<TxnTemplate>,
    /** v5.1：可归入的项目（报销账本才有） */
    projects: List<ProjectEntity>,
    onProject: (Long) -> Unit,
    showProjects: Boolean,
    /** v6.1：这笔钱走的账户 */
    accounts: List<AccountEntity>,
    onAccount: (Long) -> Unit,
    /** v10.4：编辑模式下可换目标账本 */
    allLedgers: List<LedgerEntity> = emptyList(),
    currentLedgerName: String = "",
    currentLedgerEmoji: String = "📒",
    showLedgerPicker: Boolean = false,
    onSetTargetLedger: (Long) -> Unit = {},
    columns: Int,
    compact: Boolean,
    /**
     * v16.5.1：紧凑头部 —— 窄屏浮窗专用。
     *
     * 卡片只有 ~700dp 高，键盘 + 金额占掉 ~300，剩下要放「账本/模板/方向/项目/分类」。
     * 三段小标题各占 ~25dp，加起来就是一整行分类。dense 下省掉这些标题，
     * 只留 chip 本身（账本名、模板名、项目名都自带含义，标题是重复信息）。
     */
    dense: Boolean = false,
    onDirection: (Int) -> Unit,
    onPick: (CategoryEntity) -> Unit,
    onApplyTemplate: (TxnTemplate) -> Unit,
    onSaveTemplate: () -> Unit,
    onExtra: (String, String) -> Unit,
    onNote: (String) -> Unit,
    onBackdate: (Int) -> Unit,
    onOpenPicker: () -> Unit,
    scrollable: Boolean,
    modifier: Modifier = Modifier
) {
    val scrollMod = if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier
    val gap = if (compact) Space.sm else Space.md
    // v12.0.1：滚动区的下沿原来是"一刀切"—— 分类第二行会被齐刷刷砍在字中间，
    // 紧贴着下面的「支出金额」，看起来像排版塌了。这里在下沿盖一层几像素的
    // 渐隐，让滚到边界的内容"化"进纸面（不裁字，只淡出）。
    val fadeColor = MaterialTheme.colorScheme.surface
    val fadeMod = if (scrollable) {
        Modifier.drawWithContent {
            drawContent()
            drawRect(
                brush = Brush.verticalGradient(
                    colorStops = arrayOf(
                        0.84f to Color.Transparent,
                        1f to fadeColor.copy(alpha = 0.96f)
                    )
                )
            )
        }
    } else {
        Modifier
    }

    Column(
        modifier = modifier
            .then(fadeMod)
            .then(scrollMod)
            .padding(horizontal = Space.lg)
    ) {
        if (title != null) {
            Spacer(Modifier.height(if (compact) Space.md else Space.lg))
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.height(if (compact) Space.sm else Space.md))

        // v10.4：编辑模式下可换目标账本 —— 自动记账的条目事后搬到真实账本
        if (showLedgerPicker && allLedgers.isNotEmpty()) {
            if (!dense) {
                Text(
                    t("账本"),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                allLedgers.filter { it.deletedAt == 0L }.forEach { l ->
                    val isActive = form.targetLedgerId == l.id
                    // v13.9：账本 chip 选中原来是**整体铺主色**（深蓝底 + 白字），
                    // 在玻璃面板上就是一块贴上去的不透明标签。改成同一套"白玻璃 + 主色内棱"。
                    Box(
                        Modifier
                            .glassChip(
                                shape = RoundedCornerShape(Radius.pill),
                                strength = if (isActive) 1.0f else 0.55f
                            )
                            .then(
                                if (isActive) {
                                    Modifier.border(
                                        1.2.dp,
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.42f),
                                        RoundedCornerShape(Radius.pill)
                                    )
                                } else Modifier
                            )
                            .clickableScale(
                                pressedScale = 0.92f,
                                haptic = Haptic.LIGHT
                            ) { onSetTargetLedger(l.id) }
                    ) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(l.emoji, fontSize = 13.sp)
                            Spacer(Modifier.width(5.dp))
                            Text(
                                l.name,
                                fontSize = 13.sp,
                                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (isActive) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(if (compact) Space.sm else Space.md))
        }

        // 常用模板（v2.1）：点一下把金额/分类/备注填进表单，也能把当前表单存成模板
        // v10.5：原来 if(templates.isNotEmpty()) 包整块 → 无模板时看不到「存为模板」按钮，第一条模板无法诞生
        if (!dense) {
            Text(
                "常用模板",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
                // v13.9：主色 40% 平涂 → 玻璃胶囊（主色进膜，字用主色）
                Box(
                    Modifier
                        .glassChip(
                            shape = RoundedCornerShape(Radius.pill),
                            tint = MaterialTheme.colorScheme.primary,
                            strength = 1.1f
                        )
                        .clickableScale(pressedScale = 0.93f) { onSaveTemplate() }
                ) {
                    Text(
                        "＋ 存当前为模板",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
                templates.take(8).forEach { t ->
                    Box(
                        Modifier
                            .glassChip(
                                shape = RoundedCornerShape(Radius.pill),
                                strength = 0.6f
                            )
                            .clickableScale(pressedScale = 0.93f) { onApplyTemplate(t) }
                    ) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(t.categoryEmoji, fontSize = 13.sp)
                            Spacer(Modifier.width(5.dp))
                            Text(
                                t.name,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(
                                Money.format(t.amountCents),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(gap))

        DirectionToggle(form.direction, onDirection)

        // v5.1：归入项目 / 空间 —— 报销账本才有，记的时候顺手归到这一趟差旅
        if (showProjects && projects.isNotEmpty()) {
            Spacer(Modifier.height(if (dense) 6.dp else gap))
            if (!dense) {
                Text(
                    "归入项目",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(if (compact) 6.dp else Space.sm))
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ProjectPickChip(
                    label = "不归入",
                    emoji = null,
                    active = form.projectId == 0L
                ) { onProject(0L) }
                projects.forEach { p ->
                    ProjectPickChip(
                        label = p.name,
                        emoji = p.emoji,
                        active = form.projectId == p.id
                    ) { onProject(p.id) }
                }
            }
        }

        // v6.1：这笔钱走哪个账户 —— 有账户才显示，不打扰还没建账户的新用户
        if (accounts.isNotEmpty()) {
            Spacer(Modifier.height(gap))
            Text(
                t("账户"),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(if (compact) 6.dp else Space.sm))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ProjectPickChip(
                    label = t("未指定"),
                    emoji = null,
                    active = form.accountId == 0L
                ) { onAccount(0L) }
                accounts.filter { !it.closed }.forEach { a ->
                    ProjectPickChip(
                        label = a.name,
                        emoji = a.emoji,
                        active = form.accountId == a.id
                    ) { onAccount(a.id) }
                }
            }
        }

        Spacer(Modifier.height(gap))
        Text(
            t("分类"),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(if (compact) 6.dp else Space.sm))

        if (categories.isEmpty()) {
            Text(
                "该方向下还没有分类，去「分类管理」添加",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else Space.sm),
                verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else Space.sm),
                maxItemsInEachRow = columns
            ) {
                categories.forEach { c ->
                    CategoryChip(
                        category = c,
                        selected = c.id == form.categoryId,
                        compact = compact,
                        onClick = { onPick(c) }
                    )
                }
            }
        }

        if (scene.fields.isNotEmpty()) {
            Spacer(Modifier.height(Space.lg))
            // v10.2.0：标题与字段绑进同一张卡 ——
            // 原来标题是裸 Text 挂在滚动列上，滚到可视边界时只剩一行「「报销」场景字段」，
            // 下方字段被裁掉，视觉上像一张空标题。现在整段放进 Surface：
            // 边界上看到的是"一张还没显示完的卡"，标题永远和字段长在一起。
            // v13.9：原来是一块 surfaceVariant 35% 的平涂托盘。
            // 大面积块浓度压到 0.5，靠棱声明材质（见 glassChip 的"面积与浓度成反比"）。
            Box(
                Modifier
                    .fillMaxWidth()
                    .glassChip(
                        shape = RoundedCornerShape(Radius.md),
                        strength = 0.5f
                    )
            ) {
                Column(Modifier.padding(horizontal = Space.md, vertical = Space.sm)) {
                    Text(
                        "「${scene.name}」场景字段",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(Space.sm))
                    scene.fields.forEachIndexed { idx, field ->
                        SceneFieldRow(
                            field = field,
                            value = form.extra[field.key].orEmpty(),
                            onChange = { onExtra(field.key, it) }
                        )
                        if (idx < scene.fields.lastIndex) Spacer(Modifier.height(Space.sm))
                    }
                }
            }
        }

        Spacer(Modifier.height(Space.sm))
        OutlinedTextField(
            value = form.note,
            onValueChange = onNote,
            label = { Text("备注") },
            placeholder = { Text("这笔钱花在哪了？") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(Space.md))
        Text(
            "日期与时间",
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))

        Box(
            Modifier
                .fillMaxWidth()
                .glassChip(
                    shape = RoundedCornerShape(Radius.md),
                    strength = 0.6f,
                    elevation = 2.dp
                )
                .clickableScale(pressedScale = 0.97f) { onOpenPicker() }
        ) {
            Row(
                Modifier.padding(horizontal = Space.md, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("🗓️", fontSize = 17.sp)
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        DateExt.fullDateTime(form.occurredAt),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        DateExt.dayRelation(form.occurredAt),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text("修改 ›", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
            }
        }

        Spacer(Modifier.height(Space.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            listOf(t("今天") to 0, t("昨天") to 1, t("前天") to 2).forEach { (label, days) ->
                val active = DateExt.isSameDay(
                    form.occurredAt,
                    System.currentTimeMillis() - days * 86_400_000L
                )
                // v13.9：选中态从 primaryContainer 实色换成"白玻璃 + 主色内棱"，
                // 和明细页的项目条同一套语言。⚠️ 主色字只放在白膜上（4.9:1）。
                Box(
                    Modifier
                        .glassChip(
                            shape = RoundedCornerShape(Radius.pill),
                            strength = if (active) 1.0f else 0.55f
                        )
                        .then(
                            if (active) {
                                Modifier.border(
                                    1.2.dp,
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.42f),
                                    RoundedCornerShape(Radius.pill)
                                )
                            } else Modifier
                        )
                        .clickableScale(pressedScale = 0.92f) { onBackdate(days) }
                ) {
                    Text(
                        label,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (active) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
        Spacer(Modifier.height(Space.xl))
    }
}

@Composable
private fun DirectionToggle(direction: Int, onChange: (Int) -> Unit) {
    val dc = LocalDirectionColors.current
    // 槽 + 液面：槽是"凹进去的一条"（墨 6.5%），液面仍是方向色的实心胶囊 ——
    // 这里的实色是**语义**（支出/收入），不是"选中块"，所以它保留实色。
    Surface(
        shape = RoundedCornerShape(Radius.pill),
        color = wellSurface(),
        modifier = Modifier.width(180.dp)
    ) {
        Row(Modifier.padding(3.dp)) {
            listOf(Direction.EXPENSE to t("支出"), Direction.INCOME to t("收入")).forEach { (d, label) ->
                val active = d == direction
                val c = if (d == Direction.EXPENSE) dc.expense else dc.income
                // v13.9：原来是"方向色实心胶囊"（整块深豆沙 + 白字），
                // 在一屏浅色玻璃里它是唯一的一块实色，视觉重量压过了金额。
                // 改成"方向色染膜的玻璃"：浓度用 strength 做弹簧动画（0 → 2.2），
                // 未选中时强度为 0，连棱都不画 —— 否则空膜上会出现一圈白棱。
                val strength by animateFloatAsState(
                    targetValue = if (active) 3.0f else 0f,
                    animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow),
                    label = "directionFill"
                )
                Box(
                    Modifier
                        .weight(1f)
                        .then(
                            if (strength > 0.04f) {
                                Modifier.glassChip(
                                    shape = RoundedCornerShape(Radius.pill),
                                    tint = c,
                                    strength = strength,
                                    // 2dp 影：滑块"凸出槽面"这件事全靠它，
                                    // 只靠浓度差在浅色槽里读不出"这是一个可以滑的把手"
                                    elevation = 2.dp
                                )
                            } else Modifier
                        )
                        .clip(RoundedCornerShape(Radius.pill))
                        .clickableScale(pressedScale = 0.94f, haptic = Haptic.LIGHT) { onChange(d) }
                        .padding(vertical = 7.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        label,
                        fontSize = 13.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (active) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoryChip(
    category: CategoryEntity,
    selected: Boolean,
    compact: Boolean,
    onClick: () -> Unit
) {
    // 选中时轻微放大，给一个"被挑中"的手感
    val scale by animateFloatAsState(
        targetValue = if (selected) 1.04f else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium),
        label = "categoryChipScale"
    )
    // v13.9：选中从"primaryContainer 实色 + 主色描边"换成
    // 白玻璃 + 主色内棱 + 抬影 —— 同一套"选中"语言，全站一致。
    // 未选中**不留底**（原来 bg = Transparent 也是这个意思），
    // 因为分类是 84dp 的密网格，每个都垫一层膜会把整屏压成灰。
    Box(
        Modifier
            .width(if (compact) 78.dp else 84.dp)
            .heightIn(min = if (compact) 56.dp else 62.dp)
            .then(
                if (selected) {
                    Modifier
                        .glassChip(
                            shape = RoundedCornerShape(Radius.sm),
                            strength = 1.0f,
                            elevation = 3.dp
                        )
                        .border(
                            1.2.dp,
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.42f),
                            RoundedCornerShape(Radius.sm)
                        )
                } else Modifier
            )
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clickableScale(pressedScale = 0.92f, haptic = Haptic.LIGHT) { onClick() }
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(vertical = if (compact) 6.dp else 8.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CategoryIcon(ref = category.emoji, size = if (compact) 24.dp else 28.dp, fontSize = 20)
            Spacer(Modifier.height(3.dp))
            Text(
                category.name,
                fontSize = 11.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                // v16.3.1：11/13 只剩 2sp 余量且 maxLines=2，中文两行会挤；
                // 对齐 OverlineLabel 的 11/15。
                lineHeight = 15.sp,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
            )
        }
    }
}

/** 项目选择小胶囊（记账弹层用；和明细页的项目切换条同一套视觉） */
@Composable
private fun ProjectPickChip(
    label: String,
    emoji: String?,
    active: Boolean,
    onClick: () -> Unit
) {
    val base = MaterialTheme.colorScheme.primary
    // v13.9：和明细页 ProjectChip 同一条规则 —— 选中 = 白玻璃 + 主色内棱，
    // 未选中 = 一层薄白膜。原来两块都是平涂（primary 16% / surfaceVariant 45%）。
    Box(
        Modifier
            .glassChip(
                shape = RoundedCornerShape(50),
                strength = if (active) 1.0f else 0.55f
            )
            .then(
                if (active) {
                    Modifier.border(1.2.dp, base.copy(alpha = 0.42f), RoundedCornerShape(50))
                } else {
                    Modifier
                }
            )
            .clickableScale(pressedScale = 0.94f) { onClick() }
            .padding(horizontal = 11.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (emoji != null) {
                Text(emoji, fontSize = 11.sp)
                Spacer(Modifier.width(4.dp))
            }
            Text(
                label,
                fontSize = 13.sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                color = if (active) base else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 150.dp)
            )
        }
    }
}

@Composable
private fun SceneFieldRow(
    field: SceneField,
    value: String,
    onChange: (String) -> Unit
) {
    when (field.kind) {
        FieldKind.CHOICE -> {
            Column {
                Text(
                    field.label,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(5.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    field.options.forEach { opt ->
                        val active = value == opt.value
                        // v13.9：同上，选中不再铺实色主色
                        Box(
                            Modifier
                                .glassChip(
                                    shape = RoundedCornerShape(Radius.pill),
                                    strength = if (active) 1.0f else 0.55f
                                )
                                .then(
                                    if (active) {
                                        Modifier.border(
                                            1.2.dp,
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.42f),
                                            RoundedCornerShape(Radius.pill)
                                        )
                                    } else Modifier
                                )
                                .clickableScale(
                                    pressedScale = 0.92f,
                                    haptic = Haptic.LIGHT
                                ) { onChange(if (active) "" else opt.value) }
                        ) {
                            Text(
                                opt.label,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (active) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }

        else -> {
            OutlinedTextField(
                value = value,
                onValueChange = onChange,
                label = { Text(field.label) },
                placeholder = { if (field.placeholder.isNotBlank()) Text(field.placeholder) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

// ============================================================
//  金额 + 数字键盘
// ============================================================

/**
 * 金额区 + 数字键盘。
 *
 * 多了一个**收起键盘**开关：折叠态下想改分类时，先收键盘就能一屏看到全部分类，
 * 不用"滑上去选完再滑下来输数字"。
 */
@Composable
private fun KeyArea(
    form: TxnFormState,
    onKey: (String) -> Unit,
    onSave: () -> Unit,
    padHeight: Dp,
    compact: Boolean,
    modifier: Modifier = Modifier
) {
    val dc = LocalDirectionColors.current
    val accent = if (form.direction == Direction.EXPENSE) dc.expense else dc.income
    var padOpen by remember { mutableStateOf(true) }

    Column(
        modifier = modifier
            .background(cardSurface())
            .padding(horizontal = Space.md)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = Space.sm, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (form.direction == Direction.EXPENSE) "支出金额" else "收入金额",
                    style = com.foldledger.ui.theme.OverlineLabel,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        "¥",
                        // v16.3.1：18/21 是表外字号，归到最近的 17/20 档
                        fontSize = if (compact) 17.sp else 20.sp,
                        fontWeight = FontWeight.Medium,
                        color = accent,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                    Spacer(Modifier.width(5.dp))
                    // v15：金额字号随位数递减 —— 固定 42sp 时 5 位以上就出省略号，
                    // 用户看不到自己输到哪了。位数越多字号越小，始终完整可见。
                    val amountText = form.amountText.ifEmpty { "0.00" }
                    val amountFontSize = when {
                        amountText.length <= 6 -> if (compact) 34.sp else 42.sp
                        amountText.length <= 8 -> if (compact) 28.sp else 34.sp
                        amountText.length <= 10 -> if (compact) 24.sp else 28.sp
                        else -> if (compact) 20.sp else 24.sp
                    }
                    Text(
                        amountText,
                        // v12：输入中的金额是这一屏的主角。玻璃上不做 ExtraBold ——
                        // 笔画一粗，字形与背后的高光会糊在一起；SemiBold + 更大字号更"亮"。
                        style = androidx.compose.ui.text.TextStyle(
                            fontSize = amountFontSize,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = (-1.4).sp,
                            fontFeatureSettings = "tnum"
                        ),
                        color = if (form.amountText.isEmpty()) {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.40f)
                        } else {
                            accent
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        // v5.0：每按一个数字，金额轻轻弹一下（配合震动形成"按下去有回应"）
                        modifier = Modifier.pressPulse(form.amountText)
                    )
                }
                if (form.categoryName.isNotBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CategoryIcon(ref = form.categoryEmoji, size = 18.dp, fontSize = 13)
                        Spacer(Modifier.width(5.dp))
                        Text(
                            form.categoryName,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    Text("请先选择分类", fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                }
            }

            // v5.6：连续记开关。开了以后保存不关弹层，只清金额 —— 一口气记几笔时不用反复开关。
            // 已记过几笔就直接写在按钮上（这是最省事的回执，不额外占一行布局）。
            val continuousOn = AddTxnUiState.continuous
            val savedCount = AddTxnUiState.savedCount
            Surface(
                shape = RoundedCornerShape(Radius.pill),
                color = if (continuousOn) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                else wellSurface(),
                modifier = Modifier.clickableScale(
                    pressedScale = 0.92f,
                    haptic = Haptic.LIGHT
                ) { AddTxnUiState.continuous = !continuousOn }
            ) {
                Text(
                    text = when {
                        continuousOn && savedCount > 0 -> "已记 $savedCount 笔 ✓"
                        continuousOn -> "连续记 ✓"
                        else -> "连续记"
                    },
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (continuousOn) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp)
                )
            }
            Spacer(Modifier.width(6.dp))

            // 收起 / 展开键盘
            Surface(
                shape = RoundedCornerShape(Radius.pill),
                color = wellSurface(),
                modifier = Modifier.clickableScale(
                    pressedScale = 0.92f,
                    haptic = Haptic.LIGHT
                ) { padOpen = !padOpen }
            ) {
                Text(
                    if (padOpen) "收起键盘 ⌄" else "输入数字 ⌃",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp)
                )
            }
        }

        // 收起时给一个直达保存的按钮，不用为了保存再把键盘展开
        AnimatedVisibility(
            visible = !padOpen,
            enter = fadeIn(tween(Motion.FAST)) + expandVertically(Motion.soft()),
            exit = fadeOut(tween(Motion.FAST)) + shrinkVertically(Motion.snappy())
        ) {
            // v5.0：主 CTA 换成液态按钮（品牌渐变 + 顶部高光 + 受光描边 + 按压缩进）
            LiquidButton(
                text = "保存这一笔",
                onClick = onSave,
                enabled = form.canSave,
                height = 50.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = Space.md)
            )
        }

        AnimatedVisibility(
            visible = padOpen,
            enter = fadeIn(tween(Motion.FAST)) + expandVertically(Motion.soft()),
            exit = fadeOut(tween(Motion.FAST)) + shrinkVertically(Motion.snappy())
        ) {
            NumberPad(
                onKey = onKey,
                onSave = onSave,
                enabled = form.canSave,
                accent = accent,
                height = padHeight,
                disabledHint = if ((form.amountCents ?: 0L) <= 0L) "先输入金额" else "再选一个分类",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = Space.md)
            )
        }
    }
}

@Composable
private fun NumberPad(
    onKey: (String) -> Unit,
    onSave: () -> Unit,
    enabled: Boolean,
    accent: Color,
    height: Dp,
    /** 保存被禁用时的原因（点了要能说清还差什么，v9.8.0） */
    disabledHint: String,
    modifier: Modifier = Modifier
) {
    val rows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf(".", "0", "del")
    )
    Row(modifier.height(height)) {
        Column(Modifier.weight(3f)) {
            rows.forEach { row ->
                Row(Modifier.weight(1f)) {
                    row.forEach { key ->
                        PadKey(
                            label = if (key == "del") "⌫" else key,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .padding(3.dp),
                            // v5.0：每按一个数字给一次短震动（强度在设置里可调）
                            onClick = { onKey(key) },
                            onPressFeedback = { AppHaptics.key() }
                        )
                    }
                }
            }
        }
        Column(Modifier.weight(1f)) {
            PadKey(
                label = t("清空"),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(3.dp),
                onClick = { onKey("clear") },
                onPressFeedback = { AppHaptics.tap() },
                fontSize = 15
            )
            PadKey(
                label = t("保存"),
                modifier = Modifier
                    .weight(2f)
                    .fillMaxWidth()
                    .padding(3.dp),
                // v9.7.1：条件不满足时点「保存」不该有任何副作用
                // v9.8.0：但也不能"点了什么都不发生" —— 说清还差什么
                onClick = { if (enabled) onSave() else AppToastBus.show(disabledHint) },
                onPressFeedback = { AppHaptics.confirm() },
                container = if (enabled) accent else MaterialTheme.colorScheme.surfaceVariant,
                content = if (enabled) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                bold = true,
                fontSize = 17
            )
        }
    }
}

/**
 * 数字键（v5.0 触感材质）。
 *
 * 玻璃底 + 受光描边 + 底部内阴影，像实体键帽；按下缩小 + 光斑扩散，
 * 同时播一次可调强度的触感（由 [onPressFeedback] 提供）。
 */
@Composable
private fun PadKey(
    label: String,
    modifier: Modifier,
    onClick: () -> Unit,
    onPressFeedback: () -> Unit = {},
    container: Color? = null,
    content: Color = MaterialTheme.colorScheme.onSurface,
    bold: Boolean = false,
    fontSize: Int = 20
) {
    TactileKey(
        label = label,
        modifier = modifier,
        container = container,
        content = content,
        bold = bold,
        fontSize = fontSize,
        onPress = {
            onPressFeedback()
            onClick()
        }
    )
}
