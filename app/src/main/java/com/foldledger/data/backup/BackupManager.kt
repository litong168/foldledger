package com.foldledger.data.backup

import android.content.Context
import android.net.Uri
import com.foldledger.BuildConfig
import com.foldledger.ServiceLocator
import com.foldledger.core.DripCore
import com.foldledger.data.export.ExcelExporter
import com.foldledger.data.repo.LedgerRepository
import com.foldledger.data.repo.RestoreStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 备份编排：一次导出 = 一个可恢复的 JSON + 一个人可读的 Excel。
 *
 * 目录授权（SAF）由调用方（ViewModel/UI）负责，这里只做读写与业务。
 */
class BackupManager(
    private val context: Context,
    private val repo: LedgerRepository
) {

    private val saf = SafFolderStore(context)

    companion object {
        const val MIME_JSON = "application/json"
        const val MIME_XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        /** v15.1.1：加密备份是二进制信封不是 JSON，传 JSON MIME 会被 DocumentsProvider 自动追加 .json 后缀，
         *  导致产出 `.foldledger.crypt.json`、`isBackupFileName` 匹配失败、恢复列表永远扫不到 */
        const val MIME_CRYPT = "application/octet-stream"

        /** 自动备份之外，手动备份保留的最新份数 */
        const val KEEP_MANUAL = 10
        /** v15：自动备份轮转保留份数 */
        const val KEEP_AUTO = 3
    }

    data class ExportOutcome(
        val folderLabel: String,
        val jsonName: String,
        val xlsxName: String,
        val ledgerCount: Int,
        val txnCount: Int,
        val totalBytes: Int,
        val prunedFiles: Int,
        val isAuto: Boolean
    )

    data class BackupEntry(
        val docId: String,
        val fileName: String,
        val savedAt: Long,
        val ledgerCount: Int,
        val txnCount: Int,
        val appVersion: String,
        val sizeBytes: Long,
        val isAuto: Boolean,
        /** v15.1：这份备份是否加密（恢复时需输入密码） */
        val encrypted: Boolean = false,
        /** v15：摘要都读不出来 —— 文件损坏或不是本 App 的备份。可见但禁用恢复 */
        val corrupt: Boolean = false
    )

    /** v15.1：列表用的统一摘要（加密/明文头部都映射到它，避免两种 Header 类型不兼容） */
    private data class BackupSummary(
        val exportedAt: Long,
        val ledgerCount: Int,
        val txnCount: Int,
        val appVersion: String
    )

    // ---------------- 目录授权 ----------------

    fun hasPermission(treeUri: Uri): Boolean = saf.hasPermission(treeUri)

    fun takePermission(treeUri: Uri): Boolean = saf.takePersistablePermission(treeUri)

    fun folderLabel(treeUri: Uri): String = saf.folderLabel(treeUri)

    fun release(treeUri: Uri) = saf.releasePermission(treeUri)

    // ---------------- 导出 ----------------

    suspend fun export(treeUri: Uri, auto: Boolean, passphrase: String? = null): ExportOutcome = withContext(Dispatchers.IO) {
        val snapshot = repo.snapshot()
        if (snapshot.ledgerCount == 0) {
            // v10.5：auto 也要挡住空快照 —— 清库/全软删后自动备份不能拿空数据覆盖好备份
            if (auto) return@withContext ExportOutcome(
                folderLabel = "", jsonName = "", xlsxName = "",
                ledgerCount = 0, txnCount = 0, totalBytes = 0, prunedFiles = 0, isAuto = true
            )
            error("还没有任何账本，没什么可备份的")
        }

        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.CHINA)
            .format(Date(snapshot.exportedAt))

        // v15.1：手动导出可带密码（加密信封），自动备份永不加密（静默、无密码入口，且是兜底安全网）
        val encrypted = !auto && !passphrase.isNullOrBlank()
        val jsonName = if (auto) {
            // v15：自动备份也轮转（保留最近 KEEP_AUTO 份），不再是"覆盖同一份"
            BackupFormat.AUTO_PREFIX + stamp + BackupFormat.EXT_JSON
        } else if (encrypted) {
            BackupFormat.MANUAL_CRYPT_PREFIX + stamp + BackupFormat.EXT_CRYPT
        } else {
            "${BackupFormat.FILE_PREFIX}备份-$stamp${BackupFormat.EXT_JSON}"
        }
        val xlsxName = if (encrypted) "" else jsonName.removeSuffix(BackupFormat.EXT_JSON) + BackupFormat.EXT_XLSX

        val plainJson = BackupFormat.encode(snapshot)
        val jsonBytes = if (encrypted) {
            BackupCipher.encrypt(
                plainJson,
                passphrase!!,
                BackupCipher.EncHeader(
                    appVersion = BuildConfig.VERSION_NAME,
                    exportedAt = snapshot.exportedAt,
                    ledgerCount = snapshot.ledgerCount,
                    txnCount = snapshot.txnCount
                )
            )
        } else {
            plainJson
        }

        // v10.5：补 projects —— 原来不传导致 Excel 项目列全空、项目汇总表丢失（仅明文备份产出 xlsx）
        val excelBytes = if (encrypted) ByteArray(0) else ExcelExporter(repo).build(
            snapshot.ledgers, snapshot.transactions, snapshot.projects
        ).bytes

        val okJson = saf.writeFile(treeUri, jsonName, if (encrypted) MIME_CRYPT else MIME_JSON, jsonBytes)
        if (!okJson) error("写入备份目录失败，请重新选择备份文件夹")
        val okXlsx = if (encrypted) false else saf.writeFile(treeUri, xlsxName, MIME_XLSX, excelBytes)

        // v15：自动/手动都轮转（自动保留最近 KEEP_AUTO 份，手动保留 KEEP_MANUAL 份）
        val pruned = prune(treeUri)

        ExportOutcome(
            folderLabel = saf.folderLabel(treeUri),
            jsonName = jsonName,
            xlsxName = xlsxName,
            ledgerCount = snapshot.ledgerCount,
            txnCount = snapshot.txnCount,
            totalBytes = jsonBytes.size + if (okXlsx) excelBytes.size else 0,
            prunedFiles = pruned,
            isAuto = auto
        )
    }

    /**
     * 只清理**我们自己命名规则**的旧备份，其他文件一律不碰。
     *
     * v15：自动备份从"覆盖同一份"改为轮转 —— 自动保留最近 [KEEP_AUTO] 份、
     * 手动保留 [KEEP_MANUAL] 份；各自按时间从旧到新清。
     */
    private fun prune(treeUri: Uri): Int {
        val files = runCatching { saf.listFiles(treeUri) }.getOrDefault(emptyList())
        var deleted = 0

        fun dropOld(list: List<com.foldledger.data.backup.RemoteFile>, keep: Int) {
            list.drop(keep).forEach { old ->
                val base = old.name.removeSuffix(BackupFormat.EXT_JSON)
                if (saf.delete(treeUri, old.docId)) deleted++
                files.firstOrNull { it.name == base + BackupFormat.EXT_XLSX }?.let {
                    if (saf.delete(treeUri, it.docId)) deleted++
                }
            }
        }

        val autoJson = files
            .filter { BackupFormat.isAutoBackupName(it.name) }
            .sortedByDescending { it.lastModified }
        dropOld(autoJson, KEEP_AUTO)

        val manualJson = files
            .filter { BackupFormat.isBackupFileName(it.name) && !BackupFormat.isAutoBackupName(it.name) }
            .sortedByDescending { it.lastModified }
        dropOld(manualJson, KEEP_MANUAL)

        return deleted
    }

    // ---------------- 列出 / 读取 ----------------

    suspend fun listBackups(treeUri: Uri): List<BackupEntry> = withContext(Dispatchers.IO) {
        val files = runCatching { saf.listFiles(treeUri) }.getOrDefault(emptyList())
        files.asSequence()
            .filter { BackupFormat.isBackupFileName(it.name) }
            .mapNotNull { f ->
                // 只读文件开头几百字节拿摘要，避免把每个备份都完整解析
                val head = runCatching {
                    context.contentResolver
                        .openInputStream(
                            android.provider.DocumentsContract
                                .buildDocumentUriUsingTree(treeUri, f.docId)
                        )
                        ?.use { input ->
                            val buf = ByteArray(512)
                            val n = input.read(buf)
                            if (n <= 0) null else buf.copyOf(n)
                        }
                }.getOrNull()

                // v15.1：加密备份用 BackupCipher 读头部，明文备份仍走原有 peekHeader
                val isEnc = head != null && BackupCipher.isEncrypted(head)
                val summary = head?.let { h ->
                    if (isEnc) BackupCipher.peekHeader(h)?.let { BackupSummary(it.exportedAt, it.ledgerCount, it.txnCount, it.appVersion) }
                    else BackupFormat.peekHeader(h)?.let { BackupSummary(it.exportedAt, it.ledgerCount, it.txnCount, it.appVersion) }
                }

                if (summary == null) {
                    // v15：读不出摘要 ≠ 目录里没这份 —— 标成"损坏"让它可见，
                    // 否则用户会以为备份"少了一份"（或某次写一半坏掉的备份凭空消失）
                    return@mapNotNull BackupEntry(
                        docId = f.docId,
                        fileName = f.name,
                        savedAt = f.lastModified,
                        ledgerCount = 0,
                        txnCount = 0,
                        appVersion = "",
                        sizeBytes = f.size,
                        isAuto = BackupFormat.isAutoBackupName(f.name),
                        corrupt = true
                    )
                }
                BackupEntry(
                    docId = f.docId,
                    fileName = f.name,
                    savedAt = if (summary.exportedAt > 0) summary.exportedAt else f.lastModified,
                    ledgerCount = summary.ledgerCount,
                    txnCount = summary.txnCount,
                    appVersion = summary.appVersion,
                    sizeBytes = f.size,
                    isAuto = BackupFormat.isAutoBackupName(f.name),
                    encrypted = isEnc
                )
            }
            .sortedByDescending { it.savedAt }
            .toList()
    }

    suspend fun readBackup(treeUri: Uri, docId: String, passphrase: String? = null): BackupSnapshot = withContext(Dispatchers.IO) {
        val bytes = saf.read(treeUri, docId) ?: error("读取备份文件失败")
        // v15.1：加密备份先解密，再走原有完整性预检
        val plaintext = if (BackupCipher.isEncrypted(bytes)) {
            if (passphrase.isNullOrBlank()) error("这份备份已加密，需要输入密码才能恢复")
            try {
                BackupCipher.decrypt(bytes, passphrase)
            } catch (e: IllegalStateException) {
                error(e.message ?: "备份密码不正确，无法解密")
            }
        } else {
            bytes
        }
        // v4.0：恢复前先用 Rust 核心做完整性预检（DripCore.validateBackup，so 缺失时 Kotlin 回退），
        // 挡掉明显损坏 / 不是本 App 的文件，避免把垃圾数据灌进数据库。
        if (!DripCore.validateBackup(plaintext)) {
            error("这份备份文件损坏或不是滴水记账的备份，已拒绝恢复")
        }
        BackupFormat.decode(plaintext)
    }

    /**
     * 删除一份备份：连同同名的 .xlsx 一起删。
     * 只接受本 App 命名规则的文件，绝不删目录里的其他东西。
     *
     * v9.4 起**先搬进 App 内部回收站再删原文件** —— 后悔药在回收站里。
     */
    suspend fun deleteBackup(treeUri: Uri, entry: BackupEntry): Boolean = withContext(Dispatchers.IO) {
        val safeName = entry.fileName
        if (!BackupFormat.isBackupFileName(safeName)) {
            error("这个文件不是滴水记账的备份，已拒绝删除")
        }
        val base = safeName.removeSuffix(BackupFormat.EXT_JSON)

        // 1) 先把内容读出来存进 App 私有目录（万一用户后悔，文件还在）
        val jsonBytes = saf.read(treeUri, entry.docId)
            ?: error("读取备份文件失败，已放弃删除")
        val xlsxRemote = saf.findFile(treeUri, base + BackupFormat.EXT_XLSX)
        val xlsxBytes = xlsxRemote?.let { saf.read(treeUri, it.docId) }

        val dir = File(context.filesDir, "recycle/backup").apply { mkdirs() }
        val stamp = System.currentTimeMillis()
        val storedName = "$stamp-$safeName"
        File(dir, storedName).writeBytes(jsonBytes)
        // v9.7.2：xlsx 的内部副本名也显式记下来（原来靠文件名反推，基本拼不中）
        val xlsxStoredName = if (xlsxBytes != null) {
            "$stamp-${base + BackupFormat.EXT_XLSX}".also {
                File(dir, it).writeBytes(xlsxBytes)
            }
        } else null

        // 2) 记进回收站清单（v10.3：原子读改写，不再整表覆盖）
        ServiceLocator.preferences.mutateRecycledBackups { current ->
            current + RecycledBackup(
                fileName = safeName,
                deletedAt = stamp,
                storedName = storedName,
                hasXlsx = xlsxBytes != null,
                sizeBytes = jsonBytes.size.toLong(),
                xlsxStoredName = xlsxStoredName
            )
        }

        // 3) 最后才删原文件（清单也接受本 App 命名规则的文件名，绝不删别人的东西）
        xlsxRemote?.let { saf.delete(treeUri, it.docId) }
        saf.delete(treeUri, entry.docId)
    }

    /**
     * 读回收站清单。
     *
     * v9.7.2：DataStore 读失败时不要直接抛 —— 在删除/还原链路里抛出会让
     * 「私有副本已经写完、清单却没更新」变成脏状态。读不到就当空表继续。
     */
    private suspend fun loadRecycled(): List<RecycledBackup> =
        runCatching { ServiceLocator.preferences.recycledBackups.first() }.getOrElse { emptyList() }

    /** v9.4 回收站：被删掉的备份文件 */
    fun recycledBackups(): Flow<List<RecycledBackup>> = ServiceLocator.preferences.recycledBackups

    /** v9.4 把回收站里的备份还原回备份目录 */
    suspend fun restoreRecycled(treeUri: Uri, item: RecycledBackup): Boolean = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "recycle/backup")
        val src = File(dir, item.storedName)
        if (!src.exists()) return@withContext false
        val bytes = src.readBytes()
        if (!saf.writeFile(treeUri, item.fileName, if (BackupCipher.isEncrypted(bytes)) MIME_CRYPT else MIME_JSON, bytes)) {
            return@withContext false
        }
        if (item.hasXlsx) {
            // 优先用清单里记下的名字；老清单没有这个字段时退回"按时间戳前缀找"
            val source = item.xlsxStoredName?.let { File(dir, it) }?.takeIf { it.exists() }
                ?: dir.listFiles()?.firstOrNull {
                    it.name.startsWith("${item.deletedAt}-") && it.name.endsWith(BackupFormat.EXT_XLSX)
                }
            if (source != null) {
                saf.writeFile(
                    treeUri,
                    item.fileName.removeSuffix(BackupFormat.EXT_JSON) + BackupFormat.EXT_XLSX,
                    MIME_XLSX,
                    source.readBytes()
                )
                source.delete()
            }
        }
        src.delete()
        // v10.3：原子移除，不再 load→filter→整表覆盖
        ServiceLocator.preferences.mutateRecycledBackups { list ->
            list.filter { it.storedName != item.storedName }
        }
        true
    }

    /** v9.4 彻底铲除回收站里的备份（不可还原） */
    suspend fun purgeRecycled(item: RecycledBackup): Boolean = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "recycle/backup")
        // v9.7.2：只删这一条自己的副本 —— 原来按"时间戳前缀"批量删，
        // 同一毫秒存了多份时会把别人的文件一起删掉。
        File(dir, item.storedName).delete()
        item.xlsxStoredName?.let { File(dir, it).delete() }
        // 老清单没有 xlsxStoredName：用「与 json 同名、只换扩展名」精确定位
        if (item.hasXlsx && item.xlsxStoredName == null) {
            val altName = item.storedName.removeSuffix(BackupFormat.EXT_JSON) + BackupFormat.EXT_XLSX
            File(dir, altName).delete()
        }
        // v10.3：原子移除
        ServiceLocator.preferences.mutateRecycledBackups { list ->
            list.filter { it.storedName != item.storedName }
        }
        true
    }

    suspend fun restore(snapshot: BackupSnapshot): RestoreStats = withContext(Dispatchers.IO) {
        repo.restoreFrom(snapshot)
    }
}
