package com.foldledger.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import com.foldledger.data.prefs.AppSettings
import com.foldledger.data.prefs.DarkMode

val LocalDirectionColors = staticCompositionLocalOf { DirectionColors(WarmRed, CoolGreen) }

/**
 * ⚠️ v13.9：`LocalAuroraAnimated` 已删除。
 *
 * 它唯一的用途是告诉 [AuroraBackdrop] 那四团色光要不要漂移，而四团色光本身在
 * v13.9 被整层删掉了（理由见 AuroraBackdrop 的注释）。留一个没人读的
 * CompositionLocal 只会让下一个人以为背景还有"动 / 静"两种状态。
 */

/**
 * 当前是否深色主题。材质引擎（玻璃填充、高光、色散）都要读它。
 */
val LocalDarkTheme = staticCompositionLocalOf { false }

/**
 * 玻璃滚动光相（Glass 2.0）。
 *
 * 主列表滚动时由 [GlassGlowDriver] 把滚动速度写进这个控制器，shell 级玻璃
 * （底栏胶囊 / 记一笔圆键）在**绘制阶段**读它的高光参数，让高光随内容移动而流动。
 *
 * ⚠️ 这里传的是**控制器对象**而不是 FloatState 本身：材质引擎是在
 * `drawBehind` / `drawWithContent` 的 lambda 里读值的，那儿不是 Composable 上下文，
 * 读 CompositionLocal 编译不过；退一步在组合期读 `.value` 又会让整条底栏**每帧重组**。
 * 传对象 → 组合期只取稳定引用（不触发重组），绘制期读内部状态 → **只重绘那一块**。
 *
 * 详见 [GlassGlowController] 与 [GlassGlowEngine]。
 */
val LocalGlassGlow = staticCompositionLocalOf<GlassGlowController> { error("LocalGlassGlow 未提供") }

/**
 * 亮色：光在浅灰白空间里漫反射。
 *
 * 关键取舍 —— `surface` 是纯白（玻璃的"膜"），`background` 是带冷调的浅灰（环境）。
 * 材质引擎在这个白上叠 alpha，所以 scheme 里的白必须是**不透明**的：
 * 半透明只能在 draw 阶段做，写进 scheme 会被 Surface 再叠一次。
 */
private val LightColors = lightColorScheme(
    primary = BrandBlue,
    onPrimary = Color(0xFFFDFDFF),
    primaryContainer = BrandBlueContainer,
    onPrimaryContainer = Color(0xFF1B2A36),
    secondary = GlassInkSecondary,
    onSecondary = Color(0xFFFDFDFF),
    secondaryContainer = Color(0xFFE9E7E2),
    onSecondaryContainer = GlassInk,
    background = EnvLight,
    onBackground = GlassInk,
    surface = Color(0xFFFFFFFF),
    onSurface = GlassInk,
    // surfaceVariant 用作"凹陷面"：进度槽、分段控件底、输入框底。
    // v13.2 跟着暖底走 —— 冷灰的凹槽压在暖白卡上会像"另一块材料"。
    surfaceVariant = Color(0xFFE9E6DF),
    onSurfaceVariant = GlassInkSecondary,
    outline = GlassInkTertiary,
    // 全站唯一的分割线色：半透明墨，压在玻璃上会自然与底色混合
    // ⚠️ v13.3：8% → 12%。底色压深半档之后 8% 的线在卡上已经看不见，
    // 设置页那种"三行一组"的卡片全靠这条线分区。
    outlineVariant = Color(0x1F1A1712),
    // 错误色取"更沉一档的豆沙红"：警示要能读出来，但不能是刺眼的正红。
    error = Color(0xFF9E5B52),
    onError = Color(0xFFFDFDFF),
    inverseSurface = GlassInk,
    inverseOnSurface = Color(0xFFF4F6FA),
    inversePrimary = BrandBlueDark,
    scrim = Color.Black
)

/** 深色：玻璃在暗环境里"提亮"，而不是"变黑"—— 这是深色液态玻璃的物理 */
private val DarkColors = darkColorScheme(
    primary = BrandBlueDark,
    onPrimary = Color(0xFF10202F),
    primaryContainer = BrandBlueContainerDark,
    onPrimaryContainer = Color(0xFFD3E2F2),
    secondary = GlassInkSecondaryDark,
    onSecondary = Color(0xFF0D0F13),
    secondaryContainer = Color(0xFF21252A),
    onSecondaryContainer = GlassInkDark,
    background = EnvDark,
    onBackground = GlassInkDark,
    surface = Color(0xFF1B1E23),
    onSurface = GlassInkDark,
    surfaceVariant = Color(0xFF23272D),
    onSurfaceVariant = GlassInkSecondaryDark,
    outline = GlassInkTertiaryDark,
    outlineVariant = Color(0x1FFFFFFF),
    error = Color(0xFFD9A69D),
    onError = Color(0xFF2A0F0B),
    inverseSurface = GlassInkDark,
    inverseOnSurface = Color(0xFFF4F2ED),
    inversePrimary = BrandBlue,
    scrim = Color.Black
)

/**
 * 排版（对齐 iOS 的 Dynamic Type 阶梯，中文按 1.0 缩放落地）。
 *
 * 液态玻璃对排版的要求和纸面相反：**字重不能太重**。玻璃本身有高光与色散，
 * 再压一个 Bold 大字，视觉上会"糊"在一起。所以大标题从 Bold 降到 SemiBold，
 * 层级靠**字号差 + 颜色深浅**建立，而不是靠字重硬压。
 *
 * 数字一律 `tnum`：金额纵向对齐是记账 App 的底线。
 *
 * ## 字阶（v13.7 统一，见 [TypeScale]）
 *
 * | 档 | sp | 管什么 |
 * |---|---|---|
 * | Hero | 44 | 首屏唯一的那个大数 |
 * | Display | 30 | 声明性数字（账户余额、速记金额） |
 * | Head | 24 | 卡内大金额、sheet 标题 |
 * | Title | 20 | 页面区块标题、弹层标题 |
 * | Subhead | 17 | 卡片标题、列表主文本加重 |
 * | Body | 15 | 正文、流水行主文本 |
 * | Caption | 13 | 次级说明、备注、时间 |
 * | Label | 11 | overline、chip 文字、轴刻度 |
 *
 * ⚠️ **相邻两档至少差 1.15 倍**。11/12/13 三连是这里最容易犯的错：
 * 差 1sp 在 440dpi 上只有 2.75px，既不足以读成"层级"，又足以读成"没对齐"。
 * 所以 12 被合并进 13、10 被合并进 11（10sp 中文在真机上已经糊成一团）。
 */
private val AppTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 44.sp,
        // v13.7：字号从 40 抬到 44 之后 lineHeight 必须跟着走，否则 44/46 的行盒
        // 只剩 2sp 余量，中文加 tnum 数字一旦下沿超出就被裁掉一道。
        lineHeight = 50.sp,
        letterSpacing = (-1.2).sp,
        fontFeatureSettings = "tnum"
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 30.sp,
        lineHeight = 37.sp,
        letterSpacing = (-0.8).sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 31.sp,
        letterSpacing = (-0.5).sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 24.sp,
        letterSpacing = (-0.2).sp
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 21.sp,
        letterSpacing = (-0.1).sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 17.sp,
        lineHeight = 24.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 19.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        textAlign = TextAlign.Center
    )
)

/**
 * 主数字（结余 / 合计 / 月度总额）。
 *
 * 44sp SemiBold + -1.4 字距 + 等宽数字。
 * 玻璃上不用 ExtraBold：笔画一粗，高光边缘会和笔画混在一起，数字反而"发毛"。
 */
val HeroNumber = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.SemiBold,
    fontSize = 44.sp,
    lineHeight = 50.sp,
    letterSpacing = (-1.4).sp,
    fontFeatureSettings = "tnum"
)

/** 次一级的大数字（卡片内的金额、看板主数） */
val StatementNumber = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.SemiBold,
    fontSize = 24.sp,
    lineHeight = 32.sp,
    letterSpacing = (-0.8).sp,
    fontFeatureSettings = "tnum"
)

/**
 * 小节标签（overline）。
 *
 * 11sp + 0.6 字距。玻璃时代这里必须**少加字距**：字距一宽，小字在低对比的
 * 玻璃底上会碎成一个个孤立的点，读起来费劲。0.6 足够区分"标签"与"正文"。
 */
val OverlineLabel = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.Medium,
    fontSize = 11.sp,
    lineHeight = 15.sp,
    letterSpacing = 0.6.sp
)

@Composable
fun FoldLedgerTheme(
    settings: AppSettings = AppSettings(),
    content: @Composable () -> Unit
) {
    val dark = when (settings.darkMode) {
        DarkMode.LIGHT -> false
        DarkMode.DARK -> true
        else -> isSystemInDarkTheme()
    }
    val context = LocalContext.current
    /**
     * 动态取色（v13.8：**收窄成只换强调色**）。
     *
     * v13.0~13.7 这里是整套 `dynamicLightColorScheme()` / `dynamicDarkColorScheme()` 替换。
     * 在 M3 标准应用里那是对的，在这个 App 里是**反作用**：
     *
     *  - 底色不是 `colorScheme.background` 一层平涂，而是 [backgroundBrush] 里
     *    「lerp(base, 冷白) → base → lerp(base, 砂色)」的三段色温渐变 + 四团色光。
     *    base 一换，整条色温曲线跟着跑偏 —— 而 v13.3 定的门槛（卡/底 ≥1.10:1、
     *    **暖:冷 ≥2:1**）是**按近白暖底量出来的**。换个绿壁纸，两条同时失效，
     *    玻璃膜 17.5% 白在深色底上直接把卡片压成灰块。
     *  - 语义色（豆沙红 / 收入绿 / 待处理棕）是**记账语义**，不是装饰，
     *    不能在壁纸上取色 —— 用户认的是"这个红是支出"。
     *  - 玻璃的膜 / 影 / 折射带 alpha 全部按"近白亮底"标定过，
     *    换一个中明度底色，投影暗环和上沿白棱就都不成立了。
     *
     * 所以保留这个功能，但**只让它换 primary 那一族**：按钮、进度条、选中态、
     * 图标会跟着壁纸变，底色和玻璃材质不动。这是"能用且不会毁掉材质"的
     * 唯一一条边界 —— 再往外扩一步就会撞上面三条。
     */
    val scheme = when {
        settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val dyn = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            (if (dark) DarkColors else LightColors).copy(
                primary = dyn.primary,
                onPrimary = dyn.onPrimary,
                primaryContainer = dyn.primaryContainer,
                onPrimaryContainer = dyn.onPrimaryContainer
            )
        }

        dark -> DarkColors
        else -> LightColors
    }

    val directionColors = directionColorsOf(settings.colorConvention).forDark(dark)

    SideEffect {
        // 状态栏/导航栏图标明暗由主题决定，配合 edge-to-edge
        val window = (context as? android.app.Activity)?.window ?: return@SideEffect
        val nav = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        nav.isAppearanceLightStatusBars = !dark
        // v13.9.2：导航栏对比 scrim 已关（MainActivity），这里只接管 Home 指示条的明暗，
        // 让它在浅色页上是深灰、深色页上是浅灰，始终看得清。
        nav.isAppearanceLightNavigationBars = !dark
    }

    // 触感设置同步到全局引擎（数字键盘/按钮都读它）
    SideEffect {
        com.foldledger.ui.haptic.AppHaptics.strength = settings.hapticStrength
        com.foldledger.ui.haptic.AppHaptics.keypadEnabled = settings.hapticKeypad
    }

    CompositionLocalProvider(
        LocalDirectionColors provides directionColors,
        LocalDarkTheme provides dark
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = AppTypography,
            content = {
                // ⚠️ v12.0.1：全局默认文字色必须跟着主题走。
                // 之前没人设 LocalContentColor，Compose 的默认值恒为**黑色** ——
                // 浅色主题下看不出来，深色主题里所有"没显式指定颜色"的标题
                // （例如账本页的「我的账本」）就变成黑底黑字，直接看不见。
                CompositionLocalProvider(LocalContentColor provides scheme.onBackground) {
                    content()
                }
            }
        )
    }
}
