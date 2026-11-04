package com.foldledger.data

import com.foldledger.data.db.RecurringInterval
import java.time.Instant
import java.time.ZoneId

/**
 * 周期推进（v5.7）。
 *
 * 为什么用 [java.time] 而不是加毫秒：**月末必须按日历推进**。
 * 「1 月 31 日 + 每月」如果按 30 天算会滑到 3 月 2 日，越推越偏；
 * `LocalDate.plusMonths(1)` 会正确地给出 2 月 28/29 日（并且之后不会回弹到 31 日，
 * 这是各平台日历的一致语义，房租类固定支出正好需要这个）。
 *
 * 时区固定取系统时区：周期是"人过的日子"，不是 UTC 刻度。
 */
object RecurringSchedule {

    fun advance(from: Long, kind: Int, zone: ZoneId = ZoneId.systemDefault()): Long {
        val dt = Instant.ofEpochMilli(from).atZone(zone).toLocalDateTime()
        val next = when (kind) {
            RecurringInterval.WEEKLY -> dt.plusWeeks(1)
            RecurringInterval.YEARLY -> dt.plusYears(1)
            else -> dt.plusMonths(1)
        }
        return next.atZone(zone).toInstant().toEpochMilli()
    }

    /**
     * 把 [nextDueAt] 推进到第一个 **晚于 now** 的时刻。
     *
     * 用来"跳过本次"：跳过不是删掉，而是认了这一期不入账，等下一期。
     * 上限 240 次（20 年的月付）纯粹是防御性护栏，避免数据异常时死循环。
     */
    fun advancePast(nextDueAt: Long, kind: Int, now: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        var cur = nextDueAt
        var guard = 0
        while (cur <= now && guard < 240) {
            cur = advance(cur, kind, zone)
            guard++
        }
        return cur
    }
}
