package com.foldledger.ui.vm

import com.foldledger.data.FieldKind
import com.foldledger.data.SceneField
import com.foldledger.data.ScenePreset
import com.foldledger.data.db.Direction
import com.foldledger.data.db.TransactionEntity
import com.foldledger.data.repo.LedgerRepository
import com.foldledger.ui.l10n.t
import com.foldledger.util.Money

/**
 * 场景字段看板（v17）—— 把一个 CHOICE 场景字段的各取值聚合成「笔数 / 金额」。
 *
 * 报销账本之所以好用，是因为它把 `state` 这个字段做成了看板 → 催办 → 归档的闭环。
 * 其它场景（家装的「空间/阶段」、房屋的「周期」、母婴的「宝宝」、日常的「支付方式」）
 * 以前只在流水副标题里露个脸。这里把同一套「字段 → 汇总 → 点选筛选」泛化给所有场景。
 *
 * ⚠️ 零 DB 改动：完全在内存里 decode [TransactionEntity.extraJson] 聚合，
 * 不加速度列、不改 Room schema、不动备份格式（铁律 2 / 3）。
 */
data class SceneFieldValueStat(
    /** 字段原始 value（如 "living" / "wechat"），筛选时用它比对 extra */
    val value: String,
    /** 展示文案（如「客厅」/「微信」），来自 [SceneField.options] */
    val label: String,
    val count: Int,
    val cents: Long
)

data class SceneFieldStat(
    val field: SceneField,
    /** 按金额降序；未落在 options 里的取值以原值作 label 排在末尾，不丢数据 */
    val values: List<SceneFieldValueStat>
) {
    val totalCount: Int get() = values.sumOf { it.count }
    val totalCents: Long get() = values.sumOf { it.cents }
}

/**
 * 从流水里聚出可看板的 CHOICE 字段。
 *
 * 规则（与报销/项目看板口径一致）：
 * - 只取 [FieldKind.CHOICE] 字段；默认排除 `state`（报销走它自己的看板，避免重复）；
 * - 只统计**支出**方向 —— 场景字段回答的是「钱花在哪」，收入不参与；
 * - 排除回收站（[TransactionEntity.deletedAt] != 0）；
 * - 字段值为空的流水不计；
 * - 结果按字段在场景里的定义顺序返回，每个字段内按金额降序。
 *
 * @param decodeExtra 注入以便纯 JVM 单测，无需 Android / JSONObject 环境。
 */
fun summarizeSceneFields(
    txns: List<TransactionEntity>,
    scene: ScenePreset,
    excludeFieldKeys: Set<String> = setOf("state"),
    decodeExtra: (String) -> Map<String, String> = { LedgerRepository.decodeExtra(it) }
): List<SceneFieldStat> {
    val choices = scene.fields.filter {
        it.kind == FieldKind.CHOICE && it.key !in excludeFieldKeys
    }
    if (choices.isEmpty()) return emptyList()

    val expense = txns.filter { it.direction == Direction.EXPENSE && it.deletedAt == 0L }
    if (expense.isEmpty()) return emptyList()

    val decoded: List<Map<String, String>> = expense.map { decodeExtra(it.extraJson) }

    return choices.mapNotNull { field ->
        // value -> (count, cents)
        val acc = HashMap<String, Pair<Int, Long>>()
        for (i in expense.indices) {
            val v = decoded[i][field.key]?.takeIf { it.isNotBlank() } ?: continue
            val (c, s) = acc[v] ?: (0 to 0L)
            acc[v] = (c + 1) to (s + expense[i].amountCents)
        }
        if (acc.isEmpty()) return@mapNotNull null

        val known = field.options.map { it.value }.toSet()
        val values = field.options.mapNotNull { opt ->
            acc[opt.value]?.let { (c, s) -> SceneFieldValueStat(opt.value, opt.label, c, s) }
        }.toMutableList()
        // 历史上换过场景、或自定义值：options 里没有，也别把这笔钱藏起来
        acc.forEach { (v, pair) ->
            if (v !in known) values += SceneFieldValueStat(v, v, pair.first, pair.second)
        }
        values.sortByDescending { it.cents }
        SceneFieldStat(field, values)
    }
}

/**
 * 概览卡主角数字下的那行场景摘要（v17 第三档）。
 *
 * 为什么不换主角数字：报销的「尚余垫付」是三栏里没有的独有数字；其它场景换成
 * 「总支出」会和下方三栏的「总支出」重复（v12.2 去重修过同样的坑），换「预算剩余」
 * 又会撞上月预算口径错位（20 万建议值塞在月预算字段）。所以保留一致的「累计结余」，
 * 只让这行摘要说出该场景真正关心的数。
 *
 * 纯函数返回中文原文，调用处过 t()；未命中字典回退中文。
 */
fun sceneOverviewNote(
    sceneKey: String,
    monthExpense: Long,
    monthIncome: Long,
    totalExpense: Long,
    budgetCents: Long
): String {
    val spent = Money.format(if (sceneKey == "travel" || sceneKey == "renovation") totalExpense else monthExpense)
    return when (sceneKey) {
        // 一次性账本：这趟 / 这次累计花了多少
        "travel" -> "${t("这趟花费")} $spent"
        "renovation" ->
            if (budgetCents > 0L) "${t("累计投入")} $spent · ${t("预算已用")} ${(totalExpense * 100 / budgetCents).coerceAtMost(999)}%"
            else "${t("累计投入")} $spent"
        // 长期账本：本月这块的花费
        "car" -> "${t("本月养车")} $spent"
        "housing" -> "${t("本月居住")} $spent"
        "baby" -> "${t("本月育儿")} $spent"
        "pet" -> "${t("本月养宠")} $spent"
        // 日常 / 自定义 / 自动记账：保持原口径，零回归
        else -> "${t("本月支出")} ${Money.format(monthExpense)} · ${t("收入")} ${Money.format(monthIncome)}"
    }
}

/**
 * 当前选中的场景字段筛选。存原始 [value] 比对 extra，[label] 存中文原文、显示时再过 t()，
 * 这样切换语言时筛选条文案会跟着刷新（enum 常量里存 t() 结果的坑不能踩）。
 */
data class SceneFieldSelection(
    val key: String,
    val value: String,
    val label: String
)

/**
 * 递增读数字段（里程 / 抄表）的「上次值」：从该账本流水里按发生时间取最近一笔填过的值。
 *
 * 内部显式按 occurredAt 降序，不依赖上游 DAO 的顺序（个人记账几千条，排序零延迟），
 * 符合项目「搜索/聚合不拼 SQL、内存处理」的一贯原则。已删除的不算。
 *
 * @param decodeExtra 注入以便纯 JVM 单测。
 */
fun lastCarryOverValues(
    txns: List<TransactionEntity>,
    scene: ScenePreset,
    decodeExtra: (String) -> Map<String, String> = { LedgerRepository.decodeExtra(it) }
): Map<String, String> {
    val keys = scene.fields.filter { it.carryOver }.map { it.key }.toSet()
    if (keys.isEmpty()) return emptyMap()
    val result = HashMap<String, String>()
    val ordered = txns.sortedByDescending { it.occurredAt }
    for (txn in ordered) {
        if (result.size == keys.size) break
        if (txn.deletedAt != 0L) continue
        val extra = decodeExtra(txn.extraJson)
        keys.forEach { k ->
            if (!result.containsKey(k)) extra[k]?.takeIf { it.isNotBlank() }?.let { result[k] = it }
        }
    }
    return result
}
