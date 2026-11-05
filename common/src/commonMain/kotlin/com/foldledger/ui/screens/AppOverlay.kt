package com.foldledger.ui.screens

import com.foldledger.platform.PlatformBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import com.foldledger.ui.adaptive.FoldEnv
import com.foldledger.ui.anim.Motion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.foldledger.ui.theme.Radius
import com.foldledger.ui.theme.SurfaceCard
import com.foldledger.ui.theme.overlaySurface
import com.foldledger.ui.theme.scrimColor

/** 退场动画时长，播完才真正通知外层关闭 */
private const val OVERLAY_EXIT_MS = 200L

/**
 * 弹层落位模式（v3.1.1 从 env 提炼出来，只为「打开时冻结」）。
 */
private enum class OverlayPlacement {
    /** 半折叠 · 笔记本姿态：上屏选择区 / 下屏操作区 */
    TABLETOP,

    /** 半折叠 · 书本姿态：左屏选择区 / 右屏操作区 */
    BOOK,

    /** 展开大屏：居中大卡片 */
    DIALOG,

    /** 外屏 / 直板机：底部抽屉 */
    SHEET
}

private fun overlayPlacement(env: FoldEnv): OverlayPlacement = when {
    env.isTabletop -> OverlayPlacement.TABLETOP
    env.isBook -> OverlayPlacement.BOOK
    env.useDialogOverlay -> OverlayPlacement.DIALOG
    else -> OverlayPlacement.SHEET
}

/**
 * 折叠屏感知的弹层容器（带进退场动画）。
 *
 * 不同形态下的落位策略：
 *
 * | 形态            | 弹层位置                                        |
 * |----------------|------------------------------------------------|
 * | 外屏 / 直板机   | 底部抽屉，占满宽度，从下方滑入                      |
 * | 内屏展开        | 居中大卡片，宽度上限 620dp，弹性缩放进入              |
 * | 半折叠·笔记本态 | **只占用铰链下方的一半**，上半屏继续透出主界面          |
 * | 半折叠·书本态   | **只占用铰链右侧的一半**，左侧透出主界面              |
 *
 * 关键点：弹层绝不跨越铰链，避免控件落在折叠缝上按不到。
 * 关闭时会先播退场动画，再回调 [onDismiss]，所以看起来是"滑走"而不是"啪一下没了"。
 *
 * ⚠️ v3.1.1 曾把落位模式在打开瞬间用 remember 冻结，理由是"折叠/展开时不换外壳、
 * 避免抽屉外壳与内容分裂闪帧"。但用户实测：开着记一笔时旋转（半折→横屏），
 * 外壳冻结 + 内容分支实时 = 两层错配（内容按居中卡排、外壳却是左右半屏），
 * 直接只剩半屏。物理旋转是慢动作，一次外壳重组可接受；v15.0.3 起**跟随实时姿态**，
 * 与 AddTxnOverlay 的内容分支同源同变，不再出现"外壳与内容各执一词"。
 */
@Composable
fun AppOverlay(
    env: FoldEnv,
    onDismiss: () -> Unit,
    leadingContent: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit
) {
    var visible by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { visible = true }

    val requestDismiss: () -> Unit = {
        if (!closing) {
            closing = true
            scope.launch {
                visible = false
                delay(OVERLAY_EXIT_MS)
                onDismiss()
            }
        }
    }

    PlatformBackHandler(enabled = true) { requestDismiss() }

    // v9.7.3：深色主题下 40% 黑压在 #141B2A 上几乎看不出"变暗"，
    // 弹层和背景糊成一片；深色用更重的一层，浅色沿用 34% 的系统蒙层。
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    // v12：遮罩统一走"把背景推远"的 34%，深色下略加深一点，但不再有 58% 的黑墙
    val scrim = if (isDark) Color(0xFF05070B).copy(alpha = 0.46f) else scrimColor()
    // 第二层高浓度玻璃：压在深色蒙层上，不透明度比卡片高，保证文字对比度
    val panelColor = overlaySurface()

    // 落位模式跟随实时姿态（v15.0.3，见函数文档）
    val placement = overlayPlacement(env)

    when (placement) {
        // ---------- 半折叠 · 笔记本姿态：上屏 = 选择区，下屏 = 操作区 ----------
        OverlayPlacement.TABLETOP -> {
            Column(Modifier.fillMaxSize()) {
                AnimatedVisibility(
                    visible = visible,
                    enter = fadeIn(tween(Motion.MEDIUM, easing = Motion.standard)) +
                        slideInVertically(animationSpec = Motion.soft()) { -it / 5 },
                    exit = fadeOut(tween(Motion.FAST)) +
                        slideOutVertically(animationSpec = Motion.snappy()) { -it / 8 },
                    modifier = Modifier.weight(env.leadingFraction.coerceAtLeast(0.2f))
                ) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = if (leadingContent != null) MaterialTheme.colorScheme.background else scrim,
                        shape = RoundedCornerShape(bottomStart = 22.dp, bottomEnd = 22.dp)
                    ) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .then(
                                    if (leadingContent == null) {
                                        Modifier.clickable(
                                            indication = null,
                                            interactionSource = remember { MutableInteractionSource() }
                                        ) { requestDismiss() }
                                    } else {
                                        Modifier
                                    }
                                )
                        ) {
                            leadingContent?.invoke()
                        }
                    }
                }
                if (env.hingeFraction > 0.015f) {
                    Box(
                        Modifier
                            .weight(env.hingeFraction)
                            .fillMaxWidth()
                    )
                }
                AnimatedVisibility(
                    visible = visible,
                    enter = fadeIn(tween(Motion.MEDIUM, easing = Motion.standard)) +
                        slideInVertically(animationSpec = Motion.bouncy()) { it },
                    exit = fadeOut(tween(Motion.FAST)) +
                        slideOutVertically(animationSpec = Motion.snappy()) { it },
                    modifier = Modifier.weight(env.trailingFraction.coerceAtLeast(0.25f))
                ) {
                    SurfaceCard(
                        modifier = Modifier.fillMaxSize(),
                        color = panelColor,
                        elevation = 14.dp,
                        // v12.0.1：整页弹层必须把底部白膜压平 —— 上厚下薄的落差留在
                        // 这么高的面上，下半张就成了半透的，底下的页面会整片透上来
                        filmDepth = 1f,
                        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
                    ) {
                        Box(Modifier.fillMaxSize()) { content() }
                    }
                }
            }
        }

        // ---------- 半折叠 · 书本姿态：左屏 = 选择区，右屏 = 操作区 ----------
        OverlayPlacement.BOOK -> {
            Row(Modifier.fillMaxSize()) {
                AnimatedVisibility(
                    visible = visible,
                    enter = fadeIn(tween(Motion.MEDIUM, easing = Motion.standard)),
                    exit = fadeOut(tween(Motion.FAST)),
                    modifier = Modifier.weight(env.leadingFraction.coerceAtLeast(0.2f))
                ) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = if (leadingContent != null) MaterialTheme.colorScheme.background else scrim,
                        shape = RoundedCornerShape(topEnd = 22.dp, bottomEnd = 22.dp)
                    ) {
                        // 没有 leadingContent 时，这一侧只是蒙层 —— 要能点一下关掉，
                        // 否则用户"点外面想关"会毫无反应（笔记本姿态一直是能点的，两边行为要一致）。
                        Box(
                            Modifier
                                .fillMaxSize()
                                .then(
                                    if (leadingContent == null) {
                                        Modifier.clickable(
                                            indication = null,
                                            interactionSource = remember { MutableInteractionSource() }
                                        ) { requestDismiss() }
                                    } else {
                                        Modifier
                                    }
                                )
                        ) { leadingContent?.invoke() }
                    }
                }
                if (env.hingeFraction > 0.015f) {
                    Box(Modifier.weight(env.hingeFraction).fillMaxHeight())
                }
                AnimatedVisibility(
                    visible = visible,
                    enter = fadeIn(tween(Motion.MEDIUM, easing = Motion.standard)) +
                        slideInVertically(animationSpec = Motion.bouncy()) { it / 2 },
                    exit = fadeOut(tween(Motion.FAST)),
                    modifier = Modifier.weight(env.trailingFraction.coerceAtLeast(0.25f))
                ) {
                    SurfaceCard(
                        modifier = Modifier.fillMaxSize(),
                        color = panelColor,
                        elevation = 14.dp,
                        filmDepth = 1f,
                        shape = RoundedCornerShape(topStart = 22.dp, bottomStart = 22.dp)
                    ) {
                        Box(Modifier.fillMaxSize()) { content() }
                    }
                }
            }
        }

        // ---------- 展开大屏：居中卡片，弹性缩放 ----------
        OverlayPlacement.DIALOG -> {
            Box(Modifier.fillMaxSize()) {
                Scrim(visible = visible, color = scrim, onDismiss = requestDismiss)
                AnimatedVisibility(
                    visible = visible,
                    enter = fadeIn(tween(Motion.MEDIUM, easing = Motion.glide)) +
                        scaleIn(animationSpec = Motion.bouncy(), initialScale = 0.88f),
                    exit = fadeOut(tween(Motion.FAST, easing = Motion.exit)) +
                        scaleOut(
                            animationSpec = tween(Motion.FAST, easing = Motion.exit),
                            targetScale = 0.94f
                        ),
                    modifier = Modifier.align(Alignment.Center)
                ) {
                    SurfaceCard(
                        modifier = Modifier
                            // v16.4：宽屏弹层不再锁 620dp。
                            // 620 是"手机横过来"的宽度；内部却是左右两栏（选分类 + 键盘），
                            // 键盘那栏固定 380 → 分类区只剩 240dp，于是账本 chip 折行、
                            // 模板挤成一条、数字键比分类区还宽 —— 屏幕利用率低到荒谬。
                            .widthIn(
                                max = when {
                                    env.widthDp >= 1400 -> 1000.dp
                                    env.widthDp >= 1100 -> 920.dp
                                    env.widthDp >= 900 -> 820.dp
                                    else -> 620.dp
                                }
                            )
                            .fillMaxHeight(0.9f)
                            .padding(
                                horizontal = 16.dp,
                                vertical = WindowInsets.statusBars.asPaddingValues()
                                    .calculateTopPadding() + 12.dp
                            ),
                        color = panelColor,
                        elevation = 18.dp,
                        filmDepth = 1f,
                        shape = RoundedCornerShape(24.dp)
                    ) {
                        Box(Modifier.fillMaxSize()) { content() }
                    }
                }
            }
        }

        // ---------- 外屏 / 直板机：底部抽屉，从下方滑入 ----------
        else -> {
            Box(Modifier.fillMaxSize()) {
                Scrim(visible = visible, color = scrim, onDismiss = requestDismiss)
                AnimatedVisibility(
                    visible = visible,
                    enter = fadeIn(tween(Motion.MEDIUM, easing = Motion.glide)) +
                        slideInVertically(animationSpec = Motion.soft()) { it },
                    exit = fadeOut(tween(Motion.FAST, easing = Motion.exit)) +
                        slideOutVertically(
                            animationSpec = tween(Motion.FAST, easing = Motion.exit)
                        ) { it },
                    modifier = Modifier.align(Alignment.BottomCenter)
                ) {
                    SurfaceCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(0.94f),
                        color = panelColor,
                        elevation = 18.dp,
                        filmDepth = 1f,
                        shape = RoundedCornerShape(topStart = Radius.sheet, topEnd = Radius.sheet)
                    ) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .padding(
                                    bottom = WindowInsets.navigationBars.asPaddingValues()
                                        .calculateBottomPadding()
                                )
                        ) { content() }
                    }
                }
            }
        }
    }
}

@Composable
private fun Scrim(visible: Boolean, color: Color, onDismiss: () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(Motion.MEDIUM, easing = Motion.glide)),
        exit = fadeOut(tween(Motion.FAST, easing = Motion.exit)),
        modifier = Modifier.fillMaxSize()
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(color)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { onDismiss() }
        )
    }
}

/** 弹层顶部的小拖动条，给「底部抽屉」一个视觉抓手 */
@Composable
fun SheetGrabber(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .padding(top = 8.dp, bottom = 6.dp)
                .size(width = 40.dp, height = 4.dp)
                .background(
                    MaterialTheme.colorScheme.outlineVariant,
                    RoundedCornerShape(50)
                )
        )
    }
}
