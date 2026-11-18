package com.foldledger.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foldledger.data.CategoryIconLibrary
import com.foldledger.data.PresetIcon
import com.foldledger.ui.adaptive.FoldEnv
import com.foldledger.ui.anim.clickableScale
import com.foldledger.ui.components.CategoryIcon
import com.foldledger.ui.theme.Radius
import com.foldledger.ui.theme.Space
import com.foldledger.ui.theme.glassChip
import com.foldledger.ui.theme.glassSelectChip
import com.foldledger.ui.l10n.t

/**
 * 图标选择器。
 *
 * 三种取图标的方式，都在这一个弹层里完成：
 *  1. **按分组浏览** —— 18 组、约 300 个预置图标，横向切组，纵向看
 *  2. **关键词搜索** —— 输入「加油」「疫苗」「房贷」直接命中（图标自带中文关键词）
 *  3. **上传自己的图片** —— 走系统相册选择器（Photo Picker，无需存储权限），
 *     导入后压成 256×256 的方形 PNG 存进应用私有目录
 *
 * 点一下即应用并关闭 —— 选图标这件事不该要两次确认。
 */
@Composable
fun IconPickerOverlay(
    env: FoldEnv,
    current: String?,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
    onUpload: (Uri) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var groupIndex by remember { mutableStateOf(0) }

    val results = remember(query) { CategoryIconLibrary.search(query) }
    val currentGroup = CategoryIconLibrary.groups[groupIndex.coerceIn(0, CategoryIconLibrary.groups.size - 1)]

    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) onUpload(uri) }

    AppOverlay(env = env, onDismiss = onDismiss) {
        Column(Modifier.fillMaxSize()) {

            // ---------------- 头部 ----------------
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = Space.xl, end = Space.md, top = Space.lg, bottom = Space.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        t("选择图标"),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "预置 ${CategoryIconLibrary.all.size} 个图标，也可以上传自己的图片",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // 当前图标预览
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(Radius.md))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    CategoryIcon(ref = current, size = 28.dp, fontSize = 24)
                }
                Spacer(Modifier.width(Space.sm))
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(Radius.pill))
                        .clickableScale(pressedScale = 0.88f) { onDismiss() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = t("关闭"),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // ---------------- 上传入口 ----------------
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Space.xl, vertical = Space.xs)
            ) {
                Row(
                    Modifier
                        .glassChip(
                            shape = RoundedCornerShape(Radius.pill),
                            tint = MaterialTheme.colorScheme.primary,
                            strength = 0.4f
                        )
                        .clickableScale(pressedScale = 0.94f) {
                            photoPicker.launch(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly
                                )
                            )
                        }
                        .padding(horizontal = Space.lg, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("🖼️", fontSize = 15.sp)
                    Spacer(Modifier.width(Space.sm))
                    Text(
                        t("从相册上传图片"),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(Modifier.width(Space.sm))
                Text(
                    t("自动裁成正方形，存本机私有目录"),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.CenterVertically)
                )
            }

            // ---------------- 搜索 ----------------
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("搜索（如：加油 / 疫苗 / 房贷）") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Space.xl, vertical = Space.sm)
            )

            // ---------------- 分组切换 ----------------
            if (results == null) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = Space.xl),
                    horizontalArrangement = Arrangement.spacedBy(Space.sm)
                ) {
                    items(CategoryIconLibrary.groups, key = { it.name }) { g ->
                        val active = CategoryIconLibrary.groups[groupIndex].name == g.name
                        // v16.3.1：与桌面图标选择器同款玻璃选中态（浓度 + 内棱 + 抬影），
                        // 去掉 primary 实色块；文字层级也统一到「主色 / onSurfaceVariant」。
                        Box(
                            Modifier
                                .glassSelectChip(active, shape = RoundedCornerShape(Radius.pill))
                                .clip(RoundedCornerShape(Radius.pill))
                                .clickableScale(pressedScale = 0.94f) {
                                    groupIndex = CategoryIconLibrary.groups.indexOf(g)
                                }
                                .padding(horizontal = 13.dp, vertical = 7.dp)
                        ) {
                            Text(
                                "${g.emoji} ${g.name}",
                                fontSize = 13.sp,
                                lineHeight = 19.sp,
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (active) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Spacer(Modifier.height(Space.sm))
            }

            // ---------------- 图标网格 ----------------
            if (results != null && results.isEmpty()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "没找到匹配的图标，换个词试试\n也可以点上面的「从相册上传图片」",
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                val shown: List<PresetIcon> = results ?: currentGroup.icons
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 58.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(
                        start = Space.xl, end = Space.xl, top = Space.xs, bottom = Space.xxl
                    ),
                    horizontalArrangement = Arrangement.spacedBy(Space.sm),
                    verticalArrangement = Arrangement.spacedBy(Space.sm)
                ) {
                    items(shown, key = { it.emoji }) { icon ->
                        val active = icon.emoji == current
                        Box(
                            Modifier
                                .height(54.dp)
                                .glassChip(
                                    shape = RoundedCornerShape(Radius.md),
                                    tint = if (active) MaterialTheme.colorScheme.primary else null,
                                    strength = if (active) 1.1f else 0.4f
                                )
                                .clickableScale(pressedScale = 0.9f) { onPick(icon.emoji) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(icon.emoji, fontSize = 24.sp)
                        }
                    }
                }
            }
        }
    }
}
