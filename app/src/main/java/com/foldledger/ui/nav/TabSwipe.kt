package com.foldledger.ui.nav

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import com.foldledger.ui.anim.Motion
import kotlinx.coroutines.launch

/**
 * 三 Tab 之间"按住来回抹"切换（v12.1.3 起；v12.1.4 内外屏统一为底部胶囊）。
 *
 * ### 交互语义（用户原话）
 * 「手指放上去来回滑动可以来回切换」—— 按下不松手，左右来回抹，
 * **指尖落在哪个图标格就实时切到哪个 Tab**，往回抹立刻切回来。
 * 不是"甩一下判定一次"，也不需要松手确认。
 *
 * ### 为什么用「位置映射」而不是「累计行程 / 速度」
 * 位置映射天然可逆：指尖回到原格，Tab 就回原格，来回抹多少次都一致；
 * 位移阈值 + 甩速那套是单向的（判定完就结束），做不出来回抹的手感。
 *
 * ### 跟手滑块
 * 返回的 `dragPx` = 指尖相对**选中格中心**的偏移（px），
 * 胶囊拿它给白色滑块加偏移 → 滑块中心始终贴着指尖；松手 180ms 归零。
 *
 * ### 边界
 * 指尖抹到胶囊外：目标下标夹在 `0..tabCount-1`，不会切出去；
 * 滑块偏移也被夹在胶囊内，不会飞出去。
 *
 * ### 与图标点击的关系
 * `detectHorizontalDragGestures` 只在横向拖动时消费指针；图标本身的
 * `clickableScale`（tap）照常触发，两者不冲突。
 *
 * ⚠️ 不要加 `systemGestureExclusion()`：实测 HyperOS 不尊重它（v12.1.2 踩过）。
 * ⚠️ `selectedIndex` / `onSelect` **不能进 pointerInput 的 key**：实时切换时它们每帧都变，
 * 进 key 会导致手势反复重建、拖动被掐断 → 一律走 `rememberUpdatedState`。
 *
 * @param enabled 二级页 / 弹层打开时必须为 false —— 那时导航已退场。
 * @param selectedIndex 当前选中 Tab 下标。
 * @param tabCount Tab 总数。
 * @param onSelect 实时切换回调，参数为目标下标。
 * @param cellCount 胶囊里**网格**数。默认 = `tabCount`；v13.5 曾因「记一笔」占一格而
 *   传 `tabCount + 1`，v13.6 起「记一笔」已切出去成独立圆钮，各处都回到默认值。
 *   保留这个参数是为了将来胶囊里若再出现非 Tab 的格子时，格宽还能按网格数算 ——
 *   只按 Tab 数算的话，滑块每格都会短一截，横滑时指尖明明在第 2 格、判定出来却是第 1.5 格。
 * @return (挂在胶囊上的 Modifier, 滑块当前跟手偏移 px)
 */
@Composable
internal fun rememberTabSwipe(
    enabled: Boolean,
    selectedIndex: Int,
    tabCount: Int,
    onSelect: (Int) -> Unit,
    cellCount: Int = tabCount
): Pair<Modifier, Float> {
    var drag by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current

    val selectedState by rememberUpdatedState(selectedIndex)
    val countState by rememberUpdatedState(tabCount)
    val cellsState by rememberUpdatedState(cellCount)
    val selectState by rememberUpdatedState(onSelect)

    val modifier = Modifier.pointerInput(enabled) {
        if (!enabled) return@pointerInput
        val widthPx = size.width.toFloat().coerceAtLeast(1f)

        detectHorizontalDragGestures(
            onHorizontalDrag = { change, _ ->
                change.consume()
                val count = countState
                val itemWidth = widthPx / cellsState.coerceAtLeast(1)
                val px = change.position.x

                val target = (px / itemWidth).toInt().coerceIn(0, count - 1)
                val base = if (target != selectedState) {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    selectState(target)
                    target
                } else {
                    selectedState
                }

                // 滑块中心贴指尖，但夹在胶囊内不出界
                drag = (px - itemWidth * (base + 0.5f)).coerceIn(
                    minimumValue = -itemWidth * base,
                    maximumValue = itemWidth * (count - 1 - base)
                )
            },
            onDragEnd = {
                scope.launch {
                    animate(drag, 0f, animationSpec = tween(180, easing = Motion.flow)) { v, _ ->
                        drag = v
                    }
                }
            },
            onDragCancel = {
                scope.launch {
                    animate(drag, 0f, animationSpec = tween(180, easing = Motion.flow)) { v, _ ->
                        drag = v
                    }
                }
            }
        )
    }

    return modifier to drag
}
