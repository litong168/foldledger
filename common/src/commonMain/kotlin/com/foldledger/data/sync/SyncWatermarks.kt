package com.foldledger.data.sync

/**
 * 每设备每表的水位线：上次成功同步到的 max(updatedAt) **+ 1**。
 *
 * 推进到 max+1 配合服务端 `updatedAt >= wm` 的增量语义：
 * 已同步行（updatedAt <= max < wm）绝不重发，同一毫秒的多行更新（批量导入、
 * 连续操作同戳）也绝不会被严格大于挡在门外。
 *
 * 序列化格式与 AppPreferences.syncWatermarks 一致：
 * `表名=毫秒` 每行一条，空串 = 从未同步（首同步全量上传）。
 * 只持久化 > 0 的条目，0 即「从未同步」，不占存储。
 */
class SyncWatermarks(raw: String = "") {

    private val map = HashMap<String, Long>()

    init {
        raw.lineSequence().forEach { line ->
            val eq = line.indexOf('=')
            if (eq > 0) {
                val key = line.substring(0, eq)
                val v = line.substring(eq + 1).toLongOrNull()
                if (v != null && v > 0L) map[key] = v
            }
        }
    }

    fun get(table: String): Long = map[table] ?: 0L

    fun set(table: String, wm: Long) {
        if (wm > 0L) map[table] = wm
    }

    /** 推进到 max(当前, 新值) —— 被 LWW 拒绝的行也计入，否则会无限重收 */
    fun advance(table: String, rowUpdatedAt: Long) {
        val cur = get(table)
        if (rowUpdatedAt > cur) set(table, rowUpdatedAt)
    }

    fun toRawString(): String =
        map.entries.sortedBy { it.key }
            .joinToString("\n") { "${it.key}=${it.value}" }

    fun isEmpty(): Boolean = map.isEmpty()
}
