package com.foldledger.util

/**
 * 分类建议（v8.6）——**从用户自己的历史里学**。
 *
 * 关键词表只能覆盖"星巴克=餐饮"这种通用常识；真正贴身的线索在历史里：
 * 用户上次把"星巴克"记在「餐饮」、把"公司团建"记在「人情往来」——
 * 同样的词下次就该还给同样的分类，不该让人再选一次。
 *
 * 规则极简：**当前输入里出现了历史备注/分类名，就投它一票**，票多的赢；
 * 平票取最近的一笔。全程本地统计，不联网。
 */
object CategorySuggester {

    /** 一条历史样本（流水 + 分类） */
    data class Sample(
        val note: String,
        val categoryName: String,
        val amountCents: Long,
        val occurredAt: Long
    )

    /**
     * 给一段输入建议分类名。
     *
     * @param minKeyLen 参与匹配的最短关键词长度（太短的词容易误伤）
     * @return 建议的分类名；没有历史依据返回 null（让人自己选）
     */
    fun suggest(
        text: String,
        samples: List<Sample>,
        minKeyLen: Int = 2
    ): String? {
        val input = text.trim()
        if (input.isEmpty() || samples.isEmpty()) return null

        val votes = HashMap<String, Int>()
        val latest = HashMap<String, Long>()

        for (s in samples) {
            val category = s.categoryName.trim()
            if (category.isEmpty()) continue
            var hit = false
            for (key in keysOf(s, minKeyLen)) {
                if (key.isNotEmpty() && input.contains(key)) {
                    hit = true
                    break
                }
            }
            if (!hit) continue
            votes[category] = (votes[category] ?: 0) + 1
            val prev = latest[category] ?: Long.MIN_VALUE
            if (s.occurredAt > prev) latest[category] = s.occurredAt
        }
        if (votes.isEmpty()) return null

        // 票数并列时取最近的一笔
        return votes.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }
                .thenByDescending { latest[it.key] ?: Long.MIN_VALUE })
            .first()
            .key
    }

    /** 一条样本能提供的匹配关键词：备注（去掉标点/空白）+ 分类名 */
    private fun keysOf(s: Sample, minKeyLen: Int): List<String> {
        val out = ArrayList<String>(2)
        val note = s.note.trim()
            .replace(Regex("[\\s，。,.、；;：:！!？?（）()【】\\[\\]]"), "")
        if (note.length >= minKeyLen) out += note
        if (s.categoryName.trim().length >= minKeyLen) out += s.categoryName.trim()
        return out
    }
}
