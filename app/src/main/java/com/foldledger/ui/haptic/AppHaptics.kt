package com.foldledger.ui.haptic

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings

/**
 * 触感反馈引擎（v5.0）。
 *
 * 之前只有 `LocalHapticFeedback` 的两种系统预置（TextHandleMove / LongPress），
 * **强度完全不可调**、数字键盘上也没有反馈。这里改成走 `Vibrator`：
 *
 * - 支持**振幅控制**（`hasAmplitudeControl()` 的线性马达），四档强度真实可感；
 * - 不支持振幅的设备退化为固定振幅，仍然有反馈；
 * - 尊重系统「触感反馈」总开关（关掉就静音，不做打扰）；
 * - 三个语义入口：[key] 数字键（最短促）、[tap] 普通按钮、[confirm] 确认类动作。
 *
 * 强度与开关由设置页写入（[strength] / [keypadEnabled]），AppRoot 里随设置同步。
 * 所有调用都吞掉异常 —— 震动失败绝不能让记账流程崩。
 */
object AppHaptics {

    /** 0 关 / 1 轻 / 2 中 / 3 强 */
    @Volatile
    var strength: Int = 2

    /** 数字键盘是否震动 */
    @Volatile
    var keypadEnabled: Boolean = true

    /** 数字键：极短的一下，只有"哒"的感觉 */
    fun key(ctx: Context) {
        if (!keypadEnabled) return
        // 时长也随强度变化：轻档更短更脆，强档稍长更实
        play(ctx, durationMs = when (strength) {
            1 -> 6L
            2 -> 9L
            else -> 13L
        }, amplitude = amplitude())
    }

    /** 普通按钮 / 切换 */
    fun tap(ctx: Context) = play(ctx, durationMs = 10L, amplitude = amplitude())

    /** 确认类动作（保存、删除、创建）：稍重一点，给"事情办成了"的实感 */
    fun confirm(ctx: Context) = play(ctx, durationMs = 18L, amplitude = (amplitude() * 1.15f).toInt())

    /** 设置页「试一下」用：按当前强度播一次确认反馈 */
    fun preview(ctx: Context) = confirm(ctx)

    private fun amplitude(): Int = when (strength) {
        1 -> 70    // 轻：能感觉到但不吵
        2 -> 140   // 中：默认，清晰可辨
        3 -> 235   // 强：扎实
        else -> 0
    }

    private fun play(ctx: Context, durationMs: Long, amplitude: Int) {
        if (strength <= 0 || amplitude <= 0) return
        if (!systemHapticsEnabled(ctx)) return
        val v = vibrator(ctx) ?: return
        if (!v.hasVibrator()) return
        runCatching {
            val effect = if (v.hasAmplitudeControl()) {
                VibrationEffect.createOneShot(durationMs, amplitude.coerceIn(1, 255))
            } else {
                VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE)
            }
            v.vibrate(effect)
        }
    }

    private fun vibrator(ctx: Context): Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                ?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }.getOrNull()

    /** 系统「触感反馈」总开关；读不到时按开启处理 */
    private fun systemHapticsEnabled(ctx: Context): Boolean = runCatching {
        Settings.System.getInt(
            ctx.contentResolver,
            Settings.System.HAPTIC_FEEDBACK_ENABLED,
            1
        ) != 0
    }.getOrDefault(true)
}
