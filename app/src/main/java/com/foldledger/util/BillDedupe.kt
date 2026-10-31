package com.foldledger.util

/**
 * 账单导入的去重判定（v9.7.1）。
 *
 * ## 为什么键里不能带"对方"
 *
 * 账单里的字段叫**交易对方**（星巴克），库里的流水字段是**分类名**（餐饮）——
 * 两者口径不同，拿它们拼键永远对不上。所以键只取三样：
 * **同分钟 + 同金额 + 同方向**。这三样撞齐，在同一账本里基本就是同一笔。
 *
 * ## 为什么要按"多重性"数
 *
 * 账单里本来就可能有两笔完全一样的（同一分钟买了两个同样的东西）。
 * 库里已有 1 笔、账单里 2 笔 → 只该补 1 笔，不能全跳过、也不能全导入。
 */
object BillDedupe {

    /** 去重键 */
    data class Key(val minute: Long, val amountCents: Long, val direction: Int)

    fun keyOf(time: Long, amountCents: Long, direction: Int): Key =
        Key(time / 60_000L, amountCents, direction)

    /**
     * 逐行判断"这一笔库里是否已经有了"。
     *
     * @param incoming 待导入的行（按账单顺序）
     * @param existing 库里已有的流水
     * @return 与 incoming 等长的布尔表，true = 已存在（跳过）
     */
    fun duplicates(incoming: List<Key>, existing: List<Key>): List<Boolean> {
        if (incoming.isEmpty()) return emptyList()
        // 库里每个键出现几次
        val dbCounts = HashMap<Key, Int>(existing.size * 2)
        existing.forEach { k -> dbCounts[k] = (dbCounts[k] ?: 0) + 1 }
        // 本次已经"抵扣"掉几笔
        val consumed = HashMap<Key, Int>(incoming.size * 2)
        return incoming.map { k ->
            val already = dbCounts[k] ?: 0
            val used = consumed[k] ?: 0
            if (used < already) {
                consumed[k] = used + 1
                true
            } else {
                false
            }
        }
    }

    /** 重复的笔数（预览用） */
    fun duplicateCount(incoming: List<Key>, existing: List<Key>): Int =
        duplicates(incoming, existing).count { it }
}
