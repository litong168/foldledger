package com.foldledger.ui.vm

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.foldledger.ServiceLocator
import com.foldledger.data.backup.BackupFormat
import com.foldledger.data.backup.RecycledBackup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import com.foldledger.ui.l10n.t

/**
 * 桌面回收站的副本目录：`~/.foldledger/recycle/backup/`。
 *
 * 与备份目录分离是刻意的 —— 备份轮转 `prune()` 只扫备份目录，副本放这里不会被误删。
 * 清单仍走共享的 `RecycledBackupCodec` + DataStore（不是 Room、不是备份 JSON），
 * 所以**不新增数据库列、不改 `BackupFormat.VERSION`、不触发任何迁移**。
 *
 * 文件搬运集中在这里，清单读写留给调用方：这样「先留副本 → 记清单 → 删原件」
 * 的顺序由上层保证，而搬运本身可以脱离 DataStore 单独跑单测。
 */
internal object DesktopRecycleStore {
    fun dir(): File =
        File(System.getProperty("user.home"), ".foldledger/recycle/backup").apply { mkdirs() }

    fun file(name: String): File = File(dir(), name)

    /** 与导出侧命名一致：明文 `.json` 和加密 `.crypt` 都只配一个同名 `.xlsx` */
    fun xlsxNameOf(fileName: String): String =
        fileName.removeSuffix(BackupFormat.EXT_JSON).removeSuffix(BackupFormat.EXT_CRYPT) + BackupFormat.EXT_XLSX

    /**
     * 把备份文件（含配对 xlsx）复制进私有目录，返回清单条目。
     * **不删原件、不写清单** —— 调用方须先 `mutateRecycledBackups { +item }`，最后才删原件。
     */
    fun stash(backupDir: File, fileName: String): RecycledBackup {
        val json = File(backupDir, fileName)
        val jsonBytes = json.readBytes()
        val xlsxName = xlsxNameOf(fileName)
        val xlsx = File(backupDir, xlsxName).takeIf { it.isFile }
        val xlsxBytes = xlsx?.readBytes()

        val stamp = System.currentTimeMillis()
        val storedName = "$stamp-$fileName"
        file(storedName).writeBytes(jsonBytes)
        // v9.7.2 同款：显式记下 xlsx 副本名，别靠时间戳前缀瞎猜
        val xlsxStoredName = if (xlsxBytes != null) {
            "$stamp-$xlsxName".also { file(it).writeBytes(xlsxBytes) }
        } else null

        return RecycledBackup(
            fileName = fileName,
            deletedAt = stamp,
            storedName = storedName,
            hasXlsx = xlsxBytes != null,
            sizeBytes = jsonBytes.size.toLong(),
            xlsxStoredName = xlsxStoredName
        )
    }

    /** 从副本写回备份目录；成功才删掉副本。 */
    fun restore(backupDir: File, item: RecycledBackup): Boolean {
        val src = file(item.storedName)
        if (!src.isFile) return false
        File(backupDir, item.fileName).writeBytes(src.readBytes())
        if (item.hasXlsx) {
            val xlsx = item.xlsxStoredName
                ?.let { file(it) }
                ?.takeIf { it.isFile }
            // 老清单没记 xlsxStoredName：按时间戳前缀精确找（只认这一条自己的）
                ?: dir().listFiles()?.firstOrNull {
                    it.name.startsWith("${item.deletedAt}-") &&
                        it.name.endsWith(BackupFormat.EXT_XLSX)
                }
            if (xlsx != null && xlsx.isFile) {
                File(backupDir, xlsxNameOf(item.fileName)).writeBytes(xlsx.readBytes())
                xlsx.delete()
            }
        }
        src.delete()
        return true
    }

    /** 彻底铲除：只删自己那两个副本，绝不按时间戳批量删。 */
    fun purge(item: RecycledBackup) {
        file(item.storedName).delete()
        item.xlsxStoredName?.let { file(it).delete() }
        // 老清单没有 xlsxStoredName：用「与 json 同名、只换扩展名」精确定位，
        // 同一毫秒存多份时才不会误伤别人的副本。
        if (item.hasXlsx && item.xlsxStoredName == null) {
            file(
                item.storedName.removeSuffix(BackupFormat.EXT_JSON)
                    .removeSuffix(BackupFormat.EXT_CRYPT) + BackupFormat.EXT_XLSX
            ).delete()
        }
    }
}

/**
 * 桌面端回收站（v16.3）：与 Android 的 SAF 版语义一致 ——
 * 删除时先留副本，还原时写回备份目录，彻底铲除时只删自己那两个副本。
 */
private class DesktopBackupRecycler : BackupRecycler {
    private val prefs = ServiceLocator.preferences

    override val backups: Flow<List<RecycledBackup>> = prefs.recycledBackups

    /** 桌面没有 SAF tree uri，借这个字段把「备份目录绝对路径」传给 RecycleBinViewModel。 */
    override val backupTreeUri: Flow<String?> =
        prefs.settings.map { it.desktopBackupFolderPath }

    override suspend fun restoreBackup(item: RecycledBackup, treeUri: String?): Boolean =
        withContext(Dispatchers.IO) {
            val dir = treeUri?.let { File(it) } ?: return@withContext false
            if (!dir.isDirectory || !BackupFormat.isBackupFileName(item.fileName)) return@withContext false
            runCatching {
                if (!DesktopRecycleStore.restore(dir, item)) error(t("回收站副本已不存在"))
                prefs.mutateRecycledBackups { list -> list.filter { it.storedName != item.storedName } }
                true
            }.getOrDefault(false)
        }

    override suspend fun purgeBackup(item: RecycledBackup) = withContext(Dispatchers.IO) {
        runCatching {
            DesktopRecycleStore.purge(item)
            prefs.mutateRecycledBackups { list -> list.filter { it.storedName != item.storedName } }
        }
        Unit
    }
}

@Composable
actual fun rememberBackupRecycler(): BackupRecycler = remember { DesktopBackupRecycler() }
