package com.foldledger.data

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.foldledger.data.db.AppDatabase
import com.foldledger.data.db.AppDatabase_Impl
import com.foldledger.data.db.Direction
import com.foldledger.data.db.LedgerEntity
import com.foldledger.data.db.TransactionEntity
import com.foldledger.data.repo.LedgerRepository
import com.foldledger.data.sync.EntityCodec
import com.foldledger.data.sync.SyncDeviceHolder
import com.foldledger.data.sync.SyncEngine
import com.foldledger.data.sync.SyncIdAllocator
import com.foldledger.data.sync.SyncWatermarks
import com.foldledger.data.sync.TableNames
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * M4.3 引擎真库测试：增量收集（水位线 + auto 排除）、LWW 合并应用、
 * 墓碑传播与防复活、依赖表序、统一 id 空间、双端往返。
 */
class SyncEngineDbTest {

    private lateinit var dbFile: File
    private lateinit var db: AppDatabase
    private lateinit var stamps: TestStamps
    private lateinit var allocator: SyncIdAllocator
    private lateinit var repo: LedgerRepository
    private lateinit var engine: SyncEngine

    @Before
    fun setUp() {
        stamps = TestStamps()
        allocator = SyncIdAllocator()
        dbFile = File.createTempFile("foldledger-engine", ".db")
        db = Room.databaseBuilder(
            name = dbFile.absolutePath,
            factory = { AppDatabase_Impl() }
        ).setDriver(BundledSQLiteDriver()).build()
        repo = LedgerRepository(db, stamps, allocator)
        engine = SyncEngine(db)
    }

    @After
    fun tearDown() {
        SyncDeviceHolder.deviceId = 0L
        db.close()
        dbFile.delete()
    }

    private suspend fun newLedger(name: String = "日常"): Long =
        repo.createLedger(name, "daily", "📒", "#1D3B57")

    private suspend fun newTxn(ledgerId: Long, amountCents: Long, note: String = ""): Long =
        repo.addTransaction(
            ledgerId = ledgerId, categoryId = null, categoryName = "餐饮", categoryEmoji = "🍜",
            direction = Direction.EXPENSE, amountCents = amountCents, note = note, occurredAt = 0L
        )

    // ---------------- 收集 ----------------

    @Test
    fun collectAllWithZeroWatermarkExcludesAutoLedger() = runBlocking {
        val lid = newLedger()
        newTxn(lid, 1000)
        repo.ensureAutoLedger()                                  // auto 账本
        val autoId = repo.findAutoLedgerId()!!
        repo.addTransaction(autoId, null, "自动收入", "🤖",
            Direction.INCOME, 2000, "", 0L)

        val rows = engine.collectOutgoing(SyncWatermarks(""))
        assertTrue(rows.filter { it.table == TableNames.LEDGERS }
            .none { EntityCodec.rowToLedger(it).sceneKey == "auto" })
        // auto 账本本体、它的流水、它的预置分类全被排除；日常账本的行都在
        assertEquals(1, rows.count { it.table == TableNames.LEDGERS })
        assertEquals(1, rows.count { it.table == TableNames.TRANSACTIONS })
        val catRows = rows.filter { it.table == TableNames.CATEGORIES }
        assertTrue(catRows.isNotEmpty())
        assertTrue(catRows.none { EntityCodec.rowToCategory(it).ledgerId == autoId })
    }

    @Test
    fun collectIncrementSinceWatermarkIncludesTombstones() = runBlocking {
        val lid = newLedger()
        val a = newTxn(lid, 1000)
        newTxn(lid, 500)
        stamps.advance(1_000)
        val mid = stamps.now                                  // 两条流水都在 mid 之前
        stamps.advance(1_000)
        repo.deleteTransaction(a)                             // a 的墓碑 updatedAt = mid+1000

        val wm = SyncWatermarks().apply { set(TableNames.TRANSACTIONS, mid) }
        val txnRows = engine.collectOutgoing(wm).filter { it.table == TableNames.TRANSACTIONS }
        assertEquals(1, txnRows.size)
        assertEquals(a, txnRows.single().id)
        assertTrue(txnRows.single().deletedAt > 0)
    }

    // ---------------- 应用 ----------------

    @Test
    fun applyIncomingInsertsNewRowsAndAdvancesWatermark() = runBlocking {
        val rows = listOf(
            EntityCodec.ledgerToRow(
                LedgerEntity(id = 1L, name = "远端账本", sceneKey = "daily", emoji = "📒",
                    colorHex = "#1D3B57", updatedAt = 1_000, updatedByDeviceId = 1L)
            ),
            EntityCodec.txnToRow(
                TransactionEntity(id = 1L, ledgerId = 1L, categoryName = "餐饮", categoryEmoji = "🍜",
                    direction = Direction.EXPENSE, amountCents = 888, occurredAt = 0L,
                    updatedAt = 2_000, updatedByDeviceId = 1L)
            )
        )
        val wm = SyncWatermarks("")
        val stats = engine.applyIncoming(rows, wm)
        assertEquals(2, stats.inserted)
        assertEquals(0, stats.localWins)
        assertEquals(888L, repo.allTransactionsOnce().single().amountCents)
        // 水位线推进到 max(updatedAt)+1（>= 增量语义）
        assertEquals(2_001L, wm.get(TableNames.TRANSACTIONS))
        assertEquals(1_001L, wm.get(TableNames.LEDGERS))
    }

    @Test
    fun applyIncomingLwwNewerWinsOlderLoses() = runBlocking {
        val lid = newLedger()
        val id = newTxn(lid, 1000, note = "旧")
        val local = repo.getTransaction(id)!!
        stamps.advance(1_000)

        // 远端更新版覆盖
        val newer = EntityCodec.txnToRow(
            local.copy(note = "远端覆盖", updatedAt = local.updatedAt + 500, updatedByDeviceId = 9L)
        )
        engine.applyIncoming(listOf(newer), SyncWatermarks(""))
        assertEquals("远端覆盖", repo.getTransaction(id)!!.note)
        assertEquals(9L, repo.getTransaction(id)!!.updatedByDeviceId)

        // 远端旧版输（被拒绝的行也推进水位线）
        val older = EntityCodec.txnToRow(
            local.copy(note = "远端旧", updatedAt = local.updatedAt - 500, updatedByDeviceId = 9L)
        )
        val wm = SyncWatermarks("")
        val stats = engine.applyIncoming(listOf(older), wm)
        assertEquals(1, stats.localWins)
        assertEquals("远端覆盖", repo.getTransaction(id)!!.note)
        assertEquals(older.updatedAt + 1L, wm.get(TableNames.TRANSACTIONS))
    }

    @Test
    fun tombstonePropagatesAndOldRowCannotResurrect() = runBlocking {
        val lid = newLedger()
        val id = newTxn(lid, 1000)
        stamps.advance(1_000)
        val live = repo.getTransaction(id)!!

        // 远端墓碑（更新的 updatedAt）
        val tomb = EntityCodec.txnToRow(
            live.copy(deletedAt = 9_999L, updatedAt = live.updatedAt + 100, updatedByDeviceId = 9L)
        )
        engine.applyIncoming(listOf(tomb), SyncWatermarks(""))
        assertNull(repo.getTransaction(id))

        // 远端旧行想复活 → LWW 输，墓碑保持
        val oldRow = EntityCodec.txnToRow(
            live.copy(updatedAt = live.updatedAt + 50, updatedByDeviceId = 9L)
        )
        val stats = engine.applyIncoming(listOf(oldRow), SyncWatermarks(""))
        assertEquals(1, stats.localWins)
        assertNull(repo.getTransaction(id))
    }

    @Test
    fun applyIncomingDropsAutoRowsDefensively() = runBlocking {
        repo.ensureAutoLedger()
        val autoId = repo.findAutoLedgerId()!!
        val autoLedger = repo.getLedger(autoId)!!

        val rows = listOf(
            EntityCodec.ledgerToRow(autoLedger.copy(updatedAt = 9_000, updatedByDeviceId = 9L)),
            EntityCodec.txnToRow(
                TransactionEntity(id = 99L, ledgerId = autoId, categoryName = "自动", categoryEmoji = "🤖",
                    direction = Direction.EXPENSE, amountCents = 1, occurredAt = 0L,
                    updatedAt = 9_001, updatedByDeviceId = 9L)
            )
        )
        val wm = SyncWatermarks("")
        val stats = engine.applyIncoming(rows, wm)
        assertEquals(2, stats.skipped)
        assertEquals(0, stats.inserted)
        // 本地只有 ensureAutoLedger 建的 auto 账本；远端 auto 账本/流水都没进来
        assertEquals(1, repo.allLedgersOnce().size)
        assertTrue(repo.allTransactionsOnce().isEmpty())
    }

    @Test
    fun applyIncomingRespectsFkOrder() = runBlocking {
        // 流水先于账本出现（乱序传输）：引擎按依赖表序应用，流水仍能落库
        val rows = listOf(
            EntityCodec.txnToRow(
                TransactionEntity(id = 5L, ledgerId = 3L, categoryName = "餐饮", categoryEmoji = "🍜",
                    direction = Direction.EXPENSE, amountCents = 100, occurredAt = 0L,
                    updatedAt = 1_000, updatedByDeviceId = 1L)
            ),
            EntityCodec.ledgerToRow(
                LedgerEntity(id = 3L, name = "远端账本", sceneKey = "daily", emoji = "📒",
                    colorHex = "#1D3B57", updatedAt = 500, updatedByDeviceId = 1L)
            )
        )
        val stats = engine.applyIncoming(rows, SyncWatermarks(""))
        assertEquals(2, stats.inserted)
        assertEquals(100L, repo.allTransactionsOnce().single().amountCents)
        assertEquals("远端账本", repo.getLedger(3L)!!.name)
    }

    // ---------------- 统一 id 空间 ----------------

    @Test
    fun allocatorDisabledReturnsZeroForRoomAutoincrement() = runBlocking {
        assertEquals(0L, allocator.nextId(TableNames.TRANSACTIONS) { 0L })
    }

    @Test
    fun allocatorDevicePrefixAndIncrementPerTable() = runBlocking {
        allocator.enabled = true
        SyncDeviceHolder.deviceId = 7L
        val base = 7L shl 40
        assertEquals(base or 1L, allocator.nextId(TableNames.TRANSACTIONS) { 0L })
        assertEquals(base or 2L, allocator.nextId(TableNames.TRANSACTIONS) { 0L })
        assertEquals(base or 1L, allocator.nextId(TableNames.LEDGERS) { 0L })   // 每表独立计数
    }

    @Test
    fun allocatorStartsAboveExistingMaxId() = runBlocking {
        val lid = repo.createLedger("日常", "daily", "📒", "#1D3B57")   // 未启用 → 小 id
        assertTrue(lid < (1L shl 40))
        allocator.enabled = true
        SyncDeviceHolder.deviceId = 7L
        val first = allocator.nextId(TableNames.LEDGERS) { repo.getLedger(lid)!!.id }
        // 从 maxId 低位 + 1 起步，但落在设备前缀空间
        assertEquals((7L shl 40) or (lid + 1), first)
    }

    @Test
    fun enabledWithoutDeviceThrows() = runBlocking {
        allocator.enabled = true
        SyncDeviceHolder.deviceId = 0L
        try {
            allocator.nextId(TableNames.LEDGERS) { 0L }
            fail("未注册设备不应能分配同步 id")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun repoCreatesUseDevicePrefixWhenSyncOn() = runBlocking {
        allocator.enabled = true
        SyncDeviceHolder.deviceId = 7L
        val base = 7L shl 40
        val lid = repo.createLedger("日常", "daily", "📒", "#1D3B57")
        assertEquals(base or 1L, lid)
        val t1 = newTxn(lid, 100)
        val t2 = newTxn(lid, 200)
        assertEquals(base or 1L, t1)
        assertEquals(base or 2L, t2)
    }

    // ---------------- 双端往返 ----------------

    @Test
    fun twoDeviceRoundTrip() = runBlocking {
        // 设备 1（手机）：建账本 + 2 笔
        allocator.enabled = true
        SyncDeviceHolder.deviceId = 1L
        val lid = repo.createLedger("日常", "daily", "📒", "#1D3B57")
        val t1 = newTxn(lid, 1000, note = "早")
        newTxn(lid, 500, note = "地铁")
        val wm1 = SyncWatermarks("")
        val outgoing1 = engine.collectOutgoing(wm1)

        // 设备 2（桌面，空库）：首同步应用
        val db2File = File.createTempFile("foldledger-engine2", ".db")
        val db2 = Room.databaseBuilder(
            name = db2File.absolutePath,
            factory = { AppDatabase_Impl() }
        ).setDriver(BundledSQLiteDriver()).build()
        try {
            val stamps2 = TestStamps(device = 2L)
            val alloc2 = SyncIdAllocator().apply { enabled = true }
            SyncDeviceHolder.deviceId = 2L
            val repo2 = LedgerRepository(db2, stamps2, alloc2)
            val engine2 = SyncEngine(db2)
            val wm2 = SyncWatermarks("")
            val stats2 = engine2.applyIncoming(outgoing1, wm2)
            // 全量：账本 + 预置分类 + 2 笔流水，一笔不落
            assertEquals(outgoing1.size, stats2.inserted)
            assertEquals("早", repo2.getTransaction(t1)!!.note)

            // 桌面改一笔 → 增量回传 → 手机合并
            val onDesktop = repo2.getTransaction(t1)!!
            stamps2.advance(1_000)
            repo2.updateTransaction(onDesktop.copy(note = "桌面改过"))
            val outgoing2 = engine2.collectOutgoing(wm2)
            val wm1b = SyncWatermarks("")
            val stats1 = engine.applyIncoming(outgoing2, wm1b)
            assertEquals(1, stats1.updated)
            assertEquals("桌面改过", repo.getTransaction(t1)!!.note)
            assertEquals(2L, repo.getTransaction(t1)!!.updatedByDeviceId)
        } finally {
            db2.close()
            db2File.delete()
        }
    }
}
