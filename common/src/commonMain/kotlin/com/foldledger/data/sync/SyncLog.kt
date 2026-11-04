package com.foldledger.data.sync

import org.json.JSONArray
import org.json.JSONObject

/**
 * 一次同步里被 LWW 判为"本地赢"的冲突行（v16.2.8）。
 *
 * 为什么要留明细：同步卡以前只会说"新增 3 / 更新 1"，可用户真正想知道的是
 * **"我手机上改的那笔为什么没过去"** —— 那必须落到"哪张表、哪条 id、两边各是什么时间戳"。
 */
data class SyncConflict(
    val table: String,
    val id: Long,
    val localUpdatedAt: Long,
    val remoteUpdatedAt: Long,
    val remoteByDevice: Long
)

/**
 * 一次同步的完整记录（v16.2.8）。
 *
 * 只存在 prefs 的一个 JSON 字符串里（环形缓冲，最多 [SyncLogStore.LIMIT] 条），
 * **不建表**：同步日志不是业务数据，没必要为它动 Room schema（迁移代价远大于收益），
 * 而且它天然是"最近几次"的性质，历史全量留着也没人看。
 */
data class SyncLogEntry(
    /** 完成时间（毫秒） */
    val at: Long,
    val ok: Boolean,
    /** 触发方式：手动 / 自动 / 后台 / 注册 */
    val trigger: String,
    val upRows: Int,
    val downRows: Int,
    val inserted: Int,
    val updated: Int,
    val localWins: Int,
    val skipped: Int,
    val durationMs: Long,
    val error: String? = null,
    val conflicts: List<SyncConflict> = emptyList()
)

/** 冲突/日志的持久化编解码（JSON 数组，解析失败一律当空，绝不因为日志坏了影响同步） */
object SyncLogStore {

    /** 最多保留多少条 */
    const val LIMIT = 20

    /** 单条里最多记多少冲突明细，防止一条日志撑爆 prefs */
    const val MAX_CONFLICTS = 12

    fun encode(entries: List<SyncLogEntry>): String {
        val arr = JSONArray()
        entries.take(LIMIT).forEach { e ->
            val o = JSONObject()
            o.put("at", e.at)
            o.put("ok", e.ok)
            o.put("trigger", e.trigger)
            o.put("up", e.upRows)
            o.put("down", e.downRows)
            o.put("ins", e.inserted)
            o.put("upd", e.updated)
            o.put("win", e.localWins)
            o.put("skip", e.skipped)
            o.put("dur", e.durationMs)
            e.error?.let { o.put("err", it) }
            if (e.conflicts.isNotEmpty()) {
                val cs = JSONArray()
                e.conflicts.take(MAX_CONFLICTS).forEach { c ->
                    val co = JSONObject()
                    co.put("t", c.table)
                    co.put("id", c.id)
                    co.put("la", c.localUpdatedAt)
                    co.put("ra", c.remoteUpdatedAt)
                    co.put("rd", c.remoteByDevice)
                    cs.put(co)
                }
                o.put("cf", cs)
            }
            arr.put(o)
        }
        return arr.toString()
    }

    fun decode(raw: String?): List<SyncLogEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val conflicts = buildList {
                        val cs = o.optJSONArray("cf") ?: JSONArray()
                        for (j in 0 until cs.length()) {
                            val c = cs.optJSONObject(j) ?: continue
                            add(
                                SyncConflict(
                                    table = c.optString("t"),
                                    id = c.optLong("id"),
                                    localUpdatedAt = c.optLong("la"),
                                    remoteUpdatedAt = c.optLong("ra"),
                                    remoteByDevice = c.optLong("rd")
                                )
                            )
                        }
                    }
                    add(
                        SyncLogEntry(
                            at = o.optLong("at"),
                            ok = o.optBoolean("ok"),
                            trigger = o.optString("trigger"),
                            upRows = o.optInt("up"),
                            downRows = o.optInt("down"),
                            inserted = o.optInt("ins"),
                            updated = o.optInt("upd"),
                            localWins = o.optInt("win"),
                            skipped = o.optInt("skip"),
                            durationMs = o.optLong("dur"),
                            error = o.optString("err").takeIf { it.isNotBlank() },
                            conflicts = conflicts
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    /**
     * 失败原因 → 下一步该干什么。
     *
     * 这层的价值全在"可执行"：只说"401"等于没说，用户要的是"去改哪一个输入框"。
     */
    fun hintFor(error: String?): String? {
        val e = error ?: return null
        return when {
            e.contains("401") || e.contains("密钥") ->
                "去同步卡的「访问密钥」核对，和服务端 --token / SYNC_TOKEN 保持一致"
            e.contains("404") ->
                "地址应形如 http://域名:端口，不要带路径；确认服务端已启动"
            e.contains("域名") || e.contains("UnknownHost") ->
                "域名解析失败：确认 NAS 在线、Tailscale/内网是否连通"
            e.contains("超时") ->
                "网络慢或服务端没响应：稍后重试；确认 NAS 没有被休眠"
            e.contains("拒绝") || e.contains("连不上") ->
                "服务端没在跑或端口不通：检查容器状态与端口映射"
            e.contains("注册") ->
                "点一次「注册此设备」再同步"
            e.contains("地址") ->
                "先填写服务器地址，形如 http://域名:端口"
            else -> null
        }
    }
}
