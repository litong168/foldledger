package com.foldledger.ui.theme

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import kotlin.math.abs

/**
 * ============================================================
 *  Glass 2.0 —— 滚动光相（Scroll Phase）
 * ============================================================
 *
 * ### 这个东西在做什么
 *
 * 底栏胶囊与「记一笔」圆键是**固定不动**的玻璃，而它们**底下的内容在动**。
 * 真实的光学玻璃上，这种相对运动会让表面的高光**跟着内容一起流动**（折射跟着
 * 被折的东西走），而不是钉死在某一个位置。
 *
 * v13.9 之前这套玻璃的高光是**死的**（永远在 0.28 宽度处），内容滑过去时它
 * 纹丝不动 —— 读起来就是"贴了一块贴纸"。Glass 2.0 要补的就是这一层：
 * **内容在动 → 高光在动；内容停下 → 高光自己荡回原位。**
 *
 * ### 为什么是"速度"而不是"位置"
 *
 * 第一版草稿把**滚动位置**直接换算成 0..1 的循环相位，两个致命问题：
 *
 *  1. **静止态就不等于改版前**。位置为 0 时相位也是 0，折成扫掠量是 **-1**
 *     （不是 0），于是顶部静置时底栏高光被推到 0.16 宽度处 —— 和 v13.9 的
 *     0.28 对不上。这不是"效果"，这是**回归**。
 *  2. **跨行相位跳变**。用 `index*1000 + offset` 当连续量，假设每一行占 1000px；
 *     真实流水行只有约 140px，于是每换一行连续量就猛跳 860px，相位跳 1.4 圈，
 *     高光**闪**一下。换行越密闪得越厉害。
 *
 * 改成速度驱动之后：
 *  - **静止 = 0**（速度为 0），逐像素回到 v13.9 —— 零回归；
 *  - 连续量用**跨行精确补偿**算出来（见 [GlassGlowEngine]），换行不再跳；
 *  - 停手后速度归零，高光自己荡回原位 —— 这正是"液态"的读感。
 *
 * ### 三条对比门槛不受影响
 *
 * 扫掠量只调制两处：**白棱亮度**（±6%）与**曲面反射高光中心的位置**（±12% 宽）。
 * 膜的 alpha 一个字节都没动 → 卡/底 1.10、语义色/底 4.5、三级字 4.0 三条门槛
 * 与 v13.9 完全一致。
 */

/**
 * Glass 2.0 的光相控制器。
 *
 * 持有**最终扫掠量**（供玻璃在**绘制阶段**读取）。
 *
 * ⚠️ 为什么扫掠量放在控制器里而不是直接用 CompositionLocal 传 FloatState：
 * 玻璃材质是在 `drawBehind` / `drawWithContent` 的 lambda 里读的，那儿**不是
 * Composable 上下文**，读 CompositionLocal 会编译不过；退一步只在组合期读
 * `.value` 又会让**整条底栏每帧重组**（而不是只重绘）。所以用"控制器对象 +
 * 内部 Snapshot 状态"：组合期只取控制器引用（引用不变，不触发重组），
 * 绘制期才读 `.value` —— 值变了只重绘那一块玻璃。
 */
class GlassGlowController {
    /** 最终扫掠量 ∈ [-1,1]。0 = 静止原位（与 v13.9 逐像素一致）。 */
    val sweep = mutableFloatStateOf(0f)

    /** 把驱动端算出来的值写进来。QA 强制值优先。 */
    fun publish(value: Float) {
        val v = GlassGlowDebug.forced.value ?: value
        if (sweep.floatValue != v) sweep.floatValue = v
    }
}

/**
 * QA 钩子。
 *
 * 用途：在真机上把光相**钉死**在某个值上截图，从而在**内容完全不动**的条件下
 * 量出"高光调制到底产生了多少像素差"—— 否则滚动时内容本身就在变，diff 里
 * 混着内容的位移，根本分不清是玻璃动了还是内容动了。
 *
 * 通过 intent 设置：
 * `am start -a com.foldledger.action.SET_GLOW -n com.foldledger/.MainActivity --ef glow 1.0`
 */
object GlassGlowDebug {
    /** 强制扫掠量；null = 跟随滚动自动计算。 */
    val forced = mutableStateOf<Float?>(null)
}

/**
 * 滚动 → 扫掠量的**纯计算核心**（无 Compose 依赖，可直接单测）。
 *
 * ### 连续滚动量是怎么算准的
 *
 * Compose 只给出「首项下标 + 首项内偏移」，这两个量在换行时会**跳**（偏移归零）。
 * 直接拿它们相减，得到的位移是错的。
 *
 * 这里改成算**两次采样之间的真实像素位移**：
 * ```
 * Δ = (offset_now - offset_prev) ± Σ(换行时跨过的那些行的高度)
 * ```
 * 跨行时那些行的高度从"上一帧可见项的尺寸表"里查（上一帧它们还在屏幕上，
 * 一定被记录过）→ 换行位移精确补偿，**相位不再跳变**。
 *
 * 若一帧跨了多行、其中有没被记录过的（极快的甩动），用当前可见项的平均高度
 * 兜底 —— 误差只影响那一帧的速度，且会被 EMA 抹平。
 *
 * @param referenceVel 速度多大算"满量程"（px/s）。日常滑动约 800~1500，甩动 3000+。
 * @param velocityEma 速度的指数平滑系数（越小越稳、越迟钝）。
 */
class GlassGlowEngine(
    private val referenceVel: Float = 3200f,
    private val velocityEma: Float = 0.30f
) {
    /** 已见项高度表：下标 → 高度(px)。换行时用来精确补偿。 */
    private val heightByIndex = HashMap<Int, Int>()

    private var lastIndex = -1
    private var lastOffset = 0
    private var lastNanos = 0L
    private var started = false
    private var emaVel = 0f

    /** 当前扫掠量 ∈ [-1,1]。0 = 静止原位。 */
    var sweep: Float = 0f
        private set

    /** 最近一次采样算出的真实位移(px)。正 = 向下滚（内容向上走）。 */
    var lastDeltaPx: Float = 0f
        private set

    /** 最近一次采样算出的速度(px/s)，已做 EMA 平滑。 */
    var velocityPxPerSec: Float = 0f
        private set

    /**
     * 喂一次滚动采样。
     *
     * @param index 首项下标（`firstVisibleItemIndex`）
     * @param offset 首项内偏移（`firstVisibleItemScrollOffset`）
     * @param visibleSizes 可见项的（下标, 高度px）列表，用于换行补偿
     * @param nowNanos 当前时间戳（纳秒）
     * @return 扫掠量
     */
    fun onScroll(
        index: Int,
        offset: Int,
        visibleSizes: List<Pair<Int, Int>>,
        nowNanos: Long
    ): Float {
        visibleSizes.forEach { (i, h) -> heightByIndex[i] = h }

        if (!started) {
            started = true
            lastIndex = index
            lastOffset = offset
            lastNanos = nowNanos
            lastDeltaPx = 0f
            velocityPxPerSec = 0f
            sweep = 0f
            return sweep
        }

        val delta = when {
            index == lastIndex -> (offset - lastOffset).toFloat()
            index > lastIndex -> {
                // 向下滚：跨过 lastIndex .. index-1 这些行
                var crossed = 0f
                for (k in lastIndex until index) crossed += height(k)
                crossed + (offset - lastOffset).toFloat()
            }
            else -> {
                // 向上滚：跨过 index .. lastIndex-1 这些行
                var crossed = 0f
                for (k in index until lastIndex) crossed += height(k)
                -crossed + (offset - lastOffset).toFloat()
            }
        }

        val dtSec = (nowNanos - lastNanos) / 1_000_000_000f
        val instant = if (dtSec > 0f) delta / dtSec else 0f

        lastIndex = index
        lastOffset = offset
        lastNanos = nowNanos
        lastDeltaPx = delta

        emaVel += (instant - emaVel) * velocityEma
        velocityPxPerSec = emaVel
        sweep = (emaVel / referenceVel).coerceIn(-1f, 1f)

        // 高度表会随列表变长：只保留近期项，别让它无限涨
        if (heightByIndex.size > 512) {
            val cutoff = index - 64
            heightByIndex.keys.filter { it < cutoff }.forEach { heightByIndex.remove(it) }
        }

        return sweep
    }

    /** 跨行补偿用某行的高度；查不到就用已知项的平均高度兜底。 */
    private fun height(index: Int): Float {
        heightByIndex[index]?.let { return it.toFloat() }
        val known = heightByIndex.values
        return if (known.isEmpty()) 0f else known.sum().toFloat() / known.size
    }

    /** 复位（离开页面 / 重新进入时调用）。 */
    fun reset() {
        started = false
        lastIndex = -1
        lastOffset = 0
        lastNanos = 0L
        emaVel = 0f
        sweep = 0f
        lastDeltaPx = 0f
        velocityPxPerSec = 0f
    }
}

/**
 * 把一个列表的滚动接到玻璃光相上。
 *
 * 在**明细分页**等主列表处调用一次即可。页面离开时自动归零，
 * 免得把"上一次滚动的姿态"带到别的页面。
 *
 * @param listState 目标列表的滚动状态
 */
@Composable
fun GlassGlowDriver(listState: LazyListState) {
    val controller = LocalGlassGlow.current
    val engine = remember { GlassGlowEngine() }
    val target = remember { mutableFloatStateOf(0f) }

    // 订阅 QA 钉死值：改变它时驱动自身重组 → SideEffect 重新 publish，把钉死值写下去。
    // 不读它的话，列表静止时驱动不会重组，钉死值永远下不去。
    val forced = GlassGlowDebug.forced.value

    // 速度 → 目标扫掠量，用弹簧跟随：
    //  - 滚动中：目标跟着速度走，弹簧给出"液态"的迟滞
    //  - 停止后：目标归 0，弹簧把高光荡回原位
    val smoothed by animateFloatAsState(
        targetValue = target.floatValue,
        animationSpec = spring(
            dampingRatio = 0.9f,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "glassGlow"
    )

    LaunchedEffect(listState) {
        snapshotFlow {
            Triple(
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset,
                listState.isScrollInProgress
            )
        }.collect { (index, offset, scrolling) ->
            val sizes = listState.layoutInfo.visibleItemsInfo.map { it.index to it.size }
            val v = engine.onScroll(index, offset, sizes, System.nanoTime())
            // 甩动结束时 isScrollInProgress 变 false → 目标归 0 → 高光荡回原位
            target.floatValue = if (scrolling) v else 0f
        }
    }

    // 绘制阶段用的最终值：组合期只写状态，玻璃那边在 draw lambda 里读。
    SideEffect {
        controller.publish(smoothed)
    }

    DisposableEffect(listState) {
        onDispose {
            engine.reset()
            target.floatValue = 0f
            controller.publish(0f)
        }
    }
}
