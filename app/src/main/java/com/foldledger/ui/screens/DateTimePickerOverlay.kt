package com.foldledger.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foldledger.ui.adaptive.FoldEnv
import com.foldledger.ui.anim.clickableScale
import com.foldledger.util.DateExt
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import com.foldledger.ui.theme.cardSurface
import com.foldledger.ui.theme.glassChip
import com.foldledger.ui.theme.SurfaceCard

/**
 * 日期 + 时间选择弹层。
 *
 * 记账的时间要**精确到分钟**，所以这里不是「今天/昨天/前天」三选一，
 * 而是一枚真正的日历 + 时可以加减的时分步进器，默认落在「此刻」。
 *
 * 折叠屏 / 横屏适配（弹层绝不能因为窗口变矮就把日历挤没）：
 *
 * | 场景 | 布局 |
 * |---|---|
 * | 半折叠·笔记本姿态 | 上屏日历，下屏时分 + 确定（铰链把「选哪天」和「选几点」分开） |
 * | 半折叠·书本姿态 | 左屏日历，右屏时分 + 确定 |
 * | **横屏 / 矮窗口** | **面板内左右两栏：左日历，右时分 + 确定**（不再竖向堆叠） |
 * | 竖屏 / 展开态 | 竖向堆叠：日历可滚动占满剩余高度，「取消 / 确定」常驻底部 |
 *
 * 另外窗口一矮就把大标题收起来、日期格子压到 32dp，省下的高度全给日历。
 */
@Composable
fun DateTimePickerOverlay(
    env: FoldEnv,
    initial: Long,
    onConfirm: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    val zone = remember { ZoneId.systemDefault() }
    val initLocal = remember(initial, zone) {
        Instant.ofEpochMilli(initial).atZone(zone).toLocalDateTime()
    }

    var selectedDate by remember { mutableStateOf(initLocal.toLocalDate()) }
    var hour by remember { mutableIntStateOf(initLocal.hour) }
    var minute by remember { mutableIntStateOf(initLocal.minute) }
    var visibleMonth by remember { mutableStateOf(YearMonth.from(initLocal.toLocalDate())) }

    val previewMillis = remember(selectedDate, hour, minute, zone) {
        LocalDateTime.of(selectedDate, LocalTime.of(hour, minute))
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
    }

    val applyMoment: (LocalDateTime) -> Unit = { dt ->
        selectedDate = dt.toLocalDate()
        hour = dt.hour
        minute = dt.minute
        visibleMonth = YearMonth.from(dt.toLocalDate())
    }

    // ---------- 空间判断（v3.1.1：形态在打开瞬间冻结） ----------
    // halfPanel ↔ compact ↔ 竖屏堆叠是三种不同结构。折叠/展开时若实时重算，
    // 就是"开着日期选择器折一下屏幕，面板整个换了个布局"—— 又一次闪。
    // 冻结后折屏幕只做内部重排，结构要等下次打开才跟着形态走。
    val halfPanel = remember { env.isTabletop || env.isBook }   // 半折叠：面板只有半屏
    val landscape = remember { env.widthDp > env.heightDp }     // 横屏
    val veryShort = remember { env.heightDp < 560 }
    val shortHeight = remember { env.heightDp < 640 }
    val compact = halfPanel || landscape || shortHeight
    val cellHeight: Dp = if (landscape || veryShort) 32.dp else 40.dp

    /** 预览条 + 快捷项。「选日期」的当前值与今天/昨天/前天都在这里。 */
    val header: @Composable () -> Unit = {
        // v13.9：原来是 primaryContainer 整块实色。改成"主色染膜的白玻璃"，
        // 字回到 onSurface —— 主色块上的 onPrimaryContainer 蓝字在玻璃语言里没有对应物。
        Box(
            Modifier
                .fillMaxWidth()
                .glassChip(
                    shape = RoundedCornerShape(14.dp),
                    tint = MaterialTheme.colorScheme.primary,
                    strength = 0.8f
                )
        ) {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("🗓️", fontSize = 17.sp)
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        DateExt.fullDateTime(previewMillis),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        // 窄栏里日期时间一行放不下，省略号截断好过硬裁半个字
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        DateExt.dayRelation(previewMillis),
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        QuickRow(
            onNow = { applyMoment(LocalDateTime.now(zone)) },
            onDaysAgo = { days ->
                applyMoment(
                    LocalDateTime.of(
                        LocalDate.now(zone).minusDays(days.toLong()),
                        LocalTime.of(hour, minute)
                    )
                )
            }
        )
    }

    /**
     * 日历区：竖直可滚动，窗口再矮也不会把年月日顶出屏幕。
     *
     * [withHeader] 与 [withTitle] 是两件事：标题只在「整屏竖排」时出现，
     * 而「预览条 + 快捷项」在分栏时要**只出现一次** —— 早先它跟着两个区域各画一份，
     * 恰好在窗口最矮的半折叠/横屏形态下白吃掉约 100dp，把日历挤得更小。
     */
    val calendarArea: @Composable (Boolean, Boolean) -> Unit = { withTitle, withHeader ->
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(if (withTitle) 14.dp else 10.dp))
            if (withTitle) {
                Text(
                    "选择日期与时间",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
            }
            if (withHeader) header()
            Spacer(Modifier.height(12.dp))
            CalendarCard(
                month = visibleMonth,
                selected = selectedDate,
                today = LocalDate.now(zone),
                cellHeight = cellHeight,
                onMonthChange = { visibleMonth = it },
                onPick = { date ->
                    selectedDate = date
                    if (YearMonth.from(date) != visibleMonth) visibleMonth = YearMonth.from(date)
                }
            )
            Spacer(Modifier.height(18.dp))
        }
    }

    /** 时分区：[withHeader] 同上，分栏时交给日历区去画，避免重复占高 */
    val timeArea: @Composable (Boolean) -> Unit = { withHeader ->
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(10.dp))
            if (withHeader) {
                header()
                Spacer(Modifier.height(12.dp))
            }
            TimeCard(
                hour = hour,
                minute = minute,
                onHour = { hour = it },
                onMinute = { minute = it }
            )
            Spacer(Modifier.height(14.dp))
        }
    }

    val actionArea: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PickerButton(
                    label = "取消",
                    container = null,
                    content = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                    onClick = onDismiss
                )
                PickerButton(
                    label = "确定",
                    container = MaterialTheme.colorScheme.primary,
                    content = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(2f),
                    onClick = { onConfirm(previewMillis) }
                )
            }
            Spacer(Modifier.height(14.dp))
        }
    }

    when {
        // ---------- 半折叠：日历在「上/左」，时分与确定在「下/右」 ----------
        halfPanel -> AppOverlay(
            env = env,
            onDismiss = onDismiss,
            leadingContent = { calendarArea(false, true) }
        ) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) { timeArea(false) }
                actionArea()
            }
        }

        // ---------- 横屏 / 矮窗口：面板内左右两栏，日历绝不会被挤掉 ----------
        compact -> AppOverlay(env = env, onDismiss = onDismiss) {
            Column(Modifier.fillMaxSize()) {
                // 只有底部抽屉形态才需要抓手；展开态的居中卡片不需要
                if (!env.useDialogOverlay) SheetGrabber()
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    Box(
                        Modifier
                            .weight(1.15f)
                            .fillMaxHeight()
                    ) { calendarArea(false, true) }
                    VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    ) { timeArea(false) }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                actionArea()
            }
        }

        // ---------- 竖屏 / 展开态：竖向堆叠，按钮常驻底部 ----------
        else -> AppOverlay(env = env, onDismiss = onDismiss) {
            Column(Modifier.fillMaxSize()) {
                if (!env.useDialogOverlay) SheetGrabber()
                Box(Modifier.weight(1f)) { calendarArea(true, true) }
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    TimeCard(
                        hour = hour,
                        minute = minute,
                        onHour = { hour = it },
                        onMinute = { minute = it }
                    )
                    Spacer(Modifier.height(12.dp))
                }
                actionArea()
            }
        }
    }
}

// ============================================================
//  快捷项
// ============================================================

@Composable
private fun QuickRow(onNow: () -> Unit, onDaysAgo: (Int) -> Unit) {
    // v5.5：4 个胶囊在窄栏（半折叠·书本姿态的左栏可能只有 200dp）会溢出，
    // 改成 FlowRow 自动换行 —— 否则最后一个「前天」直接被切掉，点都点不到。
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // v13.9：「现在」原来是整颗主色实心胶囊 —— 面板上唯一的一块饱和色。
        // 改成主色染膜的玻璃 + 主色字，强度 2.0 让它仍是这一行里最实的一颗。
        Box(
            Modifier
                .glassChip(
                    shape = RoundedCornerShape(50),
                    tint = MaterialTheme.colorScheme.primary,
                    strength = 1.9f,
                    elevation = 2.dp
                )
                .clickableScale(pressedScale = 0.93f) { onNow() }
        ) {
            Text(
                "🕒 现在",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
            )
        }
        listOf("今天" to 0, "昨天" to 1, "前天" to 2).forEach { (label, days) ->
            Box(
                Modifier
                    .glassChip(shape = RoundedCornerShape(50), strength = 0.6f)
                    .clickableScale(pressedScale = 0.93f) { onDaysAgo(days) }
            ) {
                Text(
                    label,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                )
            }
        }
    }
}

// ============================================================
//  日历
// ============================================================

@Composable
private fun CalendarCard(
    month: YearMonth,
    selected: LocalDate,
    today: LocalDate,
    cellHeight: Dp,
    onMonthChange: (YearMonth) -> Unit,
    onPick: (LocalDate) -> Unit
) {
    SurfaceCard(
        shape = RoundedCornerShape(18.dp),
        color = cardSurface(),
        elevation = 3.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        BoxWithConstraints {
            // 高亮圆点的边长必须同时受**行高**和**列宽**约束。
            // 早先只按 `cellHeight - 4` 算：compact 下 cellHeight = 32dp → 圆点 28dp，
            // 而横屏 / 半折叠时每格实际只有 ~24dp 宽，圆点会左右各溢出约 2dp，
            // 相邻日期看起来糊在一起。这里取两者的小值。
            val cellWidth = (maxWidth - 20.dp) / 7      // 扣掉 Column 左右各 10dp
            val dotSize = minOf(cellHeight - 4.dp, cellWidth - 4.dp).coerceAtLeast(18.dp)
            Column(Modifier.padding(horizontal = 10.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MonthNav("‹") { onMonthChange(month.minusMonths(1)) }
                Text(
                    "${month.year} 年 ${month.monthValue} 月",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
                MonthNav("›") { onMonthChange(month.plusMonths(1)) }
            }

            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth()) {
                WeekdayLabels.forEach { w ->
                    Text(
                        w,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(Modifier.height(3.dp))
            val lead = month.atDay(1).dayOfWeek.value % 7   // 周日开头：周日=0
            val dayCount = month.lengthOfMonth()
            val rows = ((lead + dayCount + 6) / 7)

            for (row in 0 until rows) {
                Row(Modifier.fillMaxWidth().height(cellHeight)) {
                    for (col in 0 until 7) {
                        val dayNum = row * 7 + col - lead + 1
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .padding(2.dp)
                        ) {
                            if (dayNum in 1..dayCount) {
                                val date = month.atDay(dayNum)
                                DayCell(
                                    day = dayNum,
                                    selected = date == selected,
                                    isToday = date == today,
                                    dotSize = dotSize,
                                    compact = cellHeight < 36.dp,
                                    // v5.5：半折叠·书本姿态的左栏很窄时（每格 ~22dp），
                                    // 13sp 的两位数会顶出圆点 → 再降一档字号
                                    tiny = dotSize < 24.dp,
                                    onClick = { onPick(date) }
                                )
                            }
                        }
                    }
                }
            }
            }
        }
    }
}

private val WeekdayLabels = listOf("日", "一", "二", "三", "四", "五", "六")

/**
 * 日期格子。
 *
 * 高亮圆点用**固定边长的正方形容器**再裁圆，而不是直接给格子裁圆 ——
 * 格子是「按权重铺满宽、高度固定」，宽高并不相等，直接裁出来会是椭圆。
 */
@Composable
private fun DayCell(
    day: Int,
    selected: Boolean,
    isToday: Boolean,
    dotSize: Dp,
    compact: Boolean,
    tiny: Boolean = false,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val bg = if (selected) scheme.primary else Color.Transparent
    val fg = when {
        selected -> scheme.onPrimary
        isToday -> scheme.primary
        else -> scheme.onSurface
    }
    val todayRing = if (isToday && !selected) {
        Modifier.border(1.dp, scheme.primary.copy(alpha = 0.6f), CircleShape)
    } else {
        Modifier
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickableScale(pressedScale = 0.86f) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(dotSize)
                .clip(CircleShape)
                .background(bg)
                .then(todayRing),
            contentAlignment = Alignment.Center
        ) {
            Text(
                day.toString(),
                fontSize = when {
                    tiny -> 10.sp
                    compact -> 12.sp
                    else -> 13.sp
                },
                color = fg,
                fontWeight = if (selected || isToday) FontWeight.SemiBold else FontWeight.Normal
            )
        }
    }
}

@Composable
private fun MonthNav(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(34.dp)
            .clip(CircleShape)
            .clickableScale(pressedScale = 0.88f) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(label, fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ============================================================
//  时分
// ============================================================

@Composable
private fun TimeCard(
    hour: Int,
    minute: Int,
    onHour: (Int) -> Unit,
    onMinute: (Int) -> Unit
) {
    SurfaceCard(
        shape = RoundedCornerShape(18.dp),
        color = cardSurface(),
        elevation = 3.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        BoxWithConstraints {
            // v5.5 修「折叠态时间挤到一块儿」：
            // 半折叠·书本姿态的右栏可能只剩 200dp 上下，横向并排的
            // 「− 23 +  :  − 45 +」在这么窄的地方会被压成一团 —— 数字和圆钮互相叠，
            // 根本看不清（用户实测反馈）。低于 340dp 就改成**上下堆叠**：
            // 时一行、分一行，各自吃满整宽，按钮和数字之间永远有富余。
            // 上下堆叠会变高，但时间区本来就是 verticalScroll，滚一下就能看全。
            val stacked = maxWidth < 340.dp
            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                Text(
                    if (stacked) "时间" else "时间（精确到分钟）",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(if (stacked) 8.dp else 6.dp))
                if (stacked) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        TimeStepper("时", hour, onHour, Modifier.fillMaxWidth())
                        TimeStepper("分", minute, onMinute, Modifier.fillMaxWidth())
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TimeStepper("时", hour, onHour, Modifier.weight(1f))
                        Text(
                            ":",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                        TimeStepper("分", minute, onMinute, Modifier.weight(1f))
                    }
                }
                Spacer(Modifier.height(10.dp))
                // 快捷分钟：窄栏放不下 4 个，自动换行（FlowRow），不会挤掉或截断
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(0, 15, 30, 45).forEach { m ->
                        val active = minute == m
                        Box(
                            Modifier
                                .glassChip(
                                    shape = RoundedCornerShape(50),
                                    strength = if (active) 1.0f else 0.55f
                                )
                                .then(
                                    if (active) {
                                        Modifier.border(
                                            1.2.dp,
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.42f),
                                            RoundedCornerShape(50)
                                        )
                                    } else Modifier
                                )
                                .clickableScale(pressedScale = 0.92f) { onMinute(m) }
                        ) {
                            Text(
                                String.format(":%02d", m),
                                fontSize = 11.sp,
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (active) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 11.dp, vertical = 5.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TimeStepper(
    label: String,
    value: Int,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            label,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        // 窄分栏（横屏 / 半折叠）时这个 stepper 可能只剩 90dp 上下，
        // 而「32 圆钮 + 最小 44 数字 + 32 圆钮」= 108dp 是硬下限 —— 一旦不够，
        // 圆钮会被约束压变形、数字和加号挤在一起。
        // 这里按**实际可用宽度**决定圆钮尺寸，并让数字吃掉剩余空间。
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val tight = maxWidth < 112.dp
            val btn = if (tight) 27.dp else 32.dp
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                StepButton("−", size = btn) { onChange((value - 1 + 60) % 60) }
                Text(
                    String.format("%02d", value),
                    fontSize = if (tight) 20.sp else 23.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                StepButton("+", size = btn) { onChange((value + 1) % 60) }
            }
        }
    }
}

@Composable
private fun StepButton(label: String, size: Dp = 32.dp, onClick: () -> Unit) {
    // v13.9：圆钮从 surfaceVariant 平涂换成玻璃（圆形的玻璃靠球面高光成立）
    Box(
        Modifier
            .size(size)
            .glassChip(shape = CircleShape, strength = 0.8f)
            .clickableScale(pressedScale = 0.85f) { onClick() }
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(label, fontSize = if (size < 30.dp) 15.sp else 17.sp, fontWeight = FontWeight.Medium)
        }
    }
}

/**
 * 动作条按钮。[container] 为 null = 中性玻璃；给主色 = 强调玻璃。
 *
 * ⚠️ v13.9：原来这两个按钮是"实色底 + onPrimary 白字"，在玻璃面板里读作
 * 两块贴上去的色板。改成玻璃之后**字一律 onSurface** —— 铺满主色的实心底
 * 才有"白字"这回事，一层淡膜上没有它。
 */
@Composable
private fun PickerButton(
    label: String,
    container: Color?,
    content: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier
            .glassChip(
                shape = RoundedCornerShape(14.dp),
                tint = container,
                strength = if (container == null) 0.6f else 1.9f,
                elevation = if (container == null) 0.dp else 2.dp
            )
            .clickableScale(pressedScale = 0.95f) { onClick() }
    ) {
        Box(Modifier.padding(vertical = 13.dp), contentAlignment = Alignment.Center) {
            Text(label, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = content)
        }
    }
}
