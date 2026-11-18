package com.foldledger.ui.screens.detail

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foldledger.data.db.Direction
import com.foldledger.data.db.TransactionEntity
import com.foldledger.ui.anim.Motion
import com.foldledger.ui.anim.clickableScale
import com.foldledger.ui.theme.Hairline
import com.foldledger.ui.theme.LocalDarkTheme
import com.foldledger.ui.theme.LocalDirectionColors
import com.foldledger.ui.theme.OverlineLabel
import com.foldledger.ui.theme.Radius
import com.foldledger.ui.theme.Space
import com.foldledger.ui.theme.SurfaceCard
import com.foldledger.util.Money
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.foldledger.ui.l10n.t

/**
 * 明细页的三段视图。
 *
 * 苹果把"同一份数据的三种看法"做成一个 segmented + 一整块可横滑的页面，
 * 而不是三个各自带标题的 Tab。这里照做：段控件在外面（不动），内容在下面横滑，
 * 滑到头/尾时由平台的弹性回弹给出"到底了"的信号。
 */
enum class DetailSegment(val label: String) {
    TXNS(t("明细")),
    CATEGORIES(t("分类")),
    MONTHS(t("月份"))
}

/**
 * 玻璃分段控件。
 *
 * 沼泽式的坑：iOS 的 segmented 是"凹槽 + 滑块"，不是 M3 的 TabRow。
 * 凹槽用 4.5% 的墨**压下去**（不是白），滑块是一小块真玻璃（白膜 + 棱边 + 极软影）。
 * 滑块位移走 [Motion.tab]（阻尼 0.82）—— 有回弹、但不过头，
 * 这就是"点下去、滑块弹到位"的手感。
 */
@Composable
fun GlassSegmentBar(
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    segments: List<DetailSegment> = DetailSegment.entries
) {
    val dark = LocalDarkTheme.current
    val trackShape = RoundedCornerShape(50)
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(36.dp)
            .clip(trackShape)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = if (dark) 0.10f else 0.045f))
    ) {
        val itemWidth = maxWidth / segments.size
        val thumbOffset by animateDpAsState(
            targetValue = itemWidth * selected,
            animationSpec = Motion.tab(),
            label = "segThumb"
        )
        // 滑块：真玻璃，压在凹槽上
        Box(
            Modifier
                .padding(3.dp)
                .offset(x = thumbOffset)
                .width(itemWidth - 3.dp)
                .height(30.dp)
                .glassSegmentThumb()
        )
        Row(Modifier.fillMaxSize()) {
            segments.forEachIndexed { i, seg ->
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .clip(trackShape)
                        .clickableScale(pressedScale = 1f) { onSelect(i) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        seg.label,
                        fontSize = 13.sp,
                        fontWeight = if (i == selected) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (i == selected) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * 滑块材质。
 *
 * 不用 `glassSurface`：它默认要 4.5dp 的内折射环，在 30dp 高的滑块上会吃掉一半面积。
 * 这里只留"膜 + 白棱 + 极软影"三样 —— 小尺寸玻璃要减层，不是等比缩小。
 */
@Composable
private fun Modifier.glassSegmentThumb(): Modifier {
    val dark = LocalDarkTheme.current
    val shape = RoundedCornerShape(50)
    return this
        .glassSegmentShadow(dark)
        .clip(shape)
        .background(
            androidx.compose.ui.graphics.Brush.verticalGradient(
                listOf(
                    Color.White.copy(alpha = if (dark) 0.16f else 0.78f),
                    Color.White.copy(alpha = if (dark) 0.10f else 0.62f)
                )
            )
        )
}

@Composable
private fun Modifier.glassSegmentShadow(dark: Boolean): Modifier =
    this.shadow(
        elevation = 3.dp,
        shape = RoundedCornerShape(50),
        clip = false,
        ambientColor = Color(0xFF0A1420).copy(alpha = if (dark) 0.30f else 0.06f),
        spotColor = Color(0xFF0A1420).copy(alpha = if (dark) 0.34f else 0.09f)
    )

// ============================================================
//  分类
// ============================================================

private data class CategoryAgg(
    val name: String,
    val emoji: String,
    val cents: Long,
    val count: Int
)

/**
 * 分类视图：把当前账本的流水按「分类名 + 方向」合并，支出在上、收入在下。
 *
 * ⚠️ 合并用的键必须是 `categoryName`（流水表里冗余存的就是名字）。
 * 拿 categoryId 当键会在这里踩坑：手动记账时 categoryId 可能为 null，
 * 而导入的历史流水又常常只有名字没有 id —— 两边永远拼不上，分类会被拆成一堆"未分类"。
 */
@Composable
fun CategoryBreakdownPage(
    transactions: List<TransactionEntity>,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(
        start = Space.pageH, end = Space.pageH, top = Space.sm, bottom = 120.dp
    )
) {
    val dc = LocalDirectionColors.current
    val expense = remember(transactions) { aggregate(transactions, Direction.EXPENSE) }
    val income = remember(transactions) { aggregate(transactions, Direction.INCOME) }
    val expenseTotal = remember(expense) { expense.sumOf { it.cents } }
    val incomeTotal = remember(income) { income.sumOf { it.cents } }

    if (expense.isEmpty() && income.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                t("这个账本还没有流水"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 15.sp
            )
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(Space.gapList)
    ) {
        item(key = "cat-expense") {
            CategoryGroupCard(
                title = t("支出"),
                subtitle = "${expense.size} 个分类 · ${expense.sumOf { it.count }} 笔",
                total = expenseTotal,
                totalColor = dc.expense,
                rows = expense,
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (income.isNotEmpty()) {
            item(key = "cat-income") {
                CategoryGroupCard(
                    title = t("收入"),
                    subtitle = "${income.size} 个分类 · ${income.sumOf { it.count }} 笔",
                    total = incomeTotal,
                    totalColor = dc.income,
                    rows = income,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

private fun aggregate(list: List<TransactionEntity>, direction: Int): List<CategoryAgg> =
    list.asSequence()
        .filter { it.direction == direction }
        .groupBy { it.categoryName }
        .map { (name, items) ->
            CategoryAgg(
                name = name,
                emoji = items.firstOrNull()?.categoryEmoji ?: "📦",
                cents = items.sumOf { it.amountCents },
                count = items.size
            )
        }
        .sortedByDescending { it.cents }

@Composable
private fun CategoryGroupCard(
    title: String,
    subtitle: String,
    total: Long,
    totalColor: Color,
    rows: List<CategoryAgg>,
    modifier: Modifier = Modifier
) {
    val max = rows.maxOfOrNull { it.cents } ?: 1L
    SurfaceCard(
        shape = RoundedCornerShape(Radius.card),
        elevation = 3.dp,
        modifier = modifier
    ) {
        Column(Modifier.padding(vertical = Space.lg)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Space.cardPad),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        subtitle,
                        style = OverlineLabel,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    Money.format(total),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = totalColor
                )
            }
            Spacer(Modifier.height(Space.md))
            rows.forEachIndexed { index, row ->
                if (index > 0) {
                    Hairline(Modifier.padding(horizontal = Space.cardPad))
                }
                CategoryRow(row = row, max = max, total = total, barColor = totalColor)
            }
        }
    }
}

@Composable
private fun CategoryRow(row: CategoryAgg, max: Long, total: Long, barColor: Color) {
    val share = (row.cents.toFloat() / max.toFloat()).coerceIn(0f, 1f)
    val pct = if (total > 0L) (row.cents * 100 / total).toInt() else 0
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.cardPad, vertical = 9.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(row.emoji, fontSize = 15.sp)
            Spacer(Modifier.width(9.dp))
            Text(
                row.name,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            // ⚠️ v13.4：原来只有「N 笔」，百分比缺失 —— 而"占比"恰恰是这一页唯一的观点。
            // 条形长度是**相对最大值**（用来比大小），百分比是**占总额**（用来判断轻重），
            // 两者不是一回事：住宿费条最长（100%），但它只占整月 37%。
            // 只给条不给数，用户会默认"最长的就是大头"，读到 37% 才发现不是。
            Text(
                "${row.count} 笔 · $pct%",
                style = OverlineLabel,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(10.dp))
            Text(
                Money.format(row.cents),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Spacer(Modifier.height(6.dp))
        // 占比条：2.5dp 的细槽。玻璃上的进度条只该有一条"光"，不该是一块色块。
        Box(
            Modifier
                .fillMaxWidth()
                .height(3.dp)
                // ⚠️ v13.3：槽 6% → 8%，条 65% → 85%，2.5dp → 3dp。
                // 原参数在真机上条和槽都消失，只剩一条悬空的淡蓝线 —— 用户读不出"这是占比"，
                // 只会以为是行之间的分割线。占比条必须同时满足：槽看得见、条压得住槽。
                // ⚠️ v13.4：槽 8% → 11%（8% 在暖米白上仍只剩 1.02 的明度差，等于没有）；
                // 条色从写的死 primary 改成随组方向走 —— 这一页讲的是"支出去哪了"，
                // 条却是雾蓝，和同一行里的豆沙红金额是两种语义色，读起来发拧。
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.11f))
        ) {
            Box(
                Modifier
                    .fillMaxWidth(share)
                    .height(3.dp)
                    .clip(RoundedCornerShape(50))
                    .background(barColor.copy(alpha = 0.68f))
            )
        }
    }
}

// ============================================================
//  月份
// ============================================================

private data class MonthAgg(
    val key: String,
    val label: String,
    val expense: Long,
    val income: Long,
    val count: Int,
    /**
     * 该月 1..31 号**每天**的支出（分）。下标 0 = 1 号。
     *
     * v13.4 新增：月份视图原来只有"一个月一行数字"，账本只有一个月流水时
     * 整页只有一张卡、下面 65% 全空 —— 看起来像没做完。
     * 补上日分布之后，单月也能读出"这个月钱花在哪几天"，而不只是一个总数。
     */
    val dailyExpense: List<Long> = emptyList(),
    /** 该月实际天数（28/29/30/31），决定柱状图画几根 */
    val daysInMonth: Int = 31,
    /** 该月支出最高的几个分类（最多 6 个），用于卡内t("主要花在") */
    val topCategories: List<MonthCategorySlice> = emptyList(),
    /**
     * 该月金额最大的几笔（最多 5 笔）。
     *
     * v13.6 新增：单月账本里这张卡只有 400dp 高，下面是 30% 的空白 ——
     * 而"这个月最大的几笔"恰恰是报销 / 对账时最先要看的东西（哪几笔垫付最多）。
     * 它不是"同一份数据出现两遍"：明细页是按时间排的，这里是按金额排的，
     * 回答的是另一个问题。
     */
    val topExpenses: List<MonthTopTxn> = emptyList()
)

/** 月份卡里的一行t("本月最大的一笔") */
private data class MonthTopTxn(
    val emoji: String,
    val category: String,
    val note: String,
    val cents: Long,
    val day: Int
)

/** 月份卡里的一行t("分类构成") */
private data class MonthCategorySlice(
    val emoji: String,
    val name: String,
    val cents: Long
)

/**
 * 月份视图：按月汇总支出/收入/结余。
 *
 * 月份键用 `yyyy-MM`（可排序），显示用 `yyyy年M月` —— 两件事分开，
 * 否则排序时会按"1月、10月、11月"的字符串顺序排，二月跑到十二月后面。
 */
@Composable
fun MonthBreakdownPage(
    transactions: List<TransactionEntity>,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(
        start = Space.pageH, end = Space.pageH, top = Space.sm, bottom = 120.dp
    )
) {
    val dc = LocalDirectionColors.current
    val faint = MaterialTheme.colorScheme.onSurfaceVariant
    val months = remember(transactions) { aggregateMonths(transactions) }

    if (months.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                t("这个账本还没有流水"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 15.sp
            )
        }
        return
    }

    val maxExpense = months.maxOfOrNull { it.expense }?.coerceAtLeast(1L) ?: 1L
    // 单月 / 多月两种篇幅：单月是"月报"，可以铺满；多月是"对比"，必须能扫。
    val single = months.size == 1

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(Space.gapList)
    ) {
        items(months.size, key = { months[it].key }) { i ->
            val m = months[i]
            SurfaceCard(
                shape = RoundedCornerShape(Radius.card),
                elevation = 3.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(Space.cardPad)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            m.label,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "${m.count} 笔",
                            style = OverlineLabel,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                        MonthMetric(t("支出"), m.expense, if (m.expense > 0L) dc.expense else faint)
                        MonthMetric(t("收入"), m.income, if (m.income > 0L) dc.income else faint)
                        // ⚠️ v13.4：结余原来一律用 onSurface（中性黑）。可它是有方向的数：
                        // 正数是攒下的、负数是透支的，用同一个黑显示，等于把最该一眼看出的
                        // 结论抹平了。±0 才用中性 —— 0 既不是赚也不是亏（和资产条同一判据）。
                        MonthMetric(
                            t("结余"),
                            m.income - m.expense,
                            when {
                                m.income - m.expense > 0L -> dc.income
                                m.income - m.expense < 0L -> dc.expense
                                else -> faint
                            }
                        )
                    }
                    // 这条"该月占最高月"的细条只在**多月**时才有意义。
                    // 单月时它恒为 100% 满宽 —— 一条横贯卡片、什么也没说的红线，
                    // 还和下面的日分布柱状图在语义上打架（一个讲"月与月"，一个讲"月内"）。
                    if (months.size > 1) {
                        Spacer(Modifier.height(10.dp))
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(2.5.dp)
                                .clip(RoundedCornerShape(50))
                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth((m.expense.toFloat() / maxExpense.toFloat()).coerceIn(0f, 1f))
                                    .height(2.5.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(dc.expense.copy(alpha = 0.55f))
                            )
                        }
                    }
                    // ---------- v13.4：该月的日分布 ----------
                    // 上面那行"支出/收入/结余"回答"这个月花了多少"，
                    // 这一段回答"花在哪几天"—— 单月账本里，后者才是这张卡存在的理由。
                    if (m.dailyExpense.isNotEmpty()) {
                        val days = m.dailyExpense.take(m.daysInMonth)
                        val peak = days.maxOrNull() ?: 0L
                        val peakDay = days.indexOfFirst { it == peak } + 1
                        val activeDays = days.count { it > 0 }
                        val perDay = if (activeDays > 0) m.expense / activeDays else 0L
                        Spacer(Modifier.height(14.dp))
                        DailyExpenseBars(
                            values = days,
                            maxValue = peak,
                            color = dc.expense,
                            daysInMonth = m.daysInMonth
                        )
                        Spacer(Modifier.height(8.dp))
                        // v13.7：同一条注释，窄栏换行。
                        // 双栏左栏的卡内容宽只有 ~292dp，两串 11sp 的信息并排时
                        // 「日均 ¥946.19」和「单日峰值 15 日 ¥2,894.81」会贴在一起
                        // （实测剩不到 4dp），读起来像一个数接一个数。
                        // 判据用**可用宽度**而不是 `env.twoPane`：同一张卡在竖屏展开、
                        // 横屏分栏、外屏三种宽度下都要各自成立。
                        androidx.compose.foundation.layout.BoxWithConstraints {
                            val narrow = maxWidth < 340.dp
                            if (narrow) {
                                Column {
                                    Text(
                                        if (activeDays > 0) "$activeDays 天有支出 · 日均 ${Money.format(perDay)}"
                                        else "这个月还没有支出",
                                        style = OverlineLabel,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    if (peak > 0L) {
                                        Spacer(Modifier.height(3.dp))
                                        Text(
                                            "单日峰值 $peakDay 日 ${Money.format(peak)}",
                                            style = OverlineLabel,
                                            color = dc.expense
                                        )
                                    }
                                }
                                return@BoxWithConstraints
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (activeDays > 0) "$activeDays 天有支出 · 日均 ${Money.format(perDay)}"
                                else "这个月还没有支出",
                                style = OverlineLabel,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.weight(1f))
                            if (peak > 0L) {
                                // ⚠️ v13.6：这里必须说"单日峰值"。它是**当天所有支出之和**，
                                // 而下面「单笔最大的几笔」讲的是**单笔**金额 —— 两个数并列时
                                // 只写"最高"，用户会读到"15 日花了 2894，可是最大一笔才 1704？"
                                // 同一屏上"最高"只能指一件事，所以这里的措辞让位给下面的单笔榜。
                                Text(
                                    "单日峰值 $peakDay 日 ${Money.format(peak)}",
                                    style = OverlineLabel,
                                    color = dc.expense
                                )
                            }
                            }
                        }
                    }
                    // ---------- v13.4：主要花在 ----------
                    // 月份卡原来只有"支出/收入/结余 + 一条占比"：把一整月压缩成三个数字，
                    // 看完仍然不知道钱去哪了。补上分类构成，这张卡才回答得了"这个月过得怎么样"。
                    // 口径说明：这里是**该月**的分类构成，和「分类」分栏的**全账本**口径不同，
                    // 不是同一份数据出现两遍。
                    if (m.topCategories.isNotEmpty() && m.expense > 0L) {
                        Spacer(Modifier.height(14.dp))
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                        )
                        Spacer(Modifier.height(10.dp))
                        m.topCategories.take(if (single) 6 else 4).forEach { c ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(c.emoji, fontSize = 13.sp)
                                Spacer(Modifier.width(7.dp))
                                Text(
                                    c.name,
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    Money.format(c.cents),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = dc.expense
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "${(c.cents * 100 / m.expense).toInt()}%",
                                    fontSize = 11.sp,
                                    color = faint,
                                    modifier = Modifier.width(34.dp),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.End
                                )
                            }
                        }
                    }
                    // ---------- v13.6：本月最大的几笔 ----------
                    // **只在该账本只有一个月时出现**。单月账本里这张卡到上面就到头了，
                    // 下面是 30% 的空白 —— 而"最大的几笔"是报销 / 对账时最先要看的。
                    // 多月时不给：那时这一页的任务是"月与月之间比较"，每张卡再挂 5 行明细，
                    // 整页就变成一堆要滚过去的长卡，反而看不出对比。
                    // 按金额排和明细页按时间排是两个问题，不是同一份数据出现两遍。
                    if (single && m.topExpenses.isNotEmpty() && m.topExpenses.first().cents > 0L) {
                        Spacer(Modifier.height(14.dp))
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "单笔最大的 ${m.topExpenses.size} 笔",
                            style = OverlineLabel,
                            color = faint
                        )
                        Spacer(Modifier.height(4.dp))
                        m.topExpenses.forEach { t ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(t.emoji, fontSize = 13.sp)
                                Spacer(Modifier.width(7.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        t.category,
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (t.note.isNotBlank()) {
                                        Text(
                                            t.note,
                                            fontSize = 11.sp,
                                            color = faint,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                Text("${t.day} 日", fontSize = 11.sp, color = faint)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    Money.format(t.cents),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = dc.expense
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 该月每日支出的迷你柱状图（v13.4；v13.6 重做可读性）。
 *
 * ### v13.6：为什么不再给"没花钱的日子"画整根槽
 * 上一版每个格子都先铺一根整高的浅灰槽，再往上叠柱子。31 格铺满之后，
 * 那 22 根空槽就是**22 条等宽等高的竖灰线** —— 真机上读起来像扫描线 / 没加载完，
 * 而不是"这天没花钱"。现在空日只留底部一小段"墩"（约柱宽的 1/12 高），
 * 有支出的日子才从底部长起来。整排于是变成"雨量图"：高低一目了然。
 *
 * ### 小额日为什么下限抬到 6dp
 * 空日的墩是 3dp。若小额支出也压到 3dp，两者就一样高 —— 分不清"花了 1 块"
 * 和"没花"。所以有支出的柱子下限 6dp，空日 3dp，高度差就是语义差。
 *
 * 底部配 1 / 月中 / 月末三个刻度：没有刻度时，读者不知道横轴是"日期"还是"别的什么"，
 * 只会把它当成一条装饰条。
 */
@Composable
private fun DailyExpenseBars(
    values: List<Long>,
    maxValue: Long,
    color: Color,
    daysInMonth: Int,
    modifier: Modifier = Modifier
) {
    val tick = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.13f)
    val faint = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier.fillMaxWidth()) {
        androidx.compose.foundation.Canvas(
            Modifier
                .fillMaxWidth()
                .height(46.dp)
        ) {
            if (values.isEmpty()) return@Canvas
            val gap = 2.dp.toPx()
            val barW = (size.width - gap * (values.size - 1)) / values.size
            if (barW <= 0f) return@Canvas
            val maxV = maxValue.coerceAtLeast(1L).toFloat()
            // 圆角固定 2dp 而不是 barW/2：柱子宽度约 11dp，圆角取半宽时，
            // 小额日（柱高被压到下限）会变成一个**正圆**，整排看下来像撒了一把珠子。
            // 固定 2dp 之后小额日是"小短柱"，所有柱子共享同一种形状语言。
            val radius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx())
            val minBarH = 6.dp.toPx()
            val emptyTickH = 3.dp.toPx()
            values.forEachIndexed { i, v ->
                val x = i * (barW + gap)
                if (v > 0L) {
                    val barH = ((v.toFloat() / maxV) * size.height).coerceAtLeast(minBarH)
                    drawRoundRect(
                        color = color.copy(alpha = 0.88f),
                        topLeft = androidx.compose.ui.geometry.Offset(x, size.height - barH),
                        size = androidx.compose.ui.geometry.Size(barW, barH),
                        cornerRadius = radius
                    )
                } else {
                    drawRoundRect(
                        color = tick,
                        topLeft = androidx.compose.ui.geometry.Offset(x, size.height - emptyTickH),
                        size = androidx.compose.ui.geometry.Size(barW, emptyTickH),
                        cornerRadius = radius
                    )
                }
            }
        }
        Spacer(Modifier.height(5.dp))
        Row(Modifier.fillMaxWidth()) {
            Text("1 日", fontSize = 11.sp, color = faint.copy(alpha = 0.78f))
            Spacer(Modifier.weight(1f))
            Text("${(daysInMonth + 1) / 2} 日", fontSize = 11.sp, color = faint.copy(alpha = 0.78f))
            Spacer(Modifier.weight(1f))
            Text("$daysInMonth 日", fontSize = 11.sp, color = faint.copy(alpha = 0.78f))
        }
    }
}

@Composable
private fun MonthMetric(label: String, cents: Long, color: Color) {
    Column {
        Text(label, style = OverlineLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            Money.format(cents),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = color
        )
    }
}

private fun aggregateMonths(list: List<TransactionEntity>): List<MonthAgg> {
    val keyFmt = SimpleDateFormat("yyyy-MM", Locale.CHINA)
    val labelFmt = SimpleDateFormat("yyyy年M月", Locale.CHINA)
    // 日分布要用 Calendar 取"几号"与"这个月有几天"（28/29/30/31 不能一律按 31 画）
    val cal = java.util.Calendar.getInstance()
    return list.groupBy { keyFmt.format(Date(it.occurredAt)) }
        .map { (key, items) ->
            val daily = MutableList(31) { 0L }
            items.forEach { t ->
                if (t.direction == Direction.EXPENSE) {
                    cal.timeInMillis = t.occurredAt
                    val d = cal.get(java.util.Calendar.DAY_OF_MONTH) - 1
                    if (d in 0..30) daily[d] += t.amountCents
                }
            }
            cal.timeInMillis = items.first().occurredAt
            // ⚠️ 顺序要紧：`daysInMonth` 依赖 cal 停在该月，而下面的 topExpenses
            // 会把 cal 挪到某一天。先取天数，再算 top —— 否则月末 31 号那笔
            // 会把 2 月的 daysInMonth 算成 31。
            val daysIn = cal.getActualMaximum(java.util.Calendar.DAY_OF_MONTH)
            val top5 = items
                .filter { it.direction == Direction.EXPENSE }
                .sortedByDescending { it.amountCents }
                .take(5)
                .map { t ->
                    cal.timeInMillis = t.occurredAt
                    MonthTopTxn(
                        emoji = t.categoryEmoji,
                        category = t.categoryName,
                        note = t.note,
                        cents = t.amountCents,
                        day = cal.get(java.util.Calendar.DAY_OF_MONTH)
                    )
                }
            MonthAgg(
                key = key,
                label = labelFmt.format(Date(items.first().occurredAt)),
                expense = items.filter { it.direction == Direction.EXPENSE }.sumOf { it.amountCents },
                income = items.filter { it.direction == Direction.INCOME }.sumOf { it.amountCents },
                count = items.size,
                dailyExpense = daily,
                daysInMonth = daysIn,
                topCategories = items
                    .filter { it.direction == Direction.EXPENSE }
                    .groupBy { it.categoryName }
                    .map { (name, rows) ->
                        MonthCategorySlice(
                            emoji = rows.first().categoryEmoji,
                            name = name,
                            cents = rows.sumOf { it.amountCents }
                        )
                    }
                    .sortedByDescending { it.cents }
                    .take(6),
                topExpenses = top5
            )
        }
        .sortedByDescending { it.key }
}
