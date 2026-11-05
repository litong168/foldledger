package com.foldledger.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 间距节奏（对齐 Apple HIG 的 4pt 栅格）。
 *
 * v12 的调整只有一处，但很关键：**区块之间从 22 收到 20，列表之间从 14 放到 12**。
 * 液态玻璃的卡片本身有半透明边界，视觉重量比实色卡轻，所以"卡与卡"的间距可以收紧，
 * 靠玻璃的边缘高光分层，而不是靠大留白硬隔。留白留给"卡内"—— 卡片内部反而更松，
 * 因为玻璃需要有面积才能看出通透感。
 */
object Space {
    val xxs: Dp = 2.dp
    val xs: Dp = 4.dp
    val sm: Dp = 8.dp
    val md: Dp = 12.dp
    val lg: Dp = 16.dp
    val xl: Dp = 20.dp
    val xxl: Dp = 26.dp
    val huge: Dp = 36.dp

    /** 页面左右留白：HIG 的 16pt 边距在大屏上会显局促，20 是中文界面的甜点 */
    val pageH: Dp = 20.dp

    /** 列表项之间的间距 */
    val gapList: Dp = 12.dp

    /** 区块之间的间距 */
    val gapSection: Dp = 20.dp

    /** 卡片内边距 —— 玻璃要"呼吸"，比实色卡多 2dp */
    val cardPad: Dp = 18.dp

    /**
     * 最小触控边长。
     * HIG 要求 44pt；Android 推荐 48dp。取 48，中文小字多，指腹更容易点偏。
     */
    val touch: Dp = 48.dp
}

/**
 * 圆角梯度。
 *
 * 液态玻璃的圆角必须"大而统一"：玻璃是有厚度的介质，边缘的折射是一整圈连续的弧，
 * 小圆角会让高光在角上"折"出来，看起来很廉价。
 * 所以卡片 26、面板 30、弹层 36 —— 与 iOS 26 的卡片比例一致（卡高 1/4 左右）。
 */
object Radius {
    /** 小徽标、色点、进度槽 */
    val sm: Dp = 12.dp

    /** 输入框、次级按钮、chip */
    val md: Dp = 18.dp

    /** 标准玻璃卡 */
    val card: Dp = 26.dp

    /** 大面板（顶部栏卡片、看板） */
    val panel: Dp = 30.dp

    /** 底部弹层 */
    val sheet: Dp = 36.dp

    /** 胶囊 */
    val pill: Dp = 50.dp
}

/**
 * 布局约束。
 *
 * `contentMaxWidth` 是照着小米《大屏应用UX体验标准》L2 那条来的：
 * 「按钮、列表、输入框等置于页面内的组件应具有最大宽度限制，不应被横向拉伸占满整个屏幕」。
 * 宽屏上把内容居中收在一列，单行字数也就舒服了。
 */
object Layout {
    /** 手机 / 折叠屏内屏单栏的阅读宽度上限 */
    val contentMaxWidth: Dp = 760.dp

    /**
     * v16.4：桌面宽屏的内容上限。
     *
     * 760dp 是**一栏文字**的舒适宽度（中文约 40 字），照搬到 1700dp 的 Windows 窗口上，
     * 左右各空出 470dp —— 屏幕利用率不到一半，这正是「手机排版直接搬过来」的观感来源。
     * 宽屏不是把一栏拉长，而是**换一种版面**（并排两栏 / 两列卡片），所以上限抬到 1120：
     * 双栏各 ~540dp，仍是好读的宽度，整屏却填满了。
     */
    val wideContentMaxWidth: Dp = 1120.dp

    /**
     * 双栏布局里「右栏」（概览 / 报销看板）的上限。
     *
     * 右栏是一堆短数字卡，不是长文 —— 它比正文更需要收窄：拉到 1300dp 时
     * `Arrangement.SpaceBetween` 会把三块金额顶到屏幕两端，中间空出一条河。
     * 但收窄只是把空白挪到两侧，所以真正的做法是**并排两列**（见 LedgerDetailScreen），
     * 上限跟着 [wideContentMaxWidth] 走，两份内容各占一半。
     */
    val formMaxWidth: Dp = 620.dp

    /** 到这个宽度改用左侧导轨；更窄仍用底部胶囊（手机 / 折叠内屏都是后者） */
    const val NAV_RAIL_FROM_DP = 1024

    /** 当前窗口该用哪一档内容上限 */
    fun contentMaxFor(widthDp: Int): Dp =
        if (widthDp >= NAV_RAIL_FROM_DP) wideContentMaxWidth else contentMaxWidth

    /**
     * 宽屏下把内容收窄并居中所需的额外左右留白（按**整个窗口**算）。
     * 窄屏返回 [Space.pageH]。
     */
    fun sidePadding(
        windowWidthDp: Int,
        maxWidth: Dp = contentMaxFor(windowWidthDp)
    ): Dp {
        val over = windowWidthDp - maxWidth.value
        return if (over <= 0f) Space.pageH else Space.pageH + (over / 2).dp
    }

    /**
     * v16.4：按**容器**（而不是整窗）算居中的左右留白。
     *
     * ⚠️ 双栏页面里必须用这个：右栏只是整窗的一部分，若还拿 `env.widthDp` 去算，
     * 1700dp 窗口会算出 313dp 的留白，塞进 1338dp 的右栏后内容只剩 712dp，
     * 甚至能在 368dp 的左栏里算出负宽度 —— 这就是「桌面双栏反而比手机更挤」的根因。
     */
    fun sidePaddingIn(
        containerWidthDp: Int,
        maxWidth: Dp = wideContentMaxWidth
    ): Dp {
        val over = containerWidthDp - maxWidth.value
        return if (over <= 0f) Space.pageH else Space.pageH + (over / 2).dp
    }
}
