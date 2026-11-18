package com.foldledger.ui.theme

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foldledger.ui.anim.clickableScale
import kotlin.math.roundToInt
import com.foldledger.ui.l10n.t

/**
 * V13「液态玻璃 / Liquid Glass」材质引擎 —— 严格对标 iOS 26 系统控件。
 *
 * ### 这一版和 v12 的差别
 *
 * v12 已经把"磨砂"改成了"薄玻璃"，但真机上看仍然偏**塑料**：它的层次太"信封式"，
 * 一层薄膜 + 一条白边。苹果拨号界面那颗圆钮真正让人感觉"是玻璃"的，是**边缘**：
 * 光从棱上折进来，在靠近轮廓的一圈形成**一条很窄、很亮的斜面**，然后才是中间那块
 * 几乎透明的区域。也就是说——
 *
 * > 玻璃的"厚度"不在填充里，在**边界往里 3~5dp 的那一圈**。
 *
 * 所以这一版把膜的透明度再降一档（亮色 26% → **13%**），把省下来的视觉重量全部投到边缘：
 *
 * | 层 | 作用 | 参数（亮色） |
 * |---|---|---|
 * | ① 薄膜 | 只给"这里有一层介质"，颜色全靠背后透上来 | 白 13% → 7%（上厚下薄） |
 * | ② 洗色 | 语义色只改色温不改明度 | accent 5% |
 * | ③ **折射带** | 光在棱上折射后向内衰减的亮带，玻璃的灵魂 | 白 21%→0，**5dp 内衰减** |
 * | ④ **高光切面** | 被切出来的那道白色斜棱，锐、窄、只在左上 | 白 96%，1.1dp |
 * | ⑤ 曲面反射 | 顶面一块椭圆形的漫反射，把"平面"变成"曲面" | 白 5%，半径 0.86 宽 |
 * | ⑥ 下缘焦散 | 内容在底边被压缩堆积，聚成一条极暗的线 | 墨 4% |
 * | ⑦ 虹彩 | 色散只出现在**左上角那段弧**上 | 青→紫，20% |
 *
 * > ⚠️ v13.1 对照光学参考图后的两处修正：
 * > 1. **膜再砍一半到 13%**。26% 仍能看出"一层淡白的壳"，而参考图里玻璃中间几乎是空的。
 * >    （参考图实测只有 4~5%，但它的底是中性灰纸；我们的底是 #E8EDF6 近白，
 * >    照抄 5% 会让所有卡片散成"浮在渐变上的一片字"。13% 是这套浅底能承受的下限。）
 * > 2. **折射带必须是"向内衰减的带"，不是一条描边**。`Stroke` 只能沿周长变亮变暗，
 * >    垂直方向是等亮硬收尾的 —— 那就是"给卡片描了一圈白边"。所以要叠多道同心描边。
 *
 * ### 三条必须守住的物理
 *
 * 1. **薄膜绝不能再加厚**。厚一点，背景就被糊掉，立刻退回"毛玻璃"。
 *    要让人看清玻璃后面是什么 —— 这才是 iOS 液态玻璃和玻璃拟态的分水岭。
 * 2. **上亮下暗的落差必须存在**。棱边梯度从左上 96% 一路掉到右下 18%，
 *    落差不够，整块玻璃就是一张贴纸。
 * 3. **阴影是"光不走直线"的副产品，不是装饰**。三层叠加总透明度不超过 6%，
 *    扩散半径大于圆角，接触处几乎没有硬边。
 *
 * ### 兼容说明
 * 对外函数名全部保留（`glassSurface` / `glassCardColor` / `AuroraBackdrop` / …），
 * 三十多个页面不需要改一行就换到新语言。
 */

// ============================================================
//  环境：底色 + 方向光 + 颗粒（v13.9 起不再有四团色光）
// ============================================================

/**
 * ⚠️ v13.9：**四团漂移色光整层删除**（连带「环境光晕」那个开关一起退役）。
 *
 * 删它的四个理由，按重要性排：
 *
 * 1. **它没有换来可读的信息**。四团光的 alpha 标定在 0.14~0.26，单帧里根本认不出
 *    那是"光"；设计意图写的是"盯着看三秒能感觉到活"，但用户不会盯着背景看三秒 ——
 *    他只是付完钱回来看金额。一个需要"被观察"才存在的装饰，性价比是负的。
 * 2. **它在稀释色温**。暖底上薄薄罩一层冷蓝 / 藕荷，两色温对冲出来的就是中性灰 ——
 *    这正是前面三个版本反复返工的那个"整体太灰"的源头之一。冷暖对比现在全部由
 *    底色渐变自己承担（冷白顶 → 暖米白 → 砂影底，暖:冷 ≥ 2:1），玻璃膜下面就是
 *    真正的底，不用再透过一层雾去看它。
 * 3. **它每帧都要钱**。4 次全屏径向渐变 × 60/120fps，光路是整屏的；
 *    而且 `rememberInfiniteTransition` 是一个永不结束的动画 —— 只要屏幕亮着，
 *    Choreographer 就一直有人在排队下一帧。帐本类 App 停在明细页看三秒是常态。
 * 4. **它的开关是一个"看起来有、实际没差"的选择题**。关掉只改"动不动"，
 *    底色、颗粒、明度一模一样 —— 这种开关只会让人怀疑自己没点中。
 *
 * 下面这两层**保留**，因为它们不是装饰，是结构：没有方向光，玻璃的上沿高光就
 * 没有来处；没有底部压沉，玻璃的下缘会和上缘一样亮，厚度感立刻消失。
 */

/**
 * 环境背景（v11 的纸纹 → v13 的液态玻璃底 → v13.9 的"纯底色 + 方向光"）。
 *
 * 只剩三层，每一层都有明确职责：
 *   1. **底色渐变** [backgroundBrush]：冷白顶 → 暖米白 → 砂影底，
 *      同一屏里同时存在两种色温，这是"不灰"的唯一来源；
 *   2. **顶部主光**：环境里唯一有方向的东西，玻璃上沿的白棱靠它才有来处；
 *   3. **底部压沉** + 一层 2% 确定性颗粒（颗粒用于消除大气氛渐变的色带，不是"质感"）。
 *
 * 全部是静态绘制 —— 没有动画、没有无限 transition，滚动与重组都不重算。
 */
@Composable
fun AuroraBackdrop(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val dark = LocalDarkTheme.current
    // 颗粒色跟着底走：暖底用暖墨，冷墨会在浅色面上泛出一层蓝点（放大能看见）。
    val grainColor = if (dark) Color.White else Color(0xFF2A2620)

    Box(
        modifier
            .fillMaxSize()
            .background(backgroundBrush())
            .drawBehind {
                // 顶部主光：环境里唯一有方向的东西，玻璃的上沿高光靠它才成立
                drawRect(
                    brush = Brush.radialGradient(
                        colorStops = arrayOf(
                            // ⚠️ v13.3：亮色 0.30 → 0.20，0.55 处 0.10 → 0.06。
                            // 这层 30% 的白会变成"全屏最白的一块"，顶栏后面直接推成纯白，
                            // 标题和它上面的卡片都没了底色可依。
                            0.0f to Color.White.copy(alpha = if (dark) 0.09f else 0.20f),
                            0.55f to Color.White.copy(alpha = if (dark) 0.025f else 0.06f),
                            1.0f to Color.Transparent
                        ),
                        center = Offset(size.width * 0.30f, -size.height * 0.08f),
                        radius = size.width * 1.10f
                    )
                )
                // 底部压沉：让"上面有光、下面没有"这件事成立。
                // 没有这一层，玻璃的下缘会亮得像上缘，厚度感立刻消失。
                // ⚠️ v13.2：压沉的色相从"蓝灰"改成"暖墨" —— 暖底上压一层冷灰，
                // 底边会泛出一条发青的脏边（真机上很明显）。
                drawRect(
                    brush = Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.62f to Color.Transparent,
                            1.0f to (if (dark) Color.Black else Color(0xFF4A443B))
                                .copy(alpha = 0.055f)
                        )
                    )
                )
            }
            .dither(grainColor, if (dark) 0.030f else 0.020f)
    ) {
        Box(Modifier.fillMaxSize(), content = content)
    }
}

/**
 * 极细颗粒（抗色带）。每个屏幕尺寸只算一次点集，滚动与重组都不重算。
 */
private fun Modifier.dither(color: Color, alpha: Float): Modifier = this.drawWithCache {
    val count = ((size.width * size.height) / 3000f).roundToInt().coerceIn(300, 2200)
    val rnd = kotlin.random.Random(20251226)
    val points = ArrayList<Offset>(count)
    repeat(count) {
        points.add(Offset(rnd.nextFloat() * size.width, rnd.nextFloat() * size.height))
    }
    onDrawBehind {
        drawPoints(
            points = points,
            pointMode = PointMode.Points,
            color = color.copy(alpha = alpha),
            strokeWidth = 1.5f,
            cap = StrokeCap.Round
        )
    }
}

// ============================================================
//  玻璃的基础色
// ============================================================

/**
 * 面板（顶栏）刷。
 *
 * 不是实色 —— 是一层**从白到几乎透明**的膜：靠近状态栏那一侧稍实（保护可读性），
 * 往下渐渐透出背景。iOS 的大标题栏在未滚动时就是这个状态：
 * 你能看见标题后面那层光，但一个字都不会被吃掉。
 */
@Composable
fun glassPanelBrush(): Brush {
    val dark = LocalDarkTheme.current
    // ⚠️ v13.1：0.46 → 0.34。膜的基准从 26% 砍到 13% 之后，顶栏这 46%
    // 会变成全应用最"厚"的一块板，一眼就能看出它和卡片不是同一种材质。
    // 顶栏仍然要比卡片实（它下面有大标题在滚），但只能实"一档"，不能实四倍。
    return Brush.verticalGradient(
        listOf(
            Color.White.copy(alpha = if (dark) 0.045f else 0.34f),
            Color.White.copy(alpha = if (dark) 0.012f else 0.13f)
        )
    )
}

/**
 * 卡片玻璃：亮色 26% 白（74% 环境透过来），深色 6% 白。
 *
 * ⚠️ v13 从 44% 降到 26% —— 这是"高透"的关键一档。
 * 上一版在真机上仍然像"淡淡的白卡片"，原因就是填充太实。iOS 的玻璃卡片
 * 能让你**清楚地看见背后壁纸的纹理**，白只占两成多。
 */
@Composable
fun glassCardColor(): Color = Color.White.copy(alpha = if (LocalDarkTheme.current) 0.065f else 0.175f)
// ⚠️ v13.1：0.26 → 0.10。这是对照光学参考图之后的第二次下调。
// 26% 在真机上仍然能看出一层"淡白的壳"，而参考图里玻璃**中间几乎是空的** ——
// 白纸上压一块玻璃，透过它看纸，纸的明度只升高一点点（不超过一成），
// 纹理和对比度完全不变。玻璃的存在感全部来自边缘那 5dp。
// 暗场的"透"不能用同样的白度衡量：黑底上 5% 白几乎不可见，卡片会只剩一圈棱，
// 所以暗色保留 6.2%（比 26% 时代的 7.5% 更低，但仍在"能看出有一层介质"的临界线以上）。
//
// ⚠️⚠️ v13.3 亮色回到 0.155 —— 这是**用真机截图算出来的**，不是审美回调。
// 0.13 压在 v13.2 的 #F4F2ED 上合成 #F5F4EF，和底的对比度 **1.02:1**：
// 那已经不是"薄"，是"没有"。整屏失層的根因就在这里，不在配色。
// 现在 0.155 压在新底 #EDEAE3 上合成 #F0EDE7（L\* 94.9），对底 1.05:1，
// 再叠投影 → 眼睛能抓到边界。**膜的绝对值不重要，"相对底的明度差"才重要。**

/** 面板玻璃（底栏 / 浮空条）：面积大，再透一点 */
@Composable
fun glassPanelColor(): Color = Color.White.copy(alpha = if (LocalDarkTheme.current) 0.052f else 0.105f)

/** 键帽玻璃：数字键盘的每一格 */
@Composable
fun glassKeyColor(): Color = Color.White.copy(alpha = if (LocalDarkTheme.current) 0.056f else 0.100f)

/**
 * 列表行的膜（v13.2）。
 *
 * 行是"薄"的：高约 58dp，一张卡片上只能显示 2~3 条边。如果膜按卡片的 13% 给，
 * 一条行在长列表里会连成一片白条（行的上边挨着上一行的下边）。
 * 所以行的膜比卡片**再低一档** —— 它靠边缘那 5dp 的折射带立住，
 * 不靠填充。暗色同理，但不能低于 5%：黑底上再低就只剩一圈棱，行会散掉。
 */
@Composable
fun glassRowColor(): Color = Color.White.copy(alpha = if (LocalDarkTheme.current) 0.058f else 0.185f)
// ⚠️ v13.4：亮 15.5% → 18.5%，暗 5.2% → 5.8%。
// 原因是行原来挂 2dp 阴影，系统阴影的 alpha 是常量（3.2%/4.8%）不随 elevation 缩，
// 于是那道影从半透明的行面底下透上来，把行压得**比行间缝隙还暗**（实测 #E3E0D9 vs #E7E4DC）。
// 行不再是"一片浮起来的玻璃"，而是"一块比纸更脏的补丁"。
// 现在行只留 1dp 阴影（半径贴边，当轮廓用）+ 膜提一档，行面才真正亮于缝隙。

/**
 * 玻璃描边。
 *
 * 注意：这里返回的是**白**，不是灰。玻璃的边界是"受光的棱"，不是"画的线"。
 * 用灰色描边会立刻掉进"卡片贴边框"的廉价感里。
 */
@Composable
fun glassBorder(width: Dp = 1.dp): BorderStroke = BorderStroke(
    width,
    Color.White.copy(alpha = if (LocalDarkTheme.current) 0.14f else 0.62f)
)

/** 旧接口：顶面高光刷（单独用它的地方很少，主要给自定义外壳） */
@Composable
fun glassTopSheen(): Brush {
    val dark = LocalDarkTheme.current
    return Brush.verticalGradient(
        colorStops = arrayOf(
            0.0f to Color.White.copy(alpha = if (dark) 0.08f else 0.26f),
            0.42f to Color.Transparent,
            1.0f to Color.Transparent
        )
    )
}

// ============================================================
//  绘制工具
// ============================================================

/** 从 Shape 里取圆角像素值（只认 [RoundedCornerShape]，其他形状退化为直角） */
private fun DrawScope.cornerRadiusPx(shape: Shape): Float {
    val rounded = shape as? RoundedCornerShape ?: return 0f
    return rounded.topStart.toPx(size, this)
}

/**
 * 折射带的**向内衰减包络**（0 = 最外圈，最后一个 = 最里圈）。
 *
 * 光学参考图给出的实测读数：白棱之下，亮度在 1.5dp 内掉掉一半，到 5dp 基本归零。
 * 四个采样点足够 —— 再多也只是把同一条曲线画得更贵。
 */
private val EdgeEnvelope = floatArrayOf(1f, 0.62f, 0.30f, 0.10f)

/**
 * 沿内壁描一圈（stroke 半宽内缩，保证笔画完整落在裁剪区内）。
 *
 * ⚠️ `Stroke` 是以路径为中心线向两侧摊开的：直接对整块尺寸描边，一半会被 clip 切掉，
 * 于是"4.5dp 的折射环"实际只剩 2.25dp，边缘看起来就"薄"了。所有边缘层都必须内缩半宽。
 */
private fun DrawScope.insetStroke(
    brush: Brush,
    corner: CornerRadius,
    widthPx: Float,
    insetPx: Float = widthPx / 2f,
    extendPx: Float = 0f
) {
    val effective = widthPx + extendPx * 2f
    val inset = (insetPx - extendPx).coerceAtLeast(0f)
    drawRoundRect(
        brush = brush,
        topLeft = Offset(inset, inset),
        size = Size(
            (size.width - inset * 2f).coerceAtLeast(0f),
            (size.height - inset * 2f).coerceAtLeast(0f)
        ),
        cornerRadius = CornerRadius((corner.x - inset).coerceAtLeast(0f), (corner.y - inset).coerceAtLeast(0f)),
        style = Stroke(width = effective)
    )
}

/**
 * 玻璃的投影。
 *
 * ⚠️ 这里**必须用系统阴影**（[androidx.compose.ui.draw.shadow]），不能自己画。
 * 早期版本用三层同心圆角矩形叠加来"模拟"扩散，实际效果是三道硬边套在一起 ——
 * 放大看就是一圈灰色描边，玻璃立刻变成"贴了边框的卡片"。系统阴影是平台真模糊，
 * 边缘是渐隐的，这才叫"柔"。
 *
 * 参数上只做两件事：**透明度压在 8~10%**（玻璃轻，影不能重）、
 * **扩散大于偏移**（光从上方漫射，接触处几乎没有硬影）。
 *
 * ⚠️ v13.3：亮色从 0.032/0.048 提到 0.078/0.105。
 * 3% 级投影在浅底上等于没有 —— 卡片只能靠边缘那 5dp 立住，而边缘在 440dpi 上
 * 只有 6~7 个物理像素，远看就散了。提到 8~10% 之后它仍然是"极软"的（elevation 3dp），
 * 但给了卡片**一颗接地气的暗** —— 这是"一个方块"和"浮着的一层白雾"的分界。
 * 深色不动：暗底上影子本来就不靠 alpha 取胜，靠的是卡片比底亮。
 */
@Composable
private fun Modifier.glassShadow(elevation: Dp, shape: Shape): Modifier {
    if (elevation <= 0.dp) return this
    val dark = LocalDarkTheme.current
    val ink = if (dark) Color.Black else Color(0xFF241A10)
    return this.shadow(
        elevation = elevation,
        shape = shape,
        clip = false,
        ambientColor = ink.copy(alpha = if (dark) 0.24f else 0.078f),
        spotColor = ink.copy(alpha = if (dark) 0.28f else 0.105f)
    )
}

// ============================================================
//  Glass 2.0 · 景深令牌（depth elevation tokens）
// ============================================================

/**
 * 玻璃的"厚度 / 受光 / 边界"应当随它在层级里的**深度**走，而不是每个调用点各写一组
 * 魔法数字。这里把深度离散成几档，每档给出一套经真机校准的 film/specular/edgeInk/elevation：
 *
 *  - **Surface** 完全复刻 v13 的默认卡（玻璃卡 17.5% 白），作为默认档 → **零回归**；
 *  - **Panel**   复刻底栏胶囊（透明玻璃 10%/22% + edgeInk 2.0 + elevation 16 + filmDepth 1）；
 *  - **Overlay** 弹层（近不透明白 88%/92%，压住背后内容）；
 *  - **Sheet**   大面板（介于卡与弹层之间）；
 *  - **Key**     圆键（与主行动键同源的高受光）。
 *
 * ⚠️ 这些数值与 `glassCardColor` / `bottomBarGlassColor` / `overlaySurface` 同源；
 * 改动必须回真机复测三条对比门槛（卡/底≥1.10、语义/底≥4.5、三级字≥4.0）。
 */
enum class GlassDepth { Surface, Panel, Overlay, Sheet, Key }

private data class GlassTier(
    val fill: Color,
    val specular: Float,
    val edgeInk: Float,
    val elevation: Dp,
    val filmDepth: Float
)

@Composable
private fun glassTier(depth: GlassDepth): GlassTier {
    val dark = LocalDarkTheme.current
    return when (depth) {
        GlassDepth.Surface -> GlassTier(
            fill = glassCardColor(),
            specular = 1f, edgeInk = 1f, elevation = 3.dp, filmDepth = 0f
        )
        GlassDepth.Panel -> GlassTier(
            // 复刻 bottomBarGlassColor：亮 10% / 暗 8% 白，透明玻璃，边界靠 edgeInk 立住
            fill = if (dark) Color.White.copy(alpha = 0.08f) else Color(0xFFF7F5F0).copy(alpha = 0.10f),
            specular = 1f, edgeInk = 2.0f, elevation = 16.dp, filmDepth = 1f
        )
        GlassDepth.Overlay -> GlassTier(
            fill = overlaySurface(),
            specular = 0.85f, edgeInk = 2.0f, elevation = 6.dp, filmDepth = 1f
        )
        GlassDepth.Sheet -> GlassTier(
            fill = if (dark) Color(0xFF1A1D23).copy(alpha = 0.90f) else Color(0xFFFCFBF8).copy(alpha = 0.86f),
            specular = 0.9f, edgeInk = 1.8f, elevation = 8.dp, filmDepth = 1f
        )
        GlassDepth.Key -> GlassTier(
            fill = if (dark) Color.White.copy(alpha = 0.06f) else Color.White.copy(alpha = 0.085f),
            specular = 1.15f, edgeInk = 1.2f, elevation = 14.dp, filmDepth = 0f
        )
    }
}

/**
 * 玻璃滚动光相（Glass 2.0）：把 0..1 的循环相位折成 -1..1 的扫掠量。
 * 仅 [scrollAware] 的玻璃读取 [LocalGlassGlow]，长列表的行不读，避免每帧重绘。
 * 必须在 @Composable 上下文内读取（drawWithContent 的 lambda 不是 Composable）。
 */
// ============================================================
//  材质
// ============================================================

/**
 * 液态玻璃材质。
 *
 * 绘制顺序（每一层都必要，去掉任何一层都会"不玻璃"）：
 *  ⓪ 投影（在裁剪之前画，才能溢出圆角）
 *  ① 薄膜 —— 竖向极轻的梯度（上略厚、下略薄），"光从上面来"
 *  ② 语义色洗色
 *  ③ 曲面反射 —— 顶面一块椭圆漫反射，把平面读成曲面（画在带之前，不能糊住棱）
 *  ④ **折射带** —— 向内衰减的亮带，5dp 内归零，**只在上沿与左沿明显**
 *  ⑤ 下缘焦散 —— 紧贴白棱内侧一条极暗的压缩线（整圈极淡、底部加重）
 *  ⑥ 虹彩 —— 青→紫→粉，**只出现在左上角那一段弧**，画在白棱之前（外侧）
 *  ⑦ 高光切面 —— 1.1dp 的白棱，只在上左，最亮 96%
 *
 * @param fill    膜的颜色。默认 [glassCardColor]；弹层传 [overlaySurface]。
 * @param tint    语义色（选中的账本、报销状态等），以极低透明度洗进膜里。
 * @param specular 高光强度倍率（0 = 关闭，用于纯功能性容器）。
 * @param filmDepth 底部白膜的"压平"程度（0 = 上厚下薄；1 = 厚度一路压到底，
 *                 用于下方有内容滑过的浮层，否则会透出文字鬼影）。
 * @param edgeInk  ⑤ 内侧暗线的倍率（1 = 内容卡档位）。近白底上的浮层必须给足，
 *                 详见下方 ⑤ 的注释。
 */
@Composable
fun Modifier.glassSurface(
    shape: Shape,
    depth: GlassDepth = GlassDepth.Surface,
    elevation: Dp = 3.dp,
    fill: Color = glassCardColor(),
    tint: Color? = null,
    specular: Float = 1f,
    showEdge: Boolean = true,
    filmDepth: Float = 0f,
    edgeInk: Float = 1f,
    /** Glass 2.0：读取 [LocalGlassGlow] 相位让高光随滚动流动（液态感）。仅 shell 级玻璃设为 true */
    scrollAware: Boolean = false
): Modifier {
    val dark = LocalDarkTheme.current
    val density = LocalDensity.current
    // 景深令牌：depth != Surface 时用令牌表覆盖各材质参数（默认档 == 当前数值，零回归）
    val tier = if (depth != GlassDepth.Surface) glassTier(depth) else null
    val effFill = tier?.fill ?: fill
    val effSpecular = tier?.specular ?: specular
    val effEdgeInk = tier?.edgeInk ?: edgeInk
    val effElevation = tier?.elevation ?: elevation
    val effFilmDepth = tier?.filmDepth ?: filmDepth
    // Glass 2.0：滚动扫掠量 sweep ∈ [-1,1]。
    //
    // ⚠️ **只能在绘制阶段读值**。这里如果读 `LocalGlassGlow.current.sweep.floatValue`，
    // 组合期就会订阅这个状态 → 滚动时**整条底栏每帧重组**（而不只是重绘那一块玻璃）。
    // 正确做法：组合期只取**控制器引用**（引用稳定，不触发重组），
    // 真正读值放进 drawBehind / drawWithContent 的 lambda 里 —— 那儿是绘制阶段，
    // 状态变化只让这一块玻璃重绘。
    val glowController = if (scrollAware) LocalGlassGlow.current else null
    // 白棱：1.1dp。再宽就不是"棱"而是"边框"了。
    val chamferPx = with(density) { 1.1.dp.toPx() }
    // 色散：3dp 的环 —— 外半边被 clip 切掉、内半边被白棱盖住，实际只露一线。
    val fringePx = with(density) { 3.dp.toPx() }
    // 折射带：5dp 宽，带内再切 4 道同心描边（见 [EdgeEnvelope]）。
    val bandPx = with(density) { 5.dp.toPx() }
    val bandStepPx = bandPx / (EdgeEnvelope.size - 1).toFloat()
    // 下缘焦散：紧贴白棱往里的一条暗线。
    val causticPx = with(density) { 2.4.dp.toPx() }

    // 上厚下薄：光从上方来，玻璃的上缘迎着光更实、下缘更透（能看见背后的颜色沉下去）。
    // 这一条如果写反（下厚上薄），整块玻璃立刻变成"渐变的塑料片"。
    // ⚠️ 落差系数 1.28 / 0.55 是 v13.1 第三次标定的：v12 的 1.62/0.46 → v13 的 1.45/0.62 → 现在。
    // 膜本身已经很薄了，落差再大就变成"上半张白布、下半张全透"，反而露馅。
    val topAlpha = (effFill.alpha * 1.28f).coerceAtMost(1f)
    val filmTop = effFill.copy(alpha = topAlpha)
    val depth = effFilmDepth.coerceIn(0f, 1f)
    val baseBottom = effFill.alpha * 0.55f
    val filmBottom = effFill.copy(alpha = baseBottom + (topAlpha * 0.94f - baseBottom) * depth)

    // Glass 2.0：白棱亮度随滚动扫掠量轻微起伏（±6%），制造"液态"呼吸。
    // 膜 alpha 不变，三条对比门槛不受影响。
    //
    // ⚠️ rimWhite 必须在**绘制阶段**算（下面 drawWithContent 里），不能在组合期算：
    // 组合期算出来的值被 lambda 捕获，扫掠量变了它不会跟着变 —— 效果直接失效。
    val rimFade = if (dark) 0.08f else 0.12f
    val inkEdge = if (dark) Color.White else Color(0xFF16202E)
    // 白棱基准亮度（暗/亮），绘制期再乘扫掠系数
    val rimBase = if (dark) 0.84f else 0.96f

    return this
        .glassShadow(elevation = if (showEdge) effElevation else 0.dp, shape = shape)
        .clip(shape)
        .drawBehind {
            val cr = CornerRadius(cornerRadiusPx(shape))
            // Glass 2.0：绘制阶段才读扫掠量 —— 状态变化只让这块玻璃重绘，不重组整条底栏
            val sweep = glowController?.sweep?.floatValue ?: 0f

            // ① 薄膜 —— 极轻。这一层只负责"这里有一层介质"，不负责好看。
            drawRoundRect(
                brush = Brush.verticalGradient(listOf(filmTop, filmBottom)),
                cornerRadius = cr
            )

            // ①b 体色温差 —— 也就是"光穿过玻璃介质之后色温变了"。
            //
            // 这是"背景透过玻璃发生**轻微光学扭曲**"在纯 Canvas 上唯一诚实的做法：
            // 我们拿不到背后的像素去真的掰弯它，但可以让玻璃**自己**带一点点
            // 冷头暖尾的偏移 —— 视线读到的不是"背景被模糊了"，而是"这层介质
            // 稍微改变了光的颜色"。3% 以下，任何再高都会变成"彩色玻璃"。
            //
            // ⚠️ 方向必须和光源一致：光从左上进来，所以冷在左上、暖在右下。
            //    反过来写，人会本能觉得"光从右下打过来"，整屏就歪了。
            drawRoundRect(
                brush = Brush.linearGradient(
                    colorStops = arrayOf(
                        0.0f to Color(0xFFBFD8F0).copy(alpha = if (dark) 0.022f else 0.030f),
                        0.46f to Color.Transparent,
                        1.0f to Color(0xFFEBD5C4).copy(alpha = if (dark) 0.016f else 0.024f)
                    ),
                    start = Offset.Zero,
                    end = Offset(size.width, size.height)
                ),
                cornerRadius = cr
            )

            // ② 语义色洗色：只改色温，不改明度
            if (tint != null) {
                drawRoundRect(
                    brush = Brush.linearGradient(
                        colorStops = arrayOf(
                            0.0f to tint.copy(alpha = if (dark) 0.085f else 0.055f),
                            1.0f to tint.copy(alpha = if (dark) 0.030f else 0.018f)
                        ),
                        start = Offset.Zero,
                        end = Offset(size.width, size.height)
                    ),
                    cornerRadius = cr
                )
            }

            // ③ 曲面反射：一枚椭圆形的漫反射落在顶面偏左。
            //    它是"平面 → 曲面"的开关：没有它，玻璃是平的；有了它，
            //    视线会认为这块表面朝左上微微鼓起 —— 苹果的玻璃全是这个形状。
            //    ⚠️ 必须画在折射带**之前**：它是"面"上的东西，带是"棱"上的东西，
            //    反过来的话带的锐度会被一层雾盖掉，边缘立刻发虚。
            //    ⚠️ v13.1：0.145 → 0.052。膜薄了之后，原本合适的顶面高光会变成一片白雾。
            if (effSpecular > 0f) {
                drawRect(
                    brush = Brush.radialGradient(
                        colorStops = arrayOf(
                            0.0f to Color.White.copy(alpha = (if (dark) 0.038f else 0.052f) * effSpecular),
                            0.48f to Color.White.copy(alpha = (if (dark) 0.015f else 0.022f) * effSpecular),
                            1.0f to Color.Transparent
                        ),
                        // Glass 2.0：高光中心随滚动扫掠横向移动，让受光点"活"起来
                        center = Offset(size.width * (0.28f + 0.12f * sweep), 0f),
                        radius = size.width * (0.92f - 0.06f * kotlin.math.abs(sweep))
                    )
                )
            }

            // ④ 折射带 —— 全材质最关键的一层。
            //
            // ⚠️ 这里绝不能只画一条 Stroke。Stroke 只能沿**周长**做亮度变化
            //    （左上亮、右下暗），但垂直于棱的方向是等亮、硬收尾的 ——
            //    看起来就是"给卡片描了一圈白边"，也就是最典型的廉价玻璃。
            //
            //    真实玻璃的棱是：最外一圈接近全反射（最亮）→ 往里 1.5dp 掉掉一半
            //    → 5dp 外基本归零。所以要叠 4 道同心内缩描边，越往内越细越淡，
            //    叠出来才是一条**有梯度的带**（[EdgeEnvelope] 就是这个梯度）。
            if (showEdge) {
                val bandAlpha = (if (dark) 0.105f else 0.155f) * effSpecular.coerceAtLeast(0.25f)
                // ⚠️ 小尺寸控件（24dp 以下的圆点、chip）必须按比例收窄带。
                // 5dp 的带在 40dp 高的卡片上合理，压在 20dp 的圆点上就是左右两条带对撞、
                // 中间只剩几像素 —— 那是"描边"，不是"玻璃"。取短边的 22% 作上限。
                val stepPx = minOf(bandStepPx, minOf(size.width, size.height) * 0.22f / 3f)
                val strokePx = stepPx * 1.45f
                EdgeEnvelope.forEachIndexed { i, e ->
                    if (e <= 0f) return@forEachIndexed
                    insetStroke(
                        brush = Brush.linearGradient(
                            colorStops = arrayOf(
                                0.0f to Color.White.copy(alpha = bandAlpha * e * 1.65f),
                                0.32f to Color.White.copy(alpha = bandAlpha * e * 1.05f),
                                0.60f to Color.White.copy(alpha = bandAlpha * e * 0.55f),
                                1.0f to Color.White.copy(alpha = bandAlpha * e * 0.75f)
                            ),
                            start = Offset.Zero,
                            end = Offset(size.width, size.height)
                        ),
                        corner = cr,
                        widthPx = strokePx,
                        insetPx = i * stepPx + strokePx / 2f
                    )
                }
            }

            // ⑤ 下缘焦散：内容在底边被压缩堆积，聚成紧贴白棱的一条极暗的线。
            //
            //    ⚠️ v13.1 从"只有底部"改成"整圈极淡 + 底部加重"。
            //    原因很实际：我们的环境底是 #E8EDF6 的近白色，而白棱是白的 ——
            //    白压白，在浅底上几乎看不见，卡片会散成"浮在渐变上的一片字"。
            //    参考图其实也有这一层：白棱**内侧**紧贴一条很暗的压缩线
            //    （左上角那条青紫边就是它）。有了它，白棱才有"衬底"，
            //    在任何底色上都能立住；没有它，只能靠背景自己够深。
            //
            //    ⚠️ v13.9：新增 edgeInk 倍率，因为**浮层**处在另一种底色关系里。
            //    内容卡的背景是暖砂（#D8CDBA 一带），4.2% 的暗线已经够立住；
            //    而菜单/弹层几乎全白（popoverColor 97.5% → #F7F6F2），
            //    它底下若也是近白（浅色明细页 #EBE8E2），白棱 + 4.2% 暗线
            //    实测两端只差 (239 vs 250) 十一级 —— 面板边界在真机上"消失"，
            //    读起来就是"一块白板上浮着几行字"，而不是一片玻璃。
            //    倍率给到 2.0 才换来一条能看见的发丝边（约 8~9%）。
            if (showEdge) {
                val ink = effEdgeInk.coerceIn(0.4f, 3f)
                insetStroke(
                    brush = Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.0f to inkEdge.copy(alpha = (if (dark) 0.05f else 0.042f) * ink),
                            0.58f to inkEdge.copy(alpha = (if (dark) 0.06f else 0.046f) * ink),
                            1.0f to inkEdge.copy(alpha = (if (dark) 0.13f else 0.085f) * ink)
                        )
                    ),
                    corner = cr,
                    widthPx = causticPx,
                    insetPx = 0.35.dp.toPx() + causticPx / 2f
                )
            }
        }
        .drawWithContent {
            drawContent()
            val cr = CornerRadius(cornerRadiusPx(shape))
            // Glass 2.0：绘制阶段读扫掠量（与 drawBehind 里同一份状态）
            val sweep = glowController?.sweep?.floatValue ?: 0f
            // 白棱亮度随扫掠量 ±6% 起伏；膜 alpha 未动，三条对比门槛不受影响
            val rimWhite = rimBase * (1f + 0.06f * sweep)
            if (showEdge) {
                // ⑥ 虹彩：**只出现在左上角那一段弧**上。
                //
                // ⚠️ v13.1 从"对角线性渐变"改成"极坐标 sweep 渐变"。
                //    线性渐变会让四条边都染上颜色 —— 右边和底边出现粉/橙，
                //    成品就是"卡片贴了一圈彩色边框"，正是参考图里要避免的廉价感。
                //    sweep 的 0° 在 3 点钟、顺时针，所以左上象限 = 180°~270° = 0.50~0.75。
                //    参考图里颜色只集中在左上那一个**角**（约 205°~250°），左右两边是干净的。
                insetStroke(
                    brush = Brush.sweepGradient(
                        colorStops = arrayOf(
                            0.00f to Color.Transparent,
                            0.52f to Color.Transparent,
                            0.565f to Color(0xFF8FDAFF).copy(alpha = if (dark) 0.22f else 0.30f),
                            0.605f to Color(0xFF9E93FF).copy(alpha = if (dark) 0.20f else 0.28f),
                            0.645f to Color(0xFFC79CFF).copy(alpha = if (dark) 0.13f else 0.18f),
                            0.672f to Color(0xFFFFB3D1).copy(alpha = if (dark) 0.07f else 0.10f),
                            0.70f to Color.Transparent,
                            1.00f to Color.Transparent
                        ),
                        center = Offset(size.width / 2f, size.height / 2f)
                    ),
                    corner = cr,
                    widthPx = fringePx,
                    insetPx = 0f
                )

                // ⑦ 高光切面：整个材质里最"锐"的一层。
                //    上左 96% 白 → 右上 40% → 底部 12%。它必须窄（1.1dp）、必须亮、
                //    而且必须在色散之后画 —— 玻璃的斜棱就压在色散边的内侧。
                insetStroke(
                    brush = Brush.linearGradient(
                        colorStops = arrayOf(
                            0.0f to Color.White.copy(alpha = rimWhite),
                            0.22f to Color.White.copy(alpha = rimWhite * 0.62f),
                            0.48f to Color.White.copy(alpha = rimFade * 1.6f),
                            0.72f to Color.White.copy(alpha = rimFade),
                            1.0f to Color.White.copy(alpha = rimFade * 1.35f)
                        ),
                        start = Offset.Zero,
                        end = Offset(size.width, size.height)
                    ),
                    corner = cr,
                    widthPx = chamferPx,
                    insetPx = 0f
                )
            }
        }
}

/**
 * 小面积玻璃：**胶囊 / 小方格 / 徽标 / 小卡片**（v13.9）。
 *
 * 这一层存在的理由是一个反复出现的错误：卡片内部的小块（状态徽标、项目 chip、
 * 三格看板、入口块）以前都是 `Surface(color = 语义色 10%)` —— 一块**平涂的色片**。
 * 它和外面的玻璃卡放在一起，读起来像"卡片上贴了几张彩色便签"：
 * 没有棱、没有折射、没有厚度，只有颜色。
 *
 * 正确做法是把**语义色放进膜里**（tint 只改色温、不改明度），再让 [glassSurface]
 * 给它盖一圈棱 —— 于是它变成「一块被染过的玻璃」，而不是「一块色纸」。
 *
 * 与 [glassSurface] 的差别只有三点，都是为小尺寸服务的：
 *  - 不挂投影（`elevation = 0`）：小块的影只会让整张卡变脏；
 *  - 膜分两种（中性 = 白膜 26%，彩色 = 兑白 38% 后铺 13% × strength），
 *    见函数体里的 ① ②；
 *  - 高光略调：小面积上"边缘"是唯一的材质证据，给 1.15 倍。
 *
 * ### ⚠️ 面积和浓度必须成反比
 *
 * 13% 的膜铺在 80×28dp 的胶囊上，读作"这里有一层介质"；同一浓度铺到
 * 150×50dp 的方块上，它就不再是介质，而是一块**灰板**。
 * 实测「捕获日志」入口块（13% 主色）落在 (208,207,207) 的**纯中性灰**，
 * 而它所在的卡片是 (230,228,221) 暖白 —— 冷暖一中和，"脏灰"立刻出现，
 * 而且这块灰比卡里任何一行字都重，把"最不重要的两个诊断入口"顶成了视觉中心。
 *
 * 大面积块只留 6~7%（`strength ≈ 0.55`）：让**棱**去声明"这是玻璃"，
 * 膜只负责"这里可以点"。
 *
 * @param tint 语义色（收支色 / 待处理色 / 主色）。**不要**再额外垫一层 alpha 背景。
 * @param strength 浓度：0.55 = 大面积块，0.7 = 列表里的弱徽标，1 = 常规胶囊，
 *                 1.9~2.4 = 被选中的格子（同一块玻璃的更高浓度，不是换材质）。
 */
@Composable
fun Modifier.glassChip(
    shape: Shape = RoundedCornerShape(50),
    tint: Color? = null,
    strength: Float = 1f,
    elevation: Dp = 0.dp
): Modifier {
    val dark = LocalDarkTheme.current
    // 两种膜，各自的理由：
    //
    // ① 中性 chip（tint == null）= **白膜**。压在页面底色上时，"比底更亮"才是
    //    "一层介质"的证据。用墨做膜会掉进另一个坑：13% 墨在暖砂底上实测
    //    (208,207,207) —— 纯中性灰，冷暖一中和就是"脏"，而且读起来是
    //    "一块凹下去的灰塑料"，不是玻璃。白膜需要更高浓度才能在暖底上被感知
    //    （白压暖的差只有十几级），所以给 26%。
    //
    // ② 彩色 chip = **先把色兑白，再上膜**。不兑白的话浓度一过 20% 就直接变成
    //    一块深色标签：主色 #4A6B87 铺 24.7% 落在 (186,196,198) 的板岩灰，
    //    和"豆沙/暖砂"这套底色完全不搭。兑白 38% 之后再铺，颜色还在，
    //    但明度始终停在"淡"这一档 —— 选中靠**浓度差**表达，不靠变深。
    val film: Color
    val alpha: Float
    if (tint == null) {
        film = Color.White
        alpha = (if (dark) 0.10f else 0.26f) * strength
    } else {
        film = lerp(Color.White, tint, 0.62f)
        alpha = (if (dark) 0.18f else 0.13f) * strength
    }
    return this.glassSurface(
        shape = shape,
        elevation = elevation,
        fill = film.copy(alpha = alpha.coerceIn(0f, 0.62f)),
        specular = 1.15f * strength.coerceIn(0.7f, 1.3f)
    )
}

/**
 * 「选中胶囊」的通用修饰符（v13.9）。
 *
 * 全站一次成型的三条规则，任何"一行可选项里挑一个"的地方都该用它：
 *  1. **选中不再铺实色**。v13.8 之前是 `Surface(color = primary)` 的深蓝胶囊 /
 *     `primaryContainer` 的浅蓝块 —— 在玻璃卡里就是一片贴上去的色片，
 *     边界从"棱"退化成"贴纸边"。
 *  2. **选中 = 白玻璃 + 主色内棱 + 抬影**，未选 = 一层薄白膜（0.55 档）。
 *     三件事叠起来才读得出"这颗被选中了"：膜（浓度）、棱（色相）、影（高度）。
 *  3. **主色字只在白膜上用**。#4A6B87 在白膜 (244,242,239) 上是 4.9:1；
 *     一旦把膜染到 20% 以上就掉到 3.9:1 —— 所以"染膜"和"染字"不能同时做。
 *
 * @param shape 胶囊形状，默认全圆
 * @param ringColor 内棱颜色，默认主色
 */
@Composable
fun Modifier.glassSelectChip(
    active: Boolean,
    shape: Shape = RoundedCornerShape(50),
    ringColor: Color = MaterialTheme.colorScheme.primary
): Modifier = this
    .glassChip(
        shape = shape,
        strength = if (active) 1.0f else 0.55f,
        elevation = if (active) 3.dp else 0.dp
    )
    .then(
        if (active) Modifier.border(1.2.dp, ringColor.copy(alpha = 0.42f), shape)
        else Modifier
    )

/**
 * 按压反馈：玻璃"被按进去"。
 *
 * 不做光斑扩散（那是 v5 的液体语言，太吵）。这里只让膜轻微变实 ——
 * 指尖压住，环境光进不来。形变交给 [clickableScale]。
 */
fun Modifier.pressBloom(
    pressed: Boolean,
    color: Color,
    intensity: Float = 0.20f
): Modifier = this.drawWithContent {
    drawContent()
    if (pressed) {
        drawRect(color = color.copy(alpha = (intensity * 0.12f).coerceAtMost(0.04f)))
    }
}

// ============================================================
//  控件
// ============================================================

/**
 * 「玻璃圆键」—— 首屏「记一笔」，对标 iOS 拨号界面那颗圆钮。
 *
 * 圆形的玻璃和圆角矩形是两种材质。矩形靠"上沿一条高光 + 四边渐变"就能成立，
 * 圆形不行：圆没有"上沿"，它只有一个**凸球面**。所以这一颗的分层完全不同：
 *
 *  - 底：**径向**渐变而不是竖向 —— 中心偏左上最亮（正对光源的那一点），
 *        向外一圈圈暗下去，最后在边缘再抬一次（全反射）。
 *  - 面：一枚小半径的球形反射（左上 32%/18% 处），半径只有直径的 22%。
 *        这就是"细腻高光"——它很小、很实、位置固定。
 *  - 棱：1dp 的一圈，左上 92% 白、右下 20%，中段掉到 6%。
 *  - 虹彩：只在左上 120° 的弧上有一点点，不绕整圈 —— 绕整圈就假了。
 *
 * 按下时整颗缩到 0.93 并把高光减半（手挡住了光），松手弹回。
 */
@Composable
fun GlassKey(
    modifier: Modifier = Modifier,
    size: Dp = 62.dp,
    enabled: Boolean = true,
    /** 语义色染色：玻璃仍然是玻璃，只是t("透出来的光")带了主色的色温 */
    tint: Color? = null,
    /**
     * 染色强度倍率（v13.8）。
     *
     * 默认 1 = 上面那套标定值（0.170 / 0.110 / 0.135）。主行动键需要重一档：
     * 它左右两边没有别的东西可以作参照，只能靠自己"比旁边的玻璃更有色"来被认出。
     * 但**不能靠调不透明度上限去超**——上限压在 0.30，超过去就退回"填了一块颜色"。
     */
    tintBoost: Float = 1f,
    /** 投影染色。玻璃本身是白的，但一颗主行动键的影要有一点点主色，才t("压在光里") */
    glow: Color? = null,
    /** Glass 2.0：读取滚动光相让主行动键的棱随滚动微微流动（液态感） */
    scrollAware: Boolean = false,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    val dark = LocalDarkTheme.current
    val shadowMod = if (glow != null) {
        Modifier.shadow(
            elevation = 16.dp,
            shape = CircleShape,
            clip = false,
            ambientColor = glow.copy(alpha = if (dark) 0.24f else 0.16f),
            spotColor = glow.copy(alpha = if (dark) 0.28f else 0.20f)
        )
    } else {
        Modifier.glassShadow(elevation = 14.dp, shape = CircleShape)
    }
    // Glass 2.0：滚动扫掠量。
    // ⚠️ 组合期只取**控制器引用**（稳定，不触发重组），值在 drawWithContent 里读。
    val glowController = if (scrollAware) LocalGlassGlow.current else null
    Box(
        modifier = modifier
            .size(size)
            .then(shadowMod)
            .clip(CircleShape)
            .drawBehind {
                val d = this.size.width
                val c = Offset(d / 2f, d / 2f)
                val r = d / 2f

                // ① 薄膜：极轻。圆形没有"上沿"，所以用径向而不是竖向 ——
                //    亮心在左上 38%/30%（正对光源的那一点），向外几乎平掉。
                //    ⚠️ v13.1：0.52 → 0.085。原来那颗"亮心"其实就是**整块白**，
                //    真机上读起来是一颗乳白塑料键。参考图里的圆键中间近乎空的，
                //    存在感全在周长那一圈棱上。
                drawCircle(
                    brush = Brush.radialGradient(
                        colorStops = arrayOf(
                            0.0f to Color.White.copy(alpha = if (dark) 0.050f else 0.080f),
                            0.62f to Color.White.copy(alpha = if (dark) 0.034f else 0.055f),
                            1.0f to Color.White.copy(alpha = if (dark) 0.046f else 0.070f)
                        ),
                        center = Offset(d * 0.38f, d * 0.30f),
                        radius = d * 0.90f
                    ),
                    radius = r,
                    center = c
                )

                // ② 染色：只改色温，仍然透。暗场用"白膜 + tint"而不是把主色压暗，
                //    否则深色主题下这颗键会变成一个洞。
                if (tint != null) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colorStops = arrayOf(
                                // 主行动键要能一眼认出"这是按钮"，所以色温比普通玻璃重一档；
                                // 但它仍然是**透明的蓝玻璃**，不是填了一块蓝色（那样就退回实色了）。
                                0.0f to tint.copy(alpha = ((if (dark) 0.20f else 0.170f) * tintBoost).coerceAtMost(0.30f)),
                                0.72f to tint.copy(alpha = ((if (dark) 0.13f else 0.110f) * tintBoost).coerceAtMost(0.30f)),
                                1.0f to tint.copy(alpha = ((if (dark) 0.17f else 0.135f) * tintBoost).coerceAtMost(0.30f))
                            ),
                            center = Offset(d * 0.40f, d * 0.34f),
                            radius = d * 0.88f
                        ),
                        radius = r,
                        center = c
                    )
                }

                // ③ 折射带：和圆角矩形同一套包络（[EdgeEnvelope]），
                //    只是把"内缩矩形描边"换成"半径递减的圆"。
                //    圆没有"边"，只有周长上的一条棱 —— 所以衰减不能靠 stroke 的宽度，
                //    必须一层层画更小的圆，靠叠加做出向内衰减。
                val band = d * 0.10f
                val stepPx = band / (EdgeEnvelope.size - 1).toFloat()
                val w = stepPx * 1.45f
                EdgeEnvelope.forEachIndexed { i, e ->
                    if (e <= 0f) return@forEachIndexed
                    drawCircle(
                        brush = Brush.linearGradient(
                            colorStops = arrayOf(
                                0.0f to Color.White.copy(alpha = (if (dark) 0.10f else 0.125f) * e * 1.6f),
                                0.40f to Color.White.copy(alpha = (if (dark) 0.10f else 0.125f) * e * 0.90f),
                                1.0f to Color.White.copy(alpha = (if (dark) 0.10f else 0.125f) * e * 0.80f)
                            ),
                            start = Offset.Zero,
                            end = Offset(d, d)
                        ),
                        radius = (r - (i * stepPx + w / 2f)).coerceAtLeast(0f),
                        center = c,
                        style = Stroke(width = w)
                    )
                }

                // ④ 下缘焦散：球面下缘没有光，也是这颗键"落"在页面上的那一笔。
                drawCircle(
                    brush = Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.62f to Color.Transparent,
                            1.0f to (if (dark) Color.Black else Color(0xFF18222F))
                                .copy(alpha = if (dark) 0.10f else 0.05f)
                        )
                    ),
                    radius = r,
                    center = c
                )
            }
            .drawWithContent {
                drawContent()
                // ⚠️ 必须写 `this.size`：本函数的参数也叫 `size`（Dp），
                // 不带接收者时 Kotlin 会优先解析到那个参数，`size.width` 立刻报错。
                val d = this.size.width
                val r = d / 2f
                val stroke = 1.dp.toPx()
                // Glass 2.0：绘制阶段读扫掠量（状态变化只重绘这颗键，不重组整棵底栏树）
                val sweep = glowController?.sweep?.floatValue ?: 0f
                val rimScale = 1f + 0.06f * sweep

                // 虹彩：只给左上 130° 的弧，且极淡
                drawArc(
                    brush = Brush.sweepGradient(
                        listOf(
                            Color(0xFF8FDAFF).copy(alpha = if (dark) 0.18f else 0.26f),
                            Color(0xFFA79BFF).copy(alpha = if (dark) 0.10f else 0.16f),
                            Color.Transparent,
                            Color.Transparent
                        ),
                        center = Offset(r, r)
                    ),
                    startAngle = 168f,
                    sweepAngle = 132f,
                    useCenter = false,
                    topLeft = Offset(stroke / 2f, stroke / 2f),
                    size = Size(d - stroke, d - stroke),
                    style = Stroke(width = stroke * 1.9f)
                )
                // 白棱：左上最亮，绕到右下只剩一点点
                drawCircle(
                    brush = Brush.linearGradient(
                        colorStops = arrayOf(
                            0.0f to Color.White.copy(alpha = (if (dark) 0.58f else 0.94f) * rimScale),
                            0.34f to Color.White.copy(alpha = (if (dark) 0.13f else 0.34f) * rimScale),
                            0.68f to Color.White.copy(alpha = (if (dark) 0.07f else 0.13f) * rimScale),
                            1.0f to Color.White.copy(alpha = (if (dark) 0.11f else 0.20f) * rimScale)
                        ),
                        start = Offset.Zero,
                        end = Offset(d, d)
                    ),
                    radius = r - stroke / 2f,
                    center = Offset(r, r),
                    style = Stroke(width = stroke)
                )
                // 细腻高光：一枚小、实、位置固定的球面反射
                drawCircle(
                    brush = Brush.radialGradient(
                        colorStops = arrayOf(
                            0.0f to Color.White.copy(alpha = if (dark) 0.24f else 0.58f),
                            0.42f to Color.White.copy(alpha = if (dark) 0.10f else 0.22f),
                            1.0f to Color.Transparent
                        ),
                        center = Offset(d * 0.33f, d * 0.25f),
                        radius = d * 0.21f
                    ),
                    radius = d * 0.21f,
                    center = Offset(d * 0.33f, d * 0.25f)
                )
            }
            .clickableScale(
                enabled = enabled,
                pressedScale = 0.93f,
                haptic = com.foldledger.ui.anim.Haptic.MEDIUM,
                bloomColor = Color.White,
                bloomIntensity = 0.10f,
                clipShape = CircleShape
            ) { onClick() },
        contentAlignment = Alignment.Center,
        content = { content() }
    )
}

/**
 * 主行动按钮：「被染色的玻璃」。
 *
 * iOS 26 的 `.glassProminent` 不是实色按钮 —— 它是一块**带主色 tint 的玻璃**：
 * 主色以 ~86% 铺底，上面仍然有膜、有上沿高光、有棱边。所以它看起来"亮"，
 * 但不"塑料"。按下时缩到 0.972，高光随之偏转。
 */
@Composable
fun LiquidButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: (@Composable () -> Unit)? = null,
    height: Dp = 52.dp,
    haptic: com.foldledger.ui.anim.Haptic = com.foldledger.ui.anim.Haptic.MEDIUM
) {
    val shape = RoundedCornerShape(height / 2f)
    val dark = LocalDarkTheme.current
    val accent = MaterialTheme.colorScheme.primary
    val fg = MaterialTheme.colorScheme.onPrimary
    val base = if (dark) accent.copy(alpha = 0.86f) else accent.copy(alpha = 0.90f)
    Box(
        modifier = modifier
            .height(height)
            .glassShadow(8.dp, shape)
            .clip(shape)
            .background(base)
            .drawBehind {
                val cr = CornerRadius(size.height / 2f)
                // 上沿高光 + 下缘压深：这颗按钮自己也是一块玻璃
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.0f to Color.White.copy(alpha = 0.24f),
                            0.44f to Color.Transparent,
                            1.0f to Color.Black.copy(alpha = 0.10f)
                        )
                    ),
                    cornerRadius = cr
                )
            }
            .drawWithContent {
                drawContent()
                drawRoundRect(
                    brush = Brush.linearGradient(
                        colorStops = arrayOf(
                            0.0f to Color.White.copy(alpha = 0.52f),
                            0.48f to Color.White.copy(alpha = 0.10f),
                            1.0f to Color.White.copy(alpha = 0.26f)
                        ),
                        start = Offset.Zero,
                        end = Offset(size.width, size.height)
                    ),
                    cornerRadius = CornerRadius(size.height / 2f),
                    style = Stroke(width = 1.dp.toPx())
                )
            }
            .clickableScale(
                enabled = enabled,
                pressedScale = 0.972f,
                haptic = if (enabled) haptic else com.foldledger.ui.anim.Haptic.NONE,
                clipShape = shape
            ) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Row(
            Modifier.padding(horizontal = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (icon != null) {
                icon()
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text,
                color = if (enabled) fg else MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.2.sp
            )
        }
    }
}

/**
 * 次级按钮：**透明玻璃胶囊**。
 * 只有膜和棱边，没有填充色 —— 和主按钮并排时，一眼能分出主次。
 */
@Composable
fun GlassButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = 46.dp,
    contentColor: Color = MaterialTheme.colorScheme.onSurface
) {
    val shape = RoundedCornerShape(height / 2f)
    Box(
        modifier = modifier
            .height(height)
            .glassSurface(shape = shape, elevation = 2.dp, specular = 0.8f)
            .clickableScale(
                enabled = enabled,
                pressedScale = 0.972f,
                haptic = com.foldledger.ui.anim.Haptic.LIGHT,
                clipShape = shape
            ) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = if (enabled) contentColor else MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 20.dp)
        )
    }
}

/**
 * 按键脉冲：数字每变一次轻轻托一下（幅度 1.5%，不回弹过头）。
 */
@Composable
fun Modifier.pressPulse(trigger: Any?, peak: Float = 1.015f): Modifier {
    val anim = remember { androidx.compose.animation.core.Animatable(1f) }
    val first = remember { mutableStateOf(true) }
    androidx.compose.runtime.LaunchedEffect(trigger) {
        if (first.value) {
            first.value = false
            return@LaunchedEffect
        }
        anim.snapTo(peak)
        anim.animateTo(
            1f,
            spring(dampingRatio = 1f, stiffness = Spring.StiffnessMedium)
        )
    }
    return this.graphicsLayer {
        scaleX = anim.value
        scaleY = anim.value
    }
}

/**
 * 数字键盘的键：一小块独立玻璃。
 *
 * 玻璃语言里**每一格都需要自己的膜** —— 因为玻璃的边界由棱边高光定义，
 * 没有键帽就没有边界，12 个数字会漂在一片空里。
 * 做法：薄膜 + 内折射环 + 白棱，圆角 16。
 */
@Composable
fun TactileKey(
    label: String,
    modifier: Modifier = Modifier,
    container: Color? = null,
    content: Color = MaterialTheme.colorScheme.onSurface,
    bold: Boolean = false,
    fontSize: Int = 24,
    onPress: () -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier = modifier
            .glassSurface(
                shape = shape,
                elevation = 1.dp,
                fill = container ?: glassKeyColor(),
                specular = 0.75f
            )
            .clickableScale(
                pressedScale = 0.95f,
                haptic = com.foldledger.ui.anim.Haptic.NONE, // 触感由 onPress 统一给（强度可调）
                // v13.2：数字键也要"高光流动"。按下时一道斜光从左上掠过去，
                // 同时整块压到 95% —— 这两件事必须一起发生，只有缩放是"按了个按钮"，
                // 缩放 + 掠光才是"按了一块玻璃"。强度压得很低（0.10），
                // 数字键盘有 12 格，任何一格亮过头都会抢金额的注意力。
                bloomColor = Color.White,
                bloomIntensity = 0.10f,
                sweepOnPress = true,
                clipShape = shape
            ) { onPress() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = fontSize.sp,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Medium,
            letterSpacing = (-0.2).sp,
            color = content
        )
    }
}
