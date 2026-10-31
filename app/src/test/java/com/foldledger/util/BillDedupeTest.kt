package com.foldledger.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 账单导入去重（v9.7.1 修的 BUG 的回归用例）。
 *
 * 曾经的 bug：键里带了"对方"，而库里对应字段是**分类名**，两边永远拼不上 →
 * 去重完全失效，同一份账单导两次就会翻倍。
 */
class BillDedupeTest {

    // 对齐到整分钟（1_760_000_000_000 % 60000 = 20000，减掉它）
    private val base = 1_759_999_980_000L

    private fun key(minuteOffset: Long = 0, amount: Long = 3250L, dir: Int = 0) =
        BillDedupe.keyOf(base + minuteOffset * 60_000L, amount, dir)

    @Test
    fun sameMinuteAmountDirectionIsDuplicate() {
        val existing = listOf(key())
        val incoming = listOf(key())
        assertEquals(listOf(true), BillDedupe.duplicates(incoming, existing))
        assertEquals(1, BillDedupe.duplicateCount(incoming, existing))
    }

    @Test
    fun differentAmountOrDirectionOrMinuteIsNotDuplicate() {
        val existing = listOf(key())
        assertFalse(BillDedupe.duplicates(listOf(key(amount = 3260L)), existing).single())
        assertFalse(BillDedupe.duplicates(listOf(key(dir = 1)), existing).single())
        assertFalse(BillDedupe.duplicates(listOf(key(minuteOffset = 1)), existing).single())
    }

    @Test
    fun sameMinuteDifferentLedgerRowsCountByMultiplicity() {
        // 库里 1 笔，账单里 2 笔完全一样（同分钟真买了两份）→ 只该判 1 笔重复
        val existing = listOf(key())
        val incoming = listOf(key(), key())
        assertEquals(listOf(true, false), BillDedupe.duplicates(incoming, existing))
        assertEquals(1, BillDedupe.duplicateCount(incoming, existing))
    }

    @Test
    fun nothingInDbMeansNothingDuplicated() {
        val incoming = listOf(key(), key(amount = 100L))
        assertEquals(listOf(false, false), BillDedupe.duplicates(incoming, emptyList()))
    }

    @Test
    fun emptyInputIsSafe() {
        assertTrue(BillDedupe.duplicates(emptyList(), listOf(key())).isEmpty())
        assertEquals(0, BillDedupe.duplicateCount(emptyList(), emptyList()))
    }

    @Test
    fun secondWithinSameMinuteMatches() {
        // 同一分钟内的不同秒都应视为同分钟
        val existing = listOf(BillDedupe.keyOf(base + 5_000L, 3250L, 0))
        val incoming = listOf(BillDedupe.keyOf(base + 55_000L, 3250L, 0))
        assertEquals(listOf(true), BillDedupe.duplicates(incoming, existing))
    }
}
