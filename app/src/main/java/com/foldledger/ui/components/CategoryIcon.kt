package com.foldledger.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foldledger.ui.theme.glassSurface
import com.foldledger.ui.theme.tintFor
import com.foldledger.util.IconRef
import com.foldledger.util.IconStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 统一的「分类图标」渲染入口。
 *
 * 所有展示分类图标的地方（明细流水、记一笔分类格、统计排行、分类管理）都走这里，
 * 于是「Emoji / 用户上传图片」这两条路径只需要实现一次。
 *
 * 图片解码放在 IO 线程 + [IconStore] 的 LRU 缓存，列表滚动时不会反复解码。
 */
@Composable
fun CategoryIcon(
    ref: String?,
    size: Dp,
    fontSize: Int = 20,
    modifier: Modifier = Modifier
) {
    if (!IconRef.isCustom(ref)) {
        // 预置 / 手选 Emoji 路径：直接文本渲染，零成本。
        // 外面套一层固定 [size] 的 Box —— 否则 Emoji 的实际占位会随字体度量浮动，
        // 和「上传图片」那条严格见方的路径对不齐（基线和大小都会错位），
        // 传进来的 size 也就成了假参数。
        Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
            Text(
                text = IconRef.asText(ref),
                fontSize = fontSize.sp,
                maxLines = 1
            )
        }
        return
    }

    val context = LocalContext.current
    val fileName = remember(ref) { IconRef.fileNameOf(ref) }
    val bitmap by produceState<ImageBitmap?>(initialValue = null, fileName) {
        value = withContext(Dispatchers.IO) {
            IconStore.load(context, fileName)?.asImageBitmap()
        }
    }

    val bmp = bitmap
    if (bmp != null) {
        Image(
            bitmap = bmp,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier
                .size(size)
                .clip(RoundedCornerShape(size * 0.28f))
        )
    } else {
        // 图片不在了（例如恢复了旧备份、但图标文件没跟过来）→ 优雅降级
        Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
            Text("🖼️", fontSize = fontSize.sp)
        }
    }
}

/**
 * 带圆角底色的图标槽位，列表与网格里最常用的形态。
 *
 * v11「墨白」把这块从"小玻璃砖"改回**淡彩纸标**：
 * 底色仍是按 [ref] 稳定哈希取的淡彩，但不再叠顶部高光、底部压暗、受光内边。
 * 理由很直接 —— 一个 40dp 的方块上叠三层光影，只有在"整站都是玻璃"时才成立；
 * 现在卡片是平的纸，小方块反而发光，就会像贴在纸上的塑料片。
 * 纸标只需要：淡彩底 + 1px 同色系描边，安静地当分类的色标。
 */
@Composable
fun CategoryIconBadge(
    ref: String?,
    size: Dp,
    fontSize: Int,
    container: Color,
    modifier: Modifier = Modifier
) {
    val radius = RoundedCornerShape(size * 0.28f)
    // 传入的是主题灰（surfaceVariant）时，换成按图标稳定的淡彩色块
    val isNeutral = container == MaterialTheme.colorScheme.surfaceVariant
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val tintColor = if (isNeutral) tintFor(ref ?: "", dark) else container
    // v12：分类标是"一块染了色的小玻璃"。
    // 玻璃上不能贴纯色小方 —— 那会像浮在表面的一张贴纸；颜色必须以**半透明的膜**存在，
    // 所以把淡彩以 55% 混进白膜里，再让材质自己叠上高光与色散边。
    val fill = androidx.compose.ui.graphics.lerp(
        Color.White.copy(alpha = if (dark) 0.10f else 0.46f),
        tintColor.copy(alpha = if (dark) 0.34f else 0.62f),
        0.55f
    )
    Box(
        modifier = modifier
            .size(size)
            .glassSurface(
                shape = radius,
                elevation = 2.dp,
                fill = fill,
                specular = 0.65f
            ),
        contentAlignment = Alignment.Center
    ) {
        CategoryIcon(ref = ref, size = size * 0.72f, fontSize = fontSize)
    }
}
