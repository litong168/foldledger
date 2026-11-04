package com.foldledger.ui.adaptive

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowLayoutInfo

/**
 * Android 实现：LocalConfiguration + WindowInfoTracker（FoldingFeature）。
 * 铰链比例换算留在本文件（纯 Android 几何），断点/姿态判定复用 commonMain 的 buildEnv。
 */
@Composable
actual fun rememberFoldEnv(): FoldEnv {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val windowInfo = LocalWindowInfo.current
    val activity = remember(context) { context.findActivity() }

    // 窗口像素尺寸（Compose 1.7+ 提供，折叠瞬间即时更新）
    val container = windowInfo.containerSize
    val pxPerDp = density.density
    val widthPx = if (container.width > 0) container.width else (configuration.screenWidthDp * pxPerDp).toInt()
    val heightPx = if (container.height > 0) container.height else (configuration.screenHeightDp * pxPerDp).toInt()
    val widthDp = (widthPx / pxPerDp).toInt()
    val heightDp = (heightPx / pxPerDp).toInt()

    // 只要见过一次铰链，就记住这是台折叠屏（v3.1.1）。
    val everFoldable = remember { mutableStateOf(false) }

    val foldFeature by produceState<FoldingFeature?>(
        initialValue = null,
        activity,
        // v15.0.2：旋转（尺寸变化）时**强制重启采集**（HyperOS 半折旋转不重新 emit）
        widthDp,
        heightDp
    ) {
        if (activity == null) return@produceState
        runCatching {
            WindowInfoTracker.getOrCreate(activity)
                .windowLayoutInfo(activity)
                .collect { info: WindowLayoutInfo ->
                    val feature = info.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull()
                    if (feature != null) everFoldable.value = true
                    value = feature
                }
        }
    }

    return remember(widthDp, heightDp, foldFeature, everFoldable.value, FoldPostureDebug.forcedPosture, activity) {
        buildEnv(
            widthDp = widthDp,
            heightDp = heightDp,
            hinge = foldFeature?.let { f ->
                val b = f.bounds
                val horizontal = f.orientation == FoldingFeature.Orientation.HORIZONTAL
                val safeH = if (heightPx > 0) heightPx.toFloat() else heightDp * pxPerDp
                val safeW = widthDp * pxPerDp
                var hingeFraction = 0f
                var leading = 1f
                var trailing = 0f
                if (horizontal) {
                    val hingeSize = b.height().toFloat().coerceAtLeast(0f)
                    hingeFraction = (hingeSize / safeH).coerceIn(0f, 0.5f)
                    leading = (b.top / safeH).coerceIn(0.15f, 0.85f)
                    trailing = ((safeH - b.bottom) / safeH).coerceIn(0.15f, 0.85f)
                } else {
                    val hingeSize = b.width().toFloat().coerceAtLeast(0f)
                    hingeFraction = (hingeSize / safeW).coerceIn(0f, 0.5f)
                    leading = (b.left / safeW).coerceIn(0.2f, 0.8f)
                    trailing = ((safeW - b.right) / safeW).coerceIn(0.2f, 0.8f)
                }
                HingeInfo(
                    isSeparating = f.isSeparating,
                    isHorizontal = horizontal,
                    halfOpened = f.state == FoldingFeature.State.HALF_OPENED,
                    hingeFraction = hingeFraction,
                    leadingFraction = leading,
                    trailingFraction = trailing
                )
            },
            everFoldable = everFoldable.value
        )
    }
}

internal fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
