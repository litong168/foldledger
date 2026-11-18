package com.foldledger.ui.l10n

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 多语言纯逻辑测试（v16.7.0）：翻译命中 / 未命中回退 / 语言切换。
 *
 * 只测 [L10n.translate]，不碰 Compose 的 Snapshot（集合读写在单线程测试里安全）。
 * 每个用例结束后把语言重置回中文，避免互相污染。
 */
class L10nTest {

    @After
    fun reset() {
        L10n.current = Language.CHINESE
    }

    @Test
    fun chineseModeReturnsOriginal() {
        L10n.current = Language.CHINESE
        assertEquals("记一笔", t("记一笔"))
        assertEquals("明细", t("明细"))
    }

    @Test
    fun englishModeTranslatesKnownKeys() {
        L10n.current = Language.ENGLISH
        assertEquals("Add record", t("记一笔"))
        assertEquals("Records", t("明细"))
        assertEquals("Ledger", t("账本"))
    }

    @Test
    fun englishModeFallsBackForUnknownKeys() {
        L10n.current = Language.ENGLISH
        // 未翻译的词条必须原样返回中文，绝不返回空串或 key 占位
        val unknown = "这是一条还没翻译的临时文案"
        assertEquals(unknown, t(unknown))
        assertTrue(t(unknown).isNotEmpty())
    }

    @Test
    fun fromCodeMatchesKnownLanguages() {
        assertEquals(Language.ENGLISH, Language.fromCode("en"))
        assertEquals(Language.CHINESE, Language.fromCode("zh"))
        // 未知/空值一律回中文
        assertEquals(Language.CHINESE, Language.fromCode(null))
        assertEquals(Language.CHINESE, Language.fromCode("fr"))
    }

    @Test
    fun dictionaryHasNoBlankValues() {
        // 字典里不允许出现空英文，否则英文界面会出现空白
        val blanks = EnStrings.map.filterValues { it.isBlank() }.keys
        assertTrue("存在空值词条: $blanks", blanks.isEmpty())
    }
}
