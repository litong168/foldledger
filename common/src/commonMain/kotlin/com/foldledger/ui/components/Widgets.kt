package com.foldledger.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foldledger.data.db.DayTotal
import com.foldledger.data.db.Direction
import com.foldledger.ui.anim.Motion
import com.foldledger.ui.anim.clickableScale
import com.foldledger.ui.theme.LocalDirectionColors
import com.foldledger.ui.theme.LocalDarkTheme
import com.foldledger.ui.theme.OverlineLabel
import com.foldledger.ui.theme.Radius
import com.foldledger.ui.theme.Space
import com.foldledger.util.Money
import com.foldledger.ui.theme.GlassButton
import com.foldledger.ui.theme.cardSurface
import com.foldledger.ui.theme.glassChip
import com.foldledger.ui.theme.glassSurface
import com.foldledger.ui.theme.softShadow
import com.foldledger.ui.theme.SurfaceCard

// ============================================================
//  通用小组件
// ============================================================

/**
 * 统一的区块标题。
 *
 * v11 的写法是"刊物栏目"：上面一行加宽字距的小字（overline）说明这一栏是什么，
 * 下面一行正常字重的标题。overline 从主色改成淡墨 —— 主色留给真正的可操作项，
 * 静默的标签不该抢颜色。
 */
@Composable
fun SectionHeader(
    title: String? = null,
    subtitle: String? = null,
    overline: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            if (overline != null) {
                Text(
                    overline,
                    style = OverlineLabel,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
            }
            if (title != null) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            if (subtitle != null) {
                Spacer(Modifier.height(3.dp))
                Text(
                    subtitle,
                    // v16.3.1：bodySmall 本身是 13/19，原来内联 18 覆盖会让所有卡片
                    // 副标题与其它 13sp 文字行盒不一致。交回主题统一定。
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        trailing?.invoke()
    }
}

@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    overline: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit
) {
    SurfaceCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radius.card),
        elevation = 6.dp,
        softShadow = true
    ) {
        Column(Modifier.padding(Space.cardPad)) {
            if (title != null || overline != null) {
                SectionHeader(
                    title = title,
                    subtitle = subtitle,
                    overline = overline,
                    trailing = trailing
                )
                Spacer(Modifier.height(Space.lg))
            }
            content()
        }
    }
}

/** 金额文本，自动按方向着色（跟随用户选择的收支配色习惯） */
@Composable
fun AmountText(
    cents: Long,
    direction: Int,
    modifier: Modifier = Modifier,
    fontSize: Int = 20,
    symbol: Boolean = true,
    prefix: String = "",
    colored: Boolean = true,
    fontWeight: FontWeight = FontWeight.SemiBold,
    /** 传入后直接使用这段文本（用于金额滚动动画，避免格式抖动） */
    textOverride: String? = null,
    /** v3.0.3：传入后直接使用该样式（如 [HeroNumber]），忽略上面的字号/字重 */
    style: TextStyle? = null
) {
    val dc = LocalDirectionColors.current
    val color = when {
        !colored -> MaterialTheme.colorScheme.onSurface
        direction == Direction.EXPENSE -> dc.expense
        else -> dc.income
    }
    val sign = if (prefix.isNotEmpty()) prefix else if (direction == Direction.EXPENSE) "-" else "+"
    if (style != null) {
        Text(
            text = textOverride ?: "$sign${Money.format(cents, symbol)}",
            modifier = modifier,
            color = color,
            style = style,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    } else {
        Text(
            text = textOverride ?: "$sign${Money.format(cents, symbol)}",
            modifier = modifier,
            color = color,
            style = TextStyle(
                fontSize = fontSize.sp,
                fontWeight = fontWeight,
                fontFeatureSettings = "tnum"
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 会滚动计数的金额文本。
 *
 * **为什么单独做成一个叶子 composable**：动画进度是逐帧变化的状态，
 * 谁读它就等于谁每帧重组。老写法 `animatedCurrency(...)` 是把值算在**调用方**的
 * 组合作用域里再传进去的 —— 于是一个「合计卡」每帧要整体重组一次，
 * 里面还嵌着玻璃卡片和 Canvas，掉帧就是这么来的。
 * 现在状态读取被关在这个小组件内部，每帧只有这一个 Text 重组。
 */
@Composable
fun AnimatedCentsText(
    cents: Long,
    modifier: Modifier = Modifier,
    /** 非空则按收支配色习惯着色；为空则不着色（用 [color]） */
    direction: Int? = null,
    color: Color = Color.Unspecified,
    fontSize: Int = 20,
    fontWeight: FontWeight = FontWeight.SemiBold,
    symbol: Boolean = true,
    prefix: String = "",
    textAlign: TextAlign? = null,
    /** v3.0.3：传入后直接使用该样式（如 [HeroNumber]），忽略上面的字号/字重 */
    style: TextStyle? = null
) {
    val dc = LocalDirectionColors.current
    val resolved = when {
        color != Color.Unspecified -> color
        direction == null -> MaterialTheme.colorScheme.onSurface
        direction == Direction.EXPENSE -> dc.expense
        else -> dc.income
    }
    val anim = remember { Animatable(cents.toFloat()) }
    LaunchedEffect(cents) {
        anim.animateTo(
            targetValue = cents.toFloat(),
            animationSpec = tween(Motion.SLOW, easing = Motion.fluid)
        )
    }
    val sign = when {
        prefix.isNotEmpty() -> prefix
        direction == null -> ""
        direction == Direction.EXPENSE -> "-"
        else -> "+"
    }
    if (style != null) {
        Text(
            text = sign + Money.format(anim.value.toLong(), symbol),
            modifier = modifier,
            color = resolved,
            style = style,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = textAlign
        )
    } else {
        Text(
            text = sign + Money.format(anim.value.toLong(), symbol),
            modifier = modifier,
            color = resolved,
            style = TextStyle(
                fontSize = fontSize.sp,
                fontWeight = fontWeight,
                fontFeatureSettings = "tnum"
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = textAlign
        )
    }
}

@Composable
fun LedgerAvatar(
    emoji: String,
    colorHex: String,
    size: Dp = 44.dp,
    emojiSize: Int = 20
) {
    val color = parseHexColor(colorHex)
    val dark = LocalDarkTheme.current
    val shape = RoundedCornerShape(size * 0.30f)
    // v12：账本头像是"一块染了色的小玻璃" —— 用户自选色以 20% 洗进膜里，
    // 外面仍然有棱边高光和色散。这样它和整站材质是一套，而不是贴上去的色片。
    Box(
        modifier = Modifier
            .size(size)
            .glassSurface(
                shape = shape,
                elevation = 3.dp,
                fill = if (dark) Color.White.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.52f),
                tint = color,
                specular = 0.8f
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(emoji, fontSize = emojiSize.sp)
    }
}

/**
 * 状态徽标（`待提交 ▾`、场景标签、账本标签……）。
 *
 * ⚠️ v13.9：从「一层 alpha 底色 + 1dp 描边」改成**真玻璃**（[glassChip]）。
 *
 * 旧写法在视觉上是"一张很小的彩色便利贴"：底色是平涂的 13%，边框是一条 1dp 的
 * 描边 —— 描边是"画出来的线"，而玻璃的边界是"受光折出来的棱"。两者放在同一屏上，
 * 一眼就能看出这个小块和它所在的卡片不是同一种材质。
 * 现在语义色只进膜（`fill = tint`），棱 / 折射带 / 焦散全部由材质引擎给。
 */
@Composable
fun PillTag(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    filled: Boolean = false
) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier = modifier
            .glassChip(shape = shape, tint = color, strength = if (filled) 1.7f else 0.9f)
            .padding(horizontal = 9.dp, vertical = 3.dp)
    ) {
        // maxLines = 1 必须配 overflow = Ellipsis：只写 maxLines 的话，
        // 在没有宽度上限的容器里（LazyRow 的主轴约束是无限的）文本既不会截断、也不会换行，
        // 会直接把同行后面的内容顶出去。
        Text(
            text,
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.1.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}


/**
 * 细进度条。
 *
 * v11：4dp、直角圆角、无高光无内阴影。玻璃时代它是"刻在玻璃上的槽 + 流动的液面"，
 * 现在它就是印刷表格里的一根实心条 —— 数据比质感重要。
 */
@Composable
fun ThinProgress(
    fraction: Float,
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = 5.dp
) {
    val animated by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow),
        label = "thinProgress"
    )
    val track = RoundedCornerShape(50)
    val dark = LocalDarkTheme.current
    // v12：一根"玻璃管"。轨道是凹陷的半透明白槽（上沿压暗、下沿受光），
    // 液面本身也有一道细高光 —— 这样它才像玻璃里的一段液体，而不是两条色带。
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(track)
            .background(Color.White.copy(alpha = if (dark) 0.06f else 0.30f))
            .drawWithContent {
                drawContent()
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.0f to Color(0xFF1B2A3D).copy(alpha = if (dark) 0.22f else 0.10f),
                            0.55f to Color.Transparent
                        )
                    ),
                    cornerRadius = CornerRadius(this.size.height / 2f)
                )
            }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(animated)
                .height(height)
                .clip(track)
                .background(color)
                .drawWithContent {
                    drawContent()
                    drawRoundRect(
                        brush = Brush.verticalGradient(
                            colorStops = arrayOf(
                                0.0f to Color.White.copy(alpha = 0.38f),
                                0.42f to Color.Transparent,
                                1.0f to Color.Black.copy(alpha = 0.10f)
                            )
                        ),
                        cornerRadius = CornerRadius(this.size.height / 2f)
                    )
                }
        )
    }
}

/**
 * 空态。
 *
 * ## v13.9：空态**不再自带动作按钮**
 *
 * 明细页右下角常驻一颗玻璃圆键「＋」。空账本时如果这里再放一颗「记一笔」，
 * 屏幕上就同时有两颗意思完全一样的按钮 —— 用户读到的不是"这里空着，去哪记"，
 * 而是"为什么有两个记一笔"。空态的职责只有一个：**解释为什么空**。
 * 动作交给唯一的那颗键，文案负责指路（"点右下角那颗「＋」"）。
 *
 * ## `highlight`：给空态一块玻璃托盘
 *
 * 没有玻璃的空态就是"一行 emoji + 两行字漂在渐变背景上" —— 那是全站唯一
 * 没有任何材质的界面元素。`highlight = true` 时把整块空态放进一片很薄的玻璃：
 * 膜只有一成、不挂投影，靠棱边立住。于是空页面读起来仍然是"这个应用的一片玻璃"，
 * 而不是"内容没画出来"。
 */
@Composable
fun EmptyHint(
    emoji: String,
    title: String,
    subtitle: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    /** 把空态放进一片薄玻璃里（页面级空态用；已经在大卡片里的空态不要用） */
    highlight: Boolean = false,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(Radius.card)
    val plate = if (highlight) {
        Modifier.glassSurface(
            shape = shape,
            elevation = 0.dp,
            fill = Color.White.copy(alpha = if (LocalDarkTheme.current) 0.05f else 0.12f),
            specular = 0.85f
        )
    } else {
        Modifier
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(plate)
            .padding(horizontal = 24.dp, vertical = if (highlight) 30.dp else 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(emoji, fontSize = 44.sp)
        Spacer(Modifier.height(10.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (subtitle != null) {
            Spacer(Modifier.height(5.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                lineHeight = 19.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
        // 兼容旧接口：仍有页面（"还没有账本 → 去账本页"）需要一个明确的跳转动作。
        // ⚠️ 只有**不是**"记一笔"的场景才该走到这里 —— 那颗键是常驻的。
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(14.dp))
            GlassButton(text = actionLabel, onClick = onAction)
        }
    }
}

/**
 * ⚠️ v13.9：`BrandFab` 已删除。
 *
 * 它是"圆形玻璃键 + 主色染色"的一枚预置件，唯一调用点是 [EmptyHint] 的空态按钮 ——
 * 而那个按钮在 v13.9 被拿掉了（右下角常驻的 `GlassKey` 才是唯一的「记一笔」）。
 * 首屏那颗键一直是直接用 `GlassKey(tint = accent, tintBoost = 1.3f)` 拼的，
 * 从这里再包一层只会多一份"同一颗键的两套写法"。
 */

/** 颜色插值小工具（避免调用处到处写 lerp） */
private fun lerpColor(a: Color, b: Color, t: Float): Color = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
    alpha = a.alpha + (b.alpha - a.alpha) * t
)

// ============================================================
//  图表
// ============================================================

/**
 * 环形占比图。纯 Canvas 绘制，不依赖第三方图表库。
 *
 * @param sweep v3.1.1：外部（屏幕级）驱动的扫入进度 0→1。null = 组件自持
 *   （旧行为：每次挂载播一次）。传入外部进度后，折叠/展开导致本组件重挂时
 *   不再从 0 重扫一遍 —— 那也是「切换分栏闪一下」的一环。
 */
@Composable
fun DonutChart(
    slices: List<Pair<String, Long>>,
    colors: List<Color>,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 18.dp,
    centerTitle: String? = null,
    centerValue: String? = null,
    sweep: Float? = null
) {
    val total = slices.sumOf { it.second }.coerceAtLeast(1L)
    // 首次出现时环从 0 扫到满，像 HyperOS 的加载动效
    val selfSweep = remember { Animatable(0f) }
    LaunchedEffect(sweep == null) {
        if (sweep == null) {
            selfSweep.animateTo(1f, tween(Motion.SLOW + 200, easing = Motion.fluid))
        }
    }
    val sweepValue = sweep ?: selfSweep.value
    // v9.7.3：空环轨道跟着主题走 —— 硬编码 0x1A808080 在深色主题下与面板同色，
    // 用户会以为"环根本没画出来"，看不到还剩多少没分配。
    val trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = strokeWidth.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            val topLeft = Offset(inset, inset)
            var start = -90f
            if (slices.isEmpty()) {
                drawArc(
                    color = trackColor,
                    startAngle = 0f,
                    sweepAngle = 360f * sweepValue,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Butt)
                )
            } else {
                slices.forEachIndexed { index, slice ->
                    val full = slice.second.toFloat() / total.toFloat() * 360f
                    val drawn = full * sweepValue
                    drawArc(
                        color = colors[index % colors.size],
                        startAngle = start,
                        sweepAngle = (drawn - 2f).coerceAtLeast(0.4f),
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = stroke, cap = StrokeCap.Round)
                    )
                    start += full
                }
            }
        }
        if (centerValue != null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (centerTitle != null) {
                    Text(
                        centerTitle,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    centerValue,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

/**
 * 简易柱状趋势图（按日）。
 *
 * @param grow v3.1.1：外部（屏幕级）驱动的生长进度 0→1。null = 组件自持
 *   （旧行为：数据变化 / 重挂时重播）。传入外部进度后，折叠/展开不重播。
 */
@Composable
fun TrendBars(
    days: List<DayTotal>,
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = 96.dp,
    grow: Float? = null
) {
    val max = (days.maxOfOrNull { it.totalCents } ?: 0L).coerceAtLeast(1L)
    // 柱子从底部弹性长出来
    val selfGrow = remember { Animatable(0f) }
    LaunchedEffect(days, grow == null) {
        if (grow == null) {
            selfGrow.snapTo(0f)
            selfGrow.animateTo(1f, tween(Motion.SLOW, easing = Motion.fluid))
        }
    }
    val growValue = grow ?: selfGrow.value
    if (days.isEmpty()) {
        Box(modifier = modifier.fillMaxWidth().height(height), contentAlignment = Alignment.Center) {
            Text(
                "暂无数据",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(height),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        days.forEach { d ->
            val f = (d.totalCents.toFloat() / max.toFloat()).coerceIn(0.04f, 1f) * growValue
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(height * f.coerceAtLeast(0.001f))
                    .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                    .background(color.copy(alpha = 0.85f))
            )
        }
    }
}

// ============================================================

fun parseHexColor(hex: String): Color {
    val cleaned = hex.removePrefix("#")
    return when (cleaned.length) {
        6 -> Color(("FF$cleaned").toLong(16))
        8 -> Color(cleaned.toLong(16))
        else -> Color(0xFF1D3B57)
    }
}
