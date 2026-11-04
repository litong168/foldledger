package com.foldledger.data

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.foldledger.data.backup.BackupCipher
import com.foldledger.data.backup.BackupFormat
import com.foldledger.data.db.AppDatabase
import com.foldledger.data.db.AppDatabase_Impl
import com.foldledger.data.db.Direction
import com.foldledger.data.export.ExcelExporter
import com.foldledger.data.repo.LedgerRepository
import com.foldledger.data.sync.SyncDeviceHolder
import com.foldledger.data.sync.SyncIdAllocator
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 桌面端「导出备份」链路测试（v16.2.5）。
 *
 * 起因：用户反馈桌面端导出备份不可用。桌面导出 = repo.snapshot() → BackupFormat.encode /
 * ExcelExporter → 落盘，全程不经过 SAF。这里用真库把这条链跑一遍，
 * 把「核心导出逻辑」与「UI 交互（JFileChooser/对话框）」两类原因区分开。
 */
class BackupExportTest {

    private lateinit var dbFile: File
    private lateinit var db: AppDatabase
    private lateinit var repo: LedgerRepository

    @Before
    fun setUp() {
        val stamps = TestStamps()
        dbFile = File.createTempFile("foldledger-backup", ".db")
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
    fun `导出明文备份同时产出 json 与 xlsx`() = runBlocking<Unit> {
        seed()
        val snapshot = repo.snapshot()
        assertTrue("快照应至少有 1 个账本", snapshot.ledgerCount >= 1)
        assertTrue("快照应至少有 1 笔流水", snapshot.txnCount >= 1)

        val json = BackupFormat.encode(snapshot)
        assertTrue("备份 JSON 不该为空", json.isNotEmpty())
        // VERSION 恒 1，且文件头能被识别
        val header = BackupFormat.peekHeader(json)
        assertTrue("JSON 头部应可解析", header != null)

        val excel = ExcelExporter(repo).build(snapshot.ledgers, snapshot.transactions, snapshot.projects)
        assertTrue("Excel 应产出字节", excel.bytes.isNotEmpty())

        val dir = File(System.getProperty("java.io.tmpdir"), "foldledger-export-test").apply {
            deleteRecursively(); mkdirs()
        }
        File(dir, "t.foldledger.json").writeBytes(json)
        File(dir, "t.xlsx").writeBytes(excel.bytes)
        assertTrue("json 落盘", File(dir, "t.foldledger.json").length() > 0)
        assertTrue("xlsx 落盘", File(dir, "t.xlsx").length() > 0)
        println("[backup] json=${json.size}B xlsx=${excel.bytes.size}B ledgers=${snapshot.ledgerCount} txns=${snapshot.txnCount}")
        dir.deleteRecursively()
    }

    @Test
    fun `导出加密备份可被解密回原文`() = runBlocking<Unit> {
        seed()
        val snapshot = repo.snapshot()
        val plain = BackupFormat.encode(snapshot)
        val envelope = BackupCipher.encrypt(
            plain, "pw1234",
            BackupCipher.EncHeader(
                appVersion = "16.2.5",
                exportedAt = snapshot.exportedAt,
                ledgerCount = snapshot.ledgerCount,
                txnCount = snapshot.txnCount
            )
        )
        assertTrue("应是加密信封", BackupCipher.isEncrypted(envelope))
        assertTrue("明文不应被误判为密文", !BackupCipher.isEncrypted(plain))
        val back = BackupCipher.decrypt(envelope, "pw1234")
        assertTrue("解密后应与原文一致", back.contentEquals(plain))
        println("[backup] 加密信封 ${envelope.size}B，解密校验通过")
    }
}
