package com.foldledger.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 搜索匹配的单测（v4.5）。
 *
 * 单测环境没有 Android `.so`，所以这里跑的是 **Kotlin 回退实现** —— 正好用来保证
 * 回退路径与 Rust `stats::matches_query` 的语义完全一致（Rust 侧有同名用例
 * `search_multi_keyword_and` / `search_matches_amount`）。
 */
class DripCoreSearchTest {

    @Test
    fun matchesNameAndNote() {
        assertTrue(DripCore.matchesQuery("餐饮", "午餐", 0L, "餐"))
        assertTrue(DripCore.matchesQuery("餐饮", "午餐", 0L, "午餐"))
        assertFalse(DripCore.matchesQuery("餐饮", "午餐", 0L, "不相关"))
        // 空查询 = 全部通过
        assertTrue(DripCore.matchesQuery("餐饮", "午餐", 0L, ""))
        // 不区分大小写
        assertTrue(DripCore.matchesQuery("Cafe", "note", 0L, "cafe"))
    }

    @Test
    fun multiKeywordIsAnd() {
        assertTrue(DripCore.matchesQuery("餐饮", "公司午餐", 0L, "餐饮 午餐"))
        assertFalse(DripCore.matchesQuery("餐饮", "公司午餐", 0L, "餐饮 打车"))
        // 多余空格不影响
        assertTrue(DripCore.matchesQuery("餐饮", "午餐", 0L, "  餐饮   午餐  "))
    }

    @Test
    fun numericQueryMatchesAmount() {
        // 3500 分 = ¥35.00
        assertTrue(DripCore.matchesQuery("餐饮", "午饭", 3500L, "35"))
        assertTrue(DripCore.matchesQuery("餐饮", "午饭", 123456L, "1234.56"))
        assertTrue(DripCore.matchesQuery("餐饮", "午饭", 123456L, "1,234"))
        assertFalse(DripCore.matchesQuery("餐饮", "午饭", 3500L, "36"))
        // 非数字关键词不去碰金额
        assertFalse(DripCore.matchesQuery("交通", "", 3500L, "午饭"))
        // 关键词 + 金额组合
        assertTrue(DripCore.matchesQuery("餐饮", "午饭", 3500L, "餐饮 35"))
    }

    @Test
    fun negativeAmountMatchesAbsoluteValue() {
        // 退款等负数流水，按绝对值也能搜到
        assertTrue(DripCore.matchesQuery("退款", "", -3500L, "35"))
    }
}
