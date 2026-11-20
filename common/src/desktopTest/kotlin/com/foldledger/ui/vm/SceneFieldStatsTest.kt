package com.foldledger.ui.vm

import com.foldledger.data.FieldKind
import com.foldledger.data.SceneField
import com.foldledger.data.withExtraField
import com.foldledger.data.ScenePreset
import com.foldledger.data.db.Direction
import com.foldledger.data.db.TransactionEntity
import com.foldledger.data.repo.LedgerRepository
import com.foldledger.ui.l10n.L10n
import com.foldledger.ui.l10n.Language
import com.foldledger.util.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 场景字段看板（v17）的聚合规则。
 *
 * 报销账本把 state 字段做成了闭环，其它场景的 CHOICE 字段以前只是副标题。
 * 这里钉死泛化后的口径，避免以后改动把「钱花在哪」的语义改坏：
 * 只算支出、排除回收站、排除空值、按金额降序、不丢自定义值。
 */
class SceneFieldStatsTest {

    /** L10nTest 会把全局语言切到英文且不还原，这里显式复位避免测试顺序依赖 */
    @Before
    fun setUp() { L10n.current = Language.CHINESE }

    private val spaceField = SceneField(
        key = "space", label = "空间", kind = FieldKind.CHOICE,
        options = listOf(
            com.foldledger.data.FieldOption("living", "客厅"),
            com.foldledger.data.FieldOption("kitchen", "厨房"),
            com.foldledger.data.FieldOption("master", "主卧")
        )
    )
    private val stageField = SceneField(
        key = "stage", label = "阶段", kind = FieldKind.CHOICE,
        options = listOf(
            com.foldledger.data.FieldOption("hardcover", "硬装"),
            com.foldledger.data.FieldOption("softcover", "软装")
        )
    )
    private val stateField = SceneField(
        key = "state", label = "报销状态", kind = FieldKind.CHOICE,
        options = listOf(com.foldledger.data.FieldOption("0", "待提交"))
    )
    private val renoScene = ScenePreset(
        key = "renovation", name = "家装", emoji = "🛠️", tagline = "",
        colorHex = "#000000", expenses = emptyList(), incomes = emptyList(),
        fields = listOf(spaceField, stageField)
    )
    private val reimburseScene = ScenePreset(
        key = "reimbursement", name = "报销", emoji = "🧾", tagline = "",
        colorHex = "#000000", expenses = emptyList(), incomes = emptyList(),
        fields = listOf(stateField, spaceField)
    )

    private fun txn(
        cents: Long,
        extra: Map<String, String>,
        direction: Int = Direction.EXPENSE,
        deletedAt: Long = 0L
    ) = TransactionEntity(
        id = 0L, ledgerId = 1L, categoryId = null, amountCents = cents,
        occurredAt = 0L, extraJson = LedgerRepository.encodeExtra(extra),
        direction = direction, deletedAt = deletedAt, createdAt = 0L
    )

    @Test
    fun aggregatesChoiceFieldByValueWithCountAndAmount() {
        val txns = listOf(
            txn(10_000, mapOf("space" to "living")),
            txn(20_000, mapOf("space" to "living")),
            txn(5_000, mapOf("space" to "kitchen"))
        )
        val stats = summarizeSceneFields(txns, renoScene, decodeExtra = { LedgerRepository.decodeExtra(it) })
        assertEquals(1, stats.size)
        assertEquals("space", stats[0].field.key)
        assertEquals(2, stats[0].values.size)
        // 金额降序：客厅 300 > 厨房 50
        assertEquals("living", stats[0].values[0].value)
        assertEquals("客厅", stats[0].values[0].label)
        assertEquals(2, stats[0].values[0].count)
        assertEquals(30_000L, stats[0].values[0].cents)
        assertEquals(35_000L, stats[0].totalCents)
        assertEquals(3, stats[0].totalCount)
    }

    @Test
    fun ignoresIncomeDeletedAndBlankValues() {
        val txns = listOf(
            txn(10_000, mapOf("space" to "living"), direction = Direction.INCOME),
            txn(20_000, mapOf("space" to "master"), deletedAt = 123L),
            txn(30_000, mapOf("space" to "")),
            txn(40_000, mapOf("stage" to "hardcover"))
        )
        val stats = summarizeSceneFields(txns, renoScene, decodeExtra = { LedgerRepository.decodeExtra(it) })
        // 只有 stage 的硬装一笔有效；space 的三笔全被过滤 → 整个字段不出现
        assertEquals(1, stats.size)
        assertEquals("stage", stats[0].field.key)
        assertEquals(1, stats[0].totalCount)
        assertEquals(40_000L, stats[0].totalCents)
    }

    @Test
    fun excludesStateByDefaultButCanIncludeIt() {
        val txns = listOf(txn(9_000, mapOf("state" to "0", "space" to "living")))
        val defaultStats = summarizeSceneFields(txns, reimburseScene, decodeExtra = { LedgerRepository.decodeExtra(it) })
        assertEquals(1, defaultStats.size)
        assertEquals("space", defaultStats[0].field.key)

        val withState = summarizeSceneFields(
            txns, reimburseScene, excludeFieldKeys = emptySet(),
            decodeExtra = { LedgerRepository.decodeExtra(it) }
        )
        assertEquals(2, withState.size)
        assertEquals("state", withState[0].field.key)
    }

    @Test
    fun keepsUnknownValuesRatherThanDroppingMoney() {
        // 换过场景：extra 里是 options 之外的值，金额不能凭空消失
        val txns = listOf(txn(7_000, mapOf("space" to "balcony2")))
        val stats = summarizeSceneFields(txns, renoScene, decodeExtra = { LedgerRepository.decodeExtra(it) })
        assertEquals(1, stats.size)
        assertEquals(1, stats[0].values.size)
        assertEquals("balcony2", stats[0].values[0].label)
        assertEquals(7_000L, stats[0].values[0].cents)
    }

    @Test
    fun emptyWhenNoExpenseOrNoChoiceFields() {
        assertTrue(summarizeSceneFields(emptyList(), renoScene).isEmpty())
        val noChoice = renoScene.copy(fields = listOf(SceneField("note", "备注", FieldKind.TEXT)))
        assertTrue(summarizeSceneFields(listOf(txn(100, mapOf("note" to "x"))), noChoice).isEmpty())
    }

    // ---- 行内快捷改 v17 ----

    @Test
    fun withExtraFieldChangesOnlyTargetKey() {
        val original = txn(500, mapOf("space" to "living", "stage" to "hardcover"))
        val changed = withExtraField(original, "space", "kitchen")
        val extra = LedgerRepository.decodeExtra(changed.extraJson)
        assertEquals("kitchen", extra["space"])
        assertEquals("hardcover", extra["stage"])
        // 其余字段一律不碰
        assertEquals(original.amountCents, changed.amountCents)
        assertEquals(original.reimburseState, changed.reimburseState)
        assertEquals(original.projectId, changed.projectId)
    }

    @Test
    fun withExtraFieldBlankRemovesKey() {
        val original = txn(500, mapOf("space" to "living", "stage" to "hardcover"))
        val changed = withExtraField(original, "space", null)
        val extra = LedgerRepository.decodeExtra(changed.extraJson)
        assertFalse(extra.containsKey("space"))
        assertEquals("hardcover", extra["stage"])
    }

    // ---- 递增字段带出上次值 v17 ----

    private val carScene = ScenePreset(
        key = "car", name = "用车", emoji = "🚗", tagline = "",
        colorHex = "#000000", expenses = emptyList(), incomes = emptyList(),
        fields = listOf(SceneField("odometer", "当前里程", FieldKind.NUMBER, carryOver = true))
    )

    @Test
    fun carryOverTakesMostRecentNonBlankValue() {
        fun readingTxn(odometer: String, at: Long) = txn(100, mapOf("odometer" to odometer))
            .copy(occurredAt = at)
        val txns = listOf(
            readingTxn("10000", 300L),
            readingTxn("12000", 100L),
            readingTxn("", 200L)   // 中间一笔没填，不该被当成上次值
        )
        val got = lastCarryOverValues(txns, carScene, decodeExtra = { LedgerRepository.decodeExtra(it) })
        // 不依赖列表顺序：按 occurredAt 取最近填过的 10000（300L），不是 12000（100L）
        assertEquals("10000", got["odometer"])
    }

    @Test
    fun carryOverIgnoresDeletedAndIgnoresNonCarryFields() {
        val txns = listOf(
            txn(100, mapOf("odometer" to "99999")).copy(occurredAt = 999L, deletedAt = 1L),
            txn(100, mapOf("space" to "living")).copy(occurredAt = 500L)
        )
        val got = lastCarryOverValues(txns, carScene, decodeExtra = { LedgerRepository.decodeExtra(it) })
        assertTrue(got.isEmpty())
    }

    // ---- 场景化概览摘要 v17 第三档 ----

    @Test
    fun overviewNoteOneOffLedgersUseTotalExpense() {
        // 旅行 / 家装是一次性账本，说「累计」而不是「本月」
        val travel = sceneOverviewNote("travel", 1_00, 2_00, 50_00, 0L)
        assertTrue(travel.contains("这趟花费"))
        assertTrue(travel.contains(Money.format(50_00)))
        val reno = sceneOverviewNote("renovation", 1_00, 2_00, 50_00, 100_00)
        assertTrue(reno.contains("累计投入"))
        assertTrue(reno.contains("50%"))
    }

    @Test
    fun overviewNoteLongTermLedgersUseMonthExpense() {
        val car = sceneOverviewNote("car", 3_00, 0L, 90_00, 0L)
        assertTrue(car.contains("本月养车"))
        assertTrue(car.contains(Money.format(3_00)))
        // 日常走原口径，保持零回归
        val daily = sceneOverviewNote("daily", 3_00, 7_00, 90_00, 0L)
        assertTrue(daily.contains("本月支出"))
        assertTrue(daily.contains("收入"))
    }
}
