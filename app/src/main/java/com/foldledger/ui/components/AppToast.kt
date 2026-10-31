package com.foldledger.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foldledger.ui.theme.Radius
import kotlinx.coroutines.delay

/**
 * 全局轻提示（v9.8.0）。
 *
 * ### 为什么需要它
 * 记账最怕"点了没反应"：保存成功、存成模板、条件不满足被拦下 —— 这些以前都是**静默**的。
 * 弹层关掉只是"消失"，不等于"成功"；用户滚在看老账时更是完全看不到新记的那一笔。
 *
 * ### 为什么不像 Snackbar
 * Material 的 Snackbar 需要一个 Scaffold 的 SnackbarHostState 一路往下传，而本 App 的
 * 弹层是独立 `Box` 叠在 NavHost 之上、且有多套外壳（折叠/平板/手机），传参成本远大于收益。
 * 这里用一个进程内"当前消息" + 根节点的一个 host 解决，任何地方一行 `AppToastBus.show("…")` 就能提示。
 */
object AppToastBus {

    /** 带自增序号：同一句话连发两次也能重新计时（只比对文案会"第二句一闪而过"） */
    data class Message(
        val text: String,
        val token: Int,
        /** v15：可选动作（如「撤销」）。带动作时停留时间翻倍，让手指有时间够到它 */
        val actionLabel: String? = null,
        val onAction: (() -> Unit)? = null
    )

    var current by mutableStateOf<Message?>(null)
        private set

    private var token by mutableIntStateOf(0)

    fun show(text: String) = show(text, null, null)

    fun show(text: String, actionLabel: String?, onAction: (() -> Unit)?) {
        if (text.isBlank()) return
        token++
        current = Message(text, token, actionLabel, onAction)
    }

    internal fun dismiss(token: Int) {
        if (current?.token == token) current = null
    }
}

/**
 * 提示的渲染点（放在 AppRoot 的根 Box 里，底部居中）。
 *
 * 位置刻意偏高 96dp：记账页底部是数字键盘，贴着底边会被遮住。
 */
@Composable
fun AppToastHost(modifier: Modifier = Modifier) {
    val message = AppToastBus.current
    LaunchedEffect(message?.token) {
        val m = message ?: return@LaunchedEffect
        // 带动作的提示多留一倍时间：撤销/回滚这种"错过就没了"的动作，不能催
        delay(if (m.actionLabel != null) 4200 else 1900)
        AppToastBus.dismiss(m.token)
    }

    AnimatedVisibility(
        visible = message != null,
        modifier = modifier,
        enter = fadeIn() + slideInVertically { it / 3 },
        exit = fadeOut() + slideOutVertically { it / 3 }
    ) {
        val m = message ?: return@AnimatedVisibility
        Surface(
            shape = RoundedCornerShape(Radius.pill),
            color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.96f),
            shadowElevation = 6.dp
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)
            ) {
                Text(
                    text = m.text,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(vertical = 3.dp)
                )
                if (m.actionLabel != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        m.actionLabel,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.inversePrimary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(Radius.pill))
                            .clickable { m.onAction?.invoke(); AppToastBus.dismiss(m.token) }
                            .padding(horizontal = 10.dp, vertical = 7.dp)
                    )
                }
            }
        }
    }
}
