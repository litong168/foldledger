package com.foldledger.data.backup

import com.foldledger.data.db.CategoryEntity
import com.foldledger.data.db.GoalEntity
import com.foldledger.data.db.LedgerEntity
import com.foldledger.data.db.AccountEntity
import com.foldledger.data.db.AccountType
import com.foldledger.data.db.ProjectEntity
import com.foldledger.data.db.RecurringEntity
import com.foldledger.data.db.TransactionEntity
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一次完整的数据库快照。恢复时按 id 重映射，做到「完美恢复」。
 */
data class BackupSnapshot(
    val exportedAt: Long,
    val appVersion: String,
    val ledgers: List<LedgerEntity>,
    val categories: List<CategoryEntity>,
    val transactions: List<TransactionEntity>,
    /**
     * v5.1 项目 / 空间。
     * 默认空 → **老备份没有这一段也能正常恢复**（老 App 读到新备份时忽略这一段，同样兼容）。
     */
    val projects: List<ProjectEntity> = emptyList(),
    /**
     * v5.7 周期性记账。
     * 同样是可选段：老备份没有它 → 恢复后周期项为空，其它数据不受影响。
     */
    val recurring: List<RecurringEntity> = emptyList(),
    /**
     * v6.1 资产账户。同样是可选段：老备份没有它 → 恢复后账户为空（流水 accountId 退回 0）。
     */
    val accounts: List<AccountEntity> = emptyList(),
    /**
     * v9.2 存钱目标。同样是可选段：老备份没有它 → 恢复后目标为空，不影响其它数据。
     */
    val goals: List<GoalEntity> = emptyList()
) {
    val ledgerCount: Int get() = ledgers.size
    val txnCount: Int get() = transactions.size
}

/**
 * 备份文件格式。
 *
 * 之所以 JSON 而不是直接复用 Excel 作为备份：
 * Excel 是给人看的，用它回灌会丢字段（分类 id / 排序 / 预算 / 归档位 / 场景字段原值），
 * 做不到「完美恢复」。所以一次导出同时产出两份：
 *   - `*.foldledger.json`  机器可读，用于恢复
 *   - `*.xlsx`             人可读，用于对账归档
 */
object BackupFormat {

    const val FORMAT_TAG = "foldledger.backup"
    const val VERSION = 1
    const val EXT_JSON = ".foldledger.json"
    const val EXT_XLSX = ".xlsx"
    /** v15.1：加密导出的扩展名（文件名仍以品牌前缀开头，列表/清理/删除白名单共用一套规则） */
    const val EXT_CRYPT = ".foldledger.crypt"

    /** 当前品牌名（应用改名「滴水记账」后启用） */
    const val FILE_PREFIX = "滴水记账-"
    /** v15.1：手动加密备份的文件名前缀（放在 FILE_PREFIX 之后，常量初始化顺序才对） */
    const val MANUAL_CRYPT_PREFIX = "${FILE_PREFIX}加密备份-"

    /** 历史品牌名前缀：改名前的备份**依然能列出来、能恢复**，不能因为它们换了名字就认不出 */
    val LEGACY_PREFIXES = listOf("折叠记账-")

    /** 所有被认作「本 App 备份」的文件名前缀 */
    val ALL_PREFIXES: List<String> get() = listOf(FILE_PREFIX) + LEGACY_PREFIXES

    const val AUTO_FILE_NAME = "${FILE_PREFIX}自动备份$EXT_JSON"

    /**
     * v15：自动备份**轮转**命名（带时间戳，保留最近 N 份）。
     * 旧版固定名 `AUTO_FILE_NAME` 每次覆盖同一份 —— 备份写一半崩溃就什么都没了；
     * 轮转后坏掉的那份留着不碍事，前一份还在。
     */
    const val AUTO_PREFIX = "${FILE_PREFIX}自动备份-"

    /** 判断是不是自动备份文件（兼容旧的固定名；自动备份永不加密，这里只是兜底兼容） */
    fun isAutoBackupName(name: String): Boolean =
        name == AUTO_FILE_NAME || (name.startsWith(AUTO_PREFIX) && (name.endsWith(EXT_JSON) || name.endsWith(EXT_CRYPT)))

    /** 判断一个文件名是不是本 App 自己写出来的备份（含历史命名），用于列表 / 清理 / 删除的白名单 */
    fun isBackupFileName(name: String): Boolean =
        (name.endsWith(EXT_JSON) || name.endsWith(EXT_CRYPT)) && ALL_PREFIXES.any { name.startsWith(it) }

    fun encode(snapshot: BackupSnapshot): ByteArray {
        val ledgers = JSONArray()
        snapshot.ledgers.forEach { ledgers.put(ledgerToJson(it)) }
        val categories = JSONArray()
        snapshot.categories.forEach { categories.put(categoryToJson(it)) }
        val txns = JSONArray()
        snapshot.transactions.forEach { txns.put(txnToJson(it)) }
        val projects = JSONArray()
        snapshot.projects.forEach { projects.put(projectToJson(it)) }

        val recurring = JSONArray()
        snapshot.recurring.forEach { recurring.put(recurringToJson(it)) }

        val accounts = JSONArray()
        snapshot.accounts.forEach { accounts.put(accountToJson(it)) }

        val goals = JSONArray()
        snapshot.goals.forEach { goals.put(goalToJson(it)) }

        // 外层信封手工拼：org.json 的 JSONObject **不保证键顺序**（Android 实现是
        // LinkedHashMap，JVM 参考实现是 HashMap），如果用它包外层，摘要字段的位置就不确定，
        // peekHeader 只读前几百字节的优化就失效了。自己拼既保证顺序，也保证是合法 JSON。
        val sb = StringBuilder(256)
        sb.append("{\"format\":\"").append(FORMAT_TAG).append('"')
        sb.append(",\"version\":").append(VERSION)
        sb.append(",\"appVersion\":\"").append(escapeJson(snapshot.appVersion)).append('"')
        sb.append(",\"exportedAt\":").append(snapshot.exportedAt)
        sb.append(",\"ledgerCount\":").append(snapshot.ledgerCount)
        sb.append(",\"txnCount\":").append(snapshot.txnCount)
        sb.append(",\"ledgers\":").append(ledgers)
        sb.append(",\"categories\":").append(categories)
        sb.append(",\"transactions\":").append(txns)
        // v5.1：项目放在最后 —— 摘要字段仍在最前面，peekHeader 的 400 字节优化不受影响
        sb.append(",\"projects\":").append(projects)
        sb.append(",\"recurring\":").append(recurring)
        // v6.1：资产账户放在最后，摘要字段仍在最前面，peekHeader 不受影响
        sb.append(",\"accounts\":").append(accounts)
        // v9.2：存钱目标放在最后，摘要字段仍在最前面（peekHeader 的 400 字节优化不受影响）
        sb.append(",\"goals\":").append(goals)
        sb.append('}')
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private fun escapeJson(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c.code < 0x20) {
                    sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                } else {
                    sb.append(c)
                }
            }
        }
        return sb.toString()
    }

    /** 解析失败会抛异常，调用方负责兜底提示 */
    fun decode(bytes: ByteArray): BackupSnapshot {        val root = JSONObject(String(bytes, Charsets.UTF_8))
        val format = root.optString("format")
        require(format == FORMAT_TAG) { "不是滴水记账的备份文件" }
        val version = root.optInt("version", 0)
        require(version in 1..VERSION) { "备份文件版本不支持（v$version）" }

        val ledgers = root.optJSONArray("ledgers").toList { o ->
            LedgerEntity(
                id = o.optLong("id"),
                name = o.optString("name"),
                sceneKey = o.optString("sceneKey", "custom"),
                emoji = o.optString("emoji", "📒"),
                colorHex = o.optString("colorHex", "#1F6FEB"),
                budgetCents = o.optLong("budgetCents"),
                note = o.optString("note"),
                archived = o.optBoolean("archived", false),
                sortOrder = o.optInt("sortOrder"),
                createdAt = o.optLong("createdAt", System.currentTimeMillis())
            )
        }

        val categories = root.optJSONArray("categories").toList { o ->
            CategoryEntity(
                id = o.optLong("id"),
                ledgerId = o.optLong("ledgerId"),
                name = o.optString("name"),
                emoji = o.optString("emoji", "🏷️"),
                direction = o.optInt("direction"),
                sortOrder = o.optInt("sortOrder"),
                builtIn = o.optBoolean("builtIn", false),
                budgetCents = o.optLong("budgetCents")
            )
        }

        val transactions = root.optJSONArray("transactions").toList { o ->
            TransactionEntity(
                id = o.optLong("id"),
                ledgerId = o.optLong("ledgerId"),
                categoryId = if (o.isNull("categoryId")) null else o.optLong("categoryId"),
                categoryName = o.optString("categoryName", "未分类"),
                categoryEmoji = o.optString("categoryEmoji", "📦"),
                direction = o.optInt("direction"),
                amountCents = o.optLong("amountCents"),
                note = o.optString("note"),
                occurredAt = o.optLong("occurredAt"),
                extraJson = o.optString("extraJson"),
                reimburseState = o.optInt("reimburseState", -1),
                // v5.1：老备份没有这个字段 → 0（未归类）
                projectId = o.optLong("projectId", 0L),
                // v6.1：老备份没有这个字段 → 0（未指定账户）
                accountId = o.optLong("accountId", 0L),
                createdAt = o.optLong("createdAt", System.currentTimeMillis())
            )
        }

        // v5.1：老备份没有 projects 数组 → 空列表（正常恢复，项目功能从零开始）
        val projects = root.optJSONArray("projects").toList { o ->
            ProjectEntity(
                id = o.optLong("id"),
                ledgerId = o.optLong("ledgerId"),
                name = o.optString("name"),
                emoji = o.optString("emoji", "📁"),
                colorHex = o.optString("colorHex", "#3B82F6"),
                budgetCents = o.optLong("budgetCents", 0L),
                note = o.optString("note"),
                closed = o.optBoolean("closed", false),
                createdAt = o.optLong("createdAt", System.currentTimeMillis())
            )
        }

        // v9.2：老备份没有 goals 数组 → 空列表（正常恢复，目标功能从零开始）
        val goals = root.optJSONArray("goals").toList { o ->
            GoalEntity(
                id = o.optLong("id"),
                // v9.7：null = 独立目标（老备份里没有这一段 / 值为 0 都算独立）
                ledgerId = if (o.isNull("ledgerId")) null else o.optLong("ledgerId").takeIf { it > 0 },
                name = o.optString("name"),
                emoji = o.optString("emoji", "🎯"),
                targetCents = o.optLong("targetCents", 0L),
                savedCents = o.optLong("savedCents", 0L),
                targetDate = o.optLong("targetDate", 0L),
                accountId = o.optLong("accountId", 0L),
                note = o.optString("note"),
                archived = o.optBoolean("archived", false),
                createdAt = o.optLong("createdAt", System.currentTimeMillis())
            )
        }

        return BackupSnapshot(
            exportedAt = root.optLong("exportedAt", System.currentTimeMillis()),
            appVersion = root.optString("appVersion", "?"),
            ledgers = ledgers,
            categories = categories,
            transactions = transactions,
            projects = projects,
            goals = goals,
            recurring = root.optJSONArray("recurring").toList { o ->
                RecurringEntity(
                    id = o.optLong("id"),
                    ledgerId = o.optLong("ledgerId"),
                    name = o.optString("name"),
                    categoryId = if (o.isNull("categoryId")) null else o.optLong("categoryId"),
                    categoryName = o.optString("categoryName"),
                    categoryEmoji = o.optString("categoryEmoji", "📦"),
                    direction = o.optInt("direction"),
                    amountCents = o.optLong("amountCents"),
                    note = o.optString("note"),
                    intervalKind = o.optInt("intervalKind"),
                    nextDueAt = o.optLong("nextDueAt"),
                    enabled = o.optBoolean("enabled", true),
                    projectId = o.optLong("projectId", 0L),
                    createdAt = o.optLong("createdAt", System.currentTimeMillis())
                )
            },
            // v6.1：老备份没有 accounts 数组 → 空列表（流水 accountId 已在上面退回 0）
            accounts = root.optJSONArray("accounts").toList { o ->
                AccountEntity(
                    id = o.optLong("id"),
                    ledgerId = o.optLong("ledgerId"),
                    name = o.optString("name"),
                    type = o.optInt("type", AccountType.SAVINGS),
                    emoji = o.optString("emoji", "💳"),
                    colorHex = o.optString("colorHex", "#3B82F6"),
                    initialBalanceCents = o.optLong("initialBalanceCents", 0L),
                    includeInNetWorth = o.optBoolean("includeInNetWorth", true),
                    note = o.optString("note"),
                    closed = o.optBoolean("closed", false),
                    createdAt = o.optLong("createdAt", System.currentTimeMillis())
                )
            }
        )
    }

    data class Header(
        val exportedAt: Long,
        val ledgerCount: Int,
        val txnCount: Int,
        val appVersion: String
    )

    /**
     * 只读文件开头一小段就能拿到摘要，列表页不必把整个 JSON 解析一遍。
     * 摘要字段编码时被刻意放在根对象的靠前位置。
     */
    fun peekHeader(bytes: ByteArray, maxChars: Int = 400): Header? {
        if (bytes.size < 32) return null
        val head = String(bytes, 0, minOf(bytes.size, maxChars), Charsets.UTF_8)
        if (!head.contains(FORMAT_TAG)) return null

        fun num(key: String): Long? =
            Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*(-?\\d+)")
                .find(head)?.groupValues?.get(1)?.toLongOrNull()

        fun str(key: String): String? =
            Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*\"([^\"]*)\"")
                .find(head)?.groupValues?.get(1)

        return Header(
            exportedAt = num("exportedAt") ?: 0L,
            ledgerCount = (num("ledgerCount") ?: 0L).toInt(),
            txnCount = (num("txnCount") ?: 0L).toInt(),
            appVersion = str("appVersion") ?: "?"
        )
    }

    // ---------------- 序列化细节 ----------------

    private fun ledgerToJson(l: LedgerEntity) = JSONObject().apply {
        put("id", l.id)
        put("name", l.name)
        put("sceneKey", l.sceneKey)
        put("emoji", l.emoji)
        put("colorHex", l.colorHex)
        put("budgetCents", l.budgetCents)
        put("note", l.note)
        put("archived", l.archived)
        put("sortOrder", l.sortOrder)
        put("createdAt", l.createdAt)
    }

    private fun categoryToJson(c: CategoryEntity) = JSONObject().apply {
        put("id", c.id)
        put("ledgerId", c.ledgerId)
        put("name", c.name)
        put("emoji", c.emoji)
        put("direction", c.direction)
        put("sortOrder", c.sortOrder)
        put("builtIn", c.builtIn)
        put("budgetCents", c.budgetCents)
    }

    private fun txnToJson(t: TransactionEntity) = JSONObject().apply {
        put("id", t.id)
        put("ledgerId", t.ledgerId)
        if (t.categoryId == null) put("categoryId", JSONObject.NULL) else put("categoryId", t.categoryId)
        put("categoryName", t.categoryName)
        put("categoryEmoji", t.categoryEmoji)
        put("direction", t.direction)
        put("amountCents", t.amountCents)
        put("note", t.note)
        put("occurredAt", t.occurredAt)
        put("extraJson", t.extraJson)
        put("reimburseState", t.reimburseState)
        put("projectId", t.projectId)
        put("accountId", t.accountId)
        put("createdAt", t.createdAt)
    }

    private fun projectToJson(p: ProjectEntity) = JSONObject().apply {
        put("id", p.id)
        put("ledgerId", p.ledgerId)
        put("name", p.name)
        put("emoji", p.emoji)
        put("colorHex", p.colorHex)
        put("budgetCents", p.budgetCents)
        put("note", p.note)
        put("closed", p.closed)
        put("createdAt", p.createdAt)
    }

    /** v9.2 存钱目标 */
    private fun goalToJson(g: GoalEntity) = JSONObject().apply {
        put("id", g.id)
        put("ledgerId", g.ledgerId ?: JSONObject.NULL)
        put("name", g.name)
        put("emoji", g.emoji)
        put("targetCents", g.targetCents)
        put("savedCents", g.savedCents)
        put("targetDate", g.targetDate)
        put("accountId", g.accountId)
        put("note", g.note)
        put("archived", g.archived)
        put("createdAt", g.createdAt)
    }

    /** v6.1 资产账户。字段与 [AccountEntity] 一一对应，恢复时 id 会重映射 */
    private fun accountToJson(a: AccountEntity) = JSONObject().apply {
        put("id", a.id)
        put("ledgerId", a.ledgerId)
        put("name", a.name)
        put("type", a.type)
        put("emoji", a.emoji)
        put("colorHex", a.colorHex)
        put("initialBalanceCents", a.initialBalanceCents)
        put("includeInNetWorth", a.includeInNetWorth)
        put("note", a.note)
        put("closed", a.closed)
        put("createdAt", a.createdAt)
    }

    /** v5.7 周期性记账。字段与 [RecurringEntity] 一一对应，恢复时 id 会重映射 */
    private fun recurringToJson(r: RecurringEntity) = JSONObject().apply {
        put("id", r.id)
        put("ledgerId", r.ledgerId)
        put("name", r.name)
        put("categoryId", r.categoryId ?: JSONObject.NULL)
        put("categoryName", r.categoryName)
        put("categoryEmoji", r.categoryEmoji)
        put("direction", r.direction)
        put("amountCents", r.amountCents)
        put("note", r.note)
        put("intervalKind", r.intervalKind)
        put("nextDueAt", r.nextDueAt)
        put("enabled", r.enabled)
        put("projectId", r.projectId)
        put("createdAt", r.createdAt)
    }

    private inline fun <T> JSONArray?.toList(map: (JSONObject) -> T): List<T> {
        if (this == null) return emptyList()
        val out = ArrayList<T>(length())
        for (i in 0 until length()) {
            val o = optJSONObject(i) ?: continue
            out += map(o)
        }
        return out
    }
}
