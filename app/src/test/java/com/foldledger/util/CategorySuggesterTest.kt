package com.foldledger.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 分类建议（v8.6）——从用户自己的历史里学。
 */
class CategorySuggesterTest {

    private fun s(note: String, cat: String, at: Long) =
        CategorySuggester.Sample(note, cat, 1000L, at)

    @Test
    fun historyNoteSuggestsItsCategory() {
        val samples = listOf(
            s("星巴克", "餐饮", 100L),
            s("地铁", "交通", 200L)
        )
        assertEquals("餐饮", CategorySuggester.suggest("星巴克32", samples))
        assertEquals("交通", CategorySuggester.suggest("地铁3", samples))
    }

    @Test
    fun categoryNameItselfIsAKey() {
        val samples = listOf(s("公司团建", "人情往来", 100L))
        assertEquals("人情往来", CategorySuggester.suggest("人情往来 200", samples))
    }

    @Test
    fun noMatchReturnsNull() {
        val samples = listOf(s("星巴克", "餐饮", 100L))
        assertNull(CategorySuggester.suggest("买了个台灯", samples))
    }

    @Test
    fun emptyInputOrHistoryReturnsNull() {
        assertNull(CategorySuggester.suggest("", listOf(s("星巴克", "餐饮", 1L))))
        assertNull(CategorySuggester.suggest("星巴克", emptyList()))
    }

    @Test
    fun majorityWins() {
        val samples = listOf(
            s("咖啡", "餐饮", 100L),
            s("咖啡", "餐饮", 200L),
            s("咖啡", "娱乐", 300L)
        )
        assertEquals("餐饮", CategorySuggester.suggest("咖啡", samples))
    }

    @Test
    fun tieBreaksByMostRecent() {
        val samples = listOf(
            s("咖啡", "餐饮", 100L),
            s("咖啡", "娱乐", 900L)
        )
        assertEquals("娱乐", CategorySuggester.suggest("咖啡", samples))
    }

    @Test
    fun punctuationInNoteIsIgnored() {
        val samples = listOf(s("星巴克（拿铁）", "餐饮", 100L))
        assertEquals("餐饮", CategorySuggester.suggest("星巴克拿铁", samples))
    }

    @Test
    fun tooShortKeysAreSkipped() {
        val samples = listOf(s("a", "餐饮", 100L))
        assertNull(CategorySuggester.suggest("a 12", samples, minKeyLen = 2))
    }
}
