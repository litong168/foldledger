package com.foldledger.data

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.foldledger.data.db.AppDatabase
import com.foldledger.data.db.AppDatabase_Impl
import com.foldledger.data.db.Direction
import com.foldledger.data.db.ReimburseState
import com.foldledger.data.db.RecurringEntity
import com.foldledger.data.db.TransactionEntity
import com.foldledger.data.repo.LedgerRepository
import com.foldledger.data.sync.SyncStampProvider
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * M4.2 打戳 / 软删 / 查询过滤 —— 真库（Room + BundledSQLiteDriver on JVM）行为验证。
 *
 * 这些行为没法用纯逻辑单测覆盖：打戳发生在仓库写路径，过滤发生在 DAO 的 SQL 里。
 * 任何一条写路径漏打戳、任何一个 SELECT 漏 `deletedAt = 0`，都会让双端增量同步丢数据。
 */
class SyncStampDbTest {

    private lateinit var dbFile: File
    private lateinit var db: AppDatabase
    private lateinit var repo: LedgerRepository
    private lateinit var stamps: FakeStamps

    class FakeStamps : SyncStampProvider {
        var now: Long = 1_700_000_000_000L
        var device: Long = 7L
        override fun nowMillis(): Long = now
        override fun deviceId(): Long = device
        fun advance(ms: Long) {
            now += ms
        }
    }

    @Before
    fun setUp() {
        stamps = FakeStamps()
        dbFile = File.createTempFile("foldledger-sync-test", ".db")
        db = Room.databaseBuilder(
            name = dbFile.absolutePath,
            factory = { AppDatabase_Impl() }
        )
            .setDriver(BundledSQLiteDriver())
            .build()
        repo = LedgerRepository(db, stamps)
    }

    @After
    fun tearDown() {
        db.close()
        dbFile.delete()
    }

    private suspend fun newLedger(name: String = "测试账本"): Long =
        repo.createLedger(name, "daily", "📒", "#1D3B57")

    private suspend fun newTxn(
        ledgerId: Long,
        amountCents: Long,
        note: String = "",
        projectId: Long = 0L
    ): Long = repo.addTransaction(
        ledgerId = ledgerId,
        categoryId = null,
        categoryName = "餐饮",
        categoryEmoji = "🍜",
        direction = Direction.EXPENSE,
        amountCents = amountCents,
        note = note,
        occurredAt = 0L,
        projectId = projectId
    )

    // ---------------- 打戳 ----------------

    @Test
    fun insertStampsUpdatedAtAndDevice() = runBlocking {
        val lid = newLedger()
        val txnId = newTxn(lid, 2000)
        val txn = repo.getTransaction(txnId)!!
        assertEquals(stamps.now, txn.updatedAt)
        assertEquals(7L, txn.updatedByDeviceId)

        val ledger = repo.getLedger(lid)!!
        assertEquals(stamps.now, ledger.updatedAt)
        assertEquals(7L, ledger.updatedByDeviceId)

        val cat = repo.listAllCategories(lid).first()
        assertEquals(stamps.now, cat.updatedAt)
    }

    @Test
    fun updateRefreshesStamp() = runBlocking {
        val lid = newLedger()
        val txnId = newTxn(lid, 2000)
        stamps.advance(1_000)
        val txn = repo.getTransaction(txnId)!!
        repo.updateTransaction(txn.copy(note = "改过的备注"))
        val after = repo.getTransaction(txnId)!!
        assertEquals(stamps.now, after.updatedAt)
        assertEquals("改过的备注", after.note)
    }

    @Test
    fun goalDepositStamps() = runBlocking {
        val gid = repo.createGoal(null, "换手机", "📱", 500_000)
        stamps.advance(1_000)
        val goal = repo.allGoals().first().first { it.id == gid }
        repo.depositGoal(goal, 10_000)
        val after = repo.allGoals().first().first { it.id == gid }
        assertEquals(10_000L, after.savedCents)
        assertEquals(stamps.now, after.updatedAt)
        assertEquals(7L, after.updatedByDeviceId)
    }

    @Test
    fun batchImportStampsEachRow() = runBlocking {
        val lid = newLedger()
        repo.addTransactionsBatch(
            listOf(
                TransactionEntity(
                    ledgerId = lid, categoryName = "餐饮", categoryEmoji = "🍜",
                    direction = Direction.EXPENSE, amountCents = 1_000, occurredAt = 1_000L
                ),
                TransactionEntity(
                    ledgerId = lid, categoryName = "交通", categoryEmoji = "🚌",
                    direction = Direction.EXPENSE, amountCents = 500, occurredAt = 2_000L
                )
            )
        )
        repo.allTransactionsOnce().forEach { assertEquals(stamps.now, it.updatedAt) }
    }

    @Test
    fun bulkEditStamps() = runBlocking {
        val lid = newLedger()
        val a = newTxn(lid, 1_000)
        val b = newTxn(lid, 2_000)
        stamps.advance(1_000)
        repo.setReimburseState(listOf(a, b), ReimburseState.PENDING)
        val after = repo.allTransactionsOnce().associateBy { it.id }
        assertEquals(ReimburseState.PENDING, after[a]!!.reimburseState)
        assertEquals(stamps.now, after[a]!!.updatedAt)
        assertEquals(stamps.now, after[b]!!.updatedAt)
    }

    @Test
    fun recurringPostStampsTxnAndItem() = runBlocking {
        val lid = newLedger()
        val rid = repo.createRecurring(
            RecurringEntity(ledgerId = lid, name = "房租", amountCents = 10_000, nextDueAt = 1_000_000L)
        )
        stamps.advance(1_000)
        val n = repo.postRecurring(repo.recurring(lid).first().first { it.id == rid }, 2_000_000L)
        assertEquals(1, n)
        val txn = repo.allTransactionsOnce().single()
        assertEquals(stamps.now, txn.updatedAt)
        val item = repo.recurring(lid).first().first { it.id == rid }
        assertEquals(stamps.now, item.updatedAt)
        assertTrue(item.nextDueAt > 1_000_000L)
    }

    @Test
    fun restoreFromBackupStampsRows() = runBlocking {
        val lid = newLedger()
        newTxn(lid, 2_000)
        val snap = repo.snapshot()
        stamps.advance(1_000)
        repo.wipeEverything()
        assertTrue(repo.isDatabaseEmpty())
        repo.restoreFrom(snap)
        assertEquals(stamps.now, repo.allLedgersOnce().single().updatedAt)
        assertEquals(stamps.now, repo.allTransactionsOnce().single().updatedAt)
        assertEquals(7L, repo.allTransactionsOnce().single().updatedByDeviceId)
    }

    // ---------------- 软删 + 查询过滤 ----------------

    @Test
    fun softDeletedTxnInvisibleEverywhere() = runBlocking {
        val lid = newLedger()
        val gone = newTxn(lid, 2_000, note = "这笔记删除")
        newTxn(lid, 3_000, note = "保留")
        stamps.advance(1_000)
        repo.deleteTransaction(gone)

        assertNull(repo.getTransaction(gone))
        assertTrue(repo.transactions(lid).first().none { it.id == gone })
        assertTrue(repo.recent(100).first().none { it.id == gone })
        assertTrue(repo.allTransactionsFlow.first().none { it.id == gone })
        assertEquals(1, repo.allTransactionsOnce().size)

        assertEquals(3_000L, repo.total(lid, Direction.EXPENSE).first())
        assertEquals(1, repo.txnCount(lid).first())
        assertEquals(3_000L, repo.balances().first()
            .single { it.ledgerId == lid && it.direction == Direction.EXPENSE }.totalCents)
        assertEquals(3_000L, repo.categoryTotals(lid, Direction.EXPENSE, 0L, Long.MAX_VALUE)
            .first().single().totalCents)
        assertEquals(1, repo.recent(100).first().size)
    }

    @Test
    fun softDeletedLedgerHiddenThenRestored() = runBlocking {
        val lid = newLedger()
        newTxn(lid, 2_000)
        stamps.advance(1_000)
        repo.softDeleteLedger(lid)

        assertTrue(repo.ledgers.first().isEmpty())
        assertEquals(1, repo.trashedLedgers().first().size)

        stamps.advance(1_000)
        repo.restoreLedger(lid)
        assertTrue(repo.trashedLedgers().first().isEmpty())
        assertEquals(1, repo.ledgers.first().size)
        // 流水原样保留（软删账本不清数据）
        assertEquals(1, repo.transactions(lid).first().size)
        assertEquals(stamps.now, repo.getLedger(lid)!!.updatedAt)
    }

    @Test
    fun categoryResurrectOnReadd() = runBlocking {
        val lid = newLedger()
        val before = repo.listCategories(lid, Direction.EXPENSE).size
        val cid = repo.addCategory(lid, "自定义餐饮", "🍜", Direction.EXPENSE)
        stamps.advance(1_000)
        repo.deleteCategory(cid)
        // 删掉的是新增那条，预置分类不动
        assertTrue(repo.listCategories(lid, Direction.EXPENSE).none { it.id == cid })
        assertEquals(before, repo.listCategories(lid, Direction.EXPENSE).size)

        // 同名重加：复活原行（不撞 (ledgerId, direction, name) 唯一索引）
        val cid2 = repo.addCategory(lid, "自定义餐饮", "🍜", Direction.EXPENSE)
        assertEquals(cid, cid2)
        assertEquals(before + 1, repo.listCategories(lid, Direction.EXPENSE).size)
    }

    @Test
    fun deleteProjectSoftAndClearsTxns() = runBlocking {
        val lid = newLedger()
        val pid = repo.createProject(lid, "差旅")
        val txnId = newTxn(lid, 800, projectId = pid)
        stamps.advance(1_000)
        repo.deleteProject(pid)

        assertTrue(repo.projects(lid).first().isEmpty())
        val txn = repo.getTransaction(txnId)!!
        assertEquals(0L, txn.projectId)
        assertEquals(stamps.now, txn.updatedAt)
    }

    @Test
    fun deleteAccountSoftAndClearsTxns() = runBlocking {
        val lid = newLedger()
        val acc = com.foldledger.data.db.AccountEntity(ledgerId = lid, name = "现金", initialBalanceCents = 10_000)
        val accId = repo.createAccount(acc)
        val txnId = repo.addTransaction(
            ledgerId = lid, categoryId = null, categoryName = "餐饮", categoryEmoji = "🍜",
            direction = Direction.EXPENSE, amountCents = 800, note = "", occurredAt = 0L, accountId = accId
        )
        stamps.advance(1_000)
        repo.deleteAccount(accId)

        assertTrue(repo.accounts(lid).first().isEmpty())
        val txn = repo.getTransaction(txnId)!!
        assertEquals(0L, txn.accountId)
        assertEquals(stamps.now, txn.updatedAt)
    }

    @Test
    fun wipeAllSoftDeletesTxnsOnly() = runBlocking {
        val lid = newLedger()
        newTxn(lid, 1_000)
        newTxn(lid, 2_000)
        stamps.advance(1_000)
        repo.wipeAll()

        assertTrue(repo.allTransactionsOnce().isEmpty())
        assertEquals(0, repo.txnCount(lid).first())
        assertEquals(1, repo.ledgers.first().size)
    }

    @Test
    fun restoredPresetCategoriesSkipDeleted() = runBlocking {
        val lid = newLedger()
        val daily = repo.listAllCategories(lid).first { it.direction == Direction.EXPENSE }
        stamps.advance(1_000)
        repo.deleteCategory(daily.id)
        stamps.advance(1_000)
        val added = repo.restorePresetCategories(lid)
        // 删过的预置分类不复活，也不补一条新的（尊重删除意图）
        assertTrue(repo.listCategories(lid, Direction.EXPENSE).none { it.name == daily.name })
        assertEquals(0, added)
    }
}
