package com.foldledger.data

import com.foldledger.data.sync.Lww
import com.foldledger.data.sync.SyncWatermarks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** M4.3 纯逻辑：LWW 裁决 + 时钟校正 + 水位线编解码（无 DB，纯函数） */
class SyncEngineLogicTest {

    // ---------------- Lww ----------------

    @Test
    fun newerTimestampWins() {
        assertTrue(Lww.aWins(200, 1, 100, 9))
        assertFalse(Lww.aWins(100, 9, 200, 1))
    }

    @Test
    fun sameTimestampHigherDeviceWins() {
        assertTrue(Lww.aWins(100, 9, 100, 1))
        assertFalse(Lww.aWins(100, 1, 100, 9))
    }

    @Test
    fun sameTimestampSameDeviceIsIdempotent() {
        // 幂等：同刻同设备 A 赢，不产生覆盖写
        assertTrue(Lww.aWins(100, 5, 100, 5))
    }

    @Test
    fun alignTimestampAddsOffset() {
        assertEquals(1_100L, Lww.alignTimestamp(1_000L, 100L))
        assertEquals(900L, Lww.alignTimestamp(1_000L, -100L))
        assertEquals(1_000L, Lww.alignTimestamp(1_000L, 0L))
    }

    // ---------------- SyncWatermarks ----------------

    @Test
    fun emptyRawIsNeverSynced() {
        val wm = SyncWatermarks("")
        assertTrue(wm.isEmpty())
        assertEquals(0L, wm.get(com.foldledger.data.sync.TableNames.TRANSACTIONS))
    }

    @Test
    fun setGetRoundTrip() {
        val wm = SyncWatermarks()
        wm.set("txns", 300L)
        wm.set("txns", 500L) // 后设覆盖先设（推进语义用 advance 保证不回退）
        assertEquals(500L, wm.get("txns"))
        assertEquals(0L, wm.get("ledgers"))

        val again = SyncWatermarks(wm.toRawString())
        assertEquals(500L, again.get("txns"))
    }

    @Test
    fun advanceTakesMaxAndKeepsZeroRowsOut() {
        val wm = SyncWatermarks()
        wm.advance("txns", 100L)
        wm.advance("txns", 50L)  // 旧的推进无效
        wm.advance("txns", 200L)
        assertEquals(200L, wm.get("txns"))

        wm.set("ledgers", 0L)     // 0 = 从未同步，不持久化
        val raw = wm.toRawString()
        assertFalse(raw.contains("ledgers"))
        assertEquals("txns=200", raw)
    }

    @Test
    fun multiTableRawStringSorted() {
        val wm = SyncWatermarks()
        wm.set("transactions", 300L)
        wm.set("ledgers", 100L)
        wm.set("categories", 200L)
        val raw = wm.toRawString()
        val lines = raw.split('\n')
        assertEquals("categories=200", lines[0])
        assertEquals("ledgers=100", lines[1])
        assertEquals("transactions=300", lines[2])
    }

    @Test
    fun garbageLinesIgnored() {
        val wm = SyncWatermarks("badline\ntxns=abc\ntransactions=42")
        assertEquals(0L, wm.get("badline"))
        assertEquals(0L, wm.get("txns"))
        assertEquals(42L, wm.get("transactions"))
    }
}
