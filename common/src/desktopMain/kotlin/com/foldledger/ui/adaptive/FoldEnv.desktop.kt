package com.foldledger.ui.adaptive

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo

/**
 * 桌面实现：窗口尺寸 → 断点/姿态（无铰链，宽窗口按宽度判 UNFOLDED）。
 */
@Composable
actual fun rememberFoldEnv(): FoldEnv {
    val windowInfo = LocalWindowInfo.current
    val density = LocalDensity.current
    val container = windowInfo.containerSize
    val pxPerDp = density.density
    val widthDp = if (container.width > 0) (container.width / pxPerDp).toInt() else 411
    val heightDp = if (container.height > 0) (container.height / pxPerDp).toInt() else 866
    return remember(widthDp, heightDp, FoldPostureDebug.forcedPosture) {
        buildEnv(widthDp = widthDp, heightDp = heightDp, hinge = null, everFoldable = false)
    }
}
