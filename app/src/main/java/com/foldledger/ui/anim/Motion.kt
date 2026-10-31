package com.foldledger.ui.anim

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Indication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.delay

/**
 * 澎湃 OS 4 风格的动效语言。
 *
 * 官方对这一代的描述里，与第三方 App 有关的是这几条，全部落在这里：
 *
 * | 官方特征 | 这里怎么落 |
 * |---|---|
 * | 动效曲线重调，模拟流体缓冲 + 轻微惯性回弹 | [glide] / [emphasized] / [soft] / [bouncy] 四组曲线按场景分工 |
 * | 点按有光、操作有回应 | [clickableScale] 提供按压缩放 + 涟漪 + 可选触感反馈 |
 * | 拖拽有光，光效跟随轨迹流动 | 数值类（金额/进度/环形图）一律**滚动增长**而不是硬跳 |
 * | 页面过渡连贯，消除跳变与卡顿 | 转场统一「轻微位移 + 缩放 + 淡入」，退场用 [exit] 快走 |
 * | 解决界面动效生硬、跳转突兀 | 入场用弹簧或 [emphasized]，绝不用线性 |
 *
 * 设计原则：**位移幅度克制**（屏高的 1/12 ~ 1/6）、**时长分档**、**退场比入场快**。
 */
object Motion {

    // ---------------- 时长分档（v4.1 收紧：向主流记账 App 的"干脆利落"看齐） ----------------
    // 鲨鱼记账 / 一木记账等的体感是「快而稳」——动效稍慢就会显拖沓。
    // 这一版整体比 3.x 缩短 ~15%，让每次交互都更跟手。

    /** 即时反馈：按压、颜色、涟漪 */
    const val FAST = 150

    /** 常规过渡：弹层、卡片出现、页面切换 */
    const val MEDIUM = 260

    /** 慢速：数值滚动、图表生长 */
    const val SLOW = 420

    // ---------------- 页面转场（v4.1 再收紧，更轻更跟手） ----------------

    /** 转场进入：比 MEDIUM 略长，给足缓冲感但仍利落 */
    const val PAGE_ENTER = 280

    /** 转场退出：比进入短，退场永远不该拖住用户 */
    const val PAGE_EXIT = 210

    /** 转场专用缓动：比 glide 更柔的收尾（接近 CSS ease），消除突兀 */
    val flow: Easing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)

    // ---------------- 缓动曲线 ----------------

    /** 标准：起步快、收尾缓（Material 3 Emphasized，也是最通用的那条） */
    val standard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** 强调减速：用于「进入」，起步有加速感、收尾极慢 */
    val emphasized: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    /** 缓入缓出：玻璃/流体材质专用，两头都软 */
    val glide: Easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

    /**
     * 流体（v4.1 新增）：澎湃 OS 4「流体缓冲」的核心曲线——起步更缓、中段更饱满、
     * 收尾极柔。用于数字滚动、图表生长这类「有机生长」的动效，比 standard 更有生命力。
     */
    val fluid: Easing = CubicBezierEasing(0.35f, 0.15f, 0.25f, 1f)

    /** 退出：起步慢、收尾极快（Accelerate），让"离开"干脆利落 */
    val exit: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    // ---------------- 弹簧（v4.1 微调阻尼，更稳不飘） ----------------

    /** 柔和：面板、宽度过渡 */
    fun <T> soft(): SpringSpec<T> =
        spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow)

    /** 回弹：按压、切换、勾选、强调 */
    fun <T> bouncy(): SpringSpec<T> =
        spring(dampingRatio = 0.68f, stiffness = Spring.StiffnessMedium)

    /** Tab 选中：比 bouncy 更收的回弹（阻尼 0.82），"按进去又弹回"但不过头 */
    fun <T> tab(): SpringSpec<T> =
        spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMedium)

    /** 干脆：颜色、透明度这类不该回弹的属性 */
    fun <T> snappy(): SpringSpec<T> =
        spring(dampingRatio = 0.94f, stiffness = Spring.StiffnessHigh)

    /** 轻柔：几乎不回弹，用于大块内容的位移 */
    fun <T> gentle(): SpringSpec<T> =
        spring(dampingRatio = 1f, stiffness = Spring.StiffnessLow)

    /** 错落入场的步长（毫秒） */
    const val STAGGER_STEP = 36L

    /** 最多错落多少个元素，再多就一起出来，避免后面等太久 */
    const val STAGGER_MAX = 7
}

// ============================================================
//  按压反馈
// ============================================================

/** 触感强度。默认 [NONE]，重要动作（保存/创建/删除）才用 */
enum class Haptic { NONE, LIGHT, MEDIUM }

/**
 * 带按压缩放 + 涟漪 + 可选触感的点击。语义与 `Modifier.clickable` 一致，直接替换即可。
 *
 * 缩放到 [pressedScale] 用的是**回弹**弹簧，抬手会轻微过冲 —— 这就是"操作有回应"的手感。
 *
 * v3.0.9：涟漪染主色 + 极淡按压辉光。
 * v5.0「液态玻璃」：按下辉光升级为**中心光斑**（径向渐变，外圈更透），
 * 并允许调用方自定义 [bloomColor] / [bloomIntensity]（液态按钮用白色光斑）。
 */
@Composable
fun Modifier.clickableScale(
    enabled: Boolean = true,
    pressedScale: Float = 0.955f,
    haptic: Haptic = Haptic.NONE,
    bloomColor: Color? = null,
    bloomIntensity: Float = 0.18f,
    /** v5.0：按下时是否扫过一道高光（液态玻璃的标志性光照动作，主 CTA 用） */
    sweepOnPress: Boolean = false,
    /**
     * v5.3：涟漪指示器。**默认 null（不画涟漪）**。
     *
     * 之前用 `LocalIndication`，Material 的涟漪是**有边界的方角圆形**、按节点矩形裁剪，
     * 落在圆角卡片/胶囊/导航格上就会出现一块方角亮斑 —— 录屏里"点 Tab 亮一个方框"就是它。
     * 现在统一靠"缩放 + 光斑 + 触感"给反馈，涟漪只在确实需要时显式传入。
     */
    indication: Indication? = null,
    /**
     * v5.3：按压光斑按什么形状裁剪。圆角卡片/胶囊务必传自己的 shape，
     * 否则光斑是矩形的，四个角会露在外面。
     */
    clipShape: Shape? = null,
    onClick: () -> Unit
): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val haptics = LocalHapticFeedback.current
    val glowColor = bloomColor

    // v12「液态玻璃」：按压不再回弹。
    // 阻尼 1.0 + 高刚度 = 按下去立刻到位、抬手立刻回位，中途没有过冲。
    // 苹果的系统控件就是这个手感：形变是"被按住的位移"，不是"被拨动的弹簧"。
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) pressedScale else 1f,
        animationSpec = spring(dampingRatio = 1f, stiffness = Spring.StiffnessHigh),
        label = "pressScale"
    )
    val glow by animateFloatAsState(
        targetValue = if (pressed && enabled) 1f else 0f,
        animationSpec = tween(if (pressed) 90 else 220, easing = Motion.standard),
        label = "pressGlow"
    )
    // 高光扫过：每次按下从 0 → 1 播一遍
    val sweep = remember { Animatable(0f) }
    var sweepArmed by remember { mutableStateOf(true) }
    LaunchedEffect(pressed) {
        if (pressed) {
            if (sweepArmed) {
                sweepArmed = false
                sweep.snapTo(0f)
                sweep.animateTo(1f, tween(520, easing = Motion.flow))
                sweepArmed = true
            }
        }
    }

    // 圆角组件先裁剪，光斑与（可选的）涟漪才不会露出方角
    val clipped = if (clipShape != null) this.clip(clipShape) else this

    return clipped
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .drawWithContent {
            drawContent()
            if (glow > 0.001f && glowColor != null) {
                // 中心亮、边缘透的光斑；比整片平涂更像"光"
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            glowColor.copy(alpha = glow * bloomIntensity),
                            glowColor.copy(alpha = glow * bloomIntensity * 0.35f),
                            Color.Transparent
                        ),
                        center = Offset(size.width / 2f, size.height / 2f),
                        radius = size.maxDimension * 0.62f
                    )
                )
            }
            if (sweepOnPress) {
                val t = sweep.value
                if (t > 0f && t < 1f) {
                    // 一道斜向白光从左扫到右
                    val w = size.width
                    val x = -w * 0.5f + t * (w * 2f)
                    val band = w * 0.22f
                    drawRect(
                        brush = Brush.linearGradient(
                            colorStops = arrayOf(
                                0.0f to Color.Transparent,
                                0.45f to Color.White.copy(alpha = 0.30f),
                                0.55f to Color.White.copy(alpha = 0.30f),
                                1.0f to Color.Transparent
                            ),
                            start = Offset(x - band, 0f),
                            end = Offset(x + band, size.height)
                        )
                    )
                }
            }
        }
        .clickable(
            interactionSource = source,
            indication = indication,
            enabled = enabled
        ) {
            when (haptic) {
                Haptic.LIGHT -> haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                Haptic.MEDIUM -> haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                Haptic.NONE -> Unit
            }
            onClick()
        }
}

/**
 * 给「自己已经有 clickable」的组件用的按压缩放：传入同一个 [MutableInteractionSource]，
 * 避免叠两层点击区域。
 */
@Composable
fun rememberPressScale(
    source: MutableInteractionSource,
    pressedScale: Float = 0.96f
): Float {
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        // v14.2：0.55 会过冲（缩一下再弹回来），与全站「按下去立刻到位、抬手立刻回位」的
        // 液态玻璃手感不一致 —— 改成 1.0 无过冲，和 clickableScale 同一条曲线。
        animationSpec = spring(dampingRatio = 1f, stiffness = Spring.StiffnessMedium),
        label = "pressScale"
    )
    return scale
}

/**
 * 数值变化时「跳一下」。
 *
 * 用在结余、合计这类会跟着操作变化的数字上：变化的一瞬间放大到 [peak] 再弹回，
 * 用户的眼睛会被自然带到变化的地方 —— 这是"操作有回应"里最便宜也最有效的一招。
 */
@Composable
fun Modifier.valuePulse(trigger: Long, peak: Float = 1.08f): Modifier {
    val anim = remember { Animatable(1f) }
    var first by remember { mutableStateOf(true) }
    LaunchedEffect(trigger) {
        if (first) {
            first = false
            return@LaunchedEffect
        }
        anim.snapTo(peak)
        anim.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMedium))
    }
    // 在绘制阶段读，只重绘不重组
    return this.graphicsLayer {
        scaleX = anim.value
        scaleY = anim.value
    }
}

/**
 * 一次性高亮脉冲：当 [active] 变为 true 时先放大到 [peak] 再弹回。
 *
 * 用于「刚刚创建 / 刚刚定位到这里」的卡片：配合列表滚动，用户一眼就知道东西落在哪儿了。
 * 和 [valuePulse] 的差别是它由布尔触发、并且不需要跳过首次 —— 因为 `active` 本来就
 * 只在被显式置位的那一次为 true。
 */
@Composable
fun Modifier.highlightPulse(active: Boolean, peak: Float = 1.035f): Modifier {
    val anim = remember { Animatable(1f) }
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        anim.snapTo(peak)
        anim.animateTo(1f, spring(dampingRatio = 0.42f, stiffness = Spring.StiffnessMediumLow))
    }
    return this.graphicsLayer {
        scaleX = anim.value
        scaleY = anim.value
    }
}

/**
 * 金额滚动：从上一个值平滑滚到新值。传入一个格式化函数以复用 Money.format。
 */@Composable
fun animatedCurrency(target: Long, format: (Long) -> String): String {
    val anim = remember { Animatable(target.toFloat()) }
    LaunchedEffect(target) {
        anim.animateTo(
            targetValue = target.toFloat(),
            animationSpec = tween(Motion.SLOW, easing = Motion.standard)
        )
    }
    return format(anim.value.toLong())
}

/**
 * 一次性错落入场。给**非懒加载**的区块用（懒列表请用 Modifier.animateItem()，
 * 否则滚动回收会重复播动画）。
 */
@Composable
fun StaggeredAppear(
    index: Int,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(index.coerceIn(0, Motion.STAGGER_MAX) * Motion.STAGGER_STEP)
        shown = true
    }
    AnimatedVisibility(
        visible = shown,
        enter = fadeIn(tween(Motion.MEDIUM, easing = Motion.glide)) +
            slideInVertically(animationSpec = Motion.soft()) { it / 6 },
        modifier = modifier
    ) {
        content()
    }
}
