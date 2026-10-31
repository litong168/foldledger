package com.foldledger.ui.screens

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/**
 * iOS 式「长按拖拽排序」。
 *
 * ### 为什么不做下标数学
 *
 * 列表里除了账本卡片还夹着合计卡、分区标题、备份提示 —— "第几个条目"和"第几个账本"
 * 不是一回事。所以这里**每一帧做一次命中测试**：把被拖卡片的**中心点**拿去和所有
 * 可见账本的实际矩形比，落在谁身上就把谁让开。命中测试天然支持多列网格，
 * 也不怕中间夹着别的类型的条目。
 *
 * ### 位移怎么修正
 *
 * 拖拽中的卡片会先挪位、再重排。如果只记"手指移动了多少"，每重排一次位移就错一次。
 * 所以记的是**手指在列表坐标系里的绝对位置**，每帧渲染时用
 * `pointer − 抓取点 − 条目当前左上角` 现算位移 —— 位置一变，位移自动修正，
 * 卡片永远贴在手指下面，不会漂。
 *
 * ### 为什么被拖的那张不用 `animateItem`
 *
 * 被让开的卡片靠 `Modifier.animateItem()` 自己滑走；被拖的这张**必须严格跟手**，
 * 任何动画都会让它慢半拍。所以它只走 `graphicsLayer.translation`。
 */
@Stable
class BookDragState {
    /** 正在被拖的账本 id；null = 没在拖 */
    var draggingId by mutableStateOf<Long?>(null)
        internal set

    /** 手指在列表坐标系里的当前位置 */
    var pointer by mutableStateOf(Offset.Zero)
        internal set

    /** 抓取点相对条目左上角的偏移（避免卡片"跳"到手指中心） */
    var grab by mutableStateOf(Offset.Zero)
        internal set

    private var itemSize by mutableStateOf(Size.Zero)

    internal fun start(id: Long, local: Offset, at: Offset, sz: Size) {
        draggingId = id
        grab = local
        pointer = at + local
        itemSize = sz
    }

    internal fun move(delta: Offset) {
        pointer += delta
    }

    internal fun end() {
        draggingId = null
    }

    /** 被拖卡片的中心点（列表坐标） */
    internal fun center(): Offset =
        Offset(pointer.x - grab.x + itemSize.width / 2f, pointer.y - grab.y + itemSize.height / 2f)

    /** 命中测试：中心点落在哪个 id 的矩形里 */
    internal fun hitTest(bounds: List<Pair<Long, Pair<Offset, Size>>>): Long? =
        bounds.firstOrNull { (_, rect) ->
            val (topLeft, sz) = rect
            val c = center()
            c.x >= topLeft.x && c.x <= topLeft.x + sz.width &&
                c.y >= topLeft.y && c.y <= topLeft.y + sz.height
        }?.first
}

@Composable
fun rememberBookDragState(): BookDragState = remember { BookDragState() }

/**
 * 让一个账本卡片变成「可长按拖动」。
 *
 * @param ids 当前顺序下的账本 id（父级持有）
 * @param boundsOf 给出某个 id 在列表坐标系里的矩形；不在屏内时返回 null
 * @param onMove 命中到别的卡片时调用，参数是 (fromIndex, toIndex)
 * @param onDrop 手指抬起：此时把顺序写回偏好
 */
@Composable
fun Modifier.bookDragHandle(
    id: Long,
    shape: Shape,
    state: BookDragState,
    ids: List<Long>,
    boundsOf: (Long) -> Pair<Offset, Size>?,
    onMove: (Int, Int) -> Unit,
    onDrop: () -> Unit
): Modifier {
    val dragging = state.draggingId == id
    val haptics = LocalHapticFeedback.current

    // ⚠️ 这几个值全部经 `rememberUpdatedState` 转一道，`pointerInput` 的 key 只留 `id`。
    // 直接把 `ids` 塞进 key 是致命的：拖拽一命中就会重排 → ids 变化 → pointerInput 重建
    // → 手势被掐断，表现为"卡片粘在原地不跟手"。这个坑在 Tab 横滑那版已经踩过一次。
    val currentIds by rememberUpdatedState(ids)
    val currentBounds by rememberUpdatedState(boundsOf)
    val currentOnMove by rememberUpdatedState(onMove)
    val currentOnDrop by rememberUpdatedState(onDrop)

    return this
        .zIndex(if (dragging) 1f else 0f)
        .then(
            if (dragging) {
                Modifier.shadow(
                    elevation = 20.dp,
                    shape = shape,
                    clip = false,
                    ambientColor = Color(0xFF0A1420).copy(alpha = 0.10f),
                    spotColor = Color(0xFF0A1420).copy(alpha = 0.14f)
                )
            } else {
                Modifier
            }
        )
        .graphicsLayer {
            // ⚠️ `boundsOf(id)` **必须在这个 lambda 里现读**，不能在组合期先算好。
            // 重排会改变条目自身的布局位置，而 layoutInfo 不是 snapshot state ——
            // 组合期读到的值重排后就永久过期，卡片会一直偏一个条目高。
            // 放在绘制阶段读：此时本帧布局已经跑完，拿到的永远是新的。
            val rect = if (state.draggingId == id) currentBounds(id) else null
            if (rect != null) {
                // 位移 = 手指位置 − 抓取点 − 条目当前左上角
                translationX = state.pointer.x - state.grab.x - rect.first.x
                translationY = state.pointer.y - state.grab.y - rect.first.y
                scaleX = 1.025f
                scaleY = 1.025f
            } else {
                translationX = 0f
                translationY = 0f
                scaleX = 1f
                scaleY = 1f
            }
        }
        .pointerInput(id) {
            detectDragGesturesAfterLongPress(
                onDragStart = { local ->
                    val rect = currentBounds(id) ?: return@detectDragGesturesAfterLongPress
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    state.start(id, local, rect.first, rect.second)
                },
                onDrag = { change, amount ->
                    if (state.draggingId != id) return@detectDragGesturesAfterLongPress
                    change.consume()
                    state.move(amount)
                    val order = currentIds
                    val target = state.hitTest(
                        order.mapNotNull { other -> currentBounds(other)?.let { other to it } }
                    ) ?: return@detectDragGesturesAfterLongPress
                    if (target != id) {
                        val from = order.indexOf(id)
                        val to = order.indexOf(target)
                        if (from >= 0 && to >= 0 && from != to) {
                            currentOnMove(from, to)
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                    }
                },
                onDragEnd = {
                    if (state.draggingId == id) {
                        state.end()
                        currentOnDrop()
                    }
                },
                onDragCancel = {
                    if (state.draggingId == id) state.end()
                }
            )
        }
}
