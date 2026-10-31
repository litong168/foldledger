package com.foldledger.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图标引用约定。
 *
 * 这里只用一个字符串字段同时表达「Emoji」和「用户上传的图片」，
 * 所以这套前缀判定的正确性直接决定老数据能不能正常显示。
 */
class IconRefTest {

    @Test
    fun plainEmojiIsNotCustom() {
        assertFalse(IconRef.isCustom("🍜"))
        assertFalse(IconRef.isCustom(""))
        assertFalse(IconRef.isCustom(null))
    }

    @Test
    fun customReferenceRoundTrips() {
        val ref = IconRef.of("ic_123_4.png")
        assertTrue(IconRef.isCustom(ref))
        assertEquals("ic_123_4.png", IconRef.fileNameOf(ref))
    }

    @Test
    fun fileNameOfToleratesNullAndPlainEmoji() {
        assertEquals("", IconRef.fileNameOf(null))
        assertEquals("🍜", IconRef.fileNameOf("🍜"))
    }

    @Test
    fun textFallbackNeverReturnsBlank() {
        assertEquals("🏷️", IconRef.asText(null))
        assertEquals("🏷️", IconRef.asText(""))
        assertEquals("🍜", IconRef.asText("🍜"))
        // 图片在没有渲染能力的场合（导出表格等）退化成这个字符
        assertEquals("🖼️", IconRef.asText(IconRef.of("a.png")))
    }
}
