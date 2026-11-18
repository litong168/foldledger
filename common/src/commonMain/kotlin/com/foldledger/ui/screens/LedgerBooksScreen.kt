package com.foldledger.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.toOffset
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foldledger.data.ScenePreset
import com.foldledger.data.ScenePresets
import com.foldledger.data.db.Direction
import com.foldledger.data.db.LedgerEntity
import com.foldledger.ui.adaptive.AdaptiveScaffold
import com.foldledger.ui.adaptive.AppTab
import com.foldledger.ui.adaptive.FoldEnv
import com.foldledger.ui.adaptive.PostureBanner
import com.foldledger.ui.anim.Motion
import com.foldledger.ui.anim.StaggeredAppear
import com.foldledger.ui.anim.clickableScale
import com.foldledger.ui.anim.highlightPulse
import com.foldledger.ui.components.AmountText
import com.foldledger.ui.components.AnimatedCentsText
import com.foldledger.ui.components.GlassDialog
import com.foldledger.ui.components.GlassMenu
import com.foldledger.ui.components.LedgerAvatar
import com.foldledger.ui.components.PillTag
import com.foldledger.ui.components.ThinProgress
import com.foldledger.ui.components.parseHexColor
import com.foldledger.ui.theme.Layout
import com.foldledger.ui.theme.LocalDirectionColors
import com.foldledger.ui.theme.Radius
import com.foldledger.ui.theme.Space
import com.foldledger.ui.vm.LedgerUi
import com.foldledger.util.Money
import com.foldledger.ui.theme.glassChip
import com.foldledger.ui.theme.cardSurface
import com.foldledger.ui.theme.LocalDarkTheme
import com.foldledger.ui.theme.cardBorder
import com.foldledger.ui.theme.dividerBrush
import com.foldledger.ui.theme.onColorFor
import com.foldledger.ui.theme.primaryGradientBrush
import com.foldledger.ui.theme.SurfaceCard
import com.foldledger.ui.theme.softShadow
import com.foldledger.ui.theme.rememberAnimatedPaneWidth
import com.foldledger.ui.theme.sheenBrush
import com.foldledger.ui.theme.HeroNumber
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.foldledger.ui.l10n.t

/**
 * 账本页 —— 所有已创建的分账本都在这里。
 *
 * 职责：
 *  1. 展示全部分账本，点任意一个即把它设为「当前账本」并跳到明细页
 *  2. **合计范围可自定义**：选择哪几个账本参与合计统计
 *  3. 提供从场景模板一键新建的入口
 */
@Composable
fun LedgerBooksScreen(
    env: FoldEnv,
    cards: List<LedgerUi>,
    currentLedgerId: Long?,
    aggregateAll: Boolean,
    aggregateIds: Set<Long>,
    backupBannerVisible: Boolean,
    onSelectLedger: (Long) -> Unit,
    onAddTransaction: (Long) -> Unit,
    /** v13：长按拖拽排序结束后，把新的 id 顺序写回偏好 */
    onReorder: (List<Long>) -> Unit = {},
    onOpenStats: (Long) -> Unit,
    onOpenCategories: (Long) -> Unit,
    onEditLedger: (LedgerEntity) -> Unit,
    onDeleteLedger: (Long) -> Unit,
    /**
     * 按场景模板一键创建账本。第二参 `onCreated` 与 [onCreateLedgerConfirm] 里的
     * 同义：由上层在**数据库写入完成**后回调新账本 id，本页拿到它才滚过去 + 高亮。
     */
    onQuickCreateScene: (String, (Long) -> Unit) -> Unit,
    /**
     * 内联创建面板确认后回调（面板长在本页里，不再是弹层）。
     *
     * 多出来的 `onCreated` 由上层在**数据库真正写入完成**后调用，带回新账本的 id ——
     * 本页拿到它才知道该把视图带到哪儿去。早先没有这个回调，只能靠猜"最后一张卡就是新的"，
     * 时间上不可靠。
     */
    onCreateLedgerConfirm: (
        name: String,
        sceneKey: String,
        emoji: String,
        colorHex: String,
        budgetCents: Long,
        note: String,
        onCreated: (Long) -> Unit
    ) -> Unit,
    onCustomizeAggregate: () -> Unit,
    onGoDetail: () -> Unit,
    onEnableBackup: () -> Unit,
    onDismissBackupBanner: () -> Unit,
    /**
     * v3.2.0：外壳（顶栏 / 导航栏 / FAB）已提升到 [com.foldledger.ui.nav.AppRoot] 统一渲染，
     * 本页只画内容。`modifier` 由外壳给来，已含顶栏与导航栏的安全区内边距。
     */
    modifier: Modifier = Modifier,
    /**
     * v3.2.0：外壳的「记一笔」FAB 在**还没有任何账本**时需要打开本页的创建面板。
     * 用一个自增的 tick 当"命令"传下来（`LaunchedEffect` 观察到变化即打开），
     * 这样 FAB 可以留在统一的外壳里，不必把外壳拆回本页。
     */
    createRequestTick: Int = 0,
    /**
     * v9.7.2：命令被消费后回调，让外壳把 tick 清零。
     *
     * ⚠️ 原来 tick 只做 `++` 永不清零 → 这个 composable 切走再切回（LaunchedEffect 重新进入组合）
     * 时会**再执行一次** openCreate()：点过一次 FAB 之后，每次回到「账本」页都会自动弹出创建面板。
     */
    onCreateRequestHandled: () -> Unit = {}
) {
    val effectiveCurrent = currentLedgerId ?: cards.firstOrNull()?.ledger?.id

    // ---- 内联创建面板的开关 ----
    // creating = 面板是否存在于列表里；panelVisible = 是否展开（两者错开是为了让退场动画演完再移除节点）
    var creating by remember { mutableStateOf(false) }
    var panelVisible by remember { mutableStateOf(false) }
    /** 刚创建好的账本 id：用来把列表滚到它那儿并给它一段高亮脉冲 */
    var justCreatedId by remember { mutableStateOf<Long?>(null) }
    /** v8.1：待确认删除的账本 id —— 删账本必须过二次确认，直接抹掉是不可逆事故 */
    var pendingDeleteId by remember { mutableStateOf<Long?>(null) }
    val scope = rememberCoroutineScope()
    val gridState = rememberLazyGridState()
    val masterListState = rememberLazyListState()

    // ---- v13 长按拖拽排序 ----
    // `order` 是**显示顺序**的本地副本：拖拽期间必须由本页自己持有，
    // 不能直接改上层传下来的 cards（那是 StateFlow 的产物，改不了）。
    // 松手时才把顺序写回偏好，上层重新排序后再回流下来。
    val dragState = rememberBookDragState()
    val order = remember { mutableStateListOf<LedgerUi>() }
    /**
     * 松手后写进偏好、但还没回流下来的顺序。
     *
     * ⚠️ 没有这一层会**闪一下**：松手 → 本地已按新序排好 → 偏好还在异步落盘 →
     * 上层 cards 仍是旧序 → 这里一同步就把用户刚拖好的位置弹回去，等落盘完再跳回来。
     * 所以"等回流、且回流对得上"之前，一律以本地顺序为准。
     */
    var pendingOrder by remember { mutableStateOf<List<Long>?>(null) }
    LaunchedEffect(cards, dragState.draggingId) {
        if (dragState.draggingId != null) return@LaunchedEffect   // 拖拽中绝不覆盖
        val incoming = cards.map { it.ledger.id }
        val pending = pendingOrder
        if (pending != null) {
            if (incoming != pending) return@LaunchedEffect       // 偏好还没回流，继续用本地序
            pendingOrder = null
        }
        order.clear()
        order.addAll(cards)
    }
    val orderIds = order.map { it.ledger.id }
    /** 某个账本卡片在列表坐标系里的矩形；不在屏内返回 null */
    val boundsOf: (Long) -> Pair<Offset, Size>? = { id ->
        if (env.twoPane) {
            // ⚠️ LazyListItemInfo 的 offset / size 是 **Int**（不像 grid 的 IntOffset/IntSize），
            // 两边要分开写，不能共用一段。
            masterListState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == id }?.let {
                // 单列列表：x 方向不参与命中测试（宽度给一个足够大的值），
                // 位移修正只用 y —— 卡片本来就不该左右跑。
                Offset(0f, it.offset.toFloat()) to Size(100_000f, it.size.toFloat())
            }
        } else {
            gridState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == id }?.let {
                it.offset.toOffset() to Size(it.size.width.toFloat(), it.size.height.toFloat())
            }
        }
    }
    val onMoveBook: (Int, Int) -> Unit = { from, to ->
        if (from in order.indices && to in order.indices) {
            order.add(to, order.removeAt(from))
        }
    }
    val paneScroll = rememberScrollState()
    val onboardingScroll = rememberScrollState()

    // v9.7.2：每次打开都换一个新 key。
    // 面板内部用 rememberSaveable 存草稿（为了滚出屏幕不丢），但「取消」后这些
    // SaveableState 还挂在懒列表的 SaveableStateHolder 里，再打开会把上次填的名字/预算回填进来。
    // 换 key = 换 holder 槽位 = 拿到全新草稿。
    var createGen by remember { mutableStateOf(0) }

    val openCreate: () -> Unit = {
        // 只有"从来没开过"才换 key；面板已经展开时再点 FAB 不该把正在填的草稿清掉
        if (!creating) createGen++
        if (creating) panelVisible = true else creating = true
    }

    // v3.2.0：外壳 FAB 在空账本时点「记一笔」要打开本页的创建面板 —— 命令从外壳传进来
    LaunchedEffect(createRequestTick) {
        if (createRequestTick > 0) {
            openCreate()
            // 消费掉这条命令，避免"切走再切回又弹一次"
            onCreateRequestHandled()
        }
    }
    val closeCreate: () -> Unit = {
        panelVisible = false
        scope.launch {
            delay(220)
            creating = false
        }
    }

    // 面板进场。
    //
    // ⚠️ 这里必须先让节点以 `visible = false` 进一次组合，下一帧再置 true ——
    // `AnimatedVisibility` 在**首次进入组合时就带着 visible = true** 的话不会播进场动画，
    // 会直接"啪"地出现。早先就是在一次 recomposition 里同时把 creating / panelVisible
    // 置 true，所以用户看到的"没有任何动画"。
    LaunchedEffect(creating) {
        if (!creating) return@LaunchedEffect
        withFrameNanos { }          // 等一帧：节点已挂上，但 visible 还是 false
        panelVisible = true
        // 顺手把视图带到面板所在的位置 —— 这就是"跳转到创建的位置"
        runCatching {
            when {
                cards.isEmpty() -> onboardingScroll.animateScrollTo(0)
                env.twoPane -> paneScroll.animateScrollTo(0)
                else -> gridState.animateScrollToItem(0)
            }
        }
    }

    // 创建完成 → 关面板 → 等列表稳定 → 滚到新账本 → 高亮一下
    LaunchedEffect(justCreatedId, cards) {
        val id = justCreatedId ?: return@LaunchedEffect
        val index = cards.indexOfFirst { it.ledger.id == id }
        if (index < 0) return@LaunchedEffect   // 数据库还没推上来，等下一次 cards 变化
        delay(260)                             // 让创建面板把退场动画播完，列表高度才稳定
        if (env.twoPane) {
            // 双栏时账本列在左栏，创建面板在右栏顶部
            runCatching { masterListState.animateScrollToItem(index) }
        } else {
            // 单栏栅格。滚动时创建面板已经退场（delay 260 > closeCreate 的 220），
            // 所以账本卡前面的 item 是：合计 / 姿态 / [备份横幅] / 标题，共 3 或 4 个。
            val lead = 3 + (if (backupBannerVisible) 1 else 0)
            runCatching { gridState.animateScrollToItem((lead + index).coerceAtLeast(0)) }
        }
        delay(1500)
        justCreatedId = null
    }

    // 无论走「手动配置」还是「场景模板」创建，最终都收敛到这里：
    // 关掉创建面板 → 记录新 id，`LaunchedEffect(justCreatedId, cards)` 会负责滚过去 + 高亮。
    val markCreated: (Long) -> Unit = { newId ->
        closeCreate()
        justCreatedId = newId
    }

    val confirmCreate: (
        String, String, String, String, Long, String
    ) -> Unit = { name, sceneKey, emoji, colorHex, budgetCents, note ->
        onCreateLedgerConfirm(name, sceneKey, emoji, colorHex, budgetCents, note, markCreated)
    }

    val side = Layout.sidePadding(env.widthDp)

    // v3.2.0：外壳已上提，这里直接画内容。`modifier` 已含顶栏 / 导航栏内边距。
    if (env.twoPane) {
        // 左栏宽度由 FoldEnv 的离线搜索结果给出，不再让用户调
        val masterWidth = rememberAnimatedPaneWidth(env.paneMasterWidthDp(navigationVisible = true))
        // v16.4：右栏的留白必须按**右栏**宽度算。
        // 原来用的是 `Layout.sidePadding(env.widthDp)` —— 1700dp 窗口算出 300+dp 留白，
        // 塞进 1000dp 的右栏后内容只剩 400dp，于是"合计 ¥5,582"下面孤零零一张窄卡，
        // 右边空出大半屏。这正是用户说的「手机排版直接搬到 Windows」。
        val paneWidth = env.paneTrailingWidthDp(navigationVisible = true) - 1
        val paneSide = Layout.sidePaddingIn(paneWidth)
        // 右栏够宽时（≥1000dp）「合计范围」与「当前账本」并排 —— 两块都在讲"现在多少钱"，
        // 并排读是同一句话的两半；单列上下堆则要多滚一屏才看到账本本身。
        val widePane = paneWidth >= 860
        Row(
            Modifier
                .then(modifier)
                .fillMaxSize()
        ) {
            BookMasterList(
                modifier = Modifier
                    .width(masterWidth)
                    .fillMaxHeight(),
                state = masterListState,
                cards = order,
                dragState = dragState,
                boundsOf = boundsOf,
                onMove = onMoveBook,
                onDrop = {
                    pendingOrder = orderIds
                    onReorder(orderIds)
                },
                currentLedgerId = effectiveCurrent,
                highlightId = justCreatedId,
                onSelect = onSelectLedger,
                onCreate = openCreate,
                onGoDetail = onGoDetail,
                onAdd = onAddTransaction,
                onStats = onOpenStats,
                onCategories = onOpenCategories,
                onEdit = onEditLedger,
                onDelete = { pendingDeleteId = it }
            )
            // 玻璃分隔线
            Box(
                Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(dividerBrush())
            )
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScroll(paneScroll)
                    .padding(horizontal = paneSide)
            ) {
                Spacer(Modifier.height(12.dp))
                if (creating) {
                    InlineCreateSlot(
                        visible = panelVisible,
                        env = env,
                        onCancel = closeCreate,
                        onConfirm = confirmCreate
                    )
                    Spacer(Modifier.height(14.dp))
                }
                val currentUi = cards.firstOrNull { it.ledger.id == effectiveCurrent }
                if (widePane && currentUi != null) {
                    // 宽屏：合计（跨账本）｜当前账本 —— 左右两栏，同一视线内读完
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Box(Modifier.weight(1.08f)) {
                            AggregateCard(
                                cards = cards,
                                aggregateAll = aggregateAll,
                                aggregateIds = aggregateIds,
                                onCustomize = onCustomizeAggregate,
                                onGoDetail = onGoDetail
                            )
                        }
                        Box(Modifier.weight(1f)) {
                            CurrentLedgerPanel(
                                ui = currentUi,
                                onAdd = { onAddTransaction(currentUi.ledger.id) },
                                onDetail = onGoDetail,
                                onStats = { onOpenStats(currentUi.ledger.id) },
                                onCategories = { onOpenCategories(currentUi.ledger.id) }
                            )
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                } else {
                    AggregateCard(
                        cards = cards,
                        aggregateAll = aggregateAll,
                        aggregateIds = aggregateIds,
                        onCustomize = onCustomizeAggregate,
                        onGoDetail = onGoDetail
                    )
                    Spacer(Modifier.height(14.dp))
                    currentUi?.let { ui ->
                        CurrentLedgerPanel(
                            ui = ui,
                            onAdd = { onAddTransaction(ui.ledger.id) },
                            onDetail = onGoDetail,
                            onStats = { onOpenStats(ui.ledger.id) },
                            onCategories = { onOpenCategories(ui.ledger.id) }
                        )
                        Spacer(Modifier.height(14.dp))
                    }
                }
                SceneTemplateSection(
                    columns = if (widePane) 6 else 3,
                    onQuickCreate = { key -> onQuickCreateScene(key, markCreated) },
                    onCreateCustom = openCreate,
                    stretch = widePane
                )
                // 窄栏（折叠内屏）时右下角还有「记一笔」浮钮，留出一截让它不压内容；
                // 宽屏已改成左侧导轨，底部不再有东西，留白收回到常规呼吸即可。
                Spacer(Modifier.height(if (widePane) 24.dp else 120.dp))
            }
        }
    } else {
        if (cards.isEmpty()) {
            EmptyOnboarding(
                env = env,
                scrollState = onboardingScroll,
                onQuickCreate = { key -> onQuickCreateScene(key, markCreated) },
                onCustom = openCreate,
                modifier = Modifier
                    .then(modifier)
                    .fillMaxSize(),
                    // ⚠️ 空账本时也必须能把创建面板挂出来。早先这里是漏的：
                    // 点「手动配置」只改了 creating 标志，却没有任何地方渲染面板，
                    // 于是新用户（账本数为 0）永远打不开手动配置 —— 表现为"完全用不了"。
                    panel = if (creating) {
                        {
                            InlineCreateSlot(
                                visible = panelVisible,
                                env = env,
                                onCancel = closeCreate,
                                onConfirm = confirmCreate
                            )
                            Spacer(Modifier.height(Space.md))
                        }
                    } else null
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(env.gridColumns),
                    state = gridState,
                    modifier = Modifier
                        .then(modifier)
                        .fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = side, end = side, top = Space.sm, bottom = 136.dp
                    ),
                    horizontalArrangement = Arrangement.spacedBy(Space.gapList),
                    verticalArrangement = Arrangement.spacedBy(Space.gapList)
                ) {
                    if (creating) {
                        item(span = { GridItemSpan(maxLineSpan) }, key = "inlineCreate-$createGen") {
                            InlineCreateSlot(
                                visible = panelVisible,
                                env = env,
                                onCancel = closeCreate,
                                onConfirm = confirmCreate
                            )
                        }
                    }
                    item(span = { GridItemSpan(maxLineSpan) }, key = "aggregate") {
                        AggregateCard(
                            cards = cards,
                            aggregateAll = aggregateAll,
                            aggregateIds = aggregateIds,
                            onCustomize = onCustomizeAggregate,
                            onGoDetail = onGoDetail
                        )
                    }
                    // v13.6：**折叠形态状态条撤掉**。
                    // 它（`608 × 859 dp · 外屏 · 单栏 · 4 个账本`）本来是为了验证折叠适配
                    // 临时挂出来的，v5.3 起一直留在正式界面上：一条浅蓝横条横在账本卡之间，
                    // 读起来就是"没删干净的调试信息"；而且文案本身也不对 —— 内屏竖握
                    // （608dp < 700dp 起分点）被判成 COVER，于是显示成"外屏 · 单栏"。
                    // 姿态信息在「我的 → 折叠屏适配状态」里还留着，专门去看就行。
                    if (backupBannerVisible) {
                        item(span = { GridItemSpan(maxLineSpan) }, key = "backupBanner") {
                            BackupPromptBanner(
                                onEnable = onEnableBackup,
                                onDismiss = onDismissBackupBanner
                            )
                        }
                    }
                    item(span = { GridItemSpan(maxLineSpan) }, key = "booksHeader") {
                        SectionHeader(
                            title = "我的账本",
                            subtitle = "选中后会显示在「明细」页，不会自动跳转",
                            trailing = {
                                Text(
                                    t("＋ 新建"),
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickableScale { openCreate() }
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        )
                    }
                    items(order, key = { it.ledger.id }) { ui ->
                        val dragging = dragState.draggingId == ui.ledger.id
                        LedgerBookCard(
                            ui = ui,
                            selected = ui.ledger.id == effectiveCurrent,
                            highlight = ui.ledger.id == justCreatedId,
                            modifier = Modifier
                                // 被拖的那张不能有 animateItem：它必须严格跟手
                                .then(if (dragging) Modifier else Modifier.animateItem())
                                .bookDragHandle(
                                    id = ui.ledger.id,
                                    shape = RoundedCornerShape(Radius.card),
                                    state = dragState,
                                    ids = orderIds,
                                    boundsOf = boundsOf,
                                    onMove = onMoveBook,
                                    onDrop = {
                                        pendingOrder = orderIds
                                        onReorder(orderIds)
                                    }
                                ),
                            onClick = { onSelectLedger(ui.ledger.id) },
                            onAdd = { onAddTransaction(ui.ledger.id) },
                            onStats = { onOpenStats(ui.ledger.id) },
                            onCategories = { onOpenCategories(ui.ledger.id) },
                            onEdit = { onEditLedger(ui.ledger) },
                            onDelete = { pendingDeleteId = ui.ledger.id }
                        )
                    }
                    item(span = { GridItemSpan(maxLineSpan) }, key = "newTile") {
                        NewLedgerTile(onClick = openCreate)
                    }
                    item(span = { GridItemSpan(maxLineSpan) }, key = "sceneHeader") {
                        Spacer(Modifier.height(4.dp))
                        SectionHeader(
                            title = t("从场景模板新建"),
                            subtitle = t("点一下即生成账本 + 整套分类")
                        )
                    }
                    items(ScenePresets.gallery, key = { "scene-${it.key}" }) { scene ->
                        SceneQuickRow(
                            scene = scene,
                            onClick = { onQuickCreateScene(scene.key, markCreated) },
                            modifier = Modifier.animateItem()
                        )
                    }
                }
            }
        }

    // v8.1：删除账本二次确认 —— 提示移入回收站，给误删留后路
    val deleting = pendingDeleteId?.let { id -> cards.firstOrNull { it.ledger.id == id }?.ledger }
    if (deleting != null) {
        GlassDialog(
            onDismissRequest = { pendingDeleteId = null },
            title = { Text("删除「${deleting.name}」？") },
            text = {
                Text("账本会移入回收站，里面的流水和分类都保留，随时可以恢复。回收站里的账本可以在「我的 · 回收站」找回。")
            },
            confirmButton = {
                Text(
                    "移入回收站",
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable {
                            pendingDeleteId = null
                            onDeleteLedger(deleting.id)
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
            },
            dismissButton = {
                Text(
                    t("取消"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable { pendingDeleteId = null }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        )
    }
}

// ============================================================
//  内联创建面板的进出场
// ============================================================

/**
 * 面板进出场。
 *
 * 用 `expandVertically`（从顶部展开）+ 淡入，而不是从下方 slideIn：
 * 面板本身长在列表顶部，配合 `animateScrollToItem(0)` 一起看，「从顶部展开」比
 * 「从屏幕下方滑进来」更顺、更不突兀 —— 后者会让整块内容先冲过头再回落，长表单上显得跳。
 */
@Composable
private fun InlineCreateSlot(
    visible: Boolean,
    env: FoldEnv,
    onCancel: () -> Unit,
    onConfirm: (
        name: String,
        sceneKey: String,
        emoji: String,
        colorHex: String,
        budgetCents: Long,
        note: String
    ) -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(
            animationSpec = tween(Motion.MEDIUM, easing = Motion.glide),
            expandFrom = Alignment.Top,
            clip = false
        ) + fadeIn(tween(Motion.MEDIUM, easing = Motion.glide)),
        exit = shrinkVertically(
            animationSpec = tween(Motion.MEDIUM, easing = Motion.flow),
            shrinkTowards = Alignment.Top,
            clip = false
        ) + fadeOut(tween(Motion.MEDIUM, easing = Motion.flow))
    ) {
        CreateLedgerInlinePanel(
            env = env,
            onCancel = onCancel,
            onConfirm = onConfirm
        )
    }
}

// ============================================================
//  合计卡（可自定义范围）
// ============================================================

@Composable
private fun AggregateCard(
    cards: List<LedgerUi>,
    aggregateAll: Boolean,
    aggregateIds: Set<Long>,
    onCustomize: () -> Unit,
    onGoDetail: () -> Unit
) {
    val included = remember(cards, aggregateAll, aggregateIds) {
        if (aggregateAll) cards else cards.filter { it.ledger.id in aggregateIds }
    }
    val isAll = aggregateAll
    val expense = included.sumOf { it.expenseCents }
    val income = included.sumOf { it.incomeCents }
    val dc = LocalDirectionColors.current

    // v13.2：这一块是首屏的"主角"，膜必须**比普通卡还低**。
    // 全站卡片基准降到 13% 之后，原来的 22% 反而成了全应用最厚的一块，
    // 真机上读作"一块白板压在最上面"，和环境光的关系整个断掉。
    // 现在亮 10.5% / 暗 5.2%：比卡片低一档，厚度交给折射环与高光切面 ——
    // 它像一片浮在壁纸上的玻璃，而不是一张贴着屏幕的色卡。
    val dark = LocalDarkTheme.current
    SurfaceCard(
        shape = RoundedCornerShape(Radius.panel),
        color = if (dark) {
            Color.White.copy(alpha = 0.052f)
        } else {
            Color.White.copy(alpha = 0.105f)
        },
        tint = MaterialTheme.colorScheme.primary,
        elevation = 8.dp,
        specular = 1.15f,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(Space.xl)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        t("合计范围"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp
                    )
                    Text(
                        if (isAll) "全部 ${cards.size} 个账本" else "自选 ${included.size} / ${cards.size} 个账本",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Surface(
                    shape = RoundedCornerShape(50),
                    // v13：卡内次级控件**不叠白**（白叠白等于消失）。
                    // 用 4.5% 的墨"凹下去"——浅色玻璃上这就是"按进来的浅槽"，
                    // 也是 iOS 里 segmented / 小按钮的默认形态。
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (dark) 0.10f else 0.045f),
                    modifier = Modifier.clickableScale { onCustomize() }
                ) {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("⚙️", fontSize = 13.sp)
                        Spacer(Modifier.width(5.dp))
                        Text(
                            t("自定义"),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            AnimatedCentsText(
                cents = income - expense,
                color = MaterialTheme.colorScheme.onSurface,
                style = HeroNumber
            )
            Text(
                t("结余"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp
            )

            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(26.dp)) {
                // 浅色玻璃上红绿语义可以回来了：总支出用暖红、总收入用冷绿
                HeroMetric(
                    label = t("总支出"),
                    cents = expense,
                    valueColor = if (expense > 0) dc.expense else MaterialTheme.colorScheme.onSurface,
                    dotColor = if (expense > 0) dc.expense else MaterialTheme.colorScheme.outline
                )
                HeroMetric(
                    label = t("总收入"),
                    cents = income,
                    valueColor = if (income > 0) dc.income else MaterialTheme.colorScheme.onSurface,
                    dotColor = if (income > 0) dc.income else MaterialTheme.colorScheme.outline
                )
            }

            if (!isAll && included.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    included.take(6).forEach { ui ->
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.onSurface.copy(
                                alpha = if (dark) 0.09f else 0.04f
                            )
                        ) {
                            Text(
                                "${ui.ledger.emoji} ${ui.ledger.name}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    if (included.size > 6) {
                        Text(
                            "还有 ${included.size - 6} 个",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (included.isEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "当前没有任何账本参与合计，点「自定义」选几个",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(14.dp))
            Row(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickableScale { onGoDetail() }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "查看明细",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(4.dp))
                Text("›", fontSize = 15.sp, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun HeroMetric(label: String, cents: Long, valueColor: Color, dotColor: Color) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(7.dp)
                    .clip(RoundedCornerShape(50))
                    .background(dotColor)
            )
            Spacer(Modifier.width(5.dp))
            Text(
                label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp
            )
        }
        AnimatedCentsText(
            cents = cents,
            color = valueColor,
            fontSize = 17,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun SectionHeader(
    title: String,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            if (subtitle != null) {
                // v9.9.9：这里原本自己写 11sp，而公共 SectionHeader 用的 bodySmall（12sp）——
                // 同一句副标题在账本页比别处小一号。对齐到同一套排版 token。
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 17.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        trailing?.invoke()
    }
}

// ============================================================
//  账本卡片（带选中态）
// ============================================================

@Composable
private fun LedgerBookCard(
    ui: LedgerUi,
    selected: Boolean,
    modifier: Modifier = Modifier,
    /** 刚创建好的那张卡：给一段一次性高亮脉冲，告诉用户"就落在这儿" */
    highlight: Boolean = false,
    onClick: () -> Unit,
    onAdd: () -> Unit,
    onStats: () -> Unit,
    onCategories: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val scene = ScenePresets.byKey(ui.ledger.sceneKey)
    val accent = parseHexColor(ui.ledger.colorHex)
    val dark = LocalDarkTheme.current
    var menuOpen by remember { mutableStateOf(false) }

    // v11：选中态从"抬高+染底"改成**一圈账本色描边**。
    // 纸面本来就没有高低差，靠阴影表达选中是玻璃时代的习惯；
    // 印刷品表达"这一项被选中"用的是框 —— 1.5dp、账本自己的颜色。
    val shape = RoundedCornerShape(Radius.card)
    // v12：选中 = 这块玻璃被账本自己的颜色"染了一层"（tint），而不是套一圈描边。
    // 描边是平面语言；玻璃的选中态应该是"材质变了"。
    // v12.0.1：选中态原来是"主色 92% 实色玻璃"，真机上是一块深色板 —— 卡里的
    // 墨色账本名、红绿金额全压在深底上，字都快看不清了。改成**浅染玻璃**：
    // 用账本自己的颜色当一层薄色温（20%），再加圆角玻璃自身的棱边和前景勾选点。
    SurfaceCard(
        shape = shape,
        // ⚠️ v13.9：选中态原来是 `lerp(White, accent, 0.28).copy(alpha = 0.60f)`——
        // 28% 的色 + 60% 的不透明，合成出来是一块**半实色的淡蓝板**，比未选中的
        // 玻璃卡还"厚"。现在把不透明压到 0.38：色相留在膜里，厚度交还给引擎，
        // 选中仍然读得出来（色温 + 12dp 投影 + 右上角勾选点三重信号）。
        color = if (selected) {
            if (dark) Color.White.copy(alpha = 0.145f)
            else lerp(Color.White, accent, 0.30f).copy(alpha = 0.38f)
        } else {
            cardSurface()
        },
        tint = if (selected) accent else null,
        elevation = if (selected) 12.dp else 6.dp,
        specular = if (selected) 1.2f else 0.95f,
        modifier = modifier
            .fillMaxWidth()
            .highlightPulse(active = highlight)
            .clickableScale { onClick() }
    ) {
        Column(Modifier.padding(Space.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LedgerAvatar(ui.ledger.emoji, ui.ledger.colorHex, size = 40.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            ui.ledger.name,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (selected) {
                            Spacer(Modifier.width(6.dp))
                            Box(
                                Modifier
                                    .size(16.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(accent),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = t("当前账本"),
                                    tint = onColorFor(accent),
                                    modifier = Modifier.size(11.dp)
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(3.dp))
                    PillTag("${scene.emoji} ${scene.name}", accent, filled = true)
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = t("更多"),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    GlassMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(t("记一笔")) },
                            onClick = { menuOpen = false; onAdd() }
                        )
                        DropdownMenuItem(
                            text = { Text(t("查看统计")) },
                            onClick = { menuOpen = false; onStats() }
                        )
                        DropdownMenuItem(
                            text = { Text(t("分类管理")) },
                            onClick = { menuOpen = false; onCategories() }
                        )
                        DropdownMenuItem(
                            text = { Text(t("编辑账本")) },
                            onClick = { menuOpen = false; onEdit() }
                        )
                        DropdownMenuItem(
                            text = { Text(t("删除账本"), color = MaterialTheme.colorScheme.error) },
                            onClick = { menuOpen = false; onDelete() }
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            // 小米钱包式：小标签 + 大数字，支出是这张卡的视觉焦点
            Text(
                t("累计支出"),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(2.dp))
            AmountText(
                cents = ui.expenseCents,
                direction = Direction.EXPENSE,
                fontSize = 24,
                fontWeight = FontWeight.ExtraBold
            )

            if (ui.ledger.budgetCents > 0L) {
                Spacer(Modifier.height(10.dp))
                ThinProgress(
                    fraction = ui.budgetUsed,
                    color = if (ui.budgetUsed > 1f) MaterialTheme.colorScheme.error else accent
                )
                Spacer(Modifier.height(5.dp))
                // v9.7.1：本月预算的剩余，与明细页口径一致
                val remaining = ui.budgetRemainingCents
                Text(
                    "预算 ${Money.format(ui.ledger.budgetCents)} · 已用 ${
                        (ui.budgetUsed * 100).toInt().coerceAtLeast(0)
                    }% · ${
                        if (remaining >= 0) "剩余 ${Money.format(remaining)}"
                        else "超支 ${Money.format(-remaining)}"
                    }",
                    fontSize = 11.sp,
                    color = if (ui.budgetUsed > 1f) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(10.dp))
            // ▲▼ 小米钱包式对称两格
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MiniStat(t("收入"), Money.format(ui.incomeCents), Direction.INCOME, Modifier.weight(1f))
                MiniStat(
                    t("结余"),
                    Money.format(ui.balanceCents),
                    if (ui.balanceCents >= 0) Direction.INCOME else Direction.EXPENSE,
                    Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun MiniStat(label: String, value: String, direction: Int, modifier: Modifier = Modifier) {
    val dc = LocalDirectionColors.current
    val color = if (direction == Direction.EXPENSE) dc.expense else dc.income
    // v11：不再给每格垫一块 6% 的色底 —— 卡里已经有主数字、进度条、两格统计，
    // 每格都垫底色就又回到"彩色马赛克"。这里只留标签 + 数字，靠位置分组。
    Column(modifier.padding(vertical = 2.dp)) {
        Text(
            label,
            fontSize = 11.sp,
            letterSpacing = 0.8.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(3.dp))
        Text(
            value,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ============================================================
//  双栏：左栏紧凑列表
// ============================================================

@Composable
private fun BookMasterList(
    modifier: Modifier,
    state: LazyListState,
    cards: List<LedgerUi>,
    dragState: BookDragState,
    boundsOf: (Long) -> Pair<Offset, Size>?,
    onMove: (Int, Int) -> Unit,
    onDrop: () -> Unit,
    currentLedgerId: Long?,
    highlightId: Long?,
    onSelect: (Long) -> Unit,
    onCreate: () -> Unit,
    onGoDetail: () -> Unit,
    onAdd: (Long) -> Unit,
    onStats: (Long) -> Unit,
    onCategories: (Long) -> Unit,
    onEdit: (LedgerEntity) -> Unit,
    onDelete: (Long) -> Unit
) {
    Column(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "分账本",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            Text(
                t("＋ 新建"),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickableScale { onCreate() }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
        LazyColumn(
            state = state,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(cards, key = { it.ledger.id }) { ui ->
                val selected = ui.ledger.id == currentLedgerId
                val scene = ScenePresets.byKey(ui.ledger.sceneKey)
                var menuOpen by remember { mutableStateOf(false) }
                val dragging = dragState.draggingId == ui.ledger.id
                // v13：这一栏也支持长按拖动排序 —— 展开态是主要使用形态，
                // 只有折叠态能排序等于没做。
                val rowShape = RoundedCornerShape(14.dp)
                Surface(
                    shape = rowShape,
                    color = if (selected) MaterialTheme.colorScheme.primaryContainer
                    else cardSurface(),
                    border = if (selected) androidx.compose.foundation.BorderStroke(
                        1.5.dp, parseHexColor(ui.ledger.colorHex)
                    ) else null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .highlightPulse(active = ui.ledger.id == highlightId)
                        .then(if (dragging) Modifier else Modifier.animateItem())
                        .bookDragHandle(
                            id = ui.ledger.id,
                            shape = rowShape,
                            state = dragState,
                            ids = cards.map { it.ledger.id },
                            boundsOf = boundsOf,
                            onMove = onMove,
                            onDrop = onDrop
                        )
                        .clickableScale { onSelect(ui.ledger.id) }
                ) {
                    Row(
                        Modifier.padding(start = 10.dp, end = 2.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        LedgerAvatar(ui.ledger.emoji, ui.ledger.colorHex, size = 36.dp, emojiSize = 16)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                ui.ledger.name,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurface
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "${scene.emoji} ${scene.name}",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                                Spacer(Modifier.width(6.dp))
                                AmountText(
                                    cents = ui.expenseCents,
                                    direction = Direction.EXPENSE,
                                    fontSize = 13,
                                    prefix = "",
                                    colored = false
                                )
                            }
                        }
                        // 展开态左栏此前没有任何删除/编辑入口 —— 补一个更多菜单，
                        // 否则用户只能回到单栏（折叠）才能删账本。
                        Box {
                            IconButton(
                                onClick = { menuOpen = true },
                                // v9.8.0：34dp 达不到 40dp 的最小可点尺寸（折叠/展开后手感还突变）
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(
                                    Icons.Filled.MoreVert,
                                    contentDescription = t("更多"),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            GlassMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text(t("记一笔")) },
                                    onClick = { menuOpen = false; onAdd(ui.ledger.id) }
                                )
                                DropdownMenuItem(
                                    text = { Text(t("查看统计")) },
                                    onClick = { menuOpen = false; onStats(ui.ledger.id) }
                                )
                                DropdownMenuItem(
                                    text = { Text(t("分类管理")) },
                                    onClick = { menuOpen = false; onCategories(ui.ledger.id) }
                                )
                                DropdownMenuItem(
                                    text = { Text(t("编辑账本")) },
                                    onClick = { menuOpen = false; onEdit(ui.ledger) }
                                )
                                DropdownMenuItem(
                                    text = { Text(t("删除账本"), color = MaterialTheme.colorScheme.error) },
                                    onClick = { menuOpen = false; onDelete(ui.ledger.id) }
                                )
                            }
                        }
                    }
                }
            }
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickableScale { onGoDetail() }
                        .padding(horizontal = 10.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("查看明细 ›", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

// ============================================================
//  双栏：右栏当前账本概览 + 场景模板
// ============================================================

@Composable
private fun CurrentLedgerPanel(
    ui: LedgerUi,
    onAdd: () -> Unit,
    onDetail: () -> Unit,
    onStats: () -> Unit,
    onCategories: () -> Unit
) {
    val scene = ScenePresets.byKey(ui.ledger.sceneKey)
    val accent = parseHexColor(ui.ledger.colorHex)

    SurfaceCard(
        shape = RoundedCornerShape(Radius.card),
        color = cardSurface(),
        elevation = 4.dp,
        modifier = Modifier
            .fillMaxWidth()
    ) {
        Column(Modifier.padding(Space.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LedgerAvatar(ui.ledger.emoji, ui.ledger.colorHex, size = 44.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        ui.ledger.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${scene.emoji} ${scene.name} · ${scene.tagline}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(t("累计支出"), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    AmountText(ui.expenseCents, Direction.EXPENSE, fontSize = 20)
                }
                Column(Modifier.weight(1f)) {
                    Text("累计收入", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    AmountText(ui.incomeCents, Direction.INCOME, fontSize = 20)
                }
            }
            if (ui.ledger.budgetCents > 0) {
                Spacer(Modifier.height(14.dp))
                ThinProgress(
                    fraction = ui.budgetUsed,
                    color = if (ui.budgetUsed > 1f) MaterialTheme.colorScheme.error else accent
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "预算 ${Money.format(ui.ledger.budgetCents)} · 已用 ${
                        (ui.budgetUsed * 100).toInt().coerceAtLeast(0)
                    }%",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuickAction(t("记一笔"), accent, onAdd)
                QuickAction(t("明细"), MaterialTheme.colorScheme.primary, onDetail)
                QuickAction(t("统计"), MaterialTheme.colorScheme.primary, onStats)
                QuickAction(t("分类"), MaterialTheme.colorScheme.primary, onCategories)
            }
        }
    }
}

@Composable
private fun QuickAction(label: String, color: Color, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.12f),
        modifier = Modifier.clickableScale { onClick() }
    ) {
        Text(
            label,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = color,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
        )
    }
}

@Composable
private fun SceneTemplateSection(
    columns: Int,
    onQuickCreate: (String) -> Unit,
    onCreateCustom: () -> Unit,
    /**
     * v16.4：宽屏把网格**撑满整行**。
     *
     * 场景芯片原本是固定 112dp，靠 FlowRow 自然换行 —— 手机上是正确的（一屏两三列）。
     * 但桌面上右栏有 1080dp，4×112 只铺到 472dp，剩下 600dp 全是空 ——
     * 「屏幕利用率低」有一半是这类固定宽度造成的。宽屏改成按列数等分，
     * 网格正好铺满，芯片也回到 180~210dp 的自然宽度（再宽就成了横幅）。
     */
    stretch: Boolean = false
) {
    Column {
        SectionHeader(
            title = t("从场景模板新建"),
            subtitle = t("点一下即生成账本 + 整套分类")
        )
        Spacer(Modifier.height(8.dp))
        if (stretch) {
            BoxWithConstraints {
                val gap = 8.dp
                val itemW = (maxWidth - gap * (columns - 1)) / columns
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(gap),
                    verticalArrangement = Arrangement.spacedBy(gap),
                    maxItemsInEachRow = columns
                ) {
                    ScenePresets.gallery.forEach { s ->
                        SceneChip(s, itemW, onQuickCreate)
                    }
                }
            }
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                maxItemsInEachRow = columns
            ) {
                ScenePresets.gallery.forEach { s ->
                    SceneChip(s, 112.dp, onQuickCreate)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .glassChip(shape = RoundedCornerShape(14.dp), tint = null, strength = 0.9f)
                .clickableScale { onCreateCustom() }
        ) {
            Row(
                Modifier.padding(vertical = 14.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("⚙️", fontSize = 13.sp)
                Spacer(Modifier.width(6.dp))
                Text(
                    "手动配置账本（名称 / 图标 / 配色 / 预算）",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

/**
 * 一枚场景模板芯片（v16.4 抽出，供固定宽度与等分拉伸两种排版共用）。
 *
 * 染色玻璃而不是"白卡 + 1dp 色边框"（v13.9 的结论）：边框是画出来的线，
 * 玻璃的边界是折出来的棱，两者不能混用。
 */
@Composable
private fun SceneChip(
    s: ScenePreset,
    width: Dp,
    onQuickCreate: (String) -> Unit
) {
    Box(
        Modifier
            .width(width)
            .glassChip(
                shape = RoundedCornerShape(Radius.sm),
                tint = parseHexColor(s.colorHex),
                strength = 1.2f
            )
            .clickableScale { onQuickCreate(s.key) }
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

// ============================================================
//  无账本时的引导页
// ============================================================

@Composable
private fun EmptyOnboarding(
    env: FoldEnv,
    scrollState: ScrollState,
    onQuickCreate: (String) -> Unit,
    onCustom: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * 供「手动配置」用的内联创建面板插槽。
     *
     * 空账本时必须能把它挂出来 —— 否则新用户点「手动配置」什么都不会发生。
     * 注意本函数自己已经是 `verticalScroll`，插入的面板**不能**再带滚动容器。
     */
    panel: (@Composable () -> Unit)? = null
) {
    Column(
        modifier
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp)
    ) {
        if (panel != null) {
            panel()
        }
        Spacer(Modifier.height(6.dp))
        StaggeredAppear(index = 0) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(20.dp)) {
                    Text(
                        "👋 欢迎使用滴水记账",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "选一个记账场景，一键生成账本和整套分类。\n账本按用途分开记，家装、报销、母婴互不干扰。",
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.88f)
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PillTag(
                            env.postureLabel,
                            MaterialTheme.colorScheme.onPrimary,
                            filled = true
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "${env.widthDp} × ${env.heightDp} dp",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        StaggeredAppear(index = 1) {
            SectionHeader(
                title = "预置场景模板",
                subtitle = "点任意一行，立即创建该场景的账本（之后可随时改名）"
            )
        }
        Spacer(Modifier.height(10.dp))

        ScenePresets.gallery.forEachIndexed { index, scene ->
            StaggeredAppear(index = index + 2) {
                SceneQuickRow(scene = scene, onClick = { onQuickCreate(scene.key) })
            }
            Spacer(Modifier.height(8.dp))
        }

        Spacer(Modifier.height(10.dp))
        StaggeredAppear(index = 10) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickableScale { onCustom() }
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "⚙️ 手动配置",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "自定义名称、图标、主题色与预算",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        Spacer(Modifier.height(110.dp))
    }
}

@Composable
private fun SceneQuickRow(
    scene: ScenePreset,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = parseHexColor(scene.colorHex)
    SurfaceCard(
        shape = RoundedCornerShape(16.dp),
        color = cardSurface(),
        elevation = 4.dp,
        modifier = modifier
            .fillMaxWidth()
            .clickableScale { onClick() }
    ) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(accent.copy(alpha = 0.13f)),
                contentAlignment = Alignment.Center
            ) {
                Text(scene.emoji, fontSize = 20.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "${scene.name}账本",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
                Text(
                    scene.tagline,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PillTag("支出 ${scene.expenses.size}", accent, filled = true)
                    PillTag(
                        "收入 ${scene.incomes.size}",
                        MaterialTheme.colorScheme.onSurfaceVariant,
                        filled = true
                    )
                    if (scene.supportsReimbursement) PillTag("报销看板", accent)
                }
            }
            Spacer(Modifier.width(8.dp))
            // v12.1.2：全 App 最后一处实色饱和块改成染色玻璃 ——
            // 与选中账本卡同一套口径（亮 lerp 白/accent 0.28·α.60，暗白膜 α.145 + tint），
            // 文字用场景色墨水，不再用"实底上的反白"。
            val dark = LocalDarkTheme.current
            SurfaceCard(
                shape = RoundedCornerShape(50),
                color = if (dark) Color.White.copy(alpha = 0.145f)
                else lerp(Color.White, accent, 0.28f).copy(alpha = 0.60f),
                tint = accent,
                elevation = 2.dp,
                specular = 0.9f,
                softShadow = false
            ) {
                Text(
                    t("创建"),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = accent,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                )
            }
        }
    }
}

@Composable
private fun NewLedgerTile(onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(Radius.card),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier
            .fillMaxWidth()
            .clickableScale { onClick() }
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(vertical = Space.xxl),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("⚙️", fontSize = 24.sp)
            Spacer(Modifier.height(Space.sm))
            Text(
                "手动配置账本",
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(3.dp))
            Text(
                "自定义名称 / 图标 / 配色 / 预算",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
