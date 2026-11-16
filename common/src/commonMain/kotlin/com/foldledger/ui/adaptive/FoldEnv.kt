package com.foldledger.ui.adaptive

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.roundToInt

// ============================================================
//  展开态分栏：宽度由离线搜索得出，不交给用户调
// ============================================================
//
// 做法：枚举 3882 种组合 —— 「左栏定值 / 左栏比例 × 上下限」× 「从多宽开始用侧边导航栏」，
// 对 15 档展开宽度（653 / 673 / 720 / 745 / 768 / 800 / 840 … 1366dp）逐机型打分。
// 打分依据是两侧内容**实测**出来的最小可用宽度：
//
//   左栏 · 流水行 TxnRow
//        padding 14×2 + 分类图标 36 + 间隔 10 + 金额列 ~88 + 报销徽标 ~54 + 更多按钮 40
//       ≈ 固定 242，加分类名/备注最少 60 → 硬下限 284，舒适 ≥ 300
//   左栏 · 账本卡片  头像 46 + 间隔 12 + 金额/占比 92 + padding 32 ≈ 182 + 文字 ≥ 110
//   右栏 · 报销看板  三块并排（11sp 标签 + 15sp 金额 ≈ 84），加间隔与卡片 padding ≈ 304
//   右栏 · 概览卡    两列金额各 ~115 + 间隔 20 + padding 32 ≈ 282
//
// 结论（比「侧栏常驻 + 拍脑袋比例」的得分好 3.4 倍）：
//   1. **800dp 以下把导航挪到底部**。侧栏固定吃 80dp，在 673dp 折叠内屏上就是 12% 的宽度，
//      两栏只剩 593dp，左栏最多 280、右栏 313，两边都触下限。
//      换底部导航后（拿高度换宽度）同机型变成 323 / 350，全机型不再触下限。
//   2. 左栏取 `min(368dp, 可分栏宽度 × 48%)`，下限 288dp。
//      368 是上限（再宽列表只是变空），48% 是占比上限（保证窄展开屏上右栏仍有 340dp+）。

/** 左侧 NavigationRail 的固定宽度 */
internal const val NAV_RAIL_DP = 96

/** 达到这个宽度才用侧边导航栏；更窄改用底部导航，把宽度让给内容 */
internal const val NAV_RAIL_FROM_DP = 1024

/** 左栏占可分栏宽度的比例上限 */
internal const val PANE_MASTER_SHARE = 0.48f

internal const val PANE_MASTER_MIN_DP = 288
internal const val PANE_MASTER_MAX_DP = 368

/** 桌面宽屏（可分栏 ≥1400dp）左栏占比与上限（v16.4） */
internal const val PANE_MASTER_SHARE_WIDE = 0.40f
internal const val PANE_MASTER_MAX_WIDE_DP = 720

/**
 * 分栏所需的最小窗口宽度（dp）。
 *
 * 左栏下限 288 + 右栏至少 ~310 才摆得开一张概览卡，所以 600dp 是"能分"的硬底线。
 *
 * ⚠️ v13.3：硬底线还是 600，但**实际起分点抬到 700**。
 * 真机实测（lhasa 内屏竖握 = 608×860dp）：608dp 分栏后左栏只有 288、右栏 320，
 * 右栏的「已回款 ¥6,979.71」直接截成「¥6,97…」，左栏四颗账本 chip 两行折行。
 * 也就是说 600~700 这一段"能分栏，但没有一栏是能读的"。
 * 现在 608dp 走单栏（一屏一行 58dp 更宽，金额完整右对齐），700dp 以上才分。
 *
 * 这同时仍是一层**防抖保护**：切换内外屏的瞬间，部分 OEM 的 WindowLayoutInfo 会比
 * LocalConfiguration 慢一拍 —— 没有兜底会先闪一帧「窄窗口双栏」。
 */
internal const val TWO_PANE_MIN_WIDTH_DP = 700

/**
 * 窗口宽度断点（对齐 Material3 WindowSizeClass：<600 紧凑 / <840 中等 / ≥840 展开）。
 * 双端共用；Android 原值来自 WindowSizeClass，桌面端由窗口宽度直接换算。
 */
enum class WindowWidthClass { Compact, Medium, Expanded }

/** 窗口高度断点（<480 紧凑 / <900 中等 / ≥900 展开） */
enum class WindowHeightClass { Compact, Medium, Expanded }

internal fun widthClassOf(dp: Int): WindowWidthClass = when {
    dp < 600 -> WindowWidthClass.Compact
    dp < 840 -> WindowWidthClass.Medium
    else -> WindowWidthClass.Expanded
}

internal fun heightClassOf(dp: Int): WindowHeightClass = when {
    dp < 480 -> WindowHeightClass.Compact
    dp < 900 -> WindowHeightClass.Medium
    else -> WindowHeightClass.Expanded
}

/**
 * 设备姿态。折叠屏适配的第一性问题：**同一个 Activity 会经历多种物理形态**。
 * 桌面窗口恒为 COVER/UNFOLDED（按宽度），无铰链姿态。
 */
enum class FoldPosture {
    /** 外屏 / 折叠合起 / 普通直板手机 / 窄窗口 —— 窄屏单栏 */
    COVER,

    /** 内屏全展开（180°）/ 宽窗口 —— 平板式双栏 */
    UNFOLDED,

    /** 半折叠 + 水平铰链（笔记本姿态）—— 上半屏看内容，下半屏放键盘 */
    TABLETOP,

    /** 半折叠 + 垂直铰链（书本姿态）—— 左右两栏，避开铰链 */
    BOOK
}

/**
 * 铰链信息（纯数据）。来源在平台 actual：
 * Android = WindowInfoTracker 的 FoldingFeature（含各区比例换算）；
 * 桌面 = 无铰链，恒 null。
 */
data class HingeInfo(
    val isSeparating: Boolean,
    val isHorizontal: Boolean,
    val halfOpened: Boolean,
    val hingeFraction: Float,
    val leadingFraction: Float,
    val trailingFraction: Float
)

/**
 * 当前窗口的自适应环境快照。UI 只依赖这一个对象做决策。
 */
data class FoldEnv(
    val widthDp: Int,
    val heightDp: Int,
    val widthSizeClass: WindowWidthClass,
    val heightSizeClass: WindowHeightClass,
    val posture: FoldPosture,
    /** 是否存在物理铰链（可折叠设备） */
    val isFoldable: Boolean,
    /** 铰链是否把窗口分割成两个独立可用区域 */
    val isSeparating: Boolean,
    /** 铰链占宽/高比例 */
    val hingeFraction: Float,
    /** 铰链前区域占比（tabletop=上区 / book=左区） */
    val leadingFraction: Float,
    /** 铰链后区域占比 */
    val trailingFraction: Float
) {
    // ---------- 布局决策 ----------

    /** 是否使用「列表 + 详情」双栏 */
    val twoPane: Boolean
        get() = posture == FoldPosture.UNFOLDED || posture == FoldPosture.BOOK

    /** 是否是笔记本姿态（内容/操作分离） */
    val isTabletop: Boolean get() = posture == FoldPosture.TABLETOP

    /** 是否是书本姿态 */
    val isBook: Boolean get() = posture == FoldPosture.BOOK

    val isCompactWidth: Boolean get() = widthSizeClass == WindowWidthClass.Compact
    val isExpandedWidth: Boolean get() = widthSizeClass == WindowWidthClass.Expanded

    /** 账本卡片网格列数 */
    val gridColumns: Int
        get() = when {
            widthDp >= 1200 -> 4
            widthDp >= 900 -> 3
            widthDp >= 600 -> 2
            else -> 1
        }

    /** 记账页分类九宫格列数（外屏 4 列、展开 5~6 列） */
    val categoryColumns: Int
        get() = when {
            widthDp >= 1000 -> 6
            widthDp >= 820 -> 5
            else -> 4
        }

    /**
     * 弹层是否用「居中大卡片」而不是底部抽屉。
     *
     * ⚠️ v16.5：判据只认**宽度**，不再跟着 [twoPane] 走。
     *
     * 记一笔的大卡片内部是左右两栏（选分类 + 键盘），键盘栏固定 340~380dp，
     * 卡片到 620dp 以下时分类区只剩 ~200dp —— 一行只放得下 2 个分类，
     * 屏幕上看着就是"分类竖着排"，用户报的正是这个。
     * 折叠屏展开竖屏只有 608dp 宽，但 `twoPane` 是 true，于是它也走了大卡片分支。
     * 卡片要放得下 4 列 84dp 的分类，卡片本身得 ≥ 790dp，对应窗口 ≥ 900dp。
     */
    val useDialogOverlay: Boolean get() = widthDp >= 900

    // ---------- 展开态分栏 ----------

    /**
     * 是否用左侧 NavigationRail。
     *
     * v12.1.4 曾**内外屏统一**为底部悬浮胶囊（侧栏退役），理由是 860dp 横屏里
     * 80dp 竖排既要宽度、可抹距离又短。v16.4 把门槛从 800 抬到 **1024** 并复活它：
     * 到 1024dp 以上（实际只有 Windows 窗口和横放平板）底部胶囊就变成了
     * 「一颗手机底栏浮在屏幕下沿」—— 它占掉一条可视高度，却只用到屏幕 30% 的宽度。
     * 这个宽度下换竖排导轨才是对的：导航贴左边，内容拿到完整的剩余宽度。
     *
     * 手机（≤ 430dp）与折叠内屏（860dp）都在门槛之下，观感与手势完全不变。
     */
    val useNavRail: Boolean get() = widthDp >= NAV_RAIL_FROM_DP

    /** 扣掉侧边导航栏后，真正能用来分栏的宽度 */
    fun contentWidthDp(navigationVisible: Boolean): Int =
        (widthDp - if (navigationVisible && useNavRail) NAV_RAIL_DP else 0).coerceAtLeast(1)

    /**
     * 左侧「主列表」栏宽度（账本列表 / 流水列表）。
     *
     * - 折叠内屏 / 平板：`min(368dp, 可分栏宽度 × 48%)`，下限 288dp。这是离线搜出来的，
     *   368 以上列表只是变空。
     * - **v16.4 桌面宽屏（可分栏 ≥1400dp）：按 40% 分，上限 720。**
     *   1600dp 的窗口里如果左栏还锁在 420，就变成"读的那一列窄、看的概览宽 1100"——
     *   主次反了。40% 让流水回到主栏位置（~640dp），右栏落在 960dp 左右，
     *   里面正好并排两列 460dp 的卡。
     */
    fun paneMasterWidthDp(navigationVisible: Boolean): Int {
        val content = contentWidthDp(navigationVisible)
        return if (content >= 1400) {
            (content * PANE_MASTER_SHARE_WIDE).roundToInt().coerceIn(420, PANE_MASTER_MAX_WIDE_DP)
        } else {
            (content * PANE_MASTER_SHARE)
                .roundToInt()
                .coerceIn(PANE_MASTER_MIN_DP, PANE_MASTER_MAX_DP)
        }
    }

    /** 右栏（概览 / 看板）宽度 = 可分栏宽度 − 左栏 */
    fun paneTrailingWidthDp(navigationVisible: Boolean): Int =
        (contentWidthDp(navigationVisible) - paneMasterWidthDp(navigationVisible))
            .coerceAtLeast(1)

    /** 顶部展示的姿态标签，便于用户/测试确认适配是否生效 */
    val postureLabel: String
        get() = when (posture) {
            FoldPosture.COVER -> if (isFoldable) "外屏 · 单栏" else "手机 · 单栏"
            FoldPosture.UNFOLDED -> "内屏展开 · 双栏"
            FoldPosture.TABLETOP -> "半折叠 · 上下分屏"
            FoldPosture.BOOK -> "半折叠 · 左右分栏"
        }
}

/**
 * QA 钩子：把姿态**钉死**在某个值上（v15，Android 真机用）。
 *
 * `am start -a com.foldledger.action.SET_POSTURE -n com.foldledger/.MainActivity --ei posture 2`
 * （0=COVER 1=UNFOLDED 2=TABLETOP 3=BOOK；不带 extra = 恢复自动）。
 *
 * 为什么需要它：悬停姿态（半折）无法用 adb 伪造 —— 铰链状态只有物理折叠才有，
 * 而「悬停时 UI 怎么摆」必须在内容完全静止的条件下反复量。钉死后桌面正常、
 * 只是这一会话的姿态被锁，方便验证布局不穿越铰链。
 */
object FoldPostureDebug {
    var forcedPosture: Int? by mutableStateOf(null)
}

private val defaultEnv = FoldEnv(
    widthDp = 411,
    heightDp = 866,
    widthSizeClass = WindowWidthClass.Compact,
    heightSizeClass = WindowHeightClass.Medium,
    posture = FoldPosture.COVER,
    isFoldable = false,
    isSeparating = false,
    hingeFraction = 0f,
    leadingFraction = 1f,
    trailingFraction = 0f
)

/**
 * 实时计算当前折叠环境。
 *
 * 平台 actual：
 * - Android：LocalConfiguration + WindowInfoTracker（FoldingFeature）→ HingeInfo；
 * - 桌面：窗口尺寸 → 无铰链。
 */
@Composable
expect fun rememberFoldEnv(): FoldEnv

/**
 * v15：QA 钉死 —— 把 [FoldPostureDebug.forcedPosture] 覆盖到计算结果上。
 * 放在 buildFoldEnv 之后单独改 posture 字段，布局分支跟着新姿态走。
 */
private fun FoldEnv.withForcedPosture(): FoldEnv {
    val forced = FoldPostureDebug.forcedPosture ?: return this
    val p = FoldPosture.entries.getOrNull(forced) ?: return this
    return copy(posture = p)
}

private fun buildFoldEnv(
    widthDp: Int,
    heightDp: Int,
    widthClass: WindowWidthClass,
    heightClass: WindowHeightClass,
    hinge: HingeInfo?,
    /** 本机见过铰链即为折叠屏；特征短暂丢失时靠它 + 宽度兜底 */
    everFoldable: Boolean
): FoldEnv {
    if (widthDp <= 0 || heightDp <= 0) {
        return defaultEnv
    }

    if (hinge == null) {
        // 无铰链：普通手机 / 平板 / 桌面窗口 / 折叠后的外屏。
        // v3.1.1：折叠屏的内屏未必到 840dp（Mix Fold 内屏约 785dp），特征慢一拍时
        // 按宽度兜底判 UNFOLDED，避免"宽屏先落一帧单栏"。
        val posture = if (
            (widthClass == WindowWidthClass.Expanded && widthDp >= 840) ||
            (everFoldable && widthDp >= TWO_PANE_MIN_WIDTH_DP)
        ) FoldPosture.UNFOLDED else FoldPosture.COVER
        return FoldEnv(
            widthDp = widthDp,
            heightDp = heightDp,
            widthSizeClass = widthClass,
            heightSizeClass = heightClass,
            posture = posture,
            isFoldable = everFoldable,
            isSeparating = false,
            hingeFraction = 0f,
            leadingFraction = 1f,
            trailingFraction = 0f
        )
    }

    // v3.1.1 宽度兜底：窄窗口（外屏 / 分屏小窗）里即使还挂着展开态特征也不分栏。
    // 合盖切换到外屏的瞬间，特征可能比窗口尺寸慢一拍，没这层就会先闪一帧双栏。
    val rawPosture = when {
        hinge.halfOpened && hinge.isHorizontal -> FoldPosture.TABLETOP
        hinge.halfOpened && !hinge.isHorizontal -> FoldPosture.BOOK
        else -> FoldPosture.UNFOLDED // 完全展开，铰链只是屏幕中央一条细线
    }
    val posture =
        if (widthDp < TWO_PANE_MIN_WIDTH_DP && rawPosture != FoldPosture.TABLETOP) {
            FoldPosture.COVER
        } else {
            rawPosture
        }

    return FoldEnv(
        widthDp = widthDp,
        heightDp = heightDp,
        widthSizeClass = widthClass,
        heightSizeClass = heightClass,
        posture = posture,
        isFoldable = true,
        isSeparating = hinge.isSeparating,
        hingeFraction = hinge.hingeFraction,
        leadingFraction = hinge.leadingFraction,
        trailingFraction = hinge.trailingFraction
    )
}

/** 平台 actual 复用的纯计算：宽度/高度断点 + 姿态（含 QA 钉死）。 */
internal fun buildEnv(
    widthDp: Int,
    heightDp: Int,
    hinge: HingeInfo?,
    everFoldable: Boolean
): FoldEnv =
    buildFoldEnv(
        widthDp = widthDp,
        heightDp = heightDp,
        widthClass = widthClassOf(widthDp),
        heightClass = heightClassOf(heightDp),
        hinge = hinge,
        everFoldable = everFoldable
    ).withForcedPosture()
