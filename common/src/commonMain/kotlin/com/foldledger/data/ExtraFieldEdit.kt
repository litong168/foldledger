package com.foldledger.data

import com.foldledger.data.db.TransactionEntity
import com.foldledger.data.repo.LedgerRepository

/**
 * 返回把 [key] 场景字段改成 [value] 后的流水副本（v17 行内快捷改）。
 *
 * - [value] 为 null 或空 = 删除该字段（点已选档位取消）；
 * - 只动 extraJson，其余字段、报销状态、项目归属一律不碰；
 * - 时间戳由调用方 repo 统一 touched()，这里保持纯函数便于单测。
 *
 * decode/encode 注入以免依赖 JSONObject 环境。
 */
fun withExtraField(
    txn: TransactionEntity,
    key: String,
    value: String?,
    decodeExtra: (String) -> Map<String, String> = { LedgerRepository.decodeExtra(it) },
    encodeExtra: (Map<String, String>) -> String = { LedgerRepository.encodeExtra(it) }
): TransactionEntity {
    val extra = decodeExtra(txn.extraJson).toMutableMap()
    if (value.isNullOrBlank()) extra.remove(key) else extra[key] = value
    return txn.copy(extraJson = encodeExtra(extra))
}
