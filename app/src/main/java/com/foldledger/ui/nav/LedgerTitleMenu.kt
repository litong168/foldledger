package com.foldledger.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foldledger.util.Money
import com.foldledger.ui.anim.clickableScale
import com.foldledger.ui.theme.Radius
import com.foldledger.ui.theme.LocalDirectionColors
import com.foldledger.ui.theme.wellSurface
import com.foldledger.ui.vm.LedgerUi

/**
 * 顶栏标题下拉里的账本列表（v13.7）。
 *
 * ### 它替掉了什么
 * 原来顶栏下面有一条 48dp 的账本 chips 横条。它和大标题其实是同一件事：
 * 标题写「明细」（恒定，无信息），副标题写账本名，chips 再写一遍账本名 ——
 * 同一个词在一屏里出现两次，而 23sp 那颗最大的字永远在说同一句话。
 *
 * 现在大标题 = 当前账本名，切换收进标题右侧的箭头。**信息一条没少**：
 * 每条仍然带结余（chips 上原来就有的那个数），当前项带勾。
 *
 * ### 为什么不用常驻 chips
 * 换账本是"切视角"，不是每天要看的内容。为它常驻 48dp，等于每屏都先把
 * 七分之一的高度交给一个偶尔才点的动作。收进菜单后，屏幕归内容。
 */
@Composable
fun LedgerTitleMenuContent(
    cards: List<LedgerUi>,
    currentId: Long?,
    onPick: (Long) -> Unit
) {
    val dc = LocalDirectionColors.current
    val ink = MaterialTheme.colorScheme.onSurface
    val faint = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.widthIn(min = 236.dp)) {
        Text(
            "切换账本",
            fontSize = 11.sp,
            letterSpacing = 0.6.sp,
            fontWeight = FontWeight.Medium,
            color = faint,
            modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 2.dp)
        )
        cards.forEach { ui ->
            val active = ui.ledger.id == currentId
            val balance = ui.balanceCents
            val rowShape = RoundedCornerShape(Radius.md)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 1.dp)
                    .clip(rowShape)
                    // 当前项用"凹槽"而不是"高亮块"：菜单里已经有一个勾，
                    // 再铺一层主色就等于同一件事说两遍（而且主色块在暖底上最扎眼）。
                    .then(if (active) Modifier.background(wellSurface(), rowShape) else Modifier)
                    .clickableScale(pressedScale = 0.98f, clipShape = rowShape) {
                        if (!active) onPick(ui.ledger.id)
                    }
                    .padding(horizontal = 8.dp, vertical = 7.dp)
            ) {
                Text(ui.ledger.emoji, fontSize = 20.sp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        ui.ledger.name,
                        fontSize = 15.sp,
                        // 显式行高：只给 fontSize 时行盒继承主题的 bodyLarge（24dp），
                        // 两行字就把一行撑到 64dp —— 菜单四条占了 260dp。
                        lineHeight = 20.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        color = ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "结余 ${Money.format(balance)}",
                        fontSize = 13.sp,
                        lineHeight = 17.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (balance >= 0) dc.income else dc.expense,
                        maxLines = 1
                    )
                }
                if (active) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = "当前账本",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(17.dp)
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}
