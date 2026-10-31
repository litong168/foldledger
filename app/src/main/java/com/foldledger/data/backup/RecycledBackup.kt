package com.foldledger.data.backup

import org.json.JSONArray
import org.json.JSONObject

/**
 * 回收站里的备份文件（v9.4）。
 *
 * 删除备份文件时，**不是直接抹掉**：先把内容搬进 App 内部目录
 * （`files/recycle/backup/`），再把原文件从备份目录删掉。
 * 后悔药在回收站里：还原 = 搬回备份目录；彻底铲除 = 删掉内部副本。
 *
 * 备份文件在 SAF 目录里（用户自己选的文件夹），App 不该擅自删用户的东西，
 * 所以"先备份再删"是底线 —— 万一用户后悔，文件还在。
 */
data class RecycledBackup(
    /** 原文件名（.foldledger.json） */
    val fileName: String,
    /** 删除时间 */
    val deletedAt: Long,
    /** 内部副本文件名（App 私有目录里的相对名） */
    val storedName: String,
    /** 同名的 .xlsx 是否也一起存了 */
    val hasXlsx: Boolean,
    /** 字节数 */
    val sizeBytes: Long,
    /**
     * 内部 .xlsx 副本的文件名（v9.7.2）。
     *
     * 老版本没存这个字段，还原时只能靠 `startsWith(时间戳)` 瞎猜 ——
     * 而备份文件名本身带日期（`滴水记账-2026-10-22.foldledger.json`），
     * 用 `substringBeforeLast("-")` 拼出来的路径基本拼不中。
     * 现在显式记下来；为 null 时才回退到旧的猜法。
     */
    val xlsxStoredName: String? = null
)

/** 回收站清单的 JSON 编解码（存在 DataStore 里，条目很少，够用） */
object RecycledBackupCodec {

    fun encode(list: List<RecycledBackup>): String {
        val arr = JSONArray()
        list.forEach { r ->
            arr.put(
                JSONObject().apply {
                    put("fileName", r.fileName)
                    put("deletedAt", r.deletedAt)
                    put("storedName", r.storedName)
                    put("hasXlsx", r.hasXlsx)
                    put("sizeBytes", r.sizeBytes)
                    // v9.7.2：显式记下 xlsx 内部副本名（老清单元此字段时还原走回退逻辑）
                    if (r.xlsxStoredName != null) put("xlsxStoredName", r.xlsxStoredName)
                }
            )
        }
        return arr.toString()
    }

    fun decode(raw: String): List<RecycledBackup> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                RecycledBackup(
                    fileName = o.optString("fileName"),
                    deletedAt = o.optLong("deletedAt"),
                    storedName = o.optString("storedName"),
                    hasXlsx = o.optBoolean("hasXlsx", false),
                    sizeBytes = o.optLong("sizeBytes", 0L),
                    xlsxStoredName = o.optString("xlsxStoredName", "").takeIf { it.isNotBlank() }
                )
            }.filter { it.fileName.isNotBlank() && it.storedName.isNotBlank() }
        }.getOrElse { emptyList() }
    }
}
