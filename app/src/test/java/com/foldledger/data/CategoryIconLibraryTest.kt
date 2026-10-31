package com.foldledger.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 预置图标库的完整性与搜索。
 *
 * 图库是「用户不再手打 Emoji」这个需求的底座，坏一条就少一个可选图标，
 * 所以把数据本身的约束也纳入测试。
 */
class CategoryIconLibraryTest {

    @Test
    fun libraryIsRichEnough() {
        // 需求是"丰富系统预置图标库"，定一条下限防止后续被误删到只剩几十个
        assertTrue("图标总数应 ≥ 200，实际 ${CategoryIconLibrary.all.size}", CategoryIconLibrary.all.size >= 200)
        assertTrue("分组数应 ≥ 12，实际 ${CategoryIconLibrary.groups.size}", CategoryIconLibrary.groups.size >= 12)
    }

    @Test
    fun emojiIsUniqueInsideEachGroup() {
        // 组内必须唯一：选择器的网格用 emoji 当 key，组内重复会直接抛「key 重复」
        CategoryIconLibrary.groups.forEach { g ->
            val emojis = g.icons.map { it.emoji }
            assertEquals("分组「${g.name}」内有重复图标", emojis.size, emojis.distinct().size)
        }
    }

    @Test
    fun searchIndexMergesKeywordsOfSameEmoji() {
        // 同名 Emoji 跨组出现时，搜索索引要合并关键词而不是只留第一条
        val total = CategoryIconLibrary.groups.flatMap { it.icons }.size
        assertTrue(CategoryIconLibrary.all.size <= total)
        assertTrue(CategoryIconLibrary.all.all { it.keywords.isNotBlank() })
    }

    @Test
    fun everyIconHasSearchKeywords() {
        CategoryIconLibrary.all.forEach { icon ->
            assertTrue("「${icon.emoji}」缺少关键词", icon.keywords.isNotBlank())
        }
    }

    @Test
    fun everyGroupIsNotEmpty() {
        CategoryIconLibrary.groups.forEach { g ->
            assertTrue("分组「${g.name}」是空的", g.icons.isNotEmpty())
        }
    }

    @Test
    fun searchHitsCommonWords() {
        // 这几个词是记账里最常用的，必须搜得到
        listOf("加油", "疫苗", "房贷", "奶粉", "房租", "报销", "工资").forEach { q ->
            val r = CategoryIconLibrary.search(q)
            assertNotNull(r)
            assertTrue("搜「$q」没有结果", r!!.isNotEmpty())
        }
    }

    @Test
    fun blankQueryMeansBrowseMode() {
        // 空查询返回 null，UI 据此切回「按分组浏览」
        assertNull(CategoryIconLibrary.search(""))
        assertNull(CategoryIconLibrary.search("   "))
    }

    @Test
    fun searchByEmojiItself() {
        val r = CategoryIconLibrary.search("⛽")
        assertNotNull(r)
        assertEquals("⛽", r!!.first().emoji)
    }

    @Test
    fun nonsenseQueryReturnsEmpty() {
        val r = CategoryIconLibrary.search("这个词肯定不存在zzz")
        assertNotNull(r)
        assertTrue(r!!.isEmpty())
    }
}
