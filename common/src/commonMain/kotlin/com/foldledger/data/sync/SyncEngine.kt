package com.foldledger.data.sync

import com.foldledger.data.ScenePresets
import com.foldledger.data.db.AppDatabase
import org.json.JSONObject

/** 应用远端增量的统计 */
data class SyncApplyStats(
    val inserted: Int,
    val updated: Int,
    val localWins: Int,
    val skipped: Int,
    /** v16.2.8：被 LWW 判为"本地赢"的明细（最多 SyncLogStore.MAX_CONFLICTS 条） */
    val conflicts: List<SyncConflict> = emptyList()
) {
    val total: Int get() = inserted + updated + localWins + skipped
}

/**
 * M4 同步引擎核心（双端共享，不负责 HTTP）。
 *
 *  - [collectOutgoing]：按水位线收集本机增量（含墓碑），**排除自动记账账本整本**；
 *  - [applyIncoming]：把远端增量 LWW 合并进本地（依赖表序 + auto 防御过滤），
 *    成功后推进水位线到 max(updatedAt)+1 —— 被 LWW 拒绝的行也计入（否则每次同步都重收），
 *    `+1` 配合服务端 `>=` 语义：同刻多行更新不漏、已见行不重发。
 *
 * 水位线由调用方（传输层）持久化到 AppPreferences.syncWatermarks，
 * 引擎只在 [SyncWatermarks] 内存实例上推进。
 *
 * LWW 比较用**含墓碑**的本地行：本地行已软删（deletedAt>0）时远端旧行必须输，
 * 否则远端一个过期快照就能把本地删除「复活」。
 */
class SyncEngine(private val db: AppDatabase) {

    private val ledgerDao = db.ledgerDao()
    private val categoryDao = db.categoryDao()
    private val projectDao = db.projectDao()
    private val accountDao = db.accountDao()
    private val recurringDao = db.recurringDao()
    private val goalDao = db.goalDao()
    private val txnDao = db.transactionDao()

    /** 收集本机待上传增量（每表 `updatedAt > watermark`，含墓碑，排除 auto 账本） */
    suspend fun collectOutgoing(watermarks: SyncWatermarks): List<SyncRow> {
        val rows = ArrayList<SyncRow>()
        rows += ledgerDao.incrementalSince(watermarks.get(TableNames.LEDGERS))
            .map { EntityCodec.ledgerToRow(it) }
        rows += categoryDao.incrementalSince(watermarks.get(TableNames.CATEGORIES))
            .map { EntityCodec.categoryToRow(it) }
        rows += projectDao.incrementalSince(watermarks.get(TableNames.PROJECTS))
            .map { EntityCodec.projectToRow(it) }
        rows += accountDao.incrementalSince(watermarks.get(TableNames.ACCOUNTS))
            .map { EntityCodec.accountToRow(it) }
        rows += recurringDao.incrementalSince(watermarks.get(TableNames.RECURRING))
            .map { EntityCodec.recurringToRow(it) }
        rows += goalDao.incrementalSince(watermarks.get(TableNames.GOALS))
            .map { EntityCodec.goalToRow(it) }
        rows += txnDao.incrementalSince(watermarks.get(TableNames.TRANSACTIONS))
            .map { EntityCodec.txnToRow(it) }
        return rows
    }

    /**
     * 应用远端增量：按依赖表序 LWW 合并，成功后推进水位线。
     *
     * 防御性过滤：ledgers 行的 `sceneKey=="auto"` 直接丢；其余表行若挂在
     * auto 账本下（含其墓碑）也丢 —— 正常流程两端都过滤，这里兜底防旧版本/服务器异常。
     */
    suspend fun applyIncoming(rows: List<SyncRow>, watermarks: SyncWatermarks): SyncApplyStats {
        val autoIds = ledgerDao.autoLedgerIds().toSet()
        var inserted = 0
        var updated = 0
        var localWins = 0
        var skipped = 0
        // 冲突明细：只在 localWins 分支里顺手记一条，不额外查库
        val conflicts = ArrayList<SyncConflict>()

        // 先按表分组（保持组内原始顺序），再按依赖表序应用：FK 引用必须先落父行
        val byTable = rows.groupBy { it.table }
        for (table in TableNames.ALL) {
            val tableRows = byTable[table] ?: continue
            for (r in tableRows) {
                if (!passesAutoFilter(r, autoIds)) {
                    skipped++
                    watermarks.advance(table, r.updatedAt + 1L)
                    continue
                }
                when (table) {
                    TableNames.LEDGERS -> {
                        val remote = EntityCodec.rowToLedger(r)
                        val local = ledgerDao.getById(remote.id)
                        if (local == null || Lww.aWins(remote.updatedAt, remote.updatedByDeviceId, local.updatedAt, local.updatedByDeviceId)) {
                            ledgerDao.upsert(remote)
                            if (local == null) inserted++ else updated++
                        } else {
                            localWins++
                            if (conflicts.size < SyncLogStore.MAX_CONFLICTS) {
                                conflicts.add(
                                    SyncConflict(
                                        table = table,
                                        id = remote.id,
                                        localUpdatedAt = local.updatedAt,
                                        remoteUpdatedAt = remote.updatedAt,
                                        remoteByDevice = remote.updatedByDeviceId
                                    )
                                )
                            }
                        }
                    }
                    TableNames.CATEGORIES -> {
                        val remote = EntityCodec.rowToCategory(r)
                        val local = categoryDao.getByIdIncludingDeleted(remote.id)
                        if (local == null || Lww.aWins(remote.updatedAt, remote.updatedByDeviceId, local.updatedAt, local.updatedByDeviceId)) {
                            // (ledgerId, direction, name) 唯一索引：撞索引保留本地（内容级冲突，本地优先）
                            if (upsertCategorySafe(remote)) {
                                if (local == null) inserted++ else updated++
                            } else skipped++
                        } else {
                            localWins++
                            if (conflicts.size < SyncLogStore.MAX_CONFLICTS) {
                                conflicts.add(
                                    SyncConflict(
                                        table = table,
                                        id = remote.id,
                                        localUpdatedAt = local.updatedAt,
                                        remoteUpdatedAt = remote.updatedAt,
                                        remoteByDevice = remote.updatedByDeviceId
                                    )
                                )
                            }
                        }
                    }
                    TableNames.PROJECTS -> {
                        val remote = EntityCodec.rowToProject(r)
                        val local = projectDao.getByIdIncludingDeleted(remote.id)
                        if (local == null || Lww.aWins(remote.updatedAt, remote.updatedByDeviceId, local.updatedAt, local.updatedByDeviceId)) {
                            projectDao.upsert(remote)
                            if (local == null) inserted++ else updated++
                        } else {
                            localWins++
                            if (conflicts.size < SyncLogStore.MAX_CONFLICTS) {
                                conflicts.add(
                                    SyncConflict(
                                        table = table,
                                        id = remote.id,
                                        localUpdatedAt = local.updatedAt,
                                        remoteUpdatedAt = remote.updatedAt,
                                        remoteByDevice = remote.updatedByDeviceId
                                    )
                                )
                            }
                        }
                    }
                    TableNames.ACCOUNTS -> {
                        val remote = EntityCodec.rowToAccount(r)
                        val local = accountDao.getByIdIncludingDeleted(remote.id)
                        if (local == null || Lww.aWins(remote.updatedAt, remote.updatedByDeviceId, local.updatedAt, local.updatedByDeviceId)) {
                            accountDao.upsert(remote)
                            if (local == null) inserted++ else updated++
                        } else {
                            localWins++
                            if (conflicts.size < SyncLogStore.MAX_CONFLICTS) {
                                conflicts.add(
                                    SyncConflict(
                                        table = table,
                                        id = remote.id,
                                        localUpdatedAt = local.updatedAt,
                                        remoteUpdatedAt = remote.updatedAt,
                                        remoteByDevice = remote.updatedByDeviceId
                                    )
                                )
                            }
                        }
                    }
                    TableNames.RECURRING -> {
                        val remote = EntityCodec.rowToRecurring(r)
                        val local = recurringDao.getByIdIncludingDeleted(remote.id)
                        if (local == null || Lww.aWins(remote.updatedAt, remote.updatedByDeviceId, local.updatedAt, local.updatedByDeviceId)) {
                            recurringDao.upsert(remote)
                            if (local == null) inserted++ else updated++
                        } else {
                            localWins++
                            if (conflicts.size < SyncLogStore.MAX_CONFLICTS) {
                                conflicts.add(
                                    SyncConflict(
                                        table = table,
                                        id = remote.id,
                                        localUpdatedAt = local.updatedAt,
                                        remoteUpdatedAt = remote.updatedAt,
                                        remoteByDevice = remote.updatedByDeviceId
                                    )
                                )
                            }
                        }
                    }
                    TableNames.GOALS -> {
                        val remote = EntityCodec.rowToGoal(r)
                        val local = goalDao.getByIdIncludingDeleted(remote.id)
                        if (local == null || Lww.aWins(remote.updatedAt, remote.updatedByDeviceId, local.updatedAt, local.updatedByDeviceId)) {
                            goalDao.upsert(remote)
                            if (local == null) inserted++ else updated++
                        } else {
                            localWins++
                            if (conflicts.size < SyncLogStore.MAX_CONFLICTS) {
                                conflicts.add(
                                    SyncConflict(
                                        table = table,
                                        id = remote.id,
                                        localUpdatedAt = local.updatedAt,
                                        remoteUpdatedAt = remote.updatedAt,
                                        remoteByDevice = remote.updatedByDeviceId
                                    )
                                )
                            }
                        }
                    }
                    TableNames.TRANSACTIONS -> {
                        val remote = EntityCodec.rowToTxn(r)
                        val local = txnDao.getByIdIncludingDeleted(remote.id)
                        if (local == null || Lww.aWins(remote.updatedAt, remote.updatedByDeviceId, local.updatedAt, local.updatedByDeviceId)) {
                            txnDao.upsert(remote)
                            if (local == null) inserted++ else updated++
                        } else {
                            localWins++
                            if (conflicts.size < SyncLogStore.MAX_CONFLICTS) {
                                conflicts.add(
                                    SyncConflict(
                                        table = table,
                                        id = remote.id,
                                        localUpdatedAt = local.updatedAt,
                                        remoteUpdatedAt = remote.updatedAt,
                                        remoteByDevice = remote.updatedByDeviceId
                                    )
                                )
                            }
                        }
                    }
                    else -> skipped++ // 未知表（未来版本），防御跳过
                }
                watermarks.advance(table, r.updatedAt + 1L)
            }
        }
        return SyncApplyStats(inserted, updated, localWins, skipped, conflicts)
    }

    private fun passesAutoFilter(r: SyncRow, autoIds: Set<Long>): Boolean = try {
        when (r.table) {
            TableNames.LEDGERS -> EntityCodec.rowToLedger(r).sceneKey != ScenePresets.AUTO_KEY
            else -> {
                // 只读 ledgerId 字段做过滤，避免为判断多解一次全实体
                val payload = JSONObject(r.payload)
                val ledgerId = if (payload.isNull("ledgerId")) null else payload.optLong("ledgerId", 0L)
                ledgerId == null || ledgerId !in autoIds
            }
        }
    } catch (_: Exception) {
        true // 解码失败的坏行放行到 LWW 分支，那里会再次解码失败被 skip 兜住
    }

    /** 分类唯一索引冲突（双端各自建过同名分类的历史窗口）时保留本地行 */
    private suspend fun upsertCategorySafe(c: com.foldledger.data.db.CategoryEntity): Boolean = try {
        categoryDao.upsert(c)
        true
    } catch (_: Exception) {
        false
    }
}
