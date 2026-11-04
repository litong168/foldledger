package com.foldledger.data

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.foldledger.data.db.AppDatabase
import com.foldledger.data.db.AppDatabase_Impl
import com.foldledger.data.db.Direction
import com.foldledger.data.repo.LedgerRepository
import com.foldledger.data.sync.EntityCodec
import com.foldledger.data.sync.SyncConflict
import com.foldledger.data.sync.SyncEngine
import com.foldledger.data.sync.SyncIdAllocator
import com.foldledger.data.sync.SyncLogEntry
import com.foldledger.data.sync.SyncLogStore
import com.foldledger.data.sync.SyncWatermarks
import com.foldledger.data.sync.TableNames
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 同步记录（v16.2.8）：编解码 + 冲突明细捕获。
 *
 * 两块都值得测：
 *  - 编解码错了会把「同步记录」页变成空白或崩溃（日志本身不该拖垮同步）；
 *  - 冲突明细错了会让"我改的那笔为什么没过去"永远查不清 —— 这正是这个功能存在的理由。
 */
class SyncLogTest {

    private lateinit var dbFile: File
    private lateinit var db: AppDatabase
    private lateinit var repo: LedgerRepository
    private lateinit var stamps: TestStamps

    @Before
    fun setUp() {
        stamps = TestStamps()
        dbFile = File.createTempFile("foldledger-synclog", ".db")
        db = Room.databaseBuilder(
            name = dbFile.absolutePath,
            factory = { AppDatabase_Impl() }
        ).setDriver(BundledSQLiteDriver()).build()
        repo = LedgerRepository(db, stamps, SyncIdAllocator())
    }

    @After
    fun tearDown() {
        db.close()
        dbFile.delete()
    }

    // ---------------- 编解码 ----------------

    @Test
    fun `同步记录能原样往返`() {
        val entry = SyncLogEntry(
            at = 1_760_000_000_000,
            ok = false,
            trigger = "自动",
            upRows = 7,
            downRows = 3,
            inserted = 2,
            updated = 1,
            localWins = 2,
            skipped = 1,
            durationMs = 412,
            error = "连接服务器超时，请检查网络后重试",
            conflicts = listOf(
                SyncConflict(TableNames.TRANSACTIONS, 42L, 1_760_000_000_100, 1_759_999_999_000, 3L),
                SyncConflict(TableNames.LEDGERS, 7L, 1_760_000_000_200, 1_759_999_998_000, 5L)
            )
        )
        val back = SyncLogStore.decode(SyncLogStore.encode(listOf(entry))).single()
        assertEquals(entry.at, back.at)
        assertEquals(entry.ok, back.ok)
        assertEquals(entry.trigger, back.trigger)
        assertEquals(entry.upRows, back.upRows)
        assertEquals(entry.downRows, back.downRows)
        assertEquals(entry.localWins, back.localWins)
        assertEquals(entry.error, back.error)
        assertEquals(2, back.conflicts.size)
        assertEquals(TableNames.TRANSACTIONS, back.conflicts[0].table)
        assertEquals(42L, back.conflicts[0].id)
        assertEquals(3L, back.conflicts[0].remoteByDevice)
    }

    @Test
    fun `坏数据当成空记录而不是抛异常`() {
        assertTrue(SyncLogStore.decode("").isEmpty())
        assertTrue(SyncLogStore.decode("不是 JSON").isEmpty())
        assertTrue(SyncLogStore.decode("""{"a":1}""").isEmpty())
    }

    @Test
    fun `环形缓冲只保留上限条`() {
        val many = (1..40).map {
            SyncLogEntry(at = it.toLong(), ok = true, trigger = "手动", upRows = 0, downRows = 0,
                inserted = 0, updated = 0, localWins = 0, skipped = 0, durationMs = 1)
        }
        val back = SyncLogStore.decode(SyncLogStore.encode(many))
        assertEquals(SyncLogStore.LIMIT, back.size)
    }

    @Test
    fun `失败提示能落到具体动作`() {
        assertTrue(SyncLogStore.hintFor("密钥错误或未授权（401）")!!.contains("访问密钥"))
        assertTrue(SyncLogStore.hintFor("无法解析服务器域名")!!.contains("域名解析"))
        assertNull(SyncLogStore.hintFor(null))
    }

    // ---------------- 冲突明细 ----------------

    @Test
    fun `本地较新的行会被记成冲突明细`() = runBlocking<Unit> {
        val lid = repo.createLedger("日常", "daily", "📒", "#1D3B57")
        repo.addTransaction(
            ledgerId = lid, categoryId = null, categoryName = "餐饮", categoryEmoji = "🍜",
            direction = Direction.EXPENSE, amountCents = 3200, note = "午饭", occurredAt = 1_760_000_000_000
        )
        val localTxn = repo.snapshot().transactions.single()
        val row = EntityCodec.txnToRow(localTxn)

        // 远端带来一条**更旧**的同一行 → 本地胜，应留下冲突明细。
        // ⚠️ 时间戳在 payload 里也有一份，applyIncoming 用的是解码后的实体，
        // 只改 SyncRow.updatedAt 是白改（第一版就踩了这个坑，测试直接暴露出来）。
        val olderAt = localTxn.updatedAt - 5_000
        val older = row.copy(
            updatedAt = olderAt,
            updatedByDeviceId = 99L,
            payload = org.json.JSONObject(row.payload)
                .put("updatedAt", olderAt)
                .put("updatedByDeviceId", 99L)
                .toString()
        )
        val stats = SyncEngine(db).applyIncoming(listOf(older), SyncWatermarks(""))

        assertEquals(1, stats.localWins)
        assertEquals(1, stats.conflicts.size)
        val c = stats.conflicts.single()
        assertEquals(TableNames.TRANSACTIONS, c.table)
        assertEquals(localTxn.id, c.id)
        assertEquals(localTxn.updatedAt, c.localUpdatedAt)
        assertEquals(older.updatedAt, c.remoteUpdatedAt)
        assertEquals(99L, c.remoteByDevice)
    }

    @Test
    fun `远端较新时不产生冲突明细`() = runBlocking<Unit> {
        val lid = repo.createLedger("日常", "daily", "📒", "#1D3B57")
        repo.addTransaction(
            ledgerId = lid, categoryId = null, categoryName = "餐饮", categoryEmoji = "🍜",
            direction = Direction.EXPENSE, amountCents = 3200, note = "午饭", occurredAt = 1_760_000_000_000
        )
        val localTxn = repo.snapshot().transactions.single()
        val newerAt = localTxn.updatedAt + 5_000
        val base = EntityCodec.txnToRow(localTxn)
        val row = base.copy(
            updatedAt = newerAt,
            payload = org.json.JSONObject(base.payload).put("updatedAt", newerAt).toString()
        )
        val stats = SyncEngine(db).applyIncoming(listOf(row), SyncWatermarks(""))
        assertEquals(0, stats.localWins)
        assertTrue(stats.conflicts.isEmpty())
    }
}
