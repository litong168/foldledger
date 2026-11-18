package com.foldledger.ui.screens.detail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.foldledger.data.ScenePresets
import com.foldledger.data.db.AccountEntity
import com.foldledger.data.db.CategoryEntity
import com.foldledger.data.db.Direction
import com.foldledger.data.db.LedgerEntity
import com.foldledger.data.db.ProjectAgg
import com.foldledger.data.db.ProjectEntity
import com.foldledger.data.db.ProjectStateAgg
import com.foldledger.data.db.ReimburseState
import com.foldledger.data.db.TransactionEntity
import com.foldledger.data.repo.LedgerRepository
import com.foldledger.ui.adaptive.AdaptiveScaffold
import com.foldledger.ui.adaptive.AppTab
import com.foldledger.ui.adaptive.FoldEnv
import com.foldledger.ui.anim.Motion
import com.foldledger.ui.anim.clickableScale
import com.foldledger.ui.anim.valuePulse
import com.foldledger.ui.components.AmountText
import com.foldledger.ui.components.AnimatedCentsText
import com.foldledger.ui.components.CategoryIconBadge
import com.foldledger.ui.components.EmptyHint
import com.foldledger.ui.components.LedgerAvatar
import com.foldledger.ui.components.PillTag
import com.foldledger.ui.components.SectionCard
import com.foldledger.ui.components.ThinProgress
import com.foldledger.ui.components.parseHexColor
import com.foldledger.ui.theme.Layout
import com.foldledger.ui.theme.HeroNumber
import com.foldledger.ui.theme.OverlineLabel
import com.foldledger.ui.theme.LocalDirectionColors
import com.foldledger.ui.theme.Radius
import com.foldledger.ui.theme.pendingTone
import com.foldledger.ui.theme.ReimburseSubmitted
import com.foldledger.ui.theme.Space
import com.foldledger.ui.theme.SurfaceCard
import com.foldledger.ui.vm.DayGroup
import com.foldledger.ui.vm.FilterAmount
import com.foldledger.ui.vm.FilterTimeRange
import com.foldledger.ui.vm.TxnFilter
import com.foldledger.ui.vm.LedgerDetailViewModel
import com.foldledger.ui.vm.LedgerUi
import com.foldledger.ui.vm.ReimburseSummary
import com.foldledger.util.DateExt
import com.foldledger.util.Money
import com.foldledger.util.ReimburseAging
import com.foldledger.ui.theme.cardSurface
import com.foldledger.ui.theme.cardBorder
import com.foldledger.ui.theme.dividerBrush
import com.foldledger.ui.theme.onColorFor
import com.foldledger.ui.theme.softShadow
import com.foldledger.ui.theme.rememberAnimatedPaneWidth
import kotlinx.coroutines.delay
import com.foldledger.ui.l10n.t

/**
 * 拆自 LedgerDetailScreen.kt（v10.2.0）—— 把 3100 行按职责拆成 detail/ 子包。
 * 本文件保持原注释与代码不变，只调整可见性（private → internal）供同包兄弟文件调用。
 */
// ============================================================
//  概览卡
// ============================================================

// ============================================================
//  资产速览（v6.2）
// ============================================================

/**
 * 资产速览（v6.2）。
 *
 * 一行横滚：**净资产**在最前（最重要的那个数），后面跟各账户余额。
 * 记账管的是流量（钱怎么走的），这里管的是**存量**（我现在有多少钱）——
 * 只看流水永远不知道自己手里有多少钱。
 *
 * 没有账户时给一句「建账户 ›」引导，而不是留个空白——用户不知道这是个功能就不会去建。
 */
@Composable
internal fun AssetBar(
    netWorth: Long,
    balances: List<Pair<AccountEntity, Long>>,
    onManage: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dc = LocalDirectionColors.current
    val faint = MaterialTheme.colorScheme.onSurfaceVariant
    // v13.4：资产条从"独立小玻璃"降级为**概览面板的一条脚注**。
    //
    // 原来它是一块独立白色圆角块，alpha 0.34 —— 比卡片膜（17.5%）实近一倍，
    // 在实机首屏上是**整屏最实的一块**。视觉重心全被它抢走，可它多数时候
    // 只有「净资产 ¥0 · 建账户 ›」一行字，四周全是空的，白占 75dp。
    //
    // 现在三处一起改：① 去掉容器（层级靠排版建立，不靠容器）；② 左内边距对齐
    // Space.cardPad，让它和概览卡里的「尚余垫付」同一条竖线，读作卡的一部分
    // 而不是并列的第二张卡；③ 高度压到 40dp（触控底线），省下的 35dp 还给流水。
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Space.cardPad)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp)
                .clip(RoundedCornerShape(Radius.sm))
                // 整行可点：原来是 11sp 的「建账户 ›」独自可点，热区只有 23dp，
                // 是整个页面所有存量的唯一入口，却最难按中。
                .clickableScale(pressedScale = 0.97f) { onManage() },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("净资产", fontSize = 11.sp, color = faint)
            Spacer(Modifier.width(7.dp))
            Text(
                Money.format(netWorth),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                // v10.0.0：±0 用中性色 —— 原来是 >=0 就走"收入绿"，¥0 也绿；
                // 深色下绿字还偏暗。0 既不是赚也不是亏。
                color = when {
                    netWorth > 0L -> dc.income
                    netWorth < 0L -> dc.expense
                    else -> faint
                }
            )
            Spacer(Modifier.width(2.dp))
            Text("·", fontSize = 11.sp, color = faint.copy(alpha = 0.5f))
            // 入口紧跟在数字后面**左对齐**，不推到最右 —— 右下角是 FAB 的地盘。
            Text(
                if (balances.isEmpty()) t("建账户 ›") else t("管理 ›"),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 6.dp)
            )
        }
        if (balances.isNotEmpty()) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 8.dp)
            ) {
                items(balances, key = { it.first.id }) { (acc, bal) ->
                    AssetChip(account = acc, balanceCents = bal)
                }
            }
        }
    }
}

@Composable
private fun AssetChip(account: AccountEntity, balanceCents: Long) {
    val dc = LocalDirectionColors.current
    // 玻璃上不再套"灰底 + 灰边"：一枚半透明白片就够了，边由高光给
    Box(
        Modifier
            .clip(RoundedCornerShape(Radius.sm))
            .background(Color.White.copy(alpha = if (com.foldledger.ui.theme.LocalDarkTheme.current) 0.07f else 0.42f))
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(account.emoji, fontSize = 15.sp)
            Spacer(Modifier.width(5.dp))
            Column {
                Text(
                    account.name,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 72.dp)
                )
                Text(
                    Money.format(balanceCents),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (balanceCents >= 0) MaterialTheme.colorScheme.onSurface else dc.expense
                )
            }
        }
    }
}

/**
 * 概览版头（v11 重排）。
 *
 * 这一块原来是"一张玻璃卡里塞进 8 个数字"：卡里有头像、场景标签、大数字、
 * 两格收支、预算进度、剩余金额 —— 每一格都有自己的底色和圆角，
 * 结果就是首屏一块彩色马赛克，什么都看见了，什么都没记住。
 *
 * v12：版头重新成为一块**玻璃面板**（v11 曾把它摊平在纸面上）。
 * 液态玻璃的分层逻辑是"每一层信息 = 一层玻璃"，所以版头必须自己是一层 ——
 * 否则大数字会漂在背景里，和下方的流水混在一起。
 *
 * 排法仍然是报表式的：
 *
 * ```
 *   [emoji] 账本名 · 场景                     ⌃
 *
 *   累计结余
 *   + ¥ 12,480.00                ← 全屏唯一的大字
 *   ─────────────────────────────
 *   总收入          │  总支出
 *   ¥ 30,000.00     │  ¥ 17,520.00
 *   本月 ¥ 8,200    │  本月 ¥ 5,100
 *   ─────────────────────────────
 *   本月预算                    ¥8,200 / ¥10,000
 *   ▬▬▬▬▬▬▬▬▬▬▬▬▬░░░░░░
 *   剩余 ¥1,800 可用
 * ```
 *
 * 没有卡片边框、没有阴影 —— 版头直接印在纸上，靠"细线 + 留白 + 字级差"分区。
 * 这是编辑设计最基本的一条：**层级用排版建立，不要用容器建立**。
 */
@Composable
internal fun LedgerOverviewCard(
    emoji: String,
    colorHex: String,
    sceneKey: String,
    totalExpense: Long,
    totalIncome: Long,
    monthExpense: Long,
    monthIncome: Long,
    budgetCents: Long,
    /** v7.1：报销账本传入垫付/回款汇总 —— 主角数字从「结余」换成「尚余垫付」 */
    reimburse: ReimburseSummary? = null,
    /** v10.1.0：收起时只留主角大数字（首屏能露出流水） */
    compact: Boolean = false,
    onToggleCompact: () -> Unit = {}
) {
    val accent = parseHexColor(colorHex)
    val used = if (budgetCents > 0) monthExpense.toFloat() / budgetCents.toFloat() else 0f
    val balance = totalIncome - totalExpense
    val dc = LocalDirectionColors.current
    val dark = com.foldledger.ui.theme.LocalDarkTheme.current
    val ink = MaterialTheme.colorScheme.onSurface
    val faint = MaterialTheme.colorScheme.onSurfaceVariant
    // 玻璃内部的分隔线：不用实色灰，用"光的中断"——一条半透明白
    val innerLine = Color.White.copy(alpha = if (dark) 0.10f else 0.55f)

    // v12：概览是一块**大玻璃面板**（不是卡片墙）。
    // 层级只有三级：报头一行 → 一个 44sp 的大数字 → 三栏收支。没有色块、没有阴影堆叠。
    SurfaceCard(
        shape = RoundedCornerShape(Radius.panel),
        elevation = 10.dp,
        specular = 1.15f,
        modifier = Modifier.fillMaxWidth()
    ) {
        // v12.2.0：收起态 = **一条摘要**，不是"半收起"的大卡。
        // 旧实现收起后仍保留报头 + 44sp 大数字 + 小注，占近 190dp，比展开只省 90dp ——
        // 用户点收起就是想让流水上来，收一半等于没收。压成一行后约 62dp。
        if (compact) {
            CompactOverviewRow(
                emoji = emoji,
                colorHex = colorHex,
                label = if (reimburse != null) t("尚余垫付") else t("累计结余"),
                cents = if (reimburse != null) reimburse.outstandingCents else kotlin.math.abs(balance),
                // v13.7：负结余必须**带负号**，不能只靠红色。
                // 账本切换菜单里写的是「结余 -¥414.26」，卡片里写「¥414.26」+ 红字 ——
                // 同一个数两种写法，而且色觉障碍用户根本读不出那个负号。
                negative = reimburse == null && balance < 0,
                centsColor = if (reimburse != null) pendingTone()
                else if (balance >= 0) dc.income else dc.expense,
                note = if (reimburse != null) {
                    if (reimburse.allSettled) t("已全部回款") else "${reimburse.outstandingCount} 笔待回款"
                } else {
                    "本月支 ${Money.format(monthExpense)}"
                },
                onClick = onToggleCompact
            )
            return@SurfaceCard
        }
        Column(Modifier.padding(horizontal = Space.cardPad, vertical = Space.lg)) {
            // ---------- 主角数字 ----------
            //
            // ⚠️ v13.7：原来的「报头」整块删除 —— 它画的是 头像 + 账本名 + 场景名，
            //    而顶栏的大标题就是账本名、副标题就是场景名。同一份数据一屏出现两遍，
            //    而且卡片里的字号（15/11sp）比标题（23sp）小得多，读起来像"标题的残影"。
            //    省下的 60dp 直接还给数字本身。
            //    收起手柄改挂在「尚余垫付 / 累计结余」这一行的右端（HeaderLine）。
            if (reimburse != null) {
                HeaderLine(t("尚余垫付"), onCollapse = onToggleCompact)
                Spacer(Modifier.height(3.dp))
                AnimatedCentsText(
                    cents = reimburse.outstandingCents,
                    color = pendingTone(),
                    style = HeroNumber,
                    prefix = "¥",
                    symbol = false,
                    modifier = Modifier.valuePulse(reimburse.outstandingCents)
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    if (reimburse.allSettled) t("全部垫付都已回款")
                    else "${reimburse.outstandingCount} 笔垫付款待回款",
                    fontSize = 13.sp,
                    color = faint
                )
            } else {
                HeaderLine(t("累计结余"), onCollapse = onToggleCompact)
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (balance >= 0) "+" else "−",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Light,
                        color = if (balance >= 0) dc.income else dc.expense,
                        modifier = Modifier.padding(end = 2.dp)
                    )
                    AnimatedCentsText(
                        cents = kotlin.math.abs(balance),
                        color = if (balance >= 0) dc.income else dc.expense,
                        style = HeroNumber,
                        prefix = "¥",
                        symbol = false,
                        modifier = Modifier.valuePulse(balance)
                    )
                }
                Spacer(Modifier.height(5.dp))
                Text(
                    "本月支出 ${Money.format(monthExpense)} · 收入 ${Money.format(monthIncome)}",
                    fontSize = 13.sp,
                    color = faint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // ⚠️ v12.2.0 去重：报销账本这里原本还有一段「累计垫付 · 已回款 / 百分比 / 进度条」，
            // 与下方 ReimburseBoard 的「已回款格 + 回款率 + 回款进度条 + 金额分母」逐项重复。
            // 同一份数据一屏出现两遍、进度条画两条，最容易让人怀疑"到底哪个数是对的"。
            // 回款信息统一交给看板，概览面板只负责「尚余垫付」这一个主角数字。
            if (reimburse == null) {
                Spacer(Modifier.height(12.dp))
                // 一条横贯面板的光缝：把"版头数字"和"明细栏"分开，比加一个色块干净
                Box(Modifier.fillMaxWidth().height(1.dp).background(innerLine))
                Spacer(Modifier.height(10.dp))
                // 三栏收支：表格结构（竖线分隔），不是三个色块
                Row(verticalAlignment = Alignment.Top) {
                    StatCell(
                        label = t("总收入"),
                        cents = totalIncome,
                        color = dc.income,
                        sub = "本月 ${Money.format(monthIncome)}",
                        modifier = Modifier.weight(1f)
                    )
                    Box(Modifier.width(1.dp).height(38.dp).background(innerLine))
                    Spacer(Modifier.width(Space.md))
                    StatCell(
                        label = t("总支出"),
                        cents = totalExpense,
                        color = dc.expense,
                        sub = "本月 ${Money.format(monthExpense)}",
                        modifier = Modifier.weight(1f)
                    )
                    if (budgetCents > 0L) {
                        Box(Modifier.width(1.dp).height(38.dp).background(innerLine))
                        Spacer(Modifier.width(Space.md))
                        StatCell(
                            label = t("本月预算"),
                            cents = budgetCents,
                            color = if (used > 1f) MaterialTheme.colorScheme.error else ink,
                            sub = if (used > 1f) "已超 ${(used * 100).toInt()}%"
                            else "剩 ${Money.format(budgetCents - monthExpense)}",
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                if (budgetCents > 0L) {
                    Spacer(Modifier.height(10.dp))
                    ThinProgress(
                        fraction = used,
                        color = if (used > 1f) MaterialTheme.colorScheme.error else accent
                    )
                }
            }
        }
    }
}

/**
 * 概览面板的**收起态**：一条摘要（v12.2.0）。
 *
 * 收起是一个承诺——「我把位置让给流水」。所以它不能是"半收起"：
 * 旧实现收起后仍保留报头 + 44sp 大数字 + 小注，占近 190dp，比展开只省 90dp，
 * 首屏照样露不出流水，收起等于没做。
 *
 * 现在压成一行：头像 + 标签&数字（23sp）+ 右侧小注 + 展开箭头，约 62dp。
 * 数字降到 23sp 是有意的：收起时"扫一眼大概"就够，要看精确数值点开即可。
 * 也**不再重复写账本名** —— 上方 chip 行已经把当前账本标出来了。
 */
/**
 * 概览卡展开态的标题行：左边一个 overline，右边一个「收起」。
 *
 * 它替掉了原来整块报头（头像 + 账本名 + 场景 + 折叠箭头，约 60dp）——
 * 那三样东西顶栏已经全说过了。字号用 overline（11sp）：这一行是**注释**，
 * 不是标题，标题在屏顶。
 */
@Composable
private fun HeaderLine(label: String, onCollapse: () -> Unit) {
    val faint = MaterialTheme.colorScheme.onSurfaceVariant
    val hitShape = RoundedCornerShape(Radius.sm)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = OverlineLabel, color = faint)
        Spacer(Modifier.weight(1f))
        Text(
            t("收起"),
            fontSize = 11.sp,
            letterSpacing = 0.3.sp,
            color = faint,
            modifier = Modifier
                .clip(hitShape)
                // 11sp 的"收起"只有 ~22×15dp，直接点到很难中；
                // 用 padding 把热区撑到 44dp 高的一半以上（Space.touch 走不动时取 34dp）。
                .clickableScale(pressedScale = 0.94f, clipShape = hitShape) { onCollapse() }
                .padding(horizontal = 8.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun CompactOverviewRow(
    emoji: String,
    colorHex: String,
    label: String,
    cents: Long,
    centsColor: Color,
    note: String,
    /** 负数金额：把负号并进前缀，因为 AnimatedCentsText 是 "prefix + format(abs)" */
    negative: Boolean = false,
    onClick: () -> Unit
) {
    val faint = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .clickableScale(pressedScale = 0.995f, clipShape = RoundedCornerShape(Radius.md)) { onClick() }
            .padding(horizontal = Space.cardPad, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LedgerAvatar(emoji, colorHex, size = 30.dp, emojiSize = 15)
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(
                label,
                fontSize = 11.sp,
                letterSpacing = 0.5.sp,
                color = faint,
                maxLines = 1
            )
            Spacer(Modifier.height(2.dp))
            AnimatedCentsText(
                cents = cents,
                color = centsColor,
                fontSize = 24,
                fontWeight = FontWeight.SemiBold,
                prefix = if (negative) "-¥" else "¥",
                symbol = false,
                modifier = Modifier.valuePulse(cents)
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            note,
            fontSize = 11.sp,
            color = faint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            "⌄",
            fontSize = 15.sp,
            color = faint,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

/**
 * 收支统计格（v12：表格单元，不是色块）。
 *
 * 玻璃面板上最忌讳"格子里再垫一块底色" —— 两层半透明叠在一起会变成脏灰。
 * 这里只有三行字：加宽字距的小标签、一个 17sp 的数字、一行小注。靠字级与留白分层。
 */
@Composable
private fun StatCell(
    label: String,
    cents: Long,
    color: Color,
    sub: String,
    modifier: Modifier = Modifier
) {
    Column(modifier) {
        Text(label, style = OverlineLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        AnimatedCentsText(
            cents = cents,
            color = color,
            fontSize = 17,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(2.dp))
        Text(
            sub,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.9f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
