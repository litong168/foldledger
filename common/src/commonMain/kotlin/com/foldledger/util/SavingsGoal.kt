package com.foldledger.util

import java.util.concurrent.TimeUnit

/**
 * 存钱目标的算术（v9.2）——抽成纯函数，好测也好复用。
 *
 * 心愿罐最怕"算得含糊"：进度到 99.9% 还差一块钱，或者"每月要存多少"给出个零头。
 * 这里把口径钉死：
 *  - 进度按**已存/目标**，封顶 1.0（超了就是达成）；
 *  - 每月要存多少 = 剩余金额 ÷ 剩余月数（向上取整到元），不足一月按一月算；
 *  - 没设目标日期 → 不给建议（0），不强推。
 */
object SavingsGoal {

    /** 进度 0f..1f */
    fun progress(savedCents: Long, targetCents: Long): Float {
        if (targetCents <= 0L) return 0f
        val p = savedCents.toDouble() / targetCents.toDouble()
        return when {
            p < 0 -> 0f
            p > 1 -> 1f
            else -> p.toFloat()
        }
    }

    /** 还差多少（分），已达成返回 0 */
    fun remaining(savedCents: Long, targetCents: Long): Long {
        val r = targetCents - savedCents
        return if (r > 0L) r else 0L
    }

    /** 是否已达成 */
    fun reached(savedCents: Long, targetCents: Long): Boolean =
        targetCents > 0L && savedCents >= targetCents

    /**
     * 距目标日期还有几天。
     * 未设期限（targetDate = 0）返回 0；已过期返回负数。
     */
    fun daysLeft(targetDate: Long, now: Long): Int {
        if (targetDate <= 0L) return 0
        val diff = targetDate - now
        val days = TimeUnit.MILLISECONDS.toDays(diff)
        return days.toInt()
    }

    /**
     * 每月要存多少（分）。
     *
     * 没设期限 → 0；已经达成 → 0；否则按剩余月数平摊，**向上取整到元**。
     */
    fun monthlyNeeded(
        savedCents: Long,
        targetCents: Long,
        targetDate: Long,
        now: Long
    ): Long {
        if (targetDate <= 0L) return 0L
        if (reached(savedCents, targetCents)) return 0L
        val left = remaining(savedCents, targetCents)
        val days = daysLeft(targetDate, now).toLong()
        if (days <= 0L) return left // 已经到期限了，剩下的全算这个月
        // 不足一个月按一个月算
        val months = ((days + 29L) / 30L).coerceAtLeast(1L)
        // 向上取整到元
        val per = (left + months * 100 - 1) / (months * 100)
        return per * 100
    }

    /**
     * 期限 → 目标时间戳。
     *
     * @param months 预设月数（0 = 不设期限）
     * @param customDate 自选日期（有值时优先于预设）
     * @return 0 = 不设期限
     */
    fun targetDateFrom(now: Long, months: Int, customDate: Long?): Long = when {
        customDate != null && customDate > 0L -> customDate
        months > 0 -> now + TimeUnit.DAYS.toMillis(months * 30L)
        else -> 0L
    }

    /** 进度文案用的百分比整数（如 62 表示 62%） */
    fun percent(savedCents: Long, targetCents: Long): Int =
        (progress(savedCents, targetCents) * 100).toInt()
}
