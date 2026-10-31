package com.foldledger.ui.adaptive

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.material3.windowsizeclass.WindowHeightSizeClass
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowLayoutInfo
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
internal const val NAV_RAIL_DP = 80

/** 达到这个宽度才用侧边导航栏；更窄改用底部导航，把宽度让给内容 */
internal const val NAV_RAIL_FROM_DP = 800

/** 左栏占可分栏宽度的比例上限 */
internal const val PANE_MASTER_SHARE = 0.48f

internal const val PANE_MASTER_MIN_DP = 288
internal const val PANE_MASTER_MAX_DP = 368

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
 * 设备姿态。折叠屏适配的第一性问题：**同一个 Activity 会经历多种物理形态**。
 */
enum class FoldPosture {
    /** 外屏 / 折叠合起 / 普通直板手机 —— 窄屏单栏 */
    COVER,

    /** 内屏全展开（180°）—— 平板式双栏 */
    UNFOLDED,

    /** 半折叠 + 水平铰链（笔记本姿态）—— 上半屏看内容，下半屏放键盘 */
    TABLETOP,

    /** 半折叠 + 垂直铰链（书本姿态）—— 左右两栏，避开铰链 */
    BOOK
}

/**
 * 当前窗口的自适应环境快照。UI 只依赖这一个对象做决策。
 */
data class FoldEnv(
    val widthDp: Int,
    val heightDp: Int,
    val widthSizeClass: WindowWidthSizeClass,
    val heightSizeClass: WindowHeightSizeClass,
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

    val isCompactWidth: Boolean get() = widthSizeClass == WindowWidthSizeClass.Compact
    val isExpandedWidth: Boolean get() = widthSizeClass == WindowWidthSizeClass.Expanded

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

    /** 弹层是否用「居中大卡片」而不是底部抽屉 */
    val useDialogOverlay: Boolean get() = twoPane || widthDp >= 600

    // ---------- 展开态分栏 ----------

    /**
     * 是否用左侧 NavigationRail。
     *
     * ⚠️ v12.1.4：**内外屏统一**为底部悬浮胶囊后，侧栏已退役（恒 false）。
     * 保留这个属性是为了让 `contentWidthDp` / 折叠屏设置页继续有一处统一口径；
     * 不要再把它接回导航（需要时改这里一处即可复活）。
     */
    val useNavRail: Boolean get() = false

    /** 扣掉侧边导航栏后，真正能用来分栏的宽度 */
    fun contentWidthDp(navigationVisible: Boolean): Int =
        (widthDp - if (navigationVisible && useNavRail) NAV_RAIL_DP else 0).coerceAtLeast(1)

    /**
     * 左侧「主列表」栏宽度（账本列表 / 流水列表）。
     * `min(368dp, 可分栏宽度 × 48%)`，下限 288dp。
     */
    fun paneMasterWidthDp(navigationVisible: Boolean): Int =
        (contentWidthDp(navigationVisible) * PANE_MASTER_SHARE)
            .roundToInt()
            .coerceIn(PANE_MASTER_MIN_DP, PANE_MASTER_MAX_DP)

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
 * QA 钩子：把姿态**钉死**在某个值上（v15）。
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
    widthSizeClass = WindowWidthSizeClass.Compact,
    heightSizeClass = WindowHeightSizeClass.Medium,
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
 * 数据来源两条：
 *  1. `LocalConfiguration` → 窗口尺寸 → 官方 WindowSizeClass 断点
 *  2. `WindowInfoTracker`  → FoldingFeature（铰链位置、角度、是否分割窗口）
 *
 * 之所以不用 `calculateWindowSizeClass(activity)`：那个 API 在部分 OEM（含小米）上
 * 的窗口尺寸缓存更新会慢一拍，折叠瞬间会先拿到旧尺寸。直接读 Composition 的
 * LocalConfiguration 与之保持同一帧，配合 configChanges 声明可做到零重建、零闪屏。
 */
@Composable
fun rememberFoldEnv(): FoldEnv {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val windowInfo = LocalWindowInfo.current
    val activity = remember(context) { context.findActivity() }

    // 窗口像素尺寸（Compose 1.7+ 提供，折叠瞬间即时更新）
    val container = windowInfo.containerSize
    val pxPerDp = density.density
    val widthPx = if (container.width > 0) container.width else (configuration.screenWidthDp * pxPerDp).toInt()
    val heightPx = if (container.height > 0) container.height else (configuration.screenHeightDp * pxPerDp).toInt()
    val widthDp = (widthPx / pxPerDp).toInt()
    val heightDp = (heightPx / pxPerDp).toInt()

    val sizeClass = remember(widthDp, heightDp) {
        WindowSizeClass.calculateFromSize(DpSize(widthDp.dp, heightDp.dp))
    }

    // 只要见过一次铰链，就记住这是台折叠屏（v3.1.1）。
    // 之后 WindowLayoutInfo 在切换内外屏的瞬间可能慢一拍：要么特征还没送到
    // （宽屏 + 无特征 → 误判单栏），要么特征迟迟不走（窄屏 + 旧特征 → 误判双栏）。
    // 两种误判各闪一帧，就是用户看到的"切一下分栏闪一下"。这个记忆让两种时序
    // 都能在第一帧就落到正确姿态。
    val everFoldable = remember { mutableStateOf(false) }

    val foldFeature by produceState<FoldingFeature?>(
        initialValue = null,
        activity,
        // v15.0.2：旋转（尺寸变化）时**强制重启采集**。
        // HyperOS 在「半折状态下旋转屏幕」时，WindowInfoTracker 不重新 emit ——
        // 铰链 bounds/orientation 还是竖屏时的旧值，横屏布局就用错比例（只有半屏），
        // 且全展开后旧特征仍挂着、不恢复。把窗口尺寸放进 key，旋转即重订阅冷流，
        // 拿到当前真实铰链状态。
        widthDp,
        heightDp
    ) {
        if (activity == null) return@produceState
        runCatching {
            WindowInfoTracker.getOrCreate(activity)
                .windowLayoutInfo(activity)
                .collect { info: WindowLayoutInfo ->
                    val feature = info.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull()
                    if (feature != null) everFoldable.value = true
                    value = feature
                }
        }
    }

    return remember(widthDp, heightDp, sizeClass, foldFeature, everFoldable.value, FoldPostureDebug.forcedPosture, activity) {
        buildFoldEnv(
            widthDp = widthDp,
            heightDp = heightDp,
            heightPx = heightPx,
            pxPerDp = pxPerDp,
            sizeClass = sizeClass,
            feature = foldFeature,
            everFoldable = everFoldable.value
        ).withForcedPosture()
    }
}

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
    heightPx: Int,
    pxPerDp: Float,
    sizeClass: WindowSizeClass,
    feature: FoldingFeature?,
    /** 本机见过铰链即为折叠屏；特征短暂丢失时靠它 + 宽度兜底 */
    everFoldable: Boolean
): FoldEnv {
    if (widthDp <= 0 || heightDp <= 0) {
        return defaultEnv
    }

    if (feature == null) {
        // 无铰链：普通手机 / 平板 / 桌面窗口 / 折叠后的外屏。
        // v3.1.1：折叠屏的内屏未必到 840dp（Mix Fold 内屏约 785dp），特征慢一拍时
        // 按宽度兜底判 UNFOLDED，避免"宽屏先落一帧单栏"。
        val posture = if (
            (sizeClass.widthSizeClass == WindowWidthSizeClass.Expanded && widthDp >= 840) ||
            (everFoldable && widthDp >= TWO_PANE_MIN_WIDTH_DP)
        ) FoldPosture.UNFOLDED else FoldPosture.COVER
        return FoldEnv(
            widthDp = widthDp,
            heightDp = heightDp,
            widthSizeClass = sizeClass.widthSizeClass,
            heightSizeClass = sizeClass.heightSizeClass,
            posture = posture,
            isFoldable = everFoldable,
            isSeparating = false,
            hingeFraction = 0f,
            leadingFraction = 1f,
            trailingFraction = 0f
        )
    }

    val b = feature.bounds
    val horizontalHinge = feature.orientation == FoldingFeature.Orientation.HORIZONTAL
    val halfOpened = feature.state == FoldingFeature.State.HALF_OPENED

    // v3.1.1 宽度兜底：窄窗口（外屏 / 分屏小窗）里即使还挂着展开态特征也不分栏。
    // 合盖切换到外屏的瞬间，特征可能比窗口尺寸慢一拍，没这层就会先闪一帧双栏。
    val rawPosture = when {
        halfOpened && horizontalHinge -> FoldPosture.TABLETOP
        halfOpened && !horizontalHinge -> FoldPosture.BOOK
        else -> FoldPosture.UNFOLDED // 完全展开，铰链只是屏幕中央一条细线
    }
    val posture =
        if (widthDp < TWO_PANE_MIN_WIDTH_DP && rawPosture != FoldPosture.TABLETOP) {
            FoldPosture.COVER
        } else {
            rawPosture
        }

    val safeH = if (heightPx > 0) heightPx.toFloat() else heightDp * pxPerDp
    val safeW = widthDp * pxPerDp

    var hingeFraction = 0f
    var leading = 1f
    var trailing = 0f

    if (horizontalHinge) {
        val hingeSize = b.height().toFloat().coerceAtLeast(0f)
        hingeFraction = (hingeSize / safeH).coerceIn(0f, 0.5f)
        leading = (b.top / safeH).coerceIn(0.15f, 0.85f)
        trailing = ((safeH - b.bottom) / safeH).coerceIn(0.15f, 0.85f)
    } else {
        val hingeSize = b.width().toFloat().coerceAtLeast(0f)
        hingeFraction = (hingeSize / safeW).coerceIn(0f, 0.5f)
        leading = (b.left / safeW).coerceIn(0.2f, 0.8f)
        trailing = ((safeW - b.right) / safeW).coerceIn(0.2f, 0.8f)
    }

    return FoldEnv(
        widthDp = widthDp,
        heightDp = heightDp,
        widthSizeClass = sizeClass.widthSizeClass,
        heightSizeClass = sizeClass.heightSizeClass,
        posture = posture,
        isFoldable = true,
        isSeparating = feature.isSeparating,
        hingeFraction = hingeFraction,
        leadingFraction = leading,
        trailingFraction = trailing
    )
}

internal fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
