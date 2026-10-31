package com.foldledger.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「流水页只留中文」这条需求的守门测试。
 *
 * 背景：场景字段（家装的「空间 / 阶段 / 付款状态」、用车的「费用类型」…）在**数据库里存的是
 * 英文 key**（`hardcover` / `deposit` …），只在 UI 层翻成中文 label。
 * 一旦有人漏了某条翻译路径、或者按 JSON 键序乱取字段，流水页副标题就会冒出英文 ——
 * 用户报过这个问题，所以把口径钉死在这里。
 */
class SceneFieldLabelTest {

    // ---------------- displayValue：单字段翻译 ----------------

    @Test
    fun choiceValueIsTranslated() {
        // 家装的「阶段」：设计 / 硬装 / 软装 / 家电进场 / 已完工
        assertEquals("硬装", ScenePresets.displayValue("renovation", "stage", "hardcover"))
        assertEquals("设计", ScenePresets.displayValue("renovation", "stage", "design"))
        assertEquals("已完工", ScenePresets.displayValue("renovation", "stage", "done"))
    }

    @Test
    fun spaceValueIsTranslated() {
        assertEquals("主卧", ScenePresets.displayValue("renovation", "space", "master"))
        assertEquals("卫生间", ScenePresets.displayValue("renovation", "space", "bathroom"))
    }

    @Test
    fun blankValueBecomesEmpty() {
        assertEquals("", ScenePresets.displayValue("renovation", "stage", ""))
        assertEquals("", ScenePresets.displayValue("renovation", "stage", "   "))
    }

    @Test
    fun unknownValueFallsBackToRaw() {
        // 认不出来的值原样返回，不丢信息（例如用户改过场景、老数据用了别的口径）
        assertEquals("mystery", ScenePresets.displayValue("renovation", "stage", "mystery"))
    }

    // ---------------- extraSummary：流水副标题 ----------------

    @Test
    fun summaryFollowsSceneFieldOrderNotJsonOrder() {
        // 故意把 map 顺序打乱（LinkedHashMap 保持插入序），结果必须仍按场景定义顺序：
        // 空间 → 商家/施工方 → 阶段 → 付款状态
        val extra = linkedMapOf(
            "stage" to "hardcover",       // 场景里排第 3
            "space" to "kitchen"          // 场景里排第 1
        )
        assertEquals(listOf("厨房", "硬装"), ScenePresets.extraSummary("renovation", extra))
    }

    @Test
    fun summaryTranslatesEveryValueToChinese() {
        val extra = mapOf("space" to "living", "stage" to "softcover")
        val out = ScenePresets.extraSummary("renovation", extra)
        assertEquals(listOf("客厅", "软装"), out)
        // 硬约束：不能出现任何英文
        out.forEach { v ->
            assertFalse("副标题里不该出现英文：$v", v.any { it in 'a'..'z' || it in 'A'..'Z' })
        }
    }

    @Test
    fun summaryRespectsMax() {
        val extra = mapOf(
            "space" to "whole",
            "vendor" to "XX装饰",
            "stage" to "design",
            "payment" to "paid"
        )
        assertEquals(1, ScenePresets.extraSummary("renovation", extra, max = 1).size)
        assertEquals(3, ScenePresets.extraSummary("renovation", extra, max = 3).size)
    }

    @Test
    fun summarySkipsReimburseState() {
        // 报销状态已经由流水行上的徽标表达，不该再占副标题的位置
        val extra = mapOf("state" to "1")
        assertTrue(ScenePresets.extraSummary("reimbursement", extra).isEmpty())
    }

    @Test
    fun summarySkipsBlankValues() {
        val extra = mapOf("space" to "", "stage" to "design")
        assertEquals(listOf("设计"), ScenePresets.extraSummary("renovation", extra))
    }

    @Test
    fun textFieldKeepsLabelAndRawValue() {
        // 自由文本字段没有可翻译的枚举，显示成「标签 + 原文」
        val extra = mapOf("vendor" to "老王施工队")
        assertEquals(listOf("商家/施工方 老王施工队"), ScenePresets.extraSummary("renovation", extra))
    }

    @Test
    fun unknownKeysAreAppendedWithoutLoss() {
        // 账本换过场景时，旧字段不在新场景定义里 —— 也要照原样补上，不能把信息吞掉
        val extra = mapOf("legacy_field" to "老数据")
        assertEquals(listOf("老数据"), ScenePresets.extraSummary("renovation", extra))
    }

    @Test
    fun emptyExtraGivesEmptySummary() {
        assertTrue(ScenePresets.extraSummary("renovation", emptyMap()).isEmpty())
    }

    // ---------------- 全场景体检：不能有漏翻的枚举 ----------------

    @Test
    fun everySceneFieldOptionLabelIsNonBlankChinese() {
        // 遍历全部场景的全部选项，任何一条 label 为空/等于 value（说明忘写中文）都算失败
        val bad = mutableListOf<String>()
        sceneKeys().forEach { key ->
            ScenePresets.byKey(key).fields.forEach { f ->
                f.options.forEach { o ->
                    if (o.label.isBlank() || o.label == o.value) {
                        bad += "$key.${f.key}=${o.value} -> '${o.label}'"
                    }
                }
            }
        }
        assertTrue("这些字段选项没有中文 label：$bad", bad.isEmpty())
    }

    @Test
    fun everySceneFieldOptionValueIsTranslatedBack() {
        // 反向验证：每个 value 都能被 displayValue 翻回它的 label
        val bad = mutableListOf<String>()
        sceneKeys().forEach { key ->
            val scene = ScenePresets.byKey(key)
            scene.fields.forEach { f ->
                f.options.forEach { o ->
                    val got = ScenePresets.displayValue(key, f.key, o.value)
                    if (got != o.label) bad += "$key.${f.key}=${o.value} 得到 '$got'，期望 '${o.label}'"
                }
            }
        }
        assertTrue("这些字段值翻不回中文：$bad", bad.isEmpty())
    }

    /** 全部场景 key（`ScenePresets.all` 里的 + custom 兜底） */
    private fun sceneKeys(): List<String> =
        (ScenePresets.all.map { it.key } + ScenePresets.CUSTOM_KEY).distinct()
}
