package com.foldledger.ui.adaptive

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.lerp

/**
 * 顶栏「大标题 → 紧凑标题」的折叠进度。
 *
 * ### 为什么需要一个跨层的状态
 * 折叠的**驱动**在内容层（明细页的 LazyColumn 滚到哪了），**消费**在外壳层
 * （`AdaptiveScaffold` 的顶栏高度）。两者之间隔着 NavHost + Scaffold，
 * 用参数一路传下去要穿过四层签名，所以用一个滑块对象挂在 CompositionLocal 上：
 * 内容层写、外壳层读，中间层完全不用知道有这回事。
 *
 * ### 为什么是「连续的浮点」而不是「展开/收起」两个布尔
 * 布尔只能做淡入淡出，会读成"标题换了一个"；浮点可以让字号、栏高、副标题
 * 同时**线性**跟着手指走 —— 这才是 iOS 大标题的手感：不是切换，是"被推上去"。
 *
 * ### 谁负责归零
 * 只归零一次是不够的。明细页在 NavHost 退到后台时**不会立刻销毁**，
 * 它留下的 `fraction = 1` 会让"我的"页顶着一条被压扁的顶栏。所以：
 *  - 明细页 dispose 时归零（[reset]）；
 *  - 切 Tab / 打开二级页时外壳再归零一次（兜底，见 AppRoot）。
 */
class TitleCollapseState {
    /**
     * 0 = 完全展开（副标题 + 23sp 大标题），1 = 完全收起（只有一行 17sp 标题）。
     * 由内容层的滚动位置驱动，所以**不要**再套动画 —— 它本身就是逐帧值。
     */
    var fraction by mutableFloatStateOf(0f)
        internal set

    fun set(value: Float) {
        val v = value.coerceIn(0f, 1f)
        // 只在小数点后三位变化时才写，避免滚动时每帧都触发外壳重组
        if (kotlin.math.abs(v - fraction) > 0.002f) fraction = v
    }

    fun reset() {
        if (fraction != 0f) fraction = 0f
    }

    /** 按当前折叠进度在两个尺寸之间插值 */
    fun between(expanded: Dp, collapsed: Dp): Dp = lerp(expanded, collapsed, fraction)
}

/** 外壳层提供，内容层写入。没有外壳时（预览 / 二级页）为 null，一切按展开处理。 */
val LocalTitleCollapse = compositionLocalOf<TitleCollapseState?> { null }
