package com.foldledger.ui.adaptive

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foldledger.ui.components.GlassMenu
import com.foldledger.ui.anim.Motion
import com.foldledger.ui.anim.clickableScale
import kotlin.math.roundToInt
import com.foldledger.ui.theme.AppBackground
import com.foldledger.ui.theme.Radius
import com.foldledger.ui.theme.Space
import com.foldledger.ui.theme.LocalDarkTheme
import com.foldledger.ui.theme.glassBorder
import com.foldledger.ui.theme.glassSurface
import com.foldledger.ui.theme.GlassDepth
import com.foldledger.ui.theme.GlassKey
import com.foldledger.ui.theme.glassPanelColor
import com.foldledger.ui.theme.glassPanelBrush
import com.foldledger.ui.theme.Hairline
import com.foldledger.ui.theme.SurfaceCard
import com.foldledger.ui.theme.dividerBrush
import com.foldledger.ui.theme.panelSurface
import com.foldledger.ui.theme.primaryGradientBrush

/**
 * 底部导航 / 侧边导航的三个分区。
 *
 * **声明顺序即展示顺序**：明细在最左（第一），账本居中，我的在最右。
 * 明细排第一是因为它是每天看得最多的页面；账本页负责「选哪个账本看」。
 */
enum class AppTab(val label: String, val icon: ImageVector) {
    /** 明细页：显示「账本页」当前选中的那个分账本的流水 */
    DETAIL("明细", Icons.Filled.DateRange),

    /** 账本页：所有已创建的分账本、合计范围设置、以及从场景模板新建 */
    LEDGERS("账本", Icons.AutoMirrored.Filled.List),

    /** 我的：备份、主题、折叠屏适配状态 */
    MINE("我的", Icons.Filled.Settings)
}

/** 导航胶囊的高度（系统手势条 inset 另补在下方 padding 里） */
// v12.1.4：胶囊整体收小一圈（64 → 52dp），从"贴底整条"彻底变成"浮在内容上的一小片玻璃"
private val NAV_BAR_CONTENT_H = 52.dp

/** 单个 Tab 的宽度：胶囊不再撑满屏宽，而是「图标格宽 × 3」后居中浮起 */
private val NAV_ITEM_W = 76.dp

/**
 * 自适应外壳：顶栏 + 导航栏（≥800dp 换侧边栏）+ 内容区。
 *
 * ### v3.2.0 外壳常驻 —— 根治「切 Tab 闪一下」
 * 以前**每个页面各自带一套外壳**：点 Tab 时两套顶栏 + 两套底部导航栏一起交叉淡入淡出，
 * 两个选中指示器在半透明状态下互相重叠，页面背景也被画了两遍 —— 用户看到的「闪一下」
 * 就是这么来的。现在外壳由 `AppRoot` 统一渲染一次并**常驻**：
 *  - 切 Tab 时导航栏原地不动，只有指示器滑动过去；
 *  - 页面背景只画一次，不再两份背景交叉淡化（也就不会有亮度塌陷）；
 *  - 转场只作用在内容区，位移缩放都是微量的。
 *
 * 其他要点：
 *  - edge-to-edge 安全区交给 [contentWindowInsets] 分发；
 *  - [chromeVisible] = false 时顶栏与导航栏带动画退场、内容区扩成整窗，供二级页
 *    （统计 / 分类，自带一套外壳）复用同一个 Scaffold，避免"双层顶栏"；
 *  - **形态切换（单栏 ↔ 双栏）绝不做整屏透明度 / 缩放** —— 那是「闪」不是「过渡」，
 *    双栏的内容直接以终态呈现，栏宽由各页 `rememberAnimatedPaneWidth` 平滑过渡，
 *    姿态判定见 `FoldEnv.everFoldable`。
 */
@Composable
fun AdaptiveScaffold(
    env: FoldEnv,
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    selectedTab: AppTab? = null,
    onTabSelected: (AppTab) -> Unit = {},
    showNavigation: Boolean = true,
    actions: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    /**
     * v13.5：**「记一笔」进底栏**（胶囊最右格）。
     *
     * 原来它是一枚悬浮圆钮，压在列表右下角：首屏第二行流水的金额末位正好被它盖住
     * （`-¥216` 盖成 `-¥21`）。悬浮钮的问题不在"钮"，在**它悬在内容之上** ——
     * 只要它浮着，就一定盖住某一行。
     *
     * 进胶囊之后，它只占胶囊自己那条已经不可读的带（内容在胶囊下方 16dp 才开始被遮挡），
     * 而且仍然落在右下角 —— 和原来 FAB 的位置、拇指习惯完全一致，只是"沉进了玻璃里"。
     * 为 null 时（二级页 / 无账本）该格不出现，胶囊自动回到三格。
     */
    onAdd: (() -> Unit)? = null,
    /**
     * v13.7：**标题下拉**。
     *
     * 明细页原来有「恒定 23sp 标题『明细』+ 副标题写账本名 + 下面一整行账本 chips」，
     * 于是同一个账本名在 300dp 内出现两遍，而那颗 23sp 的大字永远写着同一句话 ——
     * 纯装饰。现在反过来：**大标题 = 当前账本名**（变量、有信息量），
     * 副标题 = 场景名（栏目），切换动作收进标题右侧的小箭头。
     *
     * 传 null = 标题不可点（账本页 / 我的页）。
     */
    titleMenu: (@Composable (dismiss: () -> Unit) -> Unit)? = null,
    /** v3.2.0：外壳是否在场。false = 顶栏/导航栏退场，内容区占满整窗 */
    chromeVisible: Boolean = true,
    contentWindowInsets: WindowInsets = WindowInsets.safeDrawing,
    /**
     * v12.1.2：三 Tab 横滑手势**挂在底栏胶囊上**（在分栏图标上左右滑切换），
     * 由 AppRoot 用 `rememberTabSwipe` 造好传进来；`navSwipeDragPx` 是当前跟手位移，
     * 用来让白色滑块跟着手指挪。内容区不再整体位移 —— 页面不动，只有滑块动。
     */
    navSwipeModifier: Modifier = Modifier,
    navSwipeDragPx: Float = 0f,
    content: @Composable (PaddingValues) -> Unit
) {
    // 侧边导航栏只在 800dp 以上出现（见 FoldEnv 里那份搜索结论）：
    // 800dp 以下那 80dp 太贵，改用底部导航，把宽度让给内容
    // v12.1.4：**内外屏统一**为底部悬浮胶囊 —— 内屏 860dp 横屏也不再切侧栏。
    // 侧栏那套（80dp 竖排）在横屏里既占宽度、横向可抹的距离又只有几十 dp，
    // 手势天然别扭；统一成同一枚胶囊后，"按住来回抹"内外屏是同一种手感。
    val showBottomBar = chromeVisible && showNavigation && selectedTab != null

    // ⚠️ v10.2.0：底栏内容必须整体抬到系统手势条以上。
    // 原来把 `windowInsetsPadding(navigationBars)` 写在底栏内部，但它已经被
    // Scaffold 消费掉了（拿到的是 0），于是「图标 + 文字」最下面那 20dp 正好压在
    // 系统手势区里 —— 图标能点中、文字点不中，用户体感就是"这个 Tab 很难点"。
    // 在外壳这一层读 inset（此时还没被消费），显式传给底栏。
    val density = androidx.compose.ui.platform.LocalDensity.current
    val navBottomInset = with(density) {
        WindowInsets.navigationBars.getBottom(density).toDp()
    }

    AppBackground {
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = Color.Transparent,
                    contentWindowInsets = contentWindowInsets,
                    topBar = {
                        AppTopBar(
                            title = title,
                            subtitle = subtitle,
                            postureLabel = env.postureLabel,
                            onBack = onBack,
                            visible = chromeVisible,
                            actions = actions,
                            titleMenu = titleMenu
                        )
                    },
                    bottomBar = {
                        // 抽成独立函数：AdaptiveScaffold 本体在 Row 作用域里，行内直接写
                        // AnimatedVisibility 会误匹配 RowScope 重载并报隐式接收器错误。
                        AnimatedBottomBar(
                            visible = showBottomBar,
                            selected = selectedTab ?: AppTab.DETAIL,
                            onSelected = onTabSelected,
                            onAdd = onAdd,
                            // v13.6：双栏时整组停到右栏右下角 —— 见 AppBottomBar 的说明
                            twoPane = env.twoPane,
                            bottomInset = navBottomInset,
                            swipeModifier = navSwipeModifier,
                            swipeDragPx = navSwipeDragPx
                        )
                    },
                    floatingActionButton = floatingActionButton,
                    content = { paddingValues ->
                        Box(Modifier.fillMaxSize()) {
                            content(paddingValues)
                        }
                    }
                )
            }
        }
    }
}

/**
 * 顶栏（抽成独立函数：AdaptiveScaffold 本体在 Row 作用域里，行内直接写
 * AnimatedVisibility 会误匹配 RowScope 重载并报隐式接收器错误）。
 *
 * [visible] = false 时整条顶栏带动画退场，返回时滑回来 —— 于是是「整个应用界面」
 * 在移动，而不是凭空消失。
 *
 * ### v13.5：滚动时收成紧凑栏
 * 大标题（副标题 11sp + 标题 23sp，64dp）在明细页往下滚时**连续**压成
 * 一行 16sp 的 46dp 紧凑栏。三个值同时跟手：栏高 64→46、标题 23→16sp、
 * 副标题高度 14→0dp（并同步淡出）。
 *
 * 为什么不"收起后换一个标题"：那读起来是**换了张皮**。iOS 大标题的手感是
 * 「标题被推上去、缩成小号留在栏里」—— 只有连续插值能给这个。
 * 折叠进度来自 [LocalTitleCollapse]（内容层写入），本栏只是消费方。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppTopBar(
    title: String,
    subtitle: String?,
    postureLabel: String,
    onBack: (() -> Unit)?,
    visible: Boolean,
    actions: @Composable () -> Unit,
    titleMenu: (@Composable (dismiss: () -> Unit) -> Unit)? = null
) {
    val collapse = LocalTitleCollapse.current
    val f = collapse?.fraction ?: 0f
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(Motion.MEDIUM, easing = Motion.flow)) +
            slideInHorizontally(animationSpec = tween(Motion.MEDIUM, easing = Motion.flow)) { -it / 8 },
        exit = fadeOut(tween(Motion.FAST, easing = Motion.flow)) +
            slideOutHorizontally(animationSpec = tween(Motion.MEDIUM, easing = Motion.flow)) { -it / 8 },
        label = "topBar"
    ) {
        Column(Modifier.background(glassPanelBrush())) {
            TopAppBar(
                // v5.4：容器透明，由外层画玻璃渐变（顶实底透，内容从下沿透出来）
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                expandedHeight = collapse?.between(64.dp, 46.dp) ?: 64.dp,
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    }
                },
                title = {
                    Column {
                        // v11 编辑排版：小字在上（栏目标签），大字在下（页面名）。
                        // 这是刊物目录的写法 —— 先告诉你"这是什么栏目"，再给你"这一页叫什么"。
                        // 只做纵向淡入淡出，**绝不横向位移**：横向会变成"两个标题擦身而过"。
                        val sub = subtitle ?: postureLabel
                        // 折叠时副标题整块高度收到 0（不再只是变透明 —— 透明了仍占位，
                        // 栏高就压不下去，那等于没折叠）
                        // ⚠️ 展开态留 18dp：11sp 的字本身只要 ~15dp，但副标题里带 emoji，
                        //    彩色字形的行高比正文高一截；给 14dp 会**从上面**把字削掉一半
                        //    （真机上「📒 报销账本 · 报销」只剩下半截）。
                        //    盒高之外还有一层保险：下面 Text 显式给了 lineHeight，
                        //    不再让 emoji 的字体度量决定行盒。
                        Box(
                            Modifier
                                .height(collapse?.between(18.dp, 0.dp) ?: 18.dp)
                                .clipToBounds()
                        ) {
                            AnimatedContent(
                                targetState = sub,
                                transitionSpec = {
                                    (fadeIn(tween(180, easing = Motion.standard)) +
                                        slideInVertically(
                                            animationSpec = tween(180, easing = Motion.standard)
                                        ) { it / 3 }) togetherWith
                                        (fadeOut(tween(120, easing = Motion.standard)) +
                                            slideOutVertically(
                                                animationSpec = tween(120, easing = Motion.standard)
                                            ) { -it / 3 })
                                },
                                label = "postureSubtitle"
                            ) { text ->
                                Text(
                                    text,
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp,
                                    fontWeight = FontWeight.Medium,
                                    letterSpacing = 0.3.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        Spacer(Modifier.height(collapse?.between(1.dp, 0.dp) ?: 1.dp))
                        // v13.7：标题右侧的小箭头 = "这里能换东西"。
                        //
                        // 它是整条顶栏**唯一**一次替换：原来这里写着恒定的「明细」，
                        // 账本名却排在下面一整行 chips 里（48dp），于是同一份信息出现两遍。
                        // 现在大标题就是账本名 —— 大字说的终于是当前这一页到底是什么。
                        var menuOpen by remember { mutableStateOf(false) }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(Radius.sm))
                                .then(
                                    if (titleMenu != null) {
                                        Modifier.clickableScale(
                                            pressedScale = 0.985f,
                                            clipShape = RoundedCornerShape(Radius.sm)
                                        ) { menuOpen = true }
                                    } else Modifier
                                )
                        ) {
                            Text(
                                title,
                                // 23sp → 16sp：区间收得比栏高更窄（23/16 ≈ 1.44，栏高 64/46 ≈ 1.39），
                                // 这样收起后标题看起来是"同一颗字长大了"，不是"换了个小标题"
                                fontSize = if (collapse != null) {
                                    androidx.compose.ui.unit.lerp(23.sp, 16.sp, collapse.fraction)
                                } else 23.sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = if (collapse != null) {
                                    androidx.compose.ui.unit.lerp((-0.5).sp, (-0.2).sp, collapse.fraction)
                                } else (-0.5).sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                // fill = false：短名字不去撑满，箭头紧跟在字后面；
                                // 长名字才吃掉剩余宽度并省略。
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            if (titleMenu != null) {
                                // ⚠️ 这里手绘而不是用 `Icons.Filled.ExpandMore`：
                                //    项目只引了 material-icons-core，展开箭头在 extended 包里，
                                //    加上去等于为一个小箭头多引一整个图标库（离线构建也要多一份依赖）。
                                //    另外 11sp 的文字三角在真机上只有 4px 高（v13.6 踩过），
                                //    所以直接画线：两笔，宽 9dp、斜高 2.6dp、笔宽 1.6dp。
                                //    颜色必须在 Canvas 外取：DrawScope 里不是 @Composable 上下文。
                                val chevronTint =
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.82f)
                                androidx.compose.foundation.Canvas(
                                    Modifier
                                        .padding(start = 6.dp)
                                        .size(10.dp, 8.dp)
                                ) {
                                    val w = size.width
                                    val h = size.height
                                    val sw = 1.8.dp.toPx()
                                    val path = androidx.compose.ui.graphics.Path().apply {
                                        moveTo(sw / 2f, h * 0.34f)
                                        lineTo(w / 2f, h * 0.34f + h * 0.44f)
                                        lineTo(w - sw / 2f, h * 0.34f)
                                    }
                                    drawPath(
                                        path = path,
                                        color = chevronTint,
                                        style = Stroke(width = sw, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                                    )
                                }
                                GlassMenu(
                                    expanded = menuOpen,
                                    onDismissRequest = { menuOpen = false }
                                    // v13.9：形状 / 底色 / 投影统一由 GlassMenu 给（见 GlassOverlays.kt）。
                                    // 原来这里把 popoverColor 直接当 containerColor 传，
                                    // 拿到的是"一块 98.5% 的实色 + 系统投影"—— 白棱压在白面上，
                                    // 边缘那圈折射带等于没画，菜单看起来就是一张圆角贴纸。
                                ) {
                                    titleMenu?.invoke { menuOpen = false }
                                }
                            }
                        }
                    }
                },
                actions = { actions() }
            )
            Hairline()
        }
    }
}

/**
 * 澎湃 OS 4 式底部导航栏。
 *
 * ### 为什么不用 M3 的 `NavigationBar`
 * M3 每个 `NavigationBarItem` 自带一个指示器，切换时是**两个指示器各自淡入淡出**，
 * 在常驻导航栏里看起来就是"闪一下"。这里改成**一个药丸指示器在三个 Tab 之间滑动**
 * （`animateDpAsState` + 收过的弹簧，阻尼 0.82），这正是澎湃 / MIUI 的标志性动作：
 * 选中态永远是"移动过去"，不是"淡进来"。
 *
 * 其余层次照搬 HyperOS：药丸高 40dp、左右各留 4dp、图标 24dp 居中、标签 11sp 在下，
 * 选中 `onSurface` / 未选中 `onSurfaceVariant`，顶部一条 Hairline，系统手势条由
 * `WindowInsets.navigationBars` 补。
 */
@Composable
fun AppBottomBar(
    selected: AppTab,
    onSelected: (AppTab) -> Unit,
    /** 系统手势条 / 三键导航条的高度；内容整体抬到它上面，避免文字落在死区里 */
    bottomInset: Dp = 0.dp,
    /** v12.1.2：横滑手势挂在胶囊本体上 —— 在分栏图标上左右滑即可切 Tab */
    swipeModifier: Modifier = Modifier,
    /** 当前跟手位移（px）：让白色滑块跟着手指挪，页面内容不动 */
    swipeDragPx: Float = 0f,
    /** 「记一笔」圆钮。null 时整颗不出现（二级页 / 无账本） */
    onAdd: (() -> Unit)? = null,
    /**
     * v13.6：双栏形态下把「胶囊 + 记一笔」这一整组停到**右栏右下角**。
     *
     * 原来全窗居中：860dp 横屏里胶囊落在 316~544dp，而分栏缝在 368dp ——
     * 胶囊正好骑着缝，还压掉左栏月份卡的末行。右栏是「概览 / 详情」，底部
     * 本来就读不到东西；停过去之后左栏（列表）底部彻底干净，导航也落到横握时
     * 右手拇指够得到的那一侧。单栏不受影响，仍然居中。
     */
    twoPane: Boolean = false
) {
    val tabs = AppTab.entries
    val dark = LocalDarkTheme.current
    // v13.6：胶囊回到**纯三格导航** —— 「记一笔」切出去之后，再没有"多出来的第四格"，
    // 横滑映射也就不必再靠 `cellCount = tabCount + 1` 去补那半格。
    val cellCount = tabs.size

    // v12：底栏从"贴底的整条色带"改成**悬空的玻璃胶囊** —— 一片浮在内容上方的玻璃，
    // 左右各留 20dp，能看见它背后的环境光从边缘透过去。
    // v13.6：右侧多出一颗**独立的**「记一笔」圆钮，与胶囊之间隔 10dp 空气。
    // 两颗各自完整，而不是"一个胶囊被切了一半"。
    Box(
        Modifier
            .fillMaxWidth()
            .padding(
                start = Space.lg,
                end = Space.lg,
                top = Space.xs,
                bottom = 12.dp + bottomInset
            ),
        contentAlignment = if (twoPane) Alignment.BottomEnd else Alignment.BottomCenter
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BoxWithConstraints(
                Modifier
                    .width(NAV_ITEM_W * cellCount)
                    .height(NAV_BAR_CONTENT_H)
                    .glassSurface(
                        shape = RoundedCornerShape(NAV_BAR_CONTENT_H / 2),
                        // Glass 2.0：底栏胶囊改用景深令牌 Panel（精确复刻原 bottomBarGlassColor
                        // 10%/22% + edgeInk 2.0 + elevation 16 + filmDepth 1，零视觉回归），
                        // 并随主列表滚动让高光流动（液态感）。
                        depth = GlassDepth.Panel,
                        scrollAware = true
                    )
                    // v12.1.2：横滑手势。挂在胶囊上，clip 之后的 gesture 仍能收到
                    // （clip 只影响绘制）。点按仍归 Tab 图标的 clickableScale ——
                    // detectHorizontalDragGestures 只在横向拖动时消费，tap 不受影响。
                    .then(swipeModifier)
            ) {
                val itemWidth = maxWidth / cellCount
                val index = tabs.indexOf(selected).coerceAtLeast(0)
                val animatedPillX by animateDpAsState(
                    targetValue = itemWidth * index,
                    // 阻尼 0.92：滑过去时有一点点"黏"，但不回弹 —— 苹果的 Tab 切换不弹
                    animationSpec = spring(dampingRatio = 0.92f, stiffness = Spring.StiffnessMediumLow),
                    label = "tabPill"
                )
                // ⚠️ v12.1.4：拖动中**不要**让弹簧参与 —— 实时切换时 index 一直在变，
                // 弹簧会把滑块从旧格慢慢带向新格，而拖动偏移又已经按新格算好，两者叠加
                // 就是"滑块来回抽搐"。所以拖动中直接取目标位（瞬时），偏移量负责跟手；
                // 松手后偏移归零，再交回弹簧（此时它早已停在新格上，视觉无缝）。
                val pillX = if (swipeDragPx != 0f) itemWidth * index else animatedPillX
                // 选中态：一枚**玻璃滑块**（白膜 + 棱边），在胶囊内部滑动。
                // 它比"变色 + 下划线"更接近原生：选中是"这块玻璃被托起来"，不是"字变粗"。
                // ⚠️ v12.0.1：内容现在会从胶囊底下滑过去，白卡经过时整条胶囊都是 252 白 ——
                // 纯白的滑块在白底上就"消失"了。所以滑块必须自带**投影**（托起来的那条暗边）
                // 和**上下异色的棱边**（上沿迎光、下沿折暗），白底上才立得住。
                Box(
                    Modifier
                        .offset {
                            // v12.1.2：滑块跟手 —— 横滑时白色滑块随手指在胶囊内挪动，
                            // 切换瞬间 drag 快速归零、pillX 弹簧接管，两者衔接是平滑的
                            IntOffset(pillX.roundToPx() + swipeDragPx.roundToInt(), 0)
                        }
                        .width(itemWidth)
                        .fillMaxHeight()
                        .padding(4.dp)
                        .shadow(
                            elevation = if (dark) 7.dp else 5.dp,
                            shape = RoundedCornerShape(50),
                            clip = false,
                            ambientColor = Color.Black.copy(alpha = 0.10f),
                            spotColor = Color.Black.copy(alpha = 0.12f)
                        )
                        .clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = if (dark) 0.12f else 0.72f))
                        .border(
                            width = 1.dp,
                            brush = Brush.verticalGradient(
                                listOf(
                                    Color.White.copy(alpha = if (dark) 0.12f else 0.95f),
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = if (dark) 0.06f else 0.09f)
                                )
                            ),
                            shape = RoundedCornerShape(50)
                        )
                )
                Row(Modifier.fillMaxSize()) {
                    tabs.forEach { tab ->
                        val active = tab == selected
                        val activeColor = MaterialTheme.colorScheme.primary
                        val idleColor = MaterialTheme.colorScheme.onSurfaceVariant
                        Column(
                            modifier = Modifier
                                .width(itemWidth)
                                .fillMaxHeight()
                                .clickableScale(
                                    pressedScale = 0.95f,
                                    clipShape = RoundedCornerShape(50)
                                ) {
                                    // 切 Tab 给一次轻触感（不同于键盘的更短促的一下）
                                    com.foldledger.ui.haptic.AppHaptics.tap()
                                    onSelected(tab)
                                }
                                .semantics {
                                    this.selected = active
                                    this.role = Role.Tab
                                },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            AnimatedTabIcon(active, tab.icon, tab.label, if (active) activeColor else idleColor)
                            Spacer(Modifier.height(3.dp))
                            Text(
                                tab.label,
                                fontSize = 11.sp,
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                                letterSpacing = 0.2.sp,
                                color = if (active) activeColor else idleColor,
                                maxLines = 1
                            )
                        }
                    }
                }
            }

            // ---- v13.6：「记一笔」独立圆钮 ----
            // v13.5 把它塞进胶囊最右格：三格线条图标旁边忽然多出一颗实心圆 + 一行字，
            // 格宽一样、重心不一样，读起来就是"胶囊里挤了个异物"。切出去之后
            // 胶囊是**纯导航**、圆钮是**纯动作**，两件事各自只有一种读法。
            if (onAdd != null) {
                Spacer(Modifier.width(ADD_KEY_GAP))
                AddKey(size = NAV_BAR_CONTENT_H, onClick = onAdd)
            }
        }
    }
}

/** 胶囊与「记一笔」圆钮之间的空气层。太窄两者会"粘"成一条，太宽就散开了。 */
private val ADD_KEY_GAP = 10.dp

/**
 * 「记一笔」圆钮（v13.8：改回**液态玻璃**）。
 *
 * ### 为什么 v13.6 的实心球是错的
 * 那一版把「主行动必须最沉」当成了"必须最实"，于是做了一颗实心主色球。
 * 但整站唯一的材质语言是玻璃 —— 底栏、行、卡、键帽全是同一套膜 + 折射带。
 * 在一屏玻璃里放一块**不透光**的色块，它读起来不是"更重要"，而是"这不是这套 UI
 * 里的东西"：既没有边缘的弯折，也不随背后的色光变，像贴上去的。
 *
 * ### 玻璃键怎么表达"这是主行动"
 * 不是靠不透明度，靠三件事：
 *   1. **染色**（`tintBoost = 1.3`）：膜上加一层 22% 的主色，仍是透明的，
 *      但它是这一片里**唯一有色温的玻璃**；
 *   2. **体积**：球面径向亮心 + 1dp 白棱 + 左上 132° 虹彩，比旁边的胶囊更"厚"；
 *   3. **影**：投影带主色（`glow`），落在暖底上是一圈极淡的蓝影 —— 玻璃浮起来才有影。
 *
 * ### 加号为什么是这个大小
 * 20dp 字形在 52dp 圆里，十字实际只占直径的 22%。v13.5 是 16dp 塞进 26dp（61%），
 * 圆被撑满才显突兀；留白才是「加」这个动作的呼吸。
 *
 * ### 加号的颜色（算出来的，不是调出来的）
 * 底栏那条带的底色是暖砂（约 `#DED5C6`）。`primary` #4A6B87 直接压上去只有 **3.86:1**；
 * 再叠一层 22% 主色染色的玻璃面（合成 `#BFC0BC`），掉到 **3.83:1** —— 加号会发灰。
 * 压深 24%（`#385167`）回到 **4.52:1**。
 * 图标按 3:1 是及格线，但"主行动的那个符号"不该只及格。
 */
@Composable
private fun AddKey(size: Dp, onClick: () -> Unit) {
    val dark = LocalDarkTheme.current
    val accent = MaterialTheme.colorScheme.primary
    // ⚠️ 暗色下 primary 是浅蓝（#A6C2D8），染在深玻璃上刚好是"发光的蓝"，直接用；
    //    亮色下压深 24%（3.83 → 4.52:1），否则加号在浅玻璃上既没重量也不够读。
    val ink = if (dark) accent else lerp(accent, Color.Black, 0.24f)
    GlassKey(
        size = size,
        tint = accent,
        tintBoost = 1.30f,
        glow = accent,
        scrollAware = true,
        onClick = onClick
    ) {
        Icon(
            Icons.Filled.Add,
            contentDescription = "记一笔",
            tint = ink,
            modifier = Modifier.size(20.dp)
        )
    }
}

/**
 * 带高度渐变的底部导航栏。
 *
 * 可见性切换时带着滑入滑出 + 淡入淡出，让 Scaffold 的内容内边距随栏高平滑变化，
 * 跨 800dp 与侧栏互换时不跳变。
 */
@Composable
private fun AnimatedBottomBar(
    visible: Boolean,
    selected: AppTab,
    onSelected: (AppTab) -> Unit,
    bottomInset: Dp = 0.dp,
    swipeModifier: Modifier = Modifier,
    swipeDragPx: Float = 0f,
    onAdd: (() -> Unit)? = null,
    twoPane: Boolean = false
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(Motion.MEDIUM, easing = Motion.flow)) +
            slideInVertically(animationSpec = tween(Motion.MEDIUM, easing = Motion.glide)) { it },
        exit = fadeOut(tween(Motion.MEDIUM, easing = Motion.flow)) +
            slideOutVertically(animationSpec = tween(Motion.MEDIUM, easing = Motion.glide)) { it },
        label = "bottomBar"
    ) {
        AppBottomBar(
            selected = selected,
            onSelected = onSelected,
            bottomInset = bottomInset,
            swipeModifier = swipeModifier,
            swipeDragPx = swipeDragPx,
            onAdd = onAdd,
            twoPane = twoPane
        )
    }
}

/**
 * Tab 图标弹性动效：选中时图标先缩小再回弹到 1，给一个"被按进去又弹回来"的手感。
 * 缩放用 graphicsLayer，只重绘不重组。
 */
@Composable
private fun AnimatedTabIcon(selected: Boolean, icon: ImageVector, label: String, tint: Color) {
    val scale = remember { Animatable(1f) }
    LaunchedEffect(selected) {
        if (selected) {
            // 选中：先轻轻缩一下（0.88，比 0.82 更克制）再回弹到 1，
            // 用收过的 tab() 弹簧，弹一下就稳，绝不抖。
            scale.snapTo(0.88f)
            scale.animateTo(1f, Motion.tab())
        } else {
            scale.animateTo(1f, Motion.snappy())
        }
    }
    Icon(
        icon,
        contentDescription = label,
        tint = tint,
        modifier = Modifier.size(20.dp).graphicsLayer {
            scaleX = scale.value
            scaleY = scale.value
        }
    )
}

/** 顶部信息条：用于展示「已识别到折叠形态」这类实时状态 */
@Composable
fun PostureBanner(env: FoldEnv, extra: String? = null, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("📐", fontSize = 15.sp)
            Text(
                buildString {
                    append(env.widthDp).append(" × ").append(env.heightDp).append(" dp · ")
                    append(env.postureLabel)
                    if (extra != null) append(" · ").append(extra)
                },
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 2
            )
        }
    }
}
