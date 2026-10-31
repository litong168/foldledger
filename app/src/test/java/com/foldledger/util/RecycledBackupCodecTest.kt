package com.foldledger.util

import com.foldledger.data.backup.RecycledBackup
import com.foldledger.data.backup.RecycledBackupCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回收站里备份文件的清单编解码（v9.4）。
 *
 * 口径：坏数据不能把整个清单带崩（丢一条总比丢全部强），空串要当空列表。
 */
class RecycledBackupCodecTest {

    @Test
    fun roundTripKeepsEverything() {
        val list = listOf(
            RecycledBackup("滴水记账-20261022.foldledger.json", 1760000000000L, "1760000000000-a.json", true, 2048L),
            RecycledBackup("滴水记账-20261021.foldledger.json", 1760000000001L, "1760000000001-b.json", false, 1024L)
        )
        val decoded = RecycledBackupCodec.decode(RecycledBackupCodec.encode(list))
        assertEquals(2, decoded.size)
        assertEquals(list[0].fileName, decoded[0].fileName)
        assertEquals(list[0].deletedAt, decoded[0].deletedAt)
        assertTrue(decoded[0].hasXlsx)
        assertEquals(1024L, decoded[1].sizeBytes)
    }

    @Test
    fun emptyStringIsEmptyList() {
        assertTrue(RecycledBackupCodec.decode("").isEmpty())
    }

    @Test
    fun brokenJsonDoesNotCrash() {
        assertTrue(RecycledBackupCodec.decode("not json at all").isEmpty())
    }

    @Test
    fun entriesWithoutNameAreDropped() {
        val raw = """[{"fileName":"","storedName":"x.json"},{"fileName":"a.json","storedName":"b.json"}]"""
        val decoded = RecycledBackupCodec.decode(raw)
        assertEquals(1, decoded.size)
        assertEquals("a.json", decoded[0].fileName)
    }

    @Test
    fun xlsxStoredNameSurvivesRoundTrip() {
        // v9.7.2：xlsx 内部副本名要显式存下来 —— 靠文件名反推基本拼不中
        val item = RecycledBackup(
            fileName = "滴水记账-2026-10-22.foldledger.json",
            deletedAt = 1760000000000L,
            storedName = "1760000000000-滴水记账-2026-10-22.foldledger.json",
            hasXlsx = true,
            sizeBytes = 4096L,
            xlsxStoredName = "1760000000000-滴水记账-2026-10-22.foldledger.xlsx"
        )
        val decoded = RecycledBackupCodec.decode(RecycledBackupCodec.encode(listOf(item))).single()
        assertEquals(item.xlsxStoredName, decoded.xlsxStoredName)
    }

    @Test
    fun oldEntriesWithoutXlsxNameStillDecode() {
        // 老清单（v9.4~v9.7.1）没有这个字段 → 应当解成 null，而不是空串
        val raw = """[{"fileName":"a.foldledger.json","deletedAt":1,"storedName":"1-a.foldledger.json","hasXlsx":true,"sizeBytes":10}]"""
        val decoded = RecycledBackupCodec.decode(raw).single()
        assertTrue(decoded.hasXlsx)
        assertEquals(null, decoded.xlsxStoredName)
    }
}
