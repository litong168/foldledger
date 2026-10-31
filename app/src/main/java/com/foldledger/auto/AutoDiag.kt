package com.foldledger.auto

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 自动记账捕获日志（v10.6）。
 *
 * 为什么需要它：自动记账靠「猜」——通知文本格式千差万别，
 * 用户反馈"有时候记、有时候不记、营销短信又混进来"时，
 * 没有原始文本就只能盲改关键词（前几轮就是这么来回打补丁的）。
 *
 * 这里把每条**被捕获到的**通知/短信连同判定结果记下来：
 *  - 记了：金额、方向
 *  - 没记：拒绝原因（营销词/无成交动词/无金额/方向不明/重复）
 *
 * 用户在「我的 → 更多设置 → 自动记账 → 捕获日志」能看到最近 100 条，
 * 直接看到"这条银行为什么没记"，也能一眼发现"这条营销为什么记了"。
 *
 * 存储：App 私有目录 `files/auto_diag.jsonl`，单行 JSON，最多 150 条。
 * 纯本地，不上传。
 */
object AutoDiag {

    private const val FILE_NAME = "auto_diag.jsonl"
    private const val MAX_LINES = 200

    data class Entry(
        val at: Long,
        /** 渠道：通知 / 短信 */
        val channel: String,
        /** 来源包名（短信为空） */
        val pkg: String,
        val title: String,
        val text: String,
        /** 是否入账 */
        val accepted: Boolean,
        /** 入账金额（分），未入账为 null */
        val amountCents: Long?,
        /** 入账方向，未入账为 null */
        val direction: Int?,
        /** 拒绝原因 / 来源名 */
        val note: String,
        /**
         * v12.1：这条记录写进账本后的流水 id。
         *
         * 有它才能在日志里**直接撤销**这条错误记账 —— 否则用户看到"记错了"
         * 还得自己翻到明细页里找出那一条删掉。0/null = 没入账或旧版本记录。
         */
        val txnId: Long? = null
    )

    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA)

    fun formatTime(at: Long): String = fmt.format(Date(at))

    /** 记一条（幂等失败不影响主流程） */
    fun record(context: Context, entry: Entry) {
        runCatching {
            val file = file(context)
            val line = encode(entry)
            // 追加
            file.appendText(line + "\n")
            // 超限就裁剪（保留最后 MAX_LINES 条）
            val lines = file.readLines()
            if (lines.size > MAX_LINES) {
                file.writeText(lines.takeLast(MAX_LINES).joinToString("\n") + "\n")
            }
        }
    }

    /** 最近 N 条，按时间倒序（最新在前） */
    fun recent(context: Context, limit: Int = 100): List<Entry> =
        runCatching {
            file(context).readLines()
                .mapNotNull { decode(it) }
                .takeLast(limit)
                .reversed()
        }.getOrElse { emptyList() }

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }

    /**
     * v12.1：删掉一条（撤销错误记账后用）。
     *
     * 按 `时间 + 来源包名 + 判定说明` 定位第一条匹配 —— 这三个字段组合足够唯一，
     * 又不依赖日志里有稳定主键。
     */
    fun remove(context: Context, entry: Entry) {
        runCatching {
            val f = file(context)
            if (!f.exists()) return
            var removed = false
            val kept = f.readLines().filterNot { line ->
                if (removed) return@filterNot false
                val e = decode(line)
                val hit = e != null &&
                    e.at == entry.at &&
                    e.pkg == entry.pkg &&
                    e.note == entry.note
                if (hit) removed = true
                hit
            }
            f.writeText(kept.joinToString("\n").trimEnd() + "\n")
        }
    }

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    private fun encode(e: Entry): String = JSONObject().apply {
        put("at", e.at)
        put("ch", e.channel)
        put("pkg", e.pkg)
        put("title", e.title.take(80))
        put("text", e.text.take(200))
        put("ok", e.accepted)
        e.amountCents?.let { put("cents", it) }
        e.direction?.let { put("dir", it) }
        e.txnId?.let { put("txn", it) }
        put("note", e.note.take(60))
    }.toString()

    private fun decode(line: String): Entry? = runCatching {
        if (line.isBlank()) return@runCatching null
        val o = JSONObject(line)
        Entry(
            at = o.optLong("at"),
            channel = o.optString("ch"),
            pkg = o.optString("pkg"),
            title = o.optString("title"),
            text = o.optString("text"),
            accepted = o.optBoolean("ok"),
            amountCents = if (o.has("cents")) o.optLong("cents") else null,
            direction = if (o.has("dir")) o.optInt("dir") else null,
            note = o.optString("note"),
            txnId = if (o.has("txn")) o.optLong("txn").takeIf { it > 0 } else null
        )
    }.getOrNull()
}
