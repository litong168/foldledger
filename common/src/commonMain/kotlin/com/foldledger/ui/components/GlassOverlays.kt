package com.foldledger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.foldledger.platform.PlatformClearDialogDim
import com.foldledger.ui.anim.clickableScale
import com.foldledger.ui.theme.Radius
import com.foldledger.ui.theme.glassSurface
import com.foldledger.ui.theme.overlaySurface
import com.foldledger.ui.theme.popoverColor

/**
 * ============================================================
 *  呼出层（弹层 / 菜单）的液态玻璃
 * ============================================================
 *
 * ### 为什么需要这个文件
 *
 * v13.8 之前，全站「看得见的东西」（卡片、流水行、底栏胶囊、记一笔圆键）都已经
 * 换成了液态玻璃，但**呼出层没有** —— 22 个 `AlertDialog` 和十来处 `DropdownMenu`
 * 用的还是 Material3 自带的 `Surface`：
 *
 *  - `AlertDialog` 底是 `surfaceContainerHigh`，一块**不透明的实色**；
 *  - `DropdownMenu` 底是 `surfaceContainer`，一块**不透明的实色 + 系统投影**。
 *
 * 于是"点开一个菜单 / 确认框"的瞬间，界面材质从玻璃跳回塑料。这不是配色不一致，
 * 是**材质断层**：玻璃的语言是"边缘有折射、中间几乎透明"，而实色卡片的语言是
 * "一块涂满的平面"。两者放在同一屏里，读起来像两个应用。
 *
 * ### 做法：把材质注入到 M3 的**同一套布局**里
 *
 * 这里不去重写 `AlertDialog` / `DropdownMenu` 的排版（那会带来 22 处布局回归风险），
 * 而是把 `containerColor` 设成**透明**，再把 `glassSurface` 挂在它们的 `modifier` 上 ——
 * M3 的 modifier 最终落在内部那层 `Surface` 上，于是玻璃在下面、内容在上面，
 * 布局一行没变，材质整块换掉。半径、内边距、按钮位置全部沿用系统行为。
 *
 * ### 三个必须写在代码里的数
 *
 * | 项 | 值 | 为什么 |
 * |---|---|---|
 * | 膜 | [overlaySurface] 88% / [popoverColor] 94% | 弹层有 34% 遮罩托底，可以比内容卡更透；菜单没有遮罩，必须更实 |
 * | 高光倍率 | 1.45~1.55 | 小面积上"边缘"是唯一的材质证据，按内容卡的 1.0 给会读成描边 |
 * | `filmDepth` | 0.7~0.82 | 弹层高度大，留着"上厚下薄"的落差，下半张会透出背景文字 |
 *
 * @see glassSurface
 */

/** 弹层与菜单共用的圆角：比内容卡（26）大一档，比抽屉（36）小一档 */
private val OverlayShape = RoundedCornerShape(Radius.panel)

/**
 * 玻璃对话卡 —— `AlertDialog` 的自绘版。
 *
 * 参数与 M3 的 `AlertDialog` 一致（只保留本项目用到的五个槽），
 * 所以 22 个调用点只需把函数名换掉，布局与文案一个字都不用动。
 *
 * ### 为什么不再直接包 M3 的 `AlertDialog`（v13.9）
 *
 * v13.8 的第一版是 `AlertDialog(containerColor = Transparent, modifier = glassSurface)`：
 * 布局零风险，但**遮罩拿不到手**。Android 对话框主题的 `backgroundDimAmount`
 * 默认是 **0.6**，实测背景被压到 (92,92,91)。在这个底上：
 *
 *  - 玻璃"透出背景"这件事彻底失去意义 —— 背后已经是一片近黑，
 *    透过 88% 的白膜什么都看不见，透明度白给了；
 *  - 面板与背景的落差变成 247 : 92，是"白板贴在黑布上"，
 *    不是"一层玻璃浮在页面上"。整屏只剩两种材质：黑、白。
 *
 * 所以我们改成自己开 `Dialog` 并 `setDimAmount(0f)`，遮罩自己画 30%：
 * 背景仍然退到后面（可读性/焦点），但还看得出是**同一个页面**，
 * 玻璃的折射带和虹彩这时候才有东西可折射。
 *
 * 布局照抄 M3 的度量（24dp 容器内边距、标题/正文 16dp、按钮 8dp 间距），
 * 因此调用点的观感与之前一致。
 */
@Composable
fun GlassDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    /**
     * v16.1.1：小屏（折叠态竖屏 / 横屏展开态）下超高的弹窗会把底部的
     * 确认/取消按钮挤出屏幕。开启后整个弹窗内容（含按钮行）可滚动，
     * 保证按钮永远滚得到 —— 调用方须确保 text 槽内不再自带 verticalScroll
     * （同向嵌套滚动会让外层滚不动，按钮仍然够不到）。
     */
    scrollable: Boolean = false
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        // ⚠️ Android：必须在 Dialog 的内容里取 LocalView 改 dim —— 写在 GlassDialog
        // 外层拿到的是 Activity 的 view，改了没用。桌面端无此概念（平台桥 no-op）。
        PlatformClearDialogDim()
        Box(
            Modifier
                .fillMaxSize()
                .background(DialogScrim)
                .pointerInput(Unit) {
                    detectTapGestures { onDismissRequest() }
                },
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier
                    .widthIn(min = 280.dp, max = 520.dp)
                    .then(
                        if (scrollable) Modifier.verticalScroll(rememberScrollState())
                        else Modifier
                    )
                    .glassSurface(
                        shape = OverlayShape,
                        elevation = 22.dp,
                        fill = overlaySurface(),
                        specular = 1.55f,
                        // 弹层很高，不压平的话下半张会透出背后的流水行
                        filmDepth = 0.82f,
                        // 弹层底下是 30% 遮罩（近白底被压到 ~170），
                        // 白棱这时候本身就看得见，暗线按内容卡档位给就够。
                        edgeInk = 1.35f
                    )
                    .padding(horizontal = 24.dp, vertical = 22.dp)
            ) {
                title?.invoke()
                if (title != null && text != null) Spacer(Modifier.height(14.dp))
                text?.invoke()
                Spacer(Modifier.height(18.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    dismissButton?.invoke()
                    if (dismissButton != null) Spacer(Modifier.width(8.dp))
                    confirmButton()
                }
            }
        }
    }
}

/** 弹层遮罩：30% 的墨。比 Android 对话框默认的 60% 轻一半 —— 见 [GlassDialog] 的说明。 */
private val DialogScrim = Color(0xFF12161C).copy(alpha = 0.30f)


/**
 * 小号强调按钮：**被主色染色的玻璃胶囊**。
 *
 * 用来替代这一整类写法：`Surface(shape = 50, color = primary) { Text(..., color = onPrimary) }`。
 * 那是一条"实色小胶囊"——在玻璃卡里就是一片贴上去的蓝色标签。
 * 这里把主色铺到 90% 当膜（`.glassProminent` 的做法），再让材质引擎给它棱和折射环：
 * 它仍然是"一眼就认得出的主行动"，但边界是一条受光的斜棱，不是一条画出来的贴纸边。
 *
 * @param fontSize 11sp（权限行那种小按钮）/ 13sp（卡内主行动）
 */
@Composable
fun AccentPill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fontSize: Int = 11,
    height: Dp = 0.dp
) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier = modifier
            .then(if (height > 0.dp) Modifier.height(height) else Modifier)
            .glassSurface(
                shape = shape,
                elevation = 2.dp,
                fill = MaterialTheme.colorScheme.primary.copy(alpha = 0.90f),
                specular = 1.25f
            )
            .clickableScale(pressedScale = 0.95f, clipShape = shape) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            fontSize = fontSize.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.padding(horizontal = if (fontSize >= 13) 16.dp else 12.dp, vertical = 6.dp)
        )
    }
}

/**
 * 玻璃菜单 —— `DropdownMenu` 的材质替换版。
 *
 * 锚点定位、越界翻转、外部点击关闭全部沿用系统实现；这里只换材质。
 * 菜单项继续用 M3 的 `DropdownMenuItem`：它是透明底 + 文字 + 涟漪，
 * 放在玻璃上表现正确，不需要再造一套。
 *
 * ⚠️ 不要在这里再传 `containerColor` / `shadowElevation`
 * —— 系统投影是"一块不透明板被托起来"的语言，玻璃的影由 [glassSurface] 自己给。
 */
@Composable
fun GlassMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier.glassSurface(
            shape = OverlayShape,
            elevation = 18.dp,
            fill = popoverColor(),
            specular = 1.6f,
            // ⚠️ 必须 1f（等厚）。菜单没有遮罩托底，任何"上厚下薄"的落差都会让
            // 下半张直接变成半透明 —— 实测 0.7 时有 23% 的透光，
            // 背后的「筛选 / 多选」会以灰字浮在菜单上。详见 popoverColor 的注释。
            filmDepth = 1f,
            // ⚠️ v13.9：菜单是**全站底色最白的一块**（97.5% 白），而它底下是
            // 浅色明细页（#EBE8E2）。实测两端只差 (239 vs 250) 十一级 ——
            // 边界在真机上直接消失，读成"一块白板浮着两行字"。
            // 倍率给到 2.0 换一条能看见的发丝边，这是菜单唯一的材质证据。
            edgeInk = 2.0f
        ),
        offset = offset,
        shape = OverlayShape,
        containerColor = Color.Transparent,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        content = content
    )
}
