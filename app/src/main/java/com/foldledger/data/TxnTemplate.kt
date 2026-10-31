package com.foldledger.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * 常用记账模板（v2.1）。
 *
 * 一笔常用账的预设：金额 + 分类 + 备注。存 DataStore（不建表、不碰数据库 schema），
 * 应用时把分类名当主键匹配当前账本分类 —— 模板可以跨账本复用，分类删了也只是退化成
 * 「只填名称不挂 id」。
 */
data class TxnTemplate(
    val id: Long,
    val name: String,
    val direction: Int,
    val categoryName: String,
    val categoryEmoji: String,
    val amountCents: Long,
    val note: String
)

object TxnTemplateCodec {

    fun encode(list: List<TxnTemplate>): String {
        val arr = JSONArray()
        list.forEach { t ->
            arr.put(
                JSONObject().apply {
                    put("id", t.id)
                    put("name", t.name)
                    put("direction", t.direction)
                    put("categoryName", t.categoryName)
                    put("categoryEmoji", t.categoryEmoji)
                    put("amountCents", t.amountCents)
                    put("note", t.note)
                }
            )
        }
        return arr.toString()
    }

    fun decode(json: String): List<TxnTemplate> {
        if (json.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    add(
                        TxnTemplate(
                            id = o.optLong("id"),
                            name = o.optString("name"),
                            direction = o.optInt("direction"),
                            categoryName = o.optString("categoryName"),
                            categoryEmoji = o.optString("categoryEmoji"),
                            amountCents = o.optLong("amountCents"),
                            note = o.optString("note")
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
