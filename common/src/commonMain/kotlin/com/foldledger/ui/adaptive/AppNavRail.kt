package com.foldledger.ui.adaptive

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foldledger.ui.anim.clickableScale
import com.foldledger.ui.haptic.AppHaptics
import com.foldledger.ui.theme.GlassDepth
import com.foldledger.ui.theme.GlassKey
import com.foldledger.ui.theme.LocalDarkTheme
import com.foldledger.ui.theme.Radius
import com.foldledger.ui.theme.glassSurface
import com.foldledger.ui.l10n.t

/**
 * 左侧导航导轨（v16.4，≥1024dp 时代替底部胶囊）。
 *
 * ### 为什么宽屏要换导轨，而不是把胶囊拉宽
 * 底部胶囊在手机上是对的：拇指在屏幕下沿，导航就该在那儿。但 1700dp 的桌面窗口里，
 * 这颗 228dp 的胶囊只用掉屏幕宽度的 13%，却占掉一整条 76dp 的可视高度 ——
 * 屏幕越宽它越像"一颗手机底栏漂在大屏下沿"。导航竖起来之后：
 *  - 内容区拿到完整的剩余高度（长列表多三行）；
 *  - 三格导航变成一条竖列，视觉重心落到左边 —— 和侧栏账本列表连成一条阅读轴；
 *  - 导航与内容之间由一条玻璃分隔自然产生，不再需要"浮在内容上"的补丁。
 *
 * ### 材质不变
 * 仍是同一套玻璃：Panel 档的透明膜 + 2.0 棱边 + 14dp 投影，和底栏胶囊是同一块料。
 * 选中态也仍是"一块被托起来的白玻璃滑块"（不是变色），只是方向从横排变竖排。
 * 「记一笔」仍是 [GlassKey] 玻璃球 —— 主行动键不做成实色块，这条没破。
 */
@Composable
fun AppNavRail(
    selected: AppTab,
    onSelected: (AppTab) -> Unit,
    onAdd: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val dark = LocalDarkTheme.current
    val tabs = AppTab.entries

    Box(
        modifier
            .width(NAV_RAIL_DP.dp)
            .fillMaxHeight()
            .padding(start = 12.dp, top = 12.dp, bottom = 16.dp, end = 8.dp)
    ) {
        Column(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth()
                .glassSurface(
                    shape = RoundedCornerShape(Radius.panel),
                    depth = GlassDepth.Panel,
                    scrollAware = true
                )
                .padding(horizontal = 6.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ---- 品牌位：一个小水滴 + 版本层级不需要出现，只是让导轨顶端有"头" ----
            Box(
                Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(Radius.sm))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = if (dark) 0.16f else 0.10f)),
                contentAlignment = Alignment.Center
            ) {
                Text("💧", fontSize = 17.sp, lineHeight = 20.sp)
            }
            Spacer(Modifier.height(18.dp))

            // ---- 三个分区 ----
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                tabs.forEach { tab ->
                    RailItem(
                        tab = tab,
                        active = tab == selected,
                        onClick = {
                            AppHaptics.tap()
                            onSelected(tab)
                        }
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            // ---- 记一笔：导轨底部的那颗玻璃球 ----
            if (onAdd != null) {
                GlassKey(
                    size = 52.dp,
                    tint = MaterialTheme.colorScheme.primary,
                    tintBoost = 1.30f,
                    glow = MaterialTheme.colorScheme.primary,
                    scrollAware = true,
                    onClick = onAdd
                ) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = t("记一笔"),
                        tint = if (dark) MaterialTheme.colorScheme.primary
                        else androidx.compose.ui.graphics.lerp(
                            MaterialTheme.colorScheme.primary, Color.Black, 0.24f
                        ),
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    t("记一笔"),
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * 导轨里的一格。选中 = 背后一块被托起来的白玻璃（与底部胶囊的滑块同一种做法），
 * 图标微缩回弹（与 [AnimatedTabIcon] 同一个手感，参数收得更小 —— 竖排不需要大动作）。
 */@Composable
private fun RailItem(tab: AppTab, active: Boolean, onClick: () -> Unit) {
    val dark = LocalDarkTheme.current
    val scale by animateFloatAsState(
        targetValue = if (active) 1f else 0.94f,
        animationSpec = spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow),
        label = "railIcon"
    )
    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth()) {
        if (active) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(RoundedCornerShape(Radius.md))
                    .background(Color.White.copy(alpha = if (dark) 0.12f else 0.72f))
                    .border(
                        width = 1.dp,
                        brush = Brush.verticalGradient(
                            listOf(
                                Color.White.copy(alpha = if (dark) 0.12f else 0.95f),
                                MaterialTheme.colorScheme.onSurface.copy(
                                    alpha = if (dark) 0.06f else 0.09f
                                )
                            )
                        ),
                        shape = RoundedCornerShape(Radius.md)
                    )
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(RoundedCornerShape(Radius.md))
                .clickableScale(pressedScale = 0.96f, clipShape = RoundedCornerShape(Radius.md)) {
                    onClick()
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                tab.icon,
                contentDescription = tab.label,
                tint = if (active) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(20.dp)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    }
            )
            Spacer(Modifier.height(3.dp))
            Text(
                tab.label,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                color = if (active) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}
