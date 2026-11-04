package com.foldledger.util

import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 日期工具。全部围绕「自然日 / 自然月」边界，用于统计与流水分组。
 */
object DateExt {

    private val dayFmt = ThreadLocalLocalFormat("yyyy-MM-dd")
    private val monthFmt = ThreadLocalLocalFormat("yyyy年M月")

    fun startOfDay(ts: Long): Long = Calendar.getInstance().apply {
        timeInMillis = ts
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun endOfDay(ts: Long): Long = startOfDay(ts) + TimeUnit.DAYS.toMillis(1) - 1

    fun startOfMonth(ts: Long): Long = Calendar.getInstance().apply {
        timeInMillis = ts
        set(Calendar.DAY_OF_MONTH, 1)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun endOfMonth(ts: Long): Long = Calendar.getInstance().apply {
        timeInMillis = startOfMonth(ts)
        add(Calendar.MONTH, 1)
        timeInMillis -= 1
    }.timeInMillis

    fun shiftMonth(ts: Long, delta: Int): Long = Calendar.getInstance().apply {
        timeInMillis = ts
        set(Calendar.DAY_OF_MONTH, 1)
        add(Calendar.MONTH, delta)
    }.timeInMillis

    fun dayKey(ts: Long): String = dayFmt.get().format(ts)

    fun monthLabel(ts: Long): String = monthFmt.get().format(ts)

    fun isSameDay(a: Long, b: Long): Boolean = startOfDay(a) == startOfDay(b)

    private val weekdayCn = arrayOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")

    /** 今天 / 昨天 / 10月13日 周一 */
    fun dayHeader(ts: Long): String {
        val today = startOfDay(System.currentTimeMillis())
        val target = startOfDay(ts)
        val diffDays = ((today - target) / TimeUnit.DAYS.toMillis(1)).toInt()
        val prefix = when (diffDays) {
            0 -> "今天"
            1 -> "昨天"
            2 -> "前天"
            else -> {
                val c = Calendar.getInstance().apply { timeInMillis = ts }
                "${c.get(Calendar.MONTH) + 1}月${c.get(Calendar.DAY_OF_MONTH)}日"
            }
        }
        val c = Calendar.getInstance().apply { timeInMillis = ts }
        return "$prefix ${weekdayCn[c.get(Calendar.DAY_OF_WEEK) - 1]}"
    }

    fun fullDateTime(ts: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = ts }
        return String.format(
            Locale.CHINA, "%d年%d月%d日 %02d:%02d",
            c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH),
            c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE)
        )
    }

    /**
     * 相对今天的说法：今天 / 昨天 / 前天 / 3 天前 / 明天 / 2 天后。
     * 用于记账页「日期」一栏的副标题 —— 用户扫一眼就知道选的是哪天。
     */
    fun dayRelation(ts: Long): String {
        val today = startOfDay(System.currentTimeMillis())
        val target = startOfDay(ts)
        val diff = ((target - today) / TimeUnit.DAYS.toMillis(1)).toInt()
        return when (diff) {
            0 -> "今天"
            -1 -> "昨天"
            -2 -> "前天"
            1 -> "明天"
            in Int.MIN_VALUE..-3 -> "${-diff} 天前"
            else -> "$diff 天后"
        }
    }

    /** 短日期时间：10月13日 15:40（不带年份，窄屏也放得下） */
    fun shortDateTime(ts: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = ts }
        return String.format(
            Locale.CHINA, "%d月%d日 %02d:%02d",
            c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH),
            c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE)
        )
    }

    /** 用于 X 轴：10/13 */
    fun shortDay(ts: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = ts }
        return "${c.get(Calendar.MONTH) + 1}/${c.get(Calendar.DAY_OF_MONTH)}"
    }
}

/** 简单包装，避免每次 format 都新建 SimpleDateFormat（非线程安全，用 ThreadLocal） */
private class ThreadLocalLocalFormat(private val pattern: String) {
    private val holder = ThreadLocal.withInitial {
        java.text.SimpleDateFormat(pattern, Locale.CHINA)
    }

    fun get(): java.text.SimpleDateFormat = holder.get()!!
}
