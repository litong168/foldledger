package com.foldledger.data.sync

/**
 * M4 同步传输行：一条记录的完整状态（含墓碑）。
 *
 * [payload] 是实体全字段的 JSON（[EntityCodec] 编解码），header 字段用于
 * LWW 裁决与水位线推进。服务端按 (table, id) 为身份做 LWW upsert。
 */
data class SyncRow(
    val table: String,
    val id: Long,
    val updatedAt: Long,
    val updatedByDeviceId: Long,
    /** >0 = 墓碑（软删），远端据此删行 */
    val deletedAt: Long,
    val payload: String
)

/** 表名常量：Room 实体 tableName 与水位线/服务端存储的键一致 */
object TableNames {
    const val LEDGERS = "ledgers"
    const val CATEGORIES = "categories"
    const val TRANSACTIONS = "transactions"
    const val PROJECTS = "projects"
    const val ACCOUNTS = "accounts"
    const val RECURRING = "recurring"
    const val GOALS = "goals"

    /** 应用顺序：先父后子（FK 依赖），watermark 与 collect 按此遍历 */
    val ALL = listOf(LEDGERS, CATEGORIES, PROJECTS, ACCOUNTS, RECURRING, GOALS, TRANSACTIONS)
}
