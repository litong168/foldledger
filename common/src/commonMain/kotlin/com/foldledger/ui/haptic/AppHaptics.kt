package com.foldledger.ui.haptic

import com.foldledger.ui.l10n.t
/**
 * 触感反馈引擎（v5.0）。
 *
 * commonMain 只声明语义接口；平台实现：
 * - Android（androidMain）：走 Vibrator，支持振幅控制，尊重系统「触感反馈」总开关；
 * - 桌面（desktopMain）：no-op（Windows 桌面暂不做震动）。
 *
 * 强度与开关由设置页写入（[strength] / [keypadEnabled]），AppRoot 里随设置同步。
 * 所有调用都吞掉异常 —— 震动失败绝不能让记账流程崩。
 */
expect object AppHaptics {

    /** 0 关 / 1 轻 / 2 中 / 3 强 */
    var strength: Int

    /** 数字键盘是否震动 */
    var keypadEnabled: Boolean

    /** 数字键：极短的一下，只有"哒"的感觉 */
    fun key()

    /** 普通按钮 / 切换 */
    fun tap()

    /** 确认类动作（保存、删除、创建）：稍重一点，给t("事情办成了")的实感 */
    fun confirm()

    /** 设置页「试一下」用：按当前强度播一次确认反馈 */
    fun preview()
}
