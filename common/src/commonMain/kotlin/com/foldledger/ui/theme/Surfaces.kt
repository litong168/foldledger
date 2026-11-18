package com.foldledger.ui.theme

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.foldledger.ui.anim.Motion
import com.foldledger.ui.l10n.t

/**
 * 玻璃容器。
 *
 * 这一层是"材质 → 页面"的唯一通道：所有卡片都从 [SurfaceCard] 取玻璃，
 * 所以换视觉语言只需要动这一个文件与 [Glass.kt]。
 *
 * 玻璃的三种厚度（这是液态玻璃最重要的一套分级，用错就会"糊"）：
 *  - **薄**（[cardSurface]）：普通内容卡，62% 白，能透过背景看；
 *  - **中**（[panelSurface]）：顶栏/底栏/侧栏，52% 白，因为面积大，太实会闷；
 *  - **厚**（[overlaySurface]）：弹层，压住背后的内容，必须保证表单可读 —— 88% 白，
 *    它下面还压着一层遮罩，所以看起来仍是"半透的"，但字一个字都不会被吃掉。
 */

/** 卡片玻璃（内容容器） */
@Composable
fun cardSurface(): Color = glassCardColor()

/**
 * 玻璃**内部**的凹槽：输入框、弹层里的列表行、chip 的底。
 *
 * ⚠️ 不能用白色。弹层底已经是 90% 白，白叠白等于消失 —— 这条在 v12 就踩过。
 * 而且 v13.1 把卡片的膜从 26% 砍到 13% 之后，"白叠白"从"勉强能看"变成"彻底没有"：
 * 表单里的输入框会凭空少一个底，整块表单糊成一片。
 *
 * 正确的做法是**用墨色极淡地凹下去**（光进不到凹处，所以凹处是暗的），
 * 4.5% 恰好是"看得出边界、说不出颜色"的一档。
 */
@Composable
fun wellSurface(): Color =
    // ⚠️ v13.3：亮色 4.5% → 6.5%，暗色 5.5% → 7.5%。
    // 卡片膜提到 15.5%、底色压深之后，4.5% 的凹槽在浅色卡上已经糊掉了 ——
    // 大量 chip / 输入框 / 分段槽因此"没有底"（真机截图里肉眼可辨）。
    // 凹槽和膜必须同时加码，否则"凹"与"凸"的差被压缩掉。
    if (LocalDarkTheme.current) Color.White.copy(alpha = 0.075f)
    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.065f)

/** 面板玻璃（顶栏 / 导航栏 / 侧栏） */
@Composable
fun panelSurface(): Color = glassPanelColor()

/**
 * 弹层玻璃（对话卡 / 抽屉 / 大面板）。
 *
 * 弹层背后压着 34% 的遮罩，所以它可以比内容卡**更透一点**也仍然可读 ——
 * v13.9 从 0.93 降到 0.88，是为了让边缘那圈折射带与虹彩真的能被看见：
 * 在不透明度 0.93 的白面上，一层 15% 的白棱是"白压白"，等于没画。
 * 遮罩负责可读性，膜只负责"这是一层介质"，分工不能混。
 */
@Composable
fun overlaySurface(): Color =
    if (LocalDarkTheme.current) Color(0xFF1A1D23).copy(alpha = 0.92f)
    else Color(0xFFFCFBF8).copy(alpha = 0.88f)

/**
 * 浮空功能条（吸顶工具卡）的底色。
 *
 * 它下面会滚过流水，所以必须比普通卡片更"实"；但**深色主题下绝不能用白色**去加实 ——
 * 那会在暗色页面里贴一块浅灰板（v12 的第一版就犯了这个错）。
 * 深色下的"更实"是"更深"，不是"更白"。
 *
 * ⚠️ v12.0.1：亮色下也不能用纯白。内容会从吸顶卡底下滑过，白卡经过时整条卡会变成
 * 252 的一片死白，里面那枚白色的选中滑块在白底上直接消失。带一点冷灰的膜
 * 既能透出环境色，又能让滑块"亮出来"。
 *
 * ⚠️ v13.9：这只给**吸顶工具卡**用 —— 它下面有流水字在滑，必须足够实才不"透鬼影"。
 * 底栏胶囊要的是"透过玻璃看条目"，另见 [bottomBarGlassColor]。
 */
@Composable
fun floatingBarColor(): Color =
    if (LocalDarkTheme.current) Color(0xFF1A1D22).copy(alpha = 0.93f)
    else Color(0xFFF7F5F0).copy(alpha = 0.92f)

/**
 * 底栏玻璃胶囊的底色（v13.9 拆分）。
 *
 * 底栏和吸顶工具卡的诉求相反：吸顶卡要"实"（挡掉下面滑过的字），底栏要"透"——
 * 用户要求最后一条流水**透过玻璃可见**（"账本全屏显示"），不能再被 92% 的白膜
 * 洗成一块遮罩。所以底栏的膜压回透明玻璃档（亮 10% / 暗 22%），玻璃身份交给
 * 边缘那几样（白棱 + 虹彩 + 内侧暗线，见 AdaptiveScaffold 里补的 `edgeInk = 2.0`）。
 *
 * 深色下与玻璃卡同理：用**低透明度的白**做反射（对齐 glassCardColor 暗 6.5%），
 * 而不是更深的暗色 —— 暗底上"浮起来的玻璃"靠的是比底更亮的那一点点反光 + 亮边。
 */
@Composable
fun bottomBarGlassColor(): Color =
    if (LocalDarkTheme.current) Color(0xFFFFFFFF).copy(alpha = 0.08f)
    else Color(0xFFF7F5F0).copy(alpha = 0.10f)

/**
 * 浮层菜单（标题下拉、筛选弹层、行内更多菜单）的底色。
 *
 * ⚠️ 这里**不能**直接复用 [floatingBarColor]：那个是 92% —— 给底栏用刚好，
 * 因为底栏下面滑过的是流水行，透一点点反而是"玻璃"的证据。但浮层菜单打开时，
 * 底下正对着标题、段控件和一个 44sp 的大金额，8% 的透光足以让「明细」和
 * 「¥1,536」以灰字浮在菜单上（v13.7 第一版实机就是这样），读起来像重影。
 *
 * 所以它比任何一块内容都实：亮 95.5% / 暗 96.5%。
 *
 * ⚠️ v13.9 实测修正：第一版给 94% + `filmDepth = 0.7f`，真机上菜单**下半张透出了
 * 背后的「筛选 / 多选」两颗胶囊** —— 因为 filmDepth 会让膜的底部掉到 77%，
 * 23% 的透光足够把一行深色文字变成菜单上的灰字。
 * 结论：**菜单必须 filmDepth = 1f（整块等厚）**，然后 alpha 才有意义。
 * 第二版给 95.5% 等厚：**静止看还残留一层"支 ¥603.76"灰字**。
 * 4.5% 的透光在数学上只有 1 个色阶，但一行 13sp 深色字经过 4.5% 之后仍是
 * 眼睛能读出来的形状 —— 因为**没有模糊**。iOS 的菜单是"降透明度 + 高斯模糊"，
 * 我们拿不到背后像素，只剩不透明度这一根杠杆。
 *
 * 所以最终值定在 **99%（亮）/ 99.2%（暗）**。
 * 97.5% / 98.5% 在截图里仍然读得出「¥216」「已到账」的轮廓（2.5% 透光 ≈ 一个半色阶，
 * 但因为完全没有模糊，字的**形状**还在）。99% 之后残影掉到 0.6 个色阶，
 * 肉眼看不出，同时"整块是实心白"这个错觉也不会回来 —— 它的材质证据换到了边上。
 * 玻璃感不再靠透光，靠边缘那三样：白棱 + 虹彩 + 内侧焦散（见 glassSurface）。
 */
@Composable
fun popoverColor(): Color =
    if (LocalDarkTheme.current) Color(0xFF1D2025).copy(alpha = 0.992f)
    else Color(0xFFF8F6F1).copy(alpha = 0.99f)

/** 分隔线（玻璃内部）：一条会两端渐隐的白线，比灰线更贴合材质 */@Composable
fun dividerBrush(): Brush = Brush.horizontalGradient(
    colorStops = arrayOf(
        0.0f to Color.White.copy(alpha = 0.02f),
        0.15f to MaterialTheme.colorScheme.outlineVariant,
        0.85f to MaterialTheme.colorScheme.outlineVariant,
        1.0f to Color.White.copy(alpha = 0.02f)
    )
)

/** 细边框：玻璃棱边的简化版，给不能走完整材质的小元素用 */
@Composable
fun borderBrush(): Brush {
    val dark = LocalDarkTheme.current
    return Brush.linearGradient(
        listOf(
            Color.White.copy(alpha = if (dark) 0.22f else 0.80f),
            Color.White.copy(alpha = if (dark) 0.06f else 0.24f)
        )
    )
}

/** 卡片描边 = 玻璃棱边 */
@Composable
fun cardBorder(): BorderStroke = glassBorder()

/**
 * 主色刷。
 *
 * 玻璃时代的主色也不能是死平的一块：给一道 12% 的白到黑的对角渐变，
 * 按钮/徽标就会有"受光的曲面"感，而不是一张贴纸。
 */
@Composable
fun primaryGradientBrush(): Brush {
    val base = MaterialTheme.colorScheme.primary
    return Brush.linearGradient(
        colorStops = arrayOf(
            0.0f to lerp(base, Color.White, 0.14f),
            0.55f to base,
            1.0f to lerp(base, Color.Black, 0.10f)
        )
    )
}

/** 顶面高光刷 */
@Composable
fun sheenBrush(): Brush = glassTopSheen()

/** 强调色玻璃：把语义色以 8% 洗进膜里，用于t("这张卡属于某类") */
@Composable
fun cardTintBrush(tint: Color): Brush = Brush.verticalGradient(
    colorStops = arrayOf(
        0.0f to tint.copy(alpha = 0.09f),
        1.0f to tint.copy(alpha = 0.03f)
    )
)

/**
 * 弹层遮罩。
 *
 * 34% 黑 + 顶层一点点冷色：苹果的弹层遮罩不是"把背景压黑"，而是"把背景退到后面去"，
 * 所以它比常见的 60% 浅得多，你能看见背后的版面结构，但注意力被前层吸走。
 */
@Composable
fun scrimColor(): Color = Color(0xFF100E0B).copy(alpha = 0.34f)

/**
 * 页面底色。
 *
 * 顶部略亮、底部略沉，配合 [AuroraBackdrop] 的四团色光，形成一个"有空气"的空间。
 *
 * ⚠️ v13.3：顶部提白从 55% 砍到 30%。
 * 55% 会把屏幕上方 1/3 推到 L\* 97 以上 —— 而那正好是**标题、首屏卡片、顶栏**所在的位置。
 * 结果是"最重要的区域最白最灰"，卡片在那里完全没有可分的明度。
 * 现在顶 30%（L\* 94.4）、底压到暖墨 42%（L\* 88），整页有 6 个明度档的纵向落差：
 * 顶部的卡片终于比顶部的背景亮，底部的卡片也不会陷进底里。
 */
@Composable
fun backgroundBrush(): Brush {
    val base = MaterialTheme.colorScheme.background
    val dark = LocalDarkTheme.current
    return if (dark) {
        Brush.verticalGradient(
            colorStops = arrayOf(
                0.0f to lerp(base, Color(0xFF101827), 0.55f),
                0.45f to base,
                1.0f to lerp(base, Color.Black, 0.35f)
            )
        )
    } else {
        // ⚠️ v13.3：从"顶白 → 底微沉"的两段暖调，改成 **上冷下暖的四段**。
        //
        // 为什么必须跨色温：v13.2 整页都在暖区（#F4F2ED → #DCE2EC 只是明度变化），
        // 屏幕读起来就是"一张过曝的米色纸"，没有任何"光"的方向感 —— 这就是"太灰"。
        // 人眼判断一个空间是"灰"还是"有色"，靠的不是饱和度，而是**同一屏里存在两种色温**。
        // 现在：顶部 #F4F7FB（极淡冷白，像天光）+ 中部暖米白（纸）+ 底部 #DED3C0（砂影），
        // 三段连起来是一个能被读懂的"光从上方来"的空间，而不是一块平涂的底。
        Brush.verticalGradient(
            colorStops = arrayOf(
                0.0f to lerp(base, Color(0xFFF3F6FA), 0.80f),
                0.22f to lerp(base, Color(0xFFF3F6FA), 0.28f),
                0.55f to base,
                0.82f to lerp(base, Color(0xFFE3DACB), 0.30f),
                1.0f to lerp(base, Color(0xFFD8CDBA), 0.42f)
            )
        )
    }
}

/** 卡片受光刷（旧接口，等价于顶面高光） */
@Composable
fun cardLitBrush(): Brush = glassTopSheen()

/**
 * 柔和投影（给不经过 [glassSurface] 的元素用，例如 FAB、浮动条）。
 *
 * 系统 `shadow` 的 alpha 由 ambient/spot 决定，这里压到 4~6%：
 * 玻璃的投影只要"有一点"，多了就成了厚卡片。
 */
fun Modifier.softShadow(
    elevation: Dp,
    shape: Shape,
    ambient: Color = Color(0xFF0B1622).copy(alpha = 0.06f),
    spot: Color = Color(0xFF0B1622).copy(alpha = 0.07f)
): Modifier = this.shadow(
    elevation = elevation,
    shape = shape,
    clip = false,
    ambientColor = ambient,
    spotColor = spot
)

// ============================================================
//  容器
// ============================================================

/**
 * 玻璃卡片容器。
 *
 * 参数与 v4/v5 完全兼容，三十多个页面的调用一行都不改就换到新语言。
 *
 * @param sheen  是否叠一层顶面高光（默认关：材质自带，叠两层会发白）
 * @param tint   语义色洗色（选中的账本、报销状态）
 * @param glass  false 时退回"纯色 + 系统阴影"的旧路径（用于需要完全不透明的场合）
 */
@Composable
fun SurfaceCard(
    shape: Shape,
    modifier: Modifier = Modifier,
    elevation: Dp = 4.dp,
    color: Color = cardSurface(),
    showBorder: Boolean = true,
    sheen: Boolean = false,
    tint: Color? = null,
    softShadow: Boolean = true,
    lit: Boolean = false,
    glass: Boolean = true,
    /** 高光倍率：大面板/主卡给 >1，普通内容卡 1，纯功能容器 0 */
    specular: Float = 1f,
    /** 底部白膜压平程度：整页弹出的大弹层传 1f —— 否则下半张是半透的，底下的页面会透上来 */
    filmDepth: Float = 0f,
    content: @Composable () -> Unit
) {
    val baseMod = if (glass) {
        modifier.glassSurface(
            shape = shape,
            elevation = if (softShadow) elevation else 0.dp,
            fill = color,
            tint = tint,
            specular = specular,
            showEdge = showBorder,
            filmDepth = filmDepth
        )
    } else {
        val shadowMod = if (softShadow) Modifier.softShadow(elevation, shape) else Modifier
        modifier.then(shadowMod).clip(shape)
    }
    Surface(
        modifier = baseMod,
        shape = shape,
        color = if (glass) Color.Transparent else color,
        tonalElevation = 0.dp,
        shadowElevation = if (glass || softShadow) 0.dp else elevation,
        border = if (showBorder && !glass) cardBorder() else null
    ) {
        if (glass) {
            content()
        } else if (tint != null) {
            Box(Modifier.fillMaxSize()) {
                content()
                Box(Modifier.fillMaxSize().background(cardTintBrush(tint)))
            }
        } else {
            content()
        }
    }
}

/**
 * 页面底色 + 方向光。
 *
 * v13.9：`aurora` 参数随四团色光一起退役 —— 背景现在全静态，
 * 没有"动不动"这个维度可选（见 [AuroraBackdrop] 的说明）。
 */
@Composable
fun AppBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    AuroraBackdrop(modifier = modifier, content = content)
}

/**
 * 横向发丝线。
 *
 * 玻璃语言里它**不是一条灰线**，而是"两块玻璃之间的缝"：中间亮、两端渐隐。
 * 直接用 `outlineVariant` 画一条实心灰线，会在玻璃上留下一道突兀的铅笔印。
 */
@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(dividerBrush())
    )
}

// ============================================================
//  分栏动效
// ============================================================

/** 左栏宽度过渡：折叠/展开时宽度平滑变化，而不是直接跳到新值 */
@Composable
fun rememberAnimatedPaneWidth(targetDp: Int): Dp {
    val target = targetDp.dp
    return animateDpAsState(
        targetValue = target,
        animationSpec = Motion.soft(),
        label = "paneMasterWidth"
    ).value
}
