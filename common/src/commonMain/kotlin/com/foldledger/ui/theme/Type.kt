package com.foldledger.ui.theme

import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * 全仓唯一的字号集合（v13.7）。
 *
 * ### 为什么需要这个文件
 * 统一之前，`fontSize = N.sp` 一共出现过 **24 种**字号：
 * 8 / 9 / 10 / 10.5 / 11 / 12 / 13 / 13.5 / 14 / 14.5 / 15 / 16 / 17 / 18 /
 * 19 / 20 / 21 / 22 / 24 / 26 / 30 / 38 / 40 / 44。
 *
 * 这 24 种里有一半是"手滑"：13.5 和 14.5 各出现 2 次和 1 次，19 与 20 并存，
 * 22 与 24 并存。它们不会让人说"层级不清楚"，只会让人说"**这行字看着不对**" ——
 * 因为 1sp 在 440dpi 上只有 2.75px：不足以读成层级，只足以读成没对齐。
 *
 * ### 判据
 * **相邻两档至少差 1.15 倍。**
 * 11→13 = 1.18，13→15 = 1.15，15→17 = 1.13，17→20 = 1.18，
 * 20→24 = 1.20，24→30 = 1.25，30→44 = 1.47。
 * 11/12/13 这种三连之所以要去掉中间那个，就是因为 12/11 = 1.09 —— 不合格。
 *
 * ### 用不用都一样
 * 现有代码大量是内联 `fontSize = 13.sp`，它们和 [TypeScale] 里的常量**数值相等**，
 * 所以不必逐处替换。新代码优先用常量；改老代码时对照本文件的表，不要另造数字。
 *
 * ### 两条轴，一套尺
 * 除了 `fontSize = N.sp`，仓库里还有一条 **Int 轴**：`AnimatedCentsText` /
 * `AmountText` / `CategoryIcon` / `LedgerAvatar` 用 `fontSize: Int` 传值。
 * 它绕过了 TextStyle，也绕过了"看起来像字号"的那一层 —— 统一前那里藏着
 * 21 / 22 / 23 / 26 / 32 五个表外值。**两条轴必须共用同一张表。**
 *
 * 校验：`python scripts/check_type_scale.py`（三条写法都查，发布前必跑）。
 */
object TypeScale {

    /** 首屏唯一的那个大数。一屏只许出现一次。 */
    val Hero: TextUnit = 44.sp

    /** 声明性数字：账户余额、速记金额、导入结果。 */
    val Display: TextUnit = 30.sp

    /** 卡内大金额、sheet 标题。 */
    val Head: TextUnit = 24.sp

    /** 页面区块标题、弹层标题。 */
    val Title: TextUnit = 20.sp

    /** 卡片标题、列表主文本加重。 */
    val Subhead: TextUnit = 17.sp

    /** 正文、流水行主文本、输入框。 */
    val Body: TextUnit = 15.sp

    /** 次级说明：备注、时间、笔数、项目名。 */
    val Caption: TextUnit = 13.sp

    /** overline、chip 文字、图表轴刻度。**中文的下限**，不许再往下压。 */
    val Label: TextUnit = 11.sp

    /** 允许出现的全部字号（sp）。校验脚本与这里保持逐字一致。 */
    val allowed: Set<Float> = setOf(11f, 13f, 15f, 17f, 20f, 24f, 30f, 44f)
}
