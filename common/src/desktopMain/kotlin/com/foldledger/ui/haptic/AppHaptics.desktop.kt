package com.foldledger.ui.haptic

/**
 * 触感反馈引擎（桌面 no-op）。Windows 桌面暂不做震动；
 * 语义入口保留，保证双端 UI 代码一致。
 */
actual object AppHaptics {

    @Volatile
    actual var strength: Int = 2

    @Volatile
    actual var keypadEnabled: Boolean = true

    actual fun key() {}
    actual fun tap() {}
    actual fun confirm() {}
    actual fun preview() {}
}
