package com.foldledger.data.sync

import com.foldledger.data.db.AccountEntity
import com.foldledger.data.db.CategoryEntity
import com.foldledger.data.db.GoalEntity
import com.foldledger.data.db.LedgerEntity
import com.foldledger.data.db.ProjectEntity
import com.foldledger.data.db.RecurringEntity
import com.foldledger.data.db.TransactionEntity
import org.json.JSONObject

/**
 * 实体 ↔ [SyncRow] 编解码（org.json，双端共享）。
 *
 * payload 是**全字段** JSON（含 id 与同步戳）—— 行自包含，服务端中继时
 * 不需要懂业务结构，远端解析也不依赖 header 之外的信息。
 * fromJson 一律用 opt* 兜底：远端是旧版/字段缺失时降级为默认值，绝不崩。
 */
object EntityCodec {

    // ---------------- 编码 ----------------

    fun ledgerToRow(e: LedgerEntity): SyncRow = SyncRow(
        table = TableNames.LEDGERS, id = e.id,
        updatedAt = e.updatedAt, updatedByDeviceId = e.updatedByDeviceId, deletedAt = e.deletedAt,
        payload = JSONObject()
            .put("id", e.id)
            .put("name", e.name)
            .put("sceneKey", e.sceneKey)
            .put("emoji", e.emoji)
            .put("colorHex", e.colorHex)
            .put("budgetCents", e.budgetCents)
            .put("note", e.note)
            .put("archived", e.archived)
            .put("sortOrder", e.sortOrder)
            .put("createdAt", e.createdAt)
            .put("deletedAt", e.deletedAt)
            .put("updatedAt", e.updatedAt)
            .put("updatedByDeviceId", e.updatedByDeviceId)
            .toString()
    )

    fun categoryToRow(e: CategoryEntity): SyncRow = SyncRow(
        table = TableNames.CATEGORIES, id = e.id,
        updatedAt = e.updatedAt, updatedByDeviceId = e.updatedByDeviceId, deletedAt = e.deletedAt,
        payload = JSONObject()
            .put("id", e.id)
            .put("ledgerId", e.ledgerId)
            .put("name", e.name)
            .put("emoji", e.emoji)
            .put("direction", e.direction)
            .put("sortOrder", e.sortOrder)
            .put("builtIn", e.builtIn)
            .put("budgetCents", e.budgetCents)
            .put("deletedAt", e.deletedAt)
            .put("updatedAt", e.updatedAt)
            .put("updatedByDeviceId", e.updatedByDeviceId)
            .toString()
    )

    fun goalToRow(e: GoalEntity): SyncRow = SyncRow(
        table = TableNames.GOALS, id = e.id,
        updatedAt = e.updatedAt, updatedByDeviceId = e.updatedByDeviceId, deletedAt = e.deletedAt,
        payload = JSONObject()
            .put("id", e.id)
            .put("ledgerId", e.ledgerId ?: JSONObject.NULL)
            .put("name", e.name)
            .put("emoji", e.emoji)
            .put("targetCents", e.targetCents)
            .put("savedCents", e.savedCents)
            .put("targetDate", e.targetDate)
            .put("accountId", e.accountId)
            .put("note", e.note)
            .put("archived", e.archived)
            .put("deletedAt", e.deletedAt)
            .put("createdAt", e.createdAt)
            .put("updatedAt", e.updatedAt)
            .put("updatedByDeviceId", e.updatedByDeviceId)
            .toString()
    )

    fun projectToRow(e: ProjectEntity): SyncRow = SyncRow(
        table = TableNames.PROJECTS, id = e.id,
        updatedAt = e.updatedAt, updatedByDeviceId = e.updatedByDeviceId, deletedAt = e.deletedAt,
        payload = JSONObject()
            .put("id", e.id)
            .put("ledgerId", e.ledgerId)
            .put("name", e.name)
            .put("emoji", e.emoji)
            .put("colorHex", e.colorHex)
            .put("budgetCents", e.budgetCents)
            .put("note", e.note)
            .put("closed", e.closed)
            .put("createdAt", e.createdAt)
            .put("deletedAt", e.deletedAt)
            .put("updatedAt", e.updatedAt)
            .put("updatedByDeviceId", e.updatedByDeviceId)
            .toString()
    )

    fun accountToRow(e: AccountEntity): SyncRow = SyncRow(
        table = TableNames.ACCOUNTS, id = e.id,
        updatedAt = e.updatedAt, updatedByDeviceId = e.updatedByDeviceId, deletedAt = e.deletedAt,
        payload = JSONObject()
            .put("id", e.id)
            .put("ledgerId", e.ledgerId)
            .put("name", e.name)
            .put("type", e.type)
            .put("emoji", e.emoji)
            .put("colorHex", e.colorHex)
            .put("initialBalanceCents", e.initialBalanceCents)
            .put("includeInNetWorth", e.includeInNetWorth)
            .put("note", e.note)
            .put("closed", e.closed)
            .put("createdAt", e.createdAt)
            .put("deletedAt", e.deletedAt)
            .put("updatedAt", e.updatedAt)
            .put("updatedByDeviceId", e.updatedByDeviceId)
            .toString()
    )

    fun recurringToRow(e: RecurringEntity): SyncRow = SyncRow(
        table = TableNames.RECURRING, id = e.id,
        updatedAt = e.updatedAt, updatedByDeviceId = e.updatedByDeviceId, deletedAt = e.deletedAt,
        payload = JSONObject()
            .put("id", e.id)
            .put("ledgerId", e.ledgerId)
            .put("name", e.name)
            .put("categoryId", e.categoryId ?: JSONObject.NULL)
            .put("categoryName", e.categoryName)
            .put("categoryEmoji", e.categoryEmoji)
            .put("direction", e.direction)
            .put("amountCents", e.amountCents)
            .put("note", e.note)
            .put("intervalKind", e.intervalKind)
            .put("nextDueAt", e.nextDueAt)
            .put("enabled", e.enabled)
            .put("projectId", e.projectId)
            .put("createdAt", e.createdAt)
            .put("deletedAt", e.deletedAt)
            .put("updatedAt", e.updatedAt)
            .put("updatedByDeviceId", e.updatedByDeviceId)
            .toString()
    )

    fun txnToRow(e: TransactionEntity): SyncRow = SyncRow(
        table = TableNames.TRANSACTIONS, id = e.id,
        updatedAt = e.updatedAt, updatedByDeviceId = e.updatedByDeviceId, deletedAt = e.deletedAt,
        payload = JSONObject()
            .put("id", e.id)
            .put("ledgerId", e.ledgerId)
            .put("categoryId", e.categoryId ?: JSONObject.NULL)
            .put("categoryName", e.categoryName)
            .put("categoryEmoji", e.categoryEmoji)
            .put("direction", e.direction)
            .put("amountCents", e.amountCents)
            .put("note", e.note)
            .put("occurredAt", e.occurredAt)
            .put("extraJson", e.extraJson)
            .put("reimburseState", e.reimburseState)
            .put("projectId", e.projectId)
            .put("accountId", e.accountId)
            .put("createdAt", e.createdAt)
            .put("deletedAt", e.deletedAt)
            .put("updatedAt", e.updatedAt)
            .put("updatedByDeviceId", e.updatedByDeviceId)
            .toString()
    )

    // ---------------- 解码 ----------------

    fun rowToLedger(r: SyncRow): LedgerEntity = with(JSONObject(r.payload)) {
        LedgerEntity(
            id = r.id,
            name = optString("name", ""),
            sceneKey = optString("sceneKey", "daily"),
            emoji = optString("emoji", "📒"),
            colorHex = optString("colorHex", "#1D3B57"),
            budgetCents = optLong("budgetCents", 0L),
            note = optString("note", ""),
            archived = optBoolean("archived", false),
            sortOrder = optInt("sortOrder", 0),
            createdAt = optLong("createdAt", r.updatedAt),
            deletedAt = optLong("deletedAt", r.deletedAt),
            updatedAt = optLong("updatedAt", r.updatedAt),
            updatedByDeviceId = optLong("updatedByDeviceId", r.updatedByDeviceId)
        )
    }

    fun rowToCategory(r: SyncRow): CategoryEntity = with(JSONObject(r.payload)) {
        CategoryEntity(
            id = r.id,
            ledgerId = optLong("ledgerId", 0L),
            name = optString("name", ""),
            emoji = optString("emoji", "📦"),
            direction = optInt("direction", 0),
            sortOrder = optInt("sortOrder", 0),
            builtIn = optBoolean("builtIn", false),
            budgetCents = optLong("budgetCents", 0L),
            deletedAt = optLong("deletedAt", r.deletedAt),
            updatedAt = optLong("updatedAt", r.updatedAt),
            updatedByDeviceId = optLong("updatedByDeviceId", r.updatedByDeviceId)
        )
    }

    fun rowToGoal(r: SyncRow): GoalEntity = with(JSONObject(r.payload)) {
        GoalEntity(
            id = r.id,
            ledgerId = if (isNull("ledgerId")) null else optLong("ledgerId", 0L),
            name = optString("name", ""),
            emoji = optString("emoji", "🎯"),
            targetCents = optLong("targetCents", 0L),
            savedCents = optLong("savedCents", 0L),
            targetDate = optLong("targetDate", 0L),
            accountId = optLong("accountId", 0L),
            note = optString("note", ""),
            archived = optBoolean("archived", false),
            deletedAt = optLong("deletedAt", r.deletedAt),
            createdAt = optLong("createdAt", r.updatedAt),
            updatedAt = optLong("updatedAt", r.updatedAt),
            updatedByDeviceId = optLong("updatedByDeviceId", r.updatedByDeviceId)
        )
    }

    fun rowToProject(r: SyncRow): ProjectEntity = with(JSONObject(r.payload)) {
        ProjectEntity(
            id = r.id,
            ledgerId = optLong("ledgerId", 0L),
            name = optString("name", ""),
            emoji = optString("emoji", "📁"),
            colorHex = optString("colorHex", "#1D3B57"),
            budgetCents = optLong("budgetCents", 0L),
            note = optString("note", ""),
            closed = optBoolean("closed", false),
            createdAt = optLong("createdAt", r.updatedAt),
            deletedAt = optLong("deletedAt", r.deletedAt),
            updatedAt = optLong("updatedAt", r.updatedAt),
            updatedByDeviceId = optLong("updatedByDeviceId", r.updatedByDeviceId)
        )
    }

    fun rowToAccount(r: SyncRow): AccountEntity = with(JSONObject(r.payload)) {
        AccountEntity(
            id = r.id,
            ledgerId = optLong("ledgerId", 0L),
            name = optString("name", ""),
            type = optInt("type", 1),
            emoji = optString("emoji", "💳"),
            colorHex = optString("colorHex", "#1D3B57"),
            initialBalanceCents = optLong("initialBalanceCents", 0L),
            includeInNetWorth = optBoolean("includeInNetWorth", true),
            note = optString("note", ""),
            closed = optBoolean("closed", false),
            createdAt = optLong("createdAt", r.updatedAt),
            deletedAt = optLong("deletedAt", r.deletedAt),
            updatedAt = optLong("updatedAt", r.updatedAt),
            updatedByDeviceId = optLong("updatedByDeviceId", r.updatedByDeviceId)
        )
    }

    fun rowToRecurring(r: SyncRow): RecurringEntity = with(JSONObject(r.payload)) {
        RecurringEntity(
            id = r.id,
            ledgerId = optLong("ledgerId", 0L),
            name = optString("name", ""),
            categoryId = if (isNull("categoryId")) null else optLong("categoryId", 0L),
            categoryName = optString("categoryName", ""),
            categoryEmoji = optString("categoryEmoji", "📦"),
            direction = optInt("direction", 0),
            amountCents = optLong("amountCents", 0L),
            note = optString("note", ""),
            intervalKind = optInt("intervalKind", 0),
            nextDueAt = optLong("nextDueAt", 0L),
            enabled = optBoolean("enabled", true),
            projectId = optLong("projectId", 0L),
            createdAt = optLong("createdAt", r.updatedAt),
            deletedAt = optLong("deletedAt", r.deletedAt),
            updatedAt = optLong("updatedAt", r.updatedAt),
            updatedByDeviceId = optLong("updatedByDeviceId", r.updatedByDeviceId)
        )
    }

    fun rowToTxn(r: SyncRow): TransactionEntity = with(JSONObject(r.payload)) {
        TransactionEntity(
            id = r.id,
            ledgerId = optLong("ledgerId", 0L),
            categoryId = if (isNull("categoryId")) null else optLong("categoryId", 0L),
            categoryName = optString("categoryName", "未分类"),
            categoryEmoji = optString("categoryEmoji", "📦"),
            direction = optInt("direction", 0),
            amountCents = optLong("amountCents", 0L),
            note = optString("note", ""),
            occurredAt = optLong("occurredAt", 0L),
            extraJson = optString("extraJson", ""),
            reimburseState = optInt("reimburseState", -1),
            projectId = optLong("projectId", 0L),
            accountId = optLong("accountId", 0L),
            createdAt = optLong("createdAt", r.updatedAt),
            deletedAt = optLong("deletedAt", r.deletedAt),
            updatedAt = optLong("updatedAt", r.updatedAt),
            updatedByDeviceId = optLong("updatedByDeviceId", r.updatedByDeviceId)
        )
    }

    /** 按表名路由解码；未知表返回 null（防御性，跳过） */
    fun decode(r: SyncRow): Any? = when (r.table) {
        TableNames.LEDGERS -> rowToLedger(r)
        TableNames.CATEGORIES -> rowToCategory(r)
        TableNames.PROJECTS -> rowToProject(r)
        TableNames.ACCOUNTS -> rowToAccount(r)
        TableNames.RECURRING -> rowToRecurring(r)
        TableNames.GOALS -> rowToGoal(r)
        TableNames.TRANSACTIONS -> rowToTxn(r)
        else -> null
    }
}
