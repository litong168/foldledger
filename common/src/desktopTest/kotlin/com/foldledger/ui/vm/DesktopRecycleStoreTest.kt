package com.foldledger.ui.vm

import com.foldledger.data.backup.BackupFormat
import com.foldledger.data.backup.RecycledBackup
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 桌面备份回收站的文件链路（v16.3）。
 *
 * 以前桌面删备份是**硬删**，用户后悔就没了。现在与 Android 同构：
 * 先留副本 → 记清单 → 删原件 → 可还原 / 可彻底铲除。
 * 这里用临时 `user.home` 跑真实文件 IO，不碰 DataStore、不碰 Room、不碰用户真实数据。
 */
class DesktopRecycleStoreTest {

    private lateinit var originalHome: String
    private lateinit var sandbox: File
    private lateinit var backupDir: File

    @Before
    fun setUp() {
        originalHome = System.getProperty("user.home")
        sandbox = File.createTempFile("foldledger-recycle-test", "").let { f ->
            f.delete()
            File(f.absolutePath).apply { mkdirs() }
        }
        System.setProperty("user.home", sandbox.absolutePath)
        backupDir = File(sandbox, "backups").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        System.setProperty("user.home", originalHome)
        sandbox.deleteRecursively()
    }

    private fun writeBackup(name: String, body: String = "{\"v\":1}", withXlsx: Boolean = true) {
        File(backupDir, name).writeText(body)
        if (withXlsx) File(backupDir, DesktopRecycleStore.xlsxNameOf(name)).writeText("XLSX")
    }

    @Test
    fun `明文备份与配对 xlsx 一起进副本，还原后内容一致`() {
        val name = "滴水记账-备份-20261104.foldledger.json"
        writeBackup(name)

        val item = DesktopRecycleStore.stash(backupDir, name)
        assertTrue(item.hasXlsx)
        assertTrue(item.xlsxStoredName != null)
        assertEquals(name, item.fileName)
        assertTrue(DesktopRecycleStore.file(item.storedName).isFile)
        assertTrue(DesktopRecycleStore.file(item.xlsxStoredName!!).isFile)

        // 模拟「删原件」
        File(backupDir, name).delete()
        File(backupDir, DesktopRecycleStore.xlsxNameOf(name)).delete()
        assertFalse(File(backupDir, name).exists())

        assertTrue(DesktopRecycleStore.restore(backupDir, item))
        assertEquals("{\"v\":1}", File(backupDir, name).readText())
        assertEquals("XLSX", File(backupDir, DesktopRecycleStore.xlsxNameOf(name)).readText())
        // 还原成功后副本自身要清掉，别占着私有目录
        assertFalse(DesktopRecycleStore.file(item.storedName).exists())
        assertFalse(DesktopRecycleStore.file(item.xlsxStoredName!!).exists())
    }

    @Test
    fun `加密备份也能配出正确的 xlsx 名（原来只剥 json 后缀会拼错）`() {
        val name = "滴水记账-加密备份-20261104${BackupFormat.EXT_CRYPT}"
        assertEquals(
            "滴水记账-加密备份-20261104${BackupFormat.EXT_XLSX}",
            DesktopRecycleStore.xlsxNameOf(name)
        )
        writeBackup(name, body = "FLDC1-encrypted", withXlsx = false)
        val item = DesktopRecycleStore.stash(backupDir, name)
        assertFalse(item.hasXlsx)
        assertTrue(DesktopRecycleStore.restore(backupDir, item))
        assertEquals("FLDC1-encrypted", File(backupDir, name).readText())
    }

    @Test
    fun `老清单没有 xlsxStoredName 时仍能还原与铲除`() {
        val name = "滴水记账-自动备份-20261104${BackupFormat.EXT_JSON}"
        writeBackup(name)
        val fresh = DesktopRecycleStore.stash(backupDir, name)
        // 老版本清单的条目：字段名都不写，只保留 hasXlsx
        val legacy = RecycledBackup(
            fileName = fresh.fileName,
            deletedAt = fresh.deletedAt,
            storedName = fresh.storedName,
            hasXlsx = true,
            sizeBytes = fresh.sizeBytes,
            xlsxStoredName = null
        )
        File(backupDir, name).delete()
        File(backupDir, DesktopRecycleStore.xlsxNameOf(name)).delete()

        assertTrue(DesktopRecycleStore.restore(backupDir, legacy))
        assertEquals("XLSX", File(backupDir, DesktopRecycleStore.xlsxNameOf(name)).readText())
    }

    @Test
    fun `彻底铲除只删自己那两个副本，不碰别人的`() {
        val a = "滴水记账-备份-A${BackupFormat.EXT_JSON}"
        val b = "滴水记账-备份-B${BackupFormat.EXT_JSON}"
        writeBackup(a)
        writeBackup(b, withXlsx = false)
        val itemA = DesktopRecycleStore.stash(backupDir, a)
        val itemB = DesktopRecycleStore.stash(backupDir, b)

        DesktopRecycleStore.purge(itemA)
        assertFalse(DesktopRecycleStore.file(itemA.storedName).exists())
        assertFalse(DesktopRecycleStore.file(itemA.xlsxStoredName!!).exists())
        // 另一条的副本必须原封不动
        assertTrue(DesktopRecycleStore.file(itemB.storedName).exists())
    }
}
