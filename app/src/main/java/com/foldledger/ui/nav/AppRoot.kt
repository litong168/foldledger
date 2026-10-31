package com.foldledger.ui.nav

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.foldledger.data.ScenePresets
import com.foldledger.data.db.LedgerEntity
import com.foldledger.ui.adaptive.AdaptiveScaffold
import com.foldledger.ui.adaptive.AppTab
import com.foldledger.ui.adaptive.findActivity
import com.foldledger.ui.adaptive.rememberFoldEnv
import com.foldledger.ui.anim.Motion
import com.foldledger.ui.components.AppToastHost
import com.foldledger.ui.components.GlassMenu
import com.foldledger.ui.screens.AddTxnOverlay
import com.foldledger.ui.screens.AccountManageScreen
import com.foldledger.ui.screens.AssistantScreen
import com.foldledger.ui.screens.BillImportScreen
import com.foldledger.ui.screens.GlobalSearchScreen
import com.foldledger.ui.screens.GoalScreen
import com.foldledger.ui.screens.MoreSettingsScreen
import com.foldledger.ui.screens.RecycleBinScreen
import com.foldledger.ui.screens.AggregatePickerOverlay
import com.foldledger.ui.screens.BackupPickerOverlay
import com.foldledger.ui.screens.CategoryManageScreen
import com.foldledger.ui.screens.CreateLedgerOverlay
import com.foldledger.ui.screens.InsightScreen
import com.foldledger.ui.screens.LedgerBooksScreen
import com.foldledger.ui.screens.LedgerDetailTabScreen
import com.foldledger.ui.screens.SettingsScreen
import com.foldledger.ui.screens.SpeedEntryScreen
import com.foldledger.ui.screens.SpendCalendarScreen
import com.foldledger.ui.screens.StatsScreen
import com.foldledger.ui.screens.SystemAccessScreen
import com.foldledger.ui.theme.FoldLedgerTheme
import com.foldledger.ui.theme.GlassGlowController
import com.foldledger.ui.theme.LocalGlassGlow
import com.foldledger.ui.vm.AppViewModel
import com.foldledger.ui.vm.BackupViewModel
import com.foldledger.util.FrameRate
import kotlinx.coroutines.delay

object Routes {
    const val LEDGERS = "ledgers"
    const val DETAIL = "detail"
    const val SETTINGS = "settings"
}

/** 二级页退场时长：播完才把节点摘掉，否则会"啪"一下消失 */
private const val PAGE_EXIT_MS = 300L

/** 让系统目录选择器默认落在「下载」目录 —— 这是"傻瓜式"的关键一步 */
private val DOWNLOADS_HINT: Uri? = runCatching {
    Uri.parse("content://com.android.external.documents/document/primary%3ADownload")
}.getOrNull()

private sealed interface OverlayTarget {
    data class AddTxn(val ledgerId: Long, val txnId: Long? = null) : OverlayTarget
    data class EditLedger(val ledger: LedgerEntity) : OverlayTarget
    data object AggregatePicker : OverlayTarget

    /** v3.2.0：二级页（统计 / 分类）——整页覆盖，自带一套外壳 */
    data class Stats(val ledgerId: Long) : OverlayTarget
    data class Categories(val ledgerId: Long) : OverlayTarget
    data class Accounts(val ledgerId: Long) : OverlayTarget
    /** v7.5 消费日历 */
    data class SpendCalendar(val ledgerId: Long) : OverlayTarget
    /** v8.0 智能洞察 */
    data class Insight(val ledgerId: Long) : OverlayTarget
    /** v8.1.1 系统权限引导（小米管控解锁） */
    data object SystemAccess : OverlayTarget
    /** v8.6 一句话记账 */
    data class SpeedEntry(val ledgerId: Long) : OverlayTarget
    /** v9.0 记账助手 · v9.3 起为全局页（跨账本聚合） */
    data object Assistant : OverlayTarget
    /** v9.1 账单导入 */
    data class BillImport(val ledgerId: Long) : OverlayTarget
    /** v9.2 存钱目标 */
    data class Goal(val ledgerId: Long) : OverlayTarget
    /** v9.4 全局回收站（账本 / 存钱目标 / 备份文件） */
    data object RecycleBin : OverlayTarget
    /** v10.5 全局搜索（代码 v5.9 写完了但一直没入口） */
    data object GlobalSearch : OverlayTarget
    /** v9.5 更多设置（低频项二级页） */
    data object MoreSettings : OverlayTarget
}

private fun AppTab.route(): String = when (this) {
    AppTab.DETAIL -> Routes.DETAIL
    AppTab.LEDGERS -> Routes.LEDGERS
    AppTab.MINE -> Routes.SETTINGS
}

/** Tab 在导航顺序里的下标，用来判断切换方向（内容该从右进还是从左进） */
private fun tabIndexOf(route: String?): Int = when (route) {
    Routes.DETAIL -> 0
    Routes.LEDGERS -> 1
    Routes.SETTINGS -> 2
    else -> -1
}

/**
 * Tab 切换的进入转场：微位移 + 淡入。
 *
 * 位移收在内容宽的 1/24（≈17dp）：只给方向感，不喧宾夺主。方向随 Tab 顺序翻转 ——
 * 往右切（明细→账本→我的）内容从右进，往左切则从左进，与手指动作一致。
 * 时长比页面推入略短：Tab 是高频操作，要干脆；推入推出一级页面才需要仪式感。
 */
private fun tabEnter(fromRoute: String?, toRoute: String?): EnterTransition {
    val dir = if (tabIndexOf(toRoute) >= tabIndexOf(fromRoute)) 1 else -1
    return slideInHorizontally(
        animationSpec = tween(Motion.PAGE_ENTER, easing = Motion.flow)
    ) { (it / 24) * dir } +
        fadeIn(tween(220, easing = Motion.standard))
}

/** Tab 切换的退出转场：与进入严格反向、比进入更快 —— 退场永远不该拖住用户 */
private fun tabExit(fromRoute: String?, toRoute: String?): ExitTransition {
    val dir = if (tabIndexOf(toRoute) >= tabIndexOf(fromRoute)) 1 else -1
    return slideOutHorizontally(
        animationSpec = tween(Motion.PAGE_EXIT + 40, easing = Motion.flow)
    ) { (-it / 24) * dir } +
        fadeOut(tween(180, easing = Motion.standard))
}

/**
 * 应用根。
 *
 * ### v3.2.0 外壳常驻 —— 根治「切 Tab 闪一下」
 * 早先每个页面各自 `AdaptiveScaffold`，于是切 Tab 时是**两套顶栏 + 两套底部导航栏交叉淡入淡出**，
 * 两个选中指示器在半透明下互相叠影、页面背景也被画了两遍 —— 用户报的「闪一下」正是如此。
 * 现在分层各归其位：
 *
 * | 层 | 由谁渲染 | 转场 |
 * |---|---|---|
 * | 顶栏 + 导航栏 + FAB | **AppRoot 唯一一套**（常驻） | 不参与转场；指示器滑动过去 |
 * | 页面背景 | `AppBackground` 只画一次 | 不参与转场，天然没有亮度塌陷 |
 * | Tab 内容（明细/账本/我的） | NavHost | 微位移 + 淡入淡出，方向随 Tab 顺序 |
 * | 二级页（统计 / 分类） | 根 Box 的整页覆盖层 | 整页推入 + 底层视差左移 |
 * | 记账 / 编辑 / 合计 / 选备份 | 根 Box 的弹层 | 各自的 AppOverlay 动画 |
 */
@Composable
fun AppRoot(pendingQuickAdd: MutableState<Boolean> = mutableStateOf(false)) {
    val appVm: AppViewModel = viewModel(factory = AppViewModel.Factory)
    // v9.7.1：回前台推一次时间锚点 —— App 常驻跨月不重启时，
    // 账本卡上的「本月预算」也要跟着月走
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        appVm.refreshPeriod()
        onPauseOrDispose { }
    }
    val backupVm: BackupViewModel = viewModel(factory = BackupViewModel.Factory)

    val settings by appVm.settings.collectAsStateWithLifecycle()
    val rawCards by appVm.ledgerCards.collectAsStateWithLifecycle()
    val backupState by backupVm.state.collectAsStateWithLifecycle()

    // v13：账本列表的手动顺序（长按拖拽的结果）。
    // 排序只发生在**展示层**：repo 仍然按创建顺序给，这里按偏好重排 ——
    // 没出现在偏好里的 id（刚新建的）保持原相对顺序追加在后面。
    // ⚠️ 不要把这个顺序写回数据库：多一张表/多一列就要一次 Room 迁移，
    // 而这个需求本质上只是"界面上先看哪个账本"。
    val cards = remember(rawCards, settings.ledgerOrder) {
        val order = settings.ledgerOrder
        if (order.isEmpty()) rawCards
        else {
            val rank = order.withIndex().associate { (i, id) -> id to i }
            rawCards.sortedBy { rank[it.ledger.id] ?: Int.MAX_VALUE }
        }
    }

    FoldLedgerTheme(settings = settings) {
        CompositionLocalProvider(LocalGlassGlow provides remember { GlassGlowController() }) {
        val env = rememberFoldEnv()
        val nav = rememberNavController()

        // ---- 高刷新率：默认恒开启，不再提供开关 ----
        val context = LocalContext.current
        val activity = remember(context) { context.findActivity() }
        LaunchedEffect(activity) {
            activity?.window?.let { FrameRate.apply(it, true) }
        }

        var currentLedgerId by rememberSaveable { mutableStateOf<Long?>(null) }
        var overlay by remember { mutableStateOf<OverlayTarget?>(null) }
        var backupBannerDismissed by rememberSaveable { mutableStateOf(false) }

        /** v3.2.0：外壳 FAB 在空账本时点「记一笔」，把命令传给账本页去打开创建面板 */
        var createRequestTick by remember { mutableStateOf(0) }

        // ---- 二级页（统计 / 分类）状态：整页覆盖 + 底层视差 ----
        var pushedPage by remember { mutableStateOf<OverlayTarget?>(null) }
        var pushedVisible by remember { mutableStateOf(false) }
        val openPage: (OverlayTarget) -> Unit = { page ->
            pushedPage = page
            pushedVisible = true
        }
        val closePage: () -> Unit = { pushedVisible = false }

        // v14：桌面组件「速记」按钮的 QUICK_ADD intent 钩子 —— 打开 SpeedEntry 浮层。
        // 等账本列表就绪（currentLedgerId 非 null）后再开，避免空 ledger 闪一下。
        LaunchedEffect(pendingQuickAdd.value, currentLedgerId) {
            if (pendingQuickAdd.value && currentLedgerId != null) {
                openPage(OverlayTarget.SpeedEntry(currentLedgerId!!))
                pendingQuickAdd.value = false
            }
        }

        // 退场动画播完再摘节点：否则页面会"啪"一下消失，没有滑走的手感
        LaunchedEffect(pushedVisible) {
            if (!pushedVisible && pushedPage != null) {
                delay(PAGE_EXIT_MS)
                pushedPage = null
            }
        }

        // ---- 备份目录授权：唯一需要用户动一次手的地方，之后全程一键 ----
        val folderLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocumentTree()
        ) { uri ->
            if (uri != null) backupVm.onFolderGranted(uri) else backupVm.onFolderPickCancelled()
        }

        LaunchedEffect(backupState.needsFolderFor) {
            if (backupState.needsFolderFor != null) {
                folderLauncher.launch(DOWNLOADS_HINT)
            }
        }

        LaunchedEffect(backupState.restoredTick) {
            if (backupState.restoredTick > 0) currentLedgerId = null
        }

        // 「当前账本」的校正：账本被删掉或首次进入时自动落到第一个
        LaunchedEffect(cards) {
            if (cards.isEmpty()) {
                currentLedgerId = null
            } else if (cards.none { it.ledger.id == currentLedgerId }) {
                currentLedgerId = cards.first().ledger.id
            }
        }

        val goTab: (AppTab) -> Unit = { tab ->
            overlay = null
            closePage()
            nav.navigate(tab.route()) {
                // 明细是导航里的第一项，也是起始目的地，切 Tab 时以它为锚点回退
                popUpTo(Routes.DETAIL) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }

        val selectLedger: (Long) -> Unit = { id ->
            currentLedgerId = id
            appVm.setLastLedger(id)
        }

        val addTxn: (Long) -> Unit = { id ->
            selectLedger(id)
            overlay = OverlayTarget.AddTxn(id)
        }

        // 一键按场景模板创建账本。
        // onCreated 回调把新账本 id 带回账本页，让它滚过去并高亮 ——
        // 否则场景模板创建完只会静默选中，列表一动也不动。
        val quickCreate: (String, (Long) -> Unit) -> Unit = { sceneKey, onCreated ->
            val s = ScenePresets.byKey(sceneKey)
            appVm.createLedger(
                name = "${s.name}账本",
                sceneKey = sceneKey,
                emoji = s.emoji,
                colorHex = s.colorHex,
                budgetCents = s.suggestedBudgetYuan * 100,
                note = "",
                onCreated = { id -> selectLedger(id); onCreated(id) }
            )
        }

        // ================= v3.2.0 外壳：由当前路由推导 =================
        val backStackEntry by nav.currentBackStackEntryAsState()
        val currentTab = when (backStackEntry?.destination?.route) {
            Routes.LEDGERS -> AppTab.LEDGERS
            Routes.SETTINGS -> AppTab.MINE
            else -> AppTab.DETAIL
        }
        val currentLedger = cards.firstOrNull { it.ledger.id == currentLedgerId } ?: cards.firstOrNull()

        val chromeTitle: String
        val chromeSubtitle: String
        when (currentTab) {
            AppTab.LEDGERS -> {
                chromeTitle = "账本"
                chromeSubtitle =
                    if (cards.isEmpty()) "还没有账本" else "${cards.size} 个分账本 · 点一下选中它"
            }
            AppTab.MINE -> {
                chromeTitle = "我的"
                chromeSubtitle = "备份 · 外观 · 数据安全"
            }
            else -> {
                // v13.7：**大字放变量，小字放栏目**。
                // 原来 23sp 写着恒定的「明细」，账本名挤在 11sp 的副标题里，
                // 下面再排一整行账本 chips —— 最大的字没信息量，有信息量的字最小。
                // 现在反过来：大标题 = 当前账本名（点右侧箭头换），小字 = 场景。
                chromeTitle = currentLedger?.ledger?.name ?: "明细"
                chromeSubtitle = currentLedger?.let {
                    val scene = ScenePresets.byKey(it.ledger.sceneKey)
                    "${scene.emoji} ${scene.name}"
                } ?: "还没有账本"
            }
        }

        // 底层 Tab 内容在二级页推入时向左挪一点点 —— 两层不同速度，读起来就是纵深
        val contentParallax by animateDpAsState(
            targetValue = if (pushedVisible) (-26).dp else 0.dp,
            animationSpec = tween(Motion.MEDIUM + 20, easing = Motion.flow),
            label = "pageParallax"
        )

        // ---- v12.1：三 Tab 横滑切换（明细 ⇄ 账本 ⇄ 我的）----
        // v12.1.4：内外屏统一为底部悬浮胶囊，手势只挂在胶囊上（不再有侧栏分支）。
        // 按住来回抹 —— 指尖落在哪个图标格就实时切到哪个，往回抹立刻切回来。
        // 二级页 / 弹层在场时关掉：那时导航已退场，横滑没意义还会抢返回手势。
        val tabOrder = AppTab.entries
        val (navSwipeModifier, navSwipeDrag) = rememberTabSwipe(
            enabled = !pushedVisible && overlay == null,
            selectedIndex = currentTab.ordinal,
            tabCount = tabOrder.size,
            // v13.6：胶囊已回到纯三格（「记一笔」切出去成独立圆钮），网格数 = Tab 数，
            // 不再需要 cellCount 去补那"多出来的第四格"。
            onSelect = { goTab(tabOrder[it]) }
        )

        // v13.5：顶栏大标题的折叠进度（内容层写、外壳层读）。
        // 明细页在 NavHost 里退居后台时**不会销毁**，它留下的 1 会让"我的"页
        // 顶着一条被压扁的顶栏 —— 所以切 Tab / 打开二级页 / 弹层时兜底归零。
        val titleCollapse = remember { com.foldledger.ui.adaptive.TitleCollapseState() }
        androidx.compose.runtime.LaunchedEffect(currentTab, pushedVisible, overlay) {
            if (currentTab != AppTab.DETAIL || pushedVisible || overlay != null) {
                titleCollapse.reset()
            }
        }

        androidx.compose.runtime.CompositionLocalProvider(
            com.foldledger.ui.adaptive.LocalTitleCollapse provides titleCollapse
        ) {
        Box(Modifier.fillMaxSize()) {
            AdaptiveScaffold(
                env = env,
                title = chromeTitle,
                subtitle = chromeSubtitle,
                // 只有明细页有"当前对象"可换；账本页 / 我的页的标题是栏目名，点它没意义。
                // 只有一个账本时也不给箭头 —— 点开发现只有一项，比没有更烦。
                titleMenu = if (currentTab == AppTab.DETAIL && cards.size > 1) {
                    { dismiss ->
                        LedgerTitleMenuContent(
                            cards = cards,
                            currentId = currentLedger?.ledger?.id,
                            onPick = { id ->
                                selectLedger(id)
                                dismiss()
                            }
                        )
                    }
                } else null,
                selectedTab = currentTab,
                onTabSelected = goTab,
                actions = {
                    when (currentTab) {
                        // 账本页右上角直达「我的」
                        AppTab.LEDGERS -> {
                            IconButton(onClick = { goTab(AppTab.MINE) }) {
                                Icon(Icons.Filled.Settings, contentDescription = "设置")
                            }
                        }
                        // 明细页（v12）：顶栏只留两个**单色**动作 —— 搜索 + 更多。
                        // 原来的五个彩色 emoji 在 400dp 外屏上总宽 240dp，直接把标题挤爆、
                        // 最后一个还被切在屏幕外。工具本身没减少，只是收进了一个分组菜单：
                        // 图标统一为线性单色，视线才落得回标题和金额上。
                        AppTab.DETAIL -> if (currentLedger != null) {
                            TopIconAction(
                                icon = Icons.Filled.Search,
                                label = "全局搜索"
                            ) { openPage(OverlayTarget.GlobalSearch) }
                            DetailToolsMenu(
                                ledgerId = currentLedger.ledger.id,
                                openPage = openPage
                            )
                        }
                        else -> Unit
                    }
                },
                // v13.5：**悬浮球退役** —— 「记一笔」沉进底栏最右格（见 onAdd）。
                // 悬着的钮一定盖住某一行：原来首屏第二行流水的金额末位就被它吃掉。
                // 沉进胶囊后它只占胶囊自己那条本来就读不到东西的带，位置和拇指习惯不变。
                onAdd = {
                    val id = currentLedger?.ledger?.id
                    if (id != null) addTxn(id) else createRequestTick++
                },
                navSwipeModifier = navSwipeModifier,
                navSwipeDragPx = navSwipeDrag
            ) { padding ->
                // v13.9.2：**底栏不再占位 —— 内容一路铺到屏幕最底边。**
                //
                // 原来这里给底栏留了 (bottomBar 高度 - 16dp) ≈ 72dp 的带。那一带里
                // 什么都没有，只有页面的砂色底 + 背景的「底部压沉」渐变 —— 底栏明明
                // 是浮动的（胶囊和「记一笔」都浮在内容之上），它下面却压着一块
                // 不透明的底色带，读起来就是"浮层下面还有一堵墙"。
                //
                // 底栏既然是浮动的，内容就不该为它让路：直接顶满全屏，胶囊浮在上面。
                //
                // ⚠️ 三道 Tab 页自己的 bottom contentPadding（120~130dp）远大于胶囊高度，
                //    所以最后一项仍然能完整滚到胶囊上方 —— "顶满"不会把最后一条压住。
                val tabDir = LocalLayoutDirection.current
                val tabPadding = Modifier.padding(
                    start = padding.calculateStartPadding(tabDir),
                    top = padding.calculateTopPadding(),
                    end = padding.calculateEndPadding(tabDir),
                    bottom = 0.dp
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .offset(x = contentParallax)
                    // v12.1.2：横滑手势已挪到底栏胶囊上 —— 内容不再整页跟手位移
                ) {
                    NavHost(
                        navController = nav,
                        startDestination = Routes.DETAIL,
                        modifier = Modifier.fillMaxSize(),
                        // ---- Tab 切换转场（v3.2.0 重做）----
                        // 外壳常驻 + 背景只画一次 → 结构上不可能再出现
                        // 「两套导航栏叠影」或「两份背景交叉淡化」的闪烁。
                        // 位移刻意收得很小（1/24 内容宽 ≈ 17dp）：只给方向感，不喧宾夺主；
                        // 方向随 Tab 顺序翻转 —— 往右切从右进，往左切从左进。
                        enterTransition = {
                            tabEnter(
                                initialState?.destination?.route,
                                targetState?.destination?.route
                            )
                        },
                        exitTransition = {
                            tabExit(
                                initialState?.destination?.route,
                                targetState?.destination?.route
                            )
                        },
                        popEnterTransition = {
                            tabEnter(
                                initialState?.destination?.route,
                                targetState?.destination?.route
                            )
                        },
                        popExitTransition = {
                            tabExit(
                                initialState?.destination?.route,
                                targetState?.destination?.route
                            )
                        }
                    ) {
                        composable(Routes.LEDGERS) {
                            LedgerBooksScreen(
                                env = env,
                                cards = cards,
                                currentLedgerId = currentLedgerId,
                                aggregateAll = settings.aggregateAll,
                                aggregateIds = settings.aggregateLedgerIds,
                                backupBannerVisible = !backupState.folderReady && !backupBannerDismissed,
                                createRequestTick = createRequestTick,
                                // v9.7.2：命令消费后清零，否则切走再切回会重复弹创建面板
                                onCreateRequestHandled = { createRequestTick = 0 },
                                onSelectLedger = selectLedger,
                                onAddTransaction = addTxn,
                                onReorder = appVm::setLedgerOrder,
                                onOpenStats = { openPage(OverlayTarget.Stats(it)) },
                                onOpenCategories = { openPage(OverlayTarget.Categories(it)) },
                                onEditLedger = { overlay = OverlayTarget.EditLedger(it) },
                                onDeleteLedger = { id ->
                                    appVm.deleteLedger(id)
                                    if (currentLedgerId == id) currentLedgerId = null
                                },
                                onQuickCreateScene = quickCreate,
                                onCreateLedgerConfirm = { name, sceneKey, emoji, colorHex, budget, note, onCreated ->
                                    appVm.createLedger(
                                        name = name,
                                        sceneKey = sceneKey,
                                        emoji = emoji,
                                        colorHex = colorHex,
                                        budgetCents = budget,
                                        note = note,
                                        onCreated = { id ->
                                            selectLedger(id)
                                            // 把新账本 id 回传给账本页，让它滚过去并高亮
                                            onCreated(id)
                                        }
                                    )
                                },
                                onCustomizeAggregate = { overlay = OverlayTarget.AggregatePicker },
                                onGoDetail = { goTab(AppTab.DETAIL) },
                                onEnableBackup = { backupVm.setAutoBackupEnabled(true) },
                                onDismissBackupBanner = { backupBannerDismissed = true },
                                modifier = tabPadding
                            )
                        }

                        composable(Routes.DETAIL) {
                            LedgerDetailTabScreen(
                                env = env,
                                cards = cards,
                                currentLedgerId = currentLedgerId,
                                onGoBooks = { goTab(AppTab.LEDGERS) },
                                onOpenStats = { openPage(OverlayTarget.Stats(it)) },
                                onOpenCategories = { openPage(OverlayTarget.Categories(it)) },
                                onOpenAccounts = { openPage(OverlayTarget.Accounts(it)) },
                                onAddTransaction = { overlay = OverlayTarget.AddTxn(it) },
                                onEditTransaction = { ledgerId, txnId ->
                                    overlay = OverlayTarget.AddTxn(ledgerId, txnId)
                                },
                                // v10.1.0：概览卡收起状态（记住用户选择）
                                overviewCompact = settings.detailOverviewCompact,
                                onToggleOverviewCompact = {
                                    appVm.setDetailOverviewCompact(!settings.detailOverviewCompact)
                                },
                                modifier = tabPadding
                            )
                        }

                        composable(Routes.SETTINGS) {
                            SettingsScreen(
                                env = env,
                                appVm = appVm,
                                backupVm = backupVm,
                                onOpenSystemAccess = { openPage(OverlayTarget.SystemAccess) },
                                onOpenRecycleBin = { openPage(OverlayTarget.RecycleBin) },
                                onOpenMoreSettings = { openPage(OverlayTarget.MoreSettings) },
                                onOpenSpeedEntry = { lid -> openPage(OverlayTarget.SpeedEntry(lid)) },
                                onOpenAssistant = { openPage(OverlayTarget.Assistant) },
                                onOpenGoal = { lid -> openPage(OverlayTarget.Goal(lid)) },
                                onOpenBillImport = { lid ->
                                    val target = if ((currentLedgerId ?: 0L) > 0L) (currentLedgerId ?: 0L) else lid
                                    openPage(OverlayTarget.BillImport(target))
                                },
                                modifier = tabPadding
                            )
                        }
                    }
                }
            }

            // ================= 二级页：整页覆盖（自带外壳） =================
            AnimatedVisibility(
                visible = pushedVisible,
                enter = slideInHorizontally(
                    animationSpec = tween(Motion.PAGE_ENTER + 20, easing = Motion.flow)
                ) { it / 4 } +
                    fadeIn(tween(Motion.PAGE_ENTER, easing = Motion.standard)) +
                    scaleIn(
                        animationSpec = tween(Motion.PAGE_ENTER, easing = Motion.flow),
                        initialScale = 0.99f
                    ),
                exit = slideOutHorizontally(
                    animationSpec = tween(Motion.PAGE_EXIT + 40, easing = Motion.flow)
                ) { it / 4 } +
                    fadeOut(tween(Motion.PAGE_EXIT, easing = Motion.standard)) +
                    scaleOut(
                        animationSpec = tween(Motion.PAGE_EXIT, easing = Motion.flow),
                        targetScale = 0.99f
                    ),
                label = "pushedPage"
            ) {
                // ⚠️ 返回手势必须注册在**这里**（比 NavHost 深一层）：
                // 注册在外层的话优先级会被 NavHost 抢走 —— 系统返回会直接弹回明细页，
                // 而不是关掉这一层二级页。覆盖层（AppOverlay）同理，靠它自己的 BackHandler。
                BackHandler(enabled = true) { closePage() }
                when (val page = pushedPage) {
                    is OverlayTarget.Stats -> StatsScreen(
                        env = env,
                        ledgerId = page.ledgerId,
                        onBack = { closePage() },
                        onOpenCategories = { openPage(OverlayTarget.Categories(page.ledgerId)) },
                        onOpenInsight = { openPage(OverlayTarget.Insight(page.ledgerId)) }
                    )
                    is OverlayTarget.Categories -> CategoryManageScreen(
                        env = env,
                        ledgerId = page.ledgerId,
                        onBack = { closePage() }
                    )
                    is OverlayTarget.Accounts -> AccountManageScreen(
                        env = env,
                        ledgerId = page.ledgerId,
                        onBack = { closePage() }
                    )
                    is OverlayTarget.SpendCalendar -> SpendCalendarScreen(
                        env = env,
                        ledgerId = page.ledgerId,
                        onBack = { closePage() },
                        onEditTransaction = { tid ->
                            closePage()
                            overlay = OverlayTarget.AddTxn(page.ledgerId, tid)
                        }
                    )
                    is OverlayTarget.Insight -> InsightScreen(
                        env = env,
                        ledgerId = page.ledgerId,
                        onBack = { closePage() }
                    )
                    OverlayTarget.SystemAccess -> SystemAccessScreen(
                        env = env,
                        onBack = { closePage() }
                    )
                    is OverlayTarget.SpeedEntry -> SpeedEntryScreen(
                        env = env,
                        ledgerId = page.ledgerId,
                        onBack = { closePage() }
                    )
                    OverlayTarget.MoreSettings -> MoreSettingsScreen(
                        env = env,
                        appVm = appVm,
                        onBack = { closePage() }
                    )
                    OverlayTarget.RecycleBin -> RecycleBinScreen(
                        env = env,
                        onBack = { closePage() }
                    )
                    OverlayTarget.GlobalSearch -> GlobalSearchScreen(
                        env = env,
                        onBack = { closePage() },
                        onPickResult = { /* TODO: 点搜索结果可跳转到该笔 */ }
                    )
                    is OverlayTarget.Goal -> GoalScreen(
                        env = env,
                        defaultLedgerId = page.ledgerId,
                        onBack = { closePage() }
                    )
                    is OverlayTarget.BillImport -> BillImportScreen(
                        env = env,
                        ledgerId = page.ledgerId,
                        onBack = { closePage() }
                    )
                    OverlayTarget.Assistant -> AssistantScreen(
                        env = env,
                        onBack = { closePage() },
                        onOpenSpeedEntry = { lid ->
                            closePage()
                            openPage(OverlayTarget.SpeedEntry(lid))
                        },
                        onOpenStats = { lid ->
                            closePage()
                            openPage(OverlayTarget.Stats(lid))
                        },
                        onSwitchToLedger = { id ->
                            closePage()
                            selectLedger(id)
                        },
                        onAddTransaction = { lid ->
                            closePage()
                            overlay = OverlayTarget.AddTxn(lid)
                        }
                    )
                    else -> Unit
                }
            }

            // ================= 全局弹层 =================
            when (val target = overlay) {
                is OverlayTarget.AddTxn -> AddTxnOverlay(
                    env = env,
                    ledgerId = target.ledgerId,
                    editTxnId = target.txnId,
                    onDismiss = { overlay = null },
                    // v9.8.0：保存成功要有回执 —— 以前 onSaved 是空的，弹层一关，
                    // 用户滚在看老账时根本不知道新的一笔进没进
                    onSaved = {
                        com.foldledger.ui.components.AppToastBus.show(
                            if (target.txnId != null) "已保存修改" else "已记下一笔"
                        )
                    }
                )

                is OverlayTarget.EditLedger -> CreateLedgerOverlay(
                    env = env,
                    initialSceneKey = target.ledger.sceneKey,
                    editing = target.ledger,
                    onDismiss = { overlay = null },
                    onQuickCreate = { },
                    onConfirm = { name, sceneKey, emoji, colorHex, budget, note ->
                        appVm.updateLedger(
                            target.ledger.copy(
                                name = name,
                                sceneKey = sceneKey,
                                emoji = emoji,
                                colorHex = colorHex,
                                budgetCents = budget,
                                note = note
                            )
                        )
                    }
                )

                OverlayTarget.AggregatePicker -> AggregatePickerOverlay(
                    env = env,
                    cards = cards,
                    aggregateAll = settings.aggregateAll,
                    selectedIds = settings.aggregateLedgerIds,
                    onDismiss = { overlay = null },
                    onConfirm = { all, ids ->
                        appVm.setAggregate(all, ids)
                        overlay = null
                    }
                )

                // 二级页由上面的整页覆盖层负责
                is OverlayTarget.Stats, is OverlayTarget.Categories,
                is OverlayTarget.Accounts, is OverlayTarget.SpendCalendar,
                is OverlayTarget.Insight, OverlayTarget.SystemAccess,
                is OverlayTarget.SpeedEntry, OverlayTarget.Assistant,
                is OverlayTarget.BillImport, is OverlayTarget.Goal,
                OverlayTarget.RecycleBin, OverlayTarget.MoreSettings,
                OverlayTarget.GlobalSearch -> Unit

                null -> Unit
            }

            // v9.8.0：全局轻提示（保存回执 / 条件不满足的说明），底部居中、在弹层之上
            AppToastHost(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 96.dp)
            )

            // 「选哪一份备份来恢复」是全屏弹层，必须盖住顶栏与导航栏 → 放在外壳之外
            if (backupState.pickingRestore) {
                BackupPickerOverlay(
                    env = env,
                    backups = backupState.backups,
                    onPick = { backupVm.requestRestoreFrom(it) },
                    onPickLatest = { backupVm.pickLatest() },
                    onDismiss = { backupVm.cancelPick() }
                )
            }
        }
        } // ← CompositionLocalProvider(LocalTitleCollapse)：只包内容层，弹层在外不受影响
        } // ← LocalGlassGlow provider（Glass 2.0 滚动光相）
    }
}


/**
 * 顶栏的单色图标动作。
 *
 * 液态玻璃的顶栏上只该有**线性的单色字形** —— 彩色 emoji 会把这层玻璃变成"贴纸板"，
 * 而且五个 emoji 并排时没有统一的视觉重量，读起来像一排小广告。
 */
@Composable
private fun TopIconAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(44.dp).semantics { contentDescription = label }
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f),
            modifier = Modifier.size(21.dp)
        )
    }
}

/**
 * 「更多」工具菜单（v12）。
 *
 * 统计 / 消费日历 / 分类 / 资产账户这四个入口原本平铺在顶栏，占了 240dp。
 * 它们都是**低频但重要**的工具（一天最多点一次），所以收进一个玻璃菜单：
 * 每一项都有图标 + 名称 + 一句说明，比四个裸 emoji 更好认，也更容易点。
 */
@Composable
private fun DetailToolsMenu(
    ledgerId: Long,
    openPage: (OverlayTarget) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    Box {
        TopIconAction(
            icon = Icons.Filled.MoreVert,
            label = "更多工具"
        ) { open = true }
        GlassMenu(
            expanded = open,
            onDismissRequest = { open = false }
            // v13.9：形状 / 底色 / 投影全部交给 GlassMenu ——
            // 这里再传一套等于让"材质"有两个出处，改一处忘一处就会出现
            // "这个菜单是玻璃、那个菜单是实色"。
        ) {
            ToolMenuItem("📊", "统计", "收支结构 · 趋势 · 智能洞察") {
                open = false
                openPage(OverlayTarget.Stats(ledgerId))
            }
            ToolMenuItem("📅", "消费日历", "哪天花得多，一眼看出来") {
                open = false
                openPage(OverlayTarget.SpendCalendar(ledgerId))
            }
            ToolMenuItem("🏷️", "分类管理", "增删分类、设置预算") {
                open = false
                openPage(OverlayTarget.Categories(ledgerId))
            }
            ToolMenuItem("💳", "资产账户", "银行卡 / 钱包 / 余额") {
                open = false
                openPage(OverlayTarget.Accounts(ledgerId))
            }
        }
    }
}

@Composable
private fun ToolMenuItem(
    emoji: String,
    title: String,
    desc: String,
    onClick: () -> Unit
) {
    DropdownMenuItem(
        onClick = onClick,
        leadingIcon = {
            Text(emoji, fontSize = 17.sp)
        },
        text = {
            Column {
                Text(
                    title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    desc,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    )
}
