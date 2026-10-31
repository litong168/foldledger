package com.foldledger.util

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.view.Window
import android.view.WindowManager

/**
 * 高刷新率控制。
 *
 * ### 为什么不用 `Window.setFrameRate`
 * 那个方法在 AOSP 里是 **@hide**，`android.jar` 里没有（实测 compileSdk 35 的
 * `android.view.Window` 只暴露了 `setFrameRatePowerSavingsBalanced`）。
 * 公开可用的只剩三条路，这里三条一起用：
 *
 * | API | 手段 | 作用 |
 * |---|---|---|
 * | 全版本 | `LayoutParams.preferredRefreshRate` | 直接声明首选刷新率（API 30 起官方不再推荐，但仍是唯一能覆盖 26~29 的办法） |
 * | 30+ | `LayoutParams.setFrameRatePowerSavingsBalanced(false)` | **关掉自适应刷新的省电平衡** —— 开着的时候系统会把动画压到 60Hz |
 * | 35+ | `View.setRequestedFrameRate()` | 把「我要这个帧率」的渲染意图明确交给平台（Compose 的 `Modifier.preferredFrameRate` 也走它） |
 *
 * 只请求设备**实际支持的最高值**（通常是 120Hz），不硬编码，避免在不支持高刷的机器上把平台带偏。
 */
object FrameRate {

    /** 设备支持的最高刷新率，最低按 60 返回 */
    fun maxSupported(context: Context): Float {
        val dm = context.getSystemService(DisplayManager::class.java) ?: return 60f
        val display: Display = dm.getDisplay(Display.DEFAULT_DISPLAY) ?: return 60f
        val modes = runCatching { display.supportedModes }.getOrNull()
        val max = modes?.maxOfOrNull { it.refreshRate } ?: display.refreshRate
        return max.coerceIn(60f, 165f)
    }

    /**
     * 应用刷新率策略。
     * @param highRefresh true = 请求设备最高刷新率并关闭省电压制；false = 交回系统默认
     */
    @Suppress("DEPRECATION")
    fun apply(window: Window, highRefresh: Boolean) {
        val target = if (highRefresh) maxSupported(window.context) else 60f

        runCatching {
            val lp = window.attributes as WindowManager.LayoutParams
            lp.preferredRefreshRate = target
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // false = 不做"省电平衡"，动画期间不会被压到 60Hz
                lp.setFrameRatePowerSavingsBalanced(!highRefresh)
            }
            window.attributes = lp
        }

        if (Build.VERSION.SDK_INT >= 35) {
            runCatching { window.decorView.setRequestedFrameRate(target) }
        }
    }
}
