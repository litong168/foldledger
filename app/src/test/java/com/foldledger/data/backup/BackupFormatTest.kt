package com.foldledger.data.backup

import com.foldledger.data.db.AccountEntity
import com.foldledger.data.db.CategoryEntity
import com.foldledger.data.db.LedgerEntity
import com.foldledger.data.db.ProjectEntity
import com.foldledger.data.db.TransactionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 备份格式的编解码测试。
 *
 * 备份是「卸载重装后能恢复」的唯一依据，格式错了就等于数据丢了，
 * 所以这里的往返测试要覆盖中文、Emoji、引号、换行、null、空集合等边界。
 */
class BackupFormatTest {

    private fun sample() = BackupSnapshot(
        exportedAt = 1_760_000_000_000L,
        appVersion = "1.2.0",
        ledgers = listOf(
            LedgerEntity(
                id = 1L, name = "家装账本", sceneKey = "renovation", emoji = "🛠️",
                colorHex = "#E8590C", budgetCents = 20_000_000L,
                note = """备注含 <&>"' 特殊字符""", archived = false,
                sortOrder = 0, createdAt = 111L
            ),
            LedgerEntity(
                id = 2L, name = "报销\t账本", sceneKey = "reimbursement", emoji = "🧾",
                colorHex = "#0B7285", budgetCents = 0L, note = "",
                archived = true, sortOrder = 1, createdAt = 222L
            )
        ),
        categories = listOf(
            CategoryEntity(id = 10L, ledgerId = 1L, name = "主材", emoji = "🧱", direction = 0, sortOrder = 0, builtIn = true),
            CategoryEntity(id = 11L, ledgerId = 1L, name = "商家退款", emoji = "↩️", direction = 1, sortOrder = 0, builtIn = true)
        ),
        transactions = listOf(
            TransactionEntity(
                id = 100L, ledgerId = 1L, categoryId = 10L,
                categoryName = "主材", categoryEmoji = "🧱", direction = 0,
                amountCents = 865_050L, note = """买瓷砖 "800x800" 款""",
                occurredAt = 999L, extraJson = """{"space":"living","vendor":"XX装饰"}""",
                reimburseState = -1, createdAt = 1000L
            ),
            // categoryId 为 null 的情况（分类被删过）
            TransactionEntity(
                id = 101L, ledgerId = 2L, categoryId = null,
                categoryName = "已删除分类", categoryEmoji = "📦", direction = 1,
                amountCents = 12_000L, note = "", occurredAt = 1001L,
                extraJson = "", reimburseState = 2, createdAt = 1002L
            )
        )
    )

    @Test
    fun roundTripKeepsEverything() {
        val original = sample()
        val restored = BackupFormat.decode(BackupFormat.encode(original))

        assertEquals(original.exportedAt, restored.exportedAt)
        assertEquals(original.appVersion, restored.appVersion)
        assertEquals(original.ledgerCount, restored.ledgerCount)
        assertEquals(original.txnCount, restored.txnCount)

        assertEquals(original.ledgers, restored.ledgers)
        assertEquals(original.categories, restored.categories)
        assertEquals(original.transactions, restored.transactions)
    }

    @Test
    fun specialCharactersSurvive() {
        val restored = BackupFormat.decode(BackupFormat.encode(sample()))
        val ledger = restored.ledgers.first { it.id == 1L }
        assertEquals("""备注含 <&>"' 特殊字符""", ledger.note)
        assertEquals("🛠️", ledger.emoji)
        assertEquals("家装账本", ledger.name)

        // 制表符
        assertEquals("报销\t账本", restored.ledgers.first { it.id == 2L }.name)

        val txn = restored.transactions.first { it.id == 100L }
        assertEquals("""买瓷砖 "800x800" 款""", txn.note)
        assertEquals("""{"space":"living","vendor":"XX装饰"}""", txn.extraJson)
        assertEquals("🧱", txn.categoryEmoji)
    }

    @Test
    fun nullCategoryIdStaysNull() {
        val restored = BackupFormat.decode(BackupFormat.encode(sample()))
        assertNull(restored.transactions.first { it.id == 101L }.categoryId)
        assertEquals(2, restored.transactions.first { it.id == 101L }.reimburseState)
    }

    @Test
    fun emptyDatabaseRoundTrip() {
        val empty = BackupSnapshot(1L, "1.2.0", emptyList(), emptyList(), emptyList())
        val restored = BackupFormat.decode(BackupFormat.encode(empty))
        assertEquals(0, restored.ledgerCount)
        assertEquals(0, restored.txnCount)
        assertTrue(restored.categories.isEmpty())
        assertTrue(restored.projects.isEmpty())
    }

    // ---------------- v5.1 项目 / 空间 ----------------

    @Test
    fun projectsRoundTripAndTxnLink() {
        val withProjects = sample().copy(
            projects = listOf(
                ProjectEntity(
                    id = 300L, ledgerId = 2L, name = "6 月北京出差", emoji = "✈️",
                    colorHex = "#3B82F6", budgetCents = 500_000L,
                    note = "同行：老王", closed = false, createdAt = 3000L
                ),
                ProjectEntity(
                    id = 301L, ledgerId = 2L, name = "客户 A 招待", emoji = "🍽️",
                    closed = true, createdAt = 3001L
                )
            ),
            transactions = sample().transactions.map {
                if (it.id == 101L) it.copy(projectId = 300L) else it
            }
        )
        val restored = BackupFormat.decode(BackupFormat.encode(withProjects))

        assertEquals(2, restored.projects.size)
        val trip = restored.projects.first { it.id == 300L }
        assertEquals("6 月北京出差", trip.name)
        assertEquals("✈️", trip.emoji)
        assertEquals(500_000L, trip.budgetCents)
        assertEquals("同行：老王", trip.note)
        assertFalse(trip.closed)
        assertTrue(restored.projects.first { it.id == 301L }.closed)

        // 流水的 projectId 也要跟着回来
        assertEquals(300L, restored.transactions.first { it.id == 101L }.projectId)
    }

    // ---------------- v6.1 资产账户 ----------------

    @Test
    fun accountsRoundTripAndTxnLink() {
        val withAccounts = sample().copy(
            accounts = listOf(
                AccountEntity(
                    id = 500L, ledgerId = 2L, name = "招商银行", type = 1,
                    emoji = "💳", colorHex = "#3B82F6", initialBalanceCents = 1_000_000L,
                    includeInNetWorth = true, note = "工资卡", closed = false, createdAt = 5000L
                ),
                AccountEntity(
                    id = 501L, ledgerId = 2L, name = "支付宝", type = 3,
                    emoji = "📱", initialBalanceCents = -2_000L,
                    includeInNetWorth = false, closed = true, createdAt = 5001L
                )
            ),
            transactions = sample().transactions.map {
                if (it.id == 101L) it.copy(accountId = 500L) else it
            }
        )
        val restored = BackupFormat.decode(BackupFormat.encode(withAccounts))

        assertEquals(2, restored.accounts.size)
        val trip = restored.accounts.first { it.id == 500L }
        assertEquals("招商银行", trip.name)
        assertEquals("工资卡", trip.note)
        assertEquals(1_000_000L, trip.initialBalanceCents)
        assertTrue(trip.includeInNetWorth)
        assertFalse(restored.accounts.first { it.id == 501L }.includeInNetWorth)
        assertTrue(restored.accounts.first { it.id == 501L }.closed)

        // 流水的 accountId 也要跟着回来
        assertEquals(500L, restored.transactions.first { it.id == 101L }.accountId)
    }

    @Test
    fun legacyBackupWithoutAccountsStillDecodes() {
        // 模拟 v6.0 及更早导出的备份：没有 accounts 数组、流水没有 accountId 字段
        val old = sample().copy(accounts = emptyList())
        val restored = BackupFormat.decode(BackupFormat.encode(old))
        assertTrue(restored.accounts.isEmpty())
        // 老流水一律退回「未指定账户」，绝不丢
        assertTrue(restored.transactions.all { it.accountId == 0L })
    }

    @Test
    fun legacyBackupWithoutProjectsStillDecodes() {
        // 模拟 v5.0 及更早导出的备份：没有 projects 数组、流水没有 projectId 字段
        val legacy = """
            {"format":"${BackupFormat.FORMAT_TAG}","version":1,"appVersion":"5.0.0",
             "exportedAt":1,"ledgerCount":1,"txnCount":1,
             "ledgers":[{"id":1,"name":"报销","sceneKey":"reimbursement","emoji":"🧾",
                         "colorHex":"#0B7285","budgetCents":0,"note":"","archived":false,
                         "sortOrder":0,"createdAt":1}],
             "categories":[],
             "transactions":[{"id":10,"ledgerId":1,"categoryId":null,"categoryName":"餐饮",
                              "categoryEmoji":"🍜","direction":0,"amountCents":3500,
                              "note":"","occurredAt":1,"extraJson":"","reimburseState":0,
                              "createdAt":1}]}
        """.trimIndent().toByteArray()

        val restored = BackupFormat.decode(legacy)
        assertEquals(1, restored.ledgerCount)
        // 老备份没有项目 → 空列表；流水归为「未归类」
        assertTrue(restored.projects.isEmpty())
        assertEquals(0L, restored.transactions.single().projectId)
    }

    @Test
    fun peekHeaderReadsSummaryFromPrefix() {
        val bytes = BackupFormat.encode(sample())
        val header = BackupFormat.peekHeader(bytes)
        assertEquals(1_760_000_000_000L, header?.exportedAt)
        assertEquals(2, header?.ledgerCount)
        assertEquals(2, header?.txnCount)
        assertEquals("1.2.0", header?.appVersion)
    }

    @Test
    fun peekHeaderRejectsForeignFile() {
        assertNull(BackupFormat.peekHeader("hello world".toByteArray()))
        assertNull(BackupFormat.peekHeader(ByteArray(0)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun decodeRejectsForeignFile() {
        BackupFormat.decode("""{"foo":1}""".toByteArray())
    }

    @Test(expected = IllegalArgumentException::class)
    fun decodeRejectsFutureVersion() {
        BackupFormat.decode(
            """{"format":"${BackupFormat.FORMAT_TAG}","version":99,"ledgers":[]}""".toByteArray()
        )
    }

    @Test
    fun recognisesOwnBackupNames() {
        // 当前品牌名
        assertTrue(BackupFormat.isBackupFileName("滴水记账-自动备份${BackupFormat.EXT_JSON}"))
        assertTrue(BackupFormat.isBackupFileName("滴水记账-备份-20261013-154000${BackupFormat.EXT_JSON}"))
        // 改名前的历史备份也必须认得出来：老用户装了新版，不能找不到自己的备份
        assertTrue(BackupFormat.isBackupFileName("折叠记账-自动备份${BackupFormat.EXT_JSON}"))
        assertTrue(BackupFormat.isBackupFileName("折叠记账-备份-20260101-090000${BackupFormat.EXT_JSON}"))
        // 目录里别人的东西一律不认，避免误删
        assertFalse(BackupFormat.isBackupFileName("工作报告.xlsx"))
        assertFalse(BackupFormat.isBackupFileName("滴水记账-备份-20261013-154000.xlsx"))
        assertFalse(BackupFormat.isBackupFileName("报告.foldledger.json"))
        assertFalse(BackupFormat.isBackupFileName(""))
    }

    @Test
    fun dumpSampleForExternalValidation() {
        val dir = java.io.File("build/test-export")
        dir.mkdirs()
        val f = java.io.File(dir, "sample-backup${BackupFormat.EXT_JSON}")
        val bytes = BackupFormat.encode(sample())
        f.writeBytes(bytes)

        // 摘要字段必须落在文件开头 400 字节内，peekHeader 才成立
        val head = String(bytes, 0, minOf(bytes.size, 400), Charsets.UTF_8)
        assertTrue("exportedAt 不在文件开头", head.contains("\"exportedAt\""))
        assertTrue("ledgerCount 不在文件开头", head.contains("\"ledgerCount\""))
        assertTrue("txnCount 不在文件开头", head.contains("\"txnCount\""))
        assertTrue(f.length() > 200)
        println("[BackupFormatTest] sample written: ${f.absolutePath} (${f.length()} bytes)")
    }
}
