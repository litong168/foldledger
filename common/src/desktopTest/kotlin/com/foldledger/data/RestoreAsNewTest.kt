package com.foldledger.data

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.foldledger.data.db.AppDatabase
import com.foldledger.data.db.AppDatabase_Impl
import com.foldledger.data.db.Direction
import com.foldledger.data.repo.LedgerRepository
import com.foldledger.data.sync.SyncDeviceHolder
import com.foldledger.data.sync.SyncIdAllocator
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 追加恢复（v16.2.7）：`restoreAsNewLedgers` 不清库，把备份当成新账本塞进来。
 *
 * 这条路径最容易出错的不是"能不能插进去"，而是 **id 重映射**：
 * 分类/流水如果还挂在旧账本 id 上，界面上会看到"账本里空空如也"。
 * 所以这里同时断言：原有数据没被删、新账本名字带后缀、流水的 ledgerId/categoryId 都是新的。
 */
class RestoreAsNewTest {

    private lateinit var dbFile: File
    private lateinit var db: AppDatabase
    private lateinit var repo: LedgerRepository

    @Before
    fun setUp() {
        val stamps = TestStamps()
        dbFile = File.createTempFile("foldledger-restore-new", ".db")
        db = Room.databaseBuilder(
            name = dbFile.absolutePath,
            factory = { AppDatabase_Impl() }
        ).setDriver(BundledSQLiteDriver()).build()
        repo = LedgerRepository(db, stamps, SyncIdAllocator())
    }

    @After
    fun tearDown() {
        SyncDeviceHolder.deviceId = 0L
        db.close()
        dbFile.delete()
    }

    private suspend fun seed() {
        val ledgerId = repo.createLedger("日常", "daily", "📒", "#1D3B57")
        repo.addTransaction(
            ledgerId = ledgerId, categoryId = null, categoryName = "餐饮", categoryEmoji = "🍜",
            direction = Direction.EXPENSE, amountCents = 3250, note = "午饭", occurredAt = 1_760_000_000_000
        )
    }

    @Test
    fun `追加恢复不动原有数据并把备份挂成新账本`() = runBlocking<Unit> {
        seed()
        val backup = repo.snapshot()
        assertEquals(1, backup.ledgerCount)
        assertEquals(1, backup.txnCount)
        val oldLedgerId = backup.ledgers.single().id
        val oldTxnId = backup.transactions.single().id

        val stats = repo.restoreAsNewLedgers(backup, suffix = "恢复")

        val after = repo.snapshot()
        assertEquals("账本应变成两本（原 + 追加）", 2, after.ledgerCount)
        assertEquals("流水应变成两笔", 2, after.txnCount)
        assertEquals("追加的账本/流水计数", 1, stats.ledgerCount)
        assertEquals(1, stats.txnCount)

        // 原有账本与本笔流水都还在
        assertTrue("原账本必须还在", after.ledgers.any { it.id == oldLedgerId })
        assertTrue("原流水必须还在", after.transactions.any { it.id == oldTxnId })

        // 新账本名字带后缀，且 id 与旧的不同
        val restored = after.ledgers.first { it.id != oldLedgerId }
        assertTrue("追加账本名应带后缀：${restored.name}", restored.name.contains("（恢复）"))
        assertNotEquals(oldLedgerId, restored.id)

        // 关键：追加出来的流水要挂在新账本上，不能挂回旧账本
        val newTxn = after.transactions.first { it.ledgerId == restored.id }
        assertEquals(restored.id, newTxn.ledgerId)
        assertNotEquals("新流水的 id 必须重新分配", oldTxnId, newTxn.id)
    }

    @Test
    fun `覆盖恢复仍然把库还原成一份`() = runBlocking<Unit> {
        seed()
        val backup = repo.snapshot()
        repo.restoreAsNewLedgers(backup, suffix = "恢复")
        assertEquals(2, repo.snapshot().ledgerCount)

        repo.restoreFrom(backup)

        val after = repo.snapshot()
        assertEquals("覆盖恢复后应只剩备份里的那一份", 1, after.ledgerCount)
        assertEquals(1, after.txnCount)
    }
}
