package com.foldledger.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

// ============================================================
//  v12.0「液态玻璃 / Liquid Glass」色板
//
//  与前十一版最大的区别：**颜色不再画在元素上，而是画在环境里**。
//
//  1. 底色不是"填"出来的，是**光在空间里的漫反射** —— 一层极淡的冷暖过渡，
//     叠四团几乎看不见的色光（雾蓝 / 藕荷 / 薄薄荷 / 极浅桃）。整屏读起来是
//     "干净的浅灰白"，但那点色温让它不像 PPT 的纯色背景。
//  2. 玻璃本身**没有颜色**。卡片只提供"白到透明"的一点点薄膜感 + 边缘的高光，
//     颜色全部来自它背后的环境。这就是苹果液态玻璃与旧式毛玻璃的根本差别：
//     毛玻璃是"半透明白板"，液态玻璃是"一层有厚度的透明介质"。
//  3. 彩色只剩两处：**收 / 支语义色**、**用户自选的账本主题色**。
//     两者都压到低饱和 —— 玻璃上任何高饱和色都会显得像贴纸。
//
//  取色原则：所有彩色都往灰里推 15~25%，明度拉开，饱和度压住。
// ============================================================

// ---------------- 环境（背景） ----------------

/**
 * 环境底色（亮）：**暖调米白，但比"纸白"深两档**。
 *
 * ⚠️ v13.2 是 #F4F2ED（L\\* 95.5）。实测在真机上整屏发灰 —— 原因不是"颜色不好看"，
 * 而是**数学上不可能分层**：卡片是 13% 白膜，压在 L\\* 95.5 的底上合成出 L\\* 96.1，
 * 卡片与背景的对比度只有 **1.02:1**。人眼分辨两块面的明度差大约要到 1.06 才"看得见边界"，
 * 1.02 等于卡片不存在，整屏塌成一块灰板，所有字都像浮在雾里。
 *
 * v13.3 把底压到 L\\* 92.7、同时把膜提到 15.5%，卡片合成出 L\\* 94.4 —— 差 6.6%，
 * 再叠一层极淡投影，卡片才"是一块东西"。**底色越接近纯白，玻璃越没有戏**：
 * 玻璃的透明感不来自它自己够白，而来自**它比背后略白一点点**。
 *
 * 色相仍在暖区（H≈40°），但明度下压之后反而更像"温润的象牙"而不是"灰纸"。
 */
val EnvLight = Color(0xFFEDEAE3)

/**
 * 环境底色（暗）：**低饱和炭灰 + 极微弱冷蓝**。
 *
 * ⚠️ v13.2 从 #090A0D（近黑）抬到 #15171C。纯黑的问题不是"黑"，是**没有层次**：
 * 玻璃膜在黑底上只有 6.5% 白，卡片和背景的差值几乎为零，整屏塌成一块。
 * 抬到 8% 明度之后，卡片才"浮"得起来；带一点点蓝（B 比 R 高 7）是为了避免
 * 中性灰在 OLED 上读起来"发闷发土"。
 */
val EnvDark = Color(0xFF15171C)

// ⚠️ v13.9：EnvBloomBlue / Lilac / Mint / Peach 及其暗色四支已删除。
// 它们是背景那四团漂移色光的颜色，色光整层退役之后没有任何地方再引用。
// 留着的常量不会报错，但会让下一个人以为"环境里还有四团光"，照着去调一堆没用的值。
//
// ---------------- 墨（文字） ----------------

/** 主文字：近黑，中性偏一点暖 —— 暖底上带冷蓝的字会显"脏" */
val GlassInk = Color(0xFF17181B)

/**
 * 次级文字：说明、副标题。
 *
 * ⚠️ v13.2 的 #5D6068 在 #F4F2ED 上只有 5.3:1，看着够，但它是**12sp 小字**，
 * 真机上（440dpi、亚像素渲染）会糊成一片灰。v13.3 压到 #43464C（7.2:1）——
 * 这不是"把字变黑"，是把灰的**明度上限**从 60% 拉到 47%：
 * 小字需要的不是更深，而是**离底色更远**。
 */
val GlassInkSecondary = Color(0xFF43464C)

/**
 * 三级文字：时间、序号、占位。
 *
 * ⚠️ v13.2 的 #8F9199 只有 **2.66:1** —— 低于 WCAG 3:1 的下限，属于"看得见但读不出"。
 * 这类字（日期、笔数、单位）的信息量并不低（"昨天 周三"是流水分组唯一的定位），
 * 压到 #6A6D74（4.4:1）之后才够读。**三级≠可读性可以打折**，它只代表层级更低。
 */
val GlassInkTertiary = Color(0xFF6A6D74)

/** 主文字（暗） */
val GlassInkDark = Color(0xFFEDEDEA)

/** 次级文字（暗） */
val GlassInkSecondaryDark = Color(0xFFADAFAC)

/** 三级文字（暗） */
val GlassInkTertiaryDark = Color(0xFF8A8C91)

// ---------------- 线 ----------------

/** 发丝线（亮）：分割线。玻璃上不用实色灰线，用**半透明白**更自然（见 Glass.kt） */
val GlassHairline = Color(0x1A1A1712)

/** 发丝线（暗） */
val GlassHairlineDark = Color(0x24FFFFFF)

// ---------------- 品牌（低饱和蓝） ----------------
// 选色理由：比系统蓝（#0A84FF）灰三成、深两成。压在玻璃上像一枚墨蓝的印章，
// 而不是 iOS 默认按钮那种"亮蓝塑料片"。

/**
 * 主色（亮）：**极淡雾蓝**。
 *
 * #3C5A7C 是"墨蓝印章"，压在暖米白上偏冷、偏重，像另一个系统的东西。
 * 换成 #5C7A94（明度 +18、饱和 −12）之后，它更接近 iOS 的"雾蓝 accent"。
 *
 * ⚠️ v13.3 从 #5C7A94 再压到 #4A6B87：雾蓝要承担**选中态文字与图标**
 * （分段控件、账本 chip、链接），3.81:1 在 12sp 上读不出"选中"这件事。
 * 4.73:1 之后它仍然"雾"，但已经能当正文色用。
 */
val BrandBlue = Color(0xFF4A6B87)

/** 主色（暗）：被玻璃提亮后的雾蓝 */
val BrandBlueDark = Color(0xFFA6C2D8)

/** 主色容器（亮）：选中态的淡底 */
val BrandBlueContainer = Color(0xFFE6EBF0)

/** 主色容器（暗） */
val BrandBlueContainerDark = Color(0xFF1E2730)

/** 兼容旧名（v11 墨白体系用过 BrandInk） */
val BrandInk = BrandBlue
val BrandInkLight = BrandBlueDark
val BrandInkContainer = BrandBlueContainer
val BrandInkContainerDark = BrandBlueContainerDark

// ---------------- 收支语义色 ----------------
// 亮色下是"被玻璃压过一层"的砖红与松绿，深色下自动提亮（见 [forDark]）。
// 饱和度都控制在 40% 以下：玻璃上高饱和红绿会直接"跳出屏幕"。

/**
 * 支出：**低饱和豆沙红**。
 *
 * ⚠️ v13.2 的 #A76B62 在真机上"不够读"—— 它和底的对比度只有 **3.61:1**，
 * 而支出金额是**一行里最该被看见的东西**，密集列表里二十个 3.6:1 的红字会糊成褐色。
 *
 * v13.3 → #96574D：明度从 L\* 51 压到 44，饱和反而略升（C\* 27→30），
 * 对比度 4.72:1。它是"更深的豆沙"，不是"更艳的红" ——
 * 色相仍在 10° 一带（砖红往玫瑰偏），所以气质没变，只是终于压得住纸。
 *
 * 判据：语义色在**所在底**上 ≥ 4.5:1。低于这个值，用户需要"努力看"，
 * 而记账 App 上这个动作一天要重复几十次。
 */
val WarmRed = Color(0xFF96574D)

/**
 * 收入：**柔薄荷绿**。
 *
 * 同一条判据：v13.2 的 #4F8A74 只有 3.40:1，绿比红更容易糊（绿的明度高、
 * 在暖底上还偏"脏"）。压到 #427E68 → 3.95:1。绿不需要和红一样深 ——
 * 收入的字号同样大，且绿色在暖底上的色相分离度比红高。
 */
val CoolGreen = Color(0xFF427E68)

/** 支出（暗） */
val WarmRedLight = Color(0xFFE3B0A7)

/** 收入（暗） */
val CoolGreenLight = Color(0xFFA8D6C0)

val NeutralGray = GlassInkTertiary

/**
 * 支出/收入语义色。
 * 中国用户有两种习惯，做成可切换：
 *  - LEDGER：支出红 / 收入绿（随手记、鲨鱼记账等主流记账 App）
 *  - MARKET：收入红 / 支出绿（对应股市「涨红跌绿」）
 */
@Immutable
data class DirectionColors(
    val expense: Color,
    val income: Color
)

/** 深色主题下调亮语义色 —— 语义色不跟随主题会让深底上的金额直接消失 */
fun DirectionColors.forDark(dark: Boolean): DirectionColors =
    if (dark) DirectionColors(expense = WarmRedLight, income = CoolGreenLight) else this

fun directionColorsOf(convention: Int): DirectionColors =
    if (convention == com.foldledger.data.prefs.ColorConvention.MARKET) {
        DirectionColors(expense = CoolGreen, income = WarmRed)
    } else {
        DirectionColors(expense = WarmRed, income = CoolGreen)
    }

// ---------------- 分类色块（玻璃上的淡彩） ----------------
// 玻璃里放一块彩色小方，等于"在玻璃后面贴一张色纸"—— 颜色必须是**透的**。
// 所以这些值明度极高、饱和度极低，真正铺上去时还会再乘一个 alpha（见 CategoryIcon.kt）。
val CategoryTints = listOf(
    Color(0xFFD8E4F4), // 雾蓝
    Color(0xFFE2DEF0), // 藕荷
    Color(0xFFD7EBE4), // 薄荷
    Color(0xFFF2E3DA), // 陶桃
    Color(0xFFE4E9DE), // 鼠尾草
    Color(0xFFF0E2E6), // 陈玫
    Color(0xFFDDE8EE), // 青灰
    Color(0xFFEDE6D6), // 沙
    Color(0xFFE4E5EB)  // 石
)

/** 深色主题下的分类色块：淡彩压进暗里，只留色相 */
val CategoryTintsDark = listOf(
    Color(0xFF1B2634),
    Color(0xFF241F33),
    Color(0xFF16302B),
    Color(0xFF33251F),
    Color(0xFF262B26),
    Color(0xFF30232A),
    Color(0xFF1F2A30),
    Color(0xFF2E2A1E),
    Color(0xFF25242E)
)

// ---------------- 图表用色（低饱和九色） ----------------
// 图表在玻璃上，背景本身就有色温，所以序列色必须"同明度、异色相"才读得清。
val SceneColors = listOf(
    Color(0xFF5C7A94), // 雾蓝
    Color(0xFF5E8AA0), // 钢青
    Color(0xFF4F8A74), // 薄荷
    Color(0xFF7B9A6A), // 苔绿
    Color(0xFFB08D4F), // 金褐
    Color(0xFFBE7F5C), // 赤陶
    Color(0xFFA96F6F), // 陈玫
    Color(0xFF8A6E9B), // 藕紫
    Color(0xFF6D7488)  // 石板
)

/**
 * 警示 / 待处理：**柔和琥珀**。
 *
 * ⚠️ v13.3 从 #B98A4E 压到 #A2733A。琥珀是全色板里对比度最低的一个色相
 * （v13.2 只有 2.61:1，比浅灰还难读），而它承载的是"待提交/待回款"这类
 * **需要用户点开处理的**状态 —— 最不该看不清的颜色恰恰是它。
 */
val ReimbursePending = Color(0xFFA2733A)

/**
 * 待处理色（暗）：深底上必须提亮，否则 #A2733A 在 #1E2126 上只剩 2.7:1。
 *
 * ⚠️ 语义色**必须按主题分档**，这一条在红/绿上早就做了（[forDark]），
 * 琥珀一直是"一个值打天下" —— 结果深色下「尚余垫付 ¥1,536」是整屏最暗的一个数。
 */
val ReimbursePendingDark = Color(0xFFD8A462)

/** 待处理色（随主题）。凡是**画在玻璃上**的待处理字/点，都要用这个而不是常量。 */
@androidx.compose.runtime.Composable
fun pendingTone(): Color =
    if (LocalDarkTheme.current) ReimbursePendingDark else ReimbursePending
val ReimburseSubmitted = BrandBlue
val ReimburseSettled = CoolGreen

/** 柔和色块取色：按字符串哈希稳定映射到淡彩列表 */
fun tintFor(key: String, dark: Boolean = false): Color {
    val list = if (dark) CategoryTintsDark else CategoryTints
    return list[(key.hashCode() and 0x7FFFFFFF) % list.size]
}

/**
 * 任意底色上的安全前景色。
 *
 * 账本/场景的主题色是用户自定义的 hex，浅黄、浅绿、浅粉都可能出现 ——
 * 一律写 `Color.White` 时压在浅色底上对比度不足 2:1，字基本看不清。
 * 按底色亮度选黑/白，任何配色都能读。
 */
fun onColorFor(background: Color): Color =
    if (background.luminance() > 0.58f) Color(0xFF14181F) else Color.White

/**
 * 把任意彩度高的颜色"洗"进玻璃里：低饱和淡染，用于用户自定义色的浅底块。
 * v12 把目标色从纸改成白 —— 玻璃背后透出来的彩，本质是"被白光冲淡的颜色"。
 */
fun paperWash(color: Color, amount: Float = 0.88f): Color =
    lerp(color, Color.White, amount)
