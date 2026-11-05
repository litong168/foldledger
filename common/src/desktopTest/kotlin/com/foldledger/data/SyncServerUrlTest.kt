package com.foldledger.data

import com.foldledger.data.sync.normalizeServerUrl
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 服务器地址整理（v16.4.2）。
 *
 * 背景：v16.4.1 有用户点「注册此设备」只看到
 * `NumberFormatException: Error at index 5 in: ":10097"` —— 原因是中文输入法把
 * `:` 和数字打成了全角，肉眼看不出区别。这里把「自动纠正」钉成契约。
 */
class SyncServerUrlTest {

    @Test
    fun trimsWhitespaceAndTrailingSlash() {
        assertEquals("http://192.168.110.57:10097", normalizeServerUrl("  http://192.168.110.57:10097/  "))
    }

    @Test
    fun fullWidthCharsBecomeHalfWidth() {
        // 全角冒号 + 全角数字 + 全角点，正是出事那种输入
        assertEquals(
            "http://192.168.110.57:10097",
            normalizeServerUrl("http：//192．168．110．57：１００９７")
        )
    }

    @Test
    fun missingSchemeGetsHttp() {
        assertEquals("http://192.168.110.57:10097", normalizeServerUrl("192.168.110.57:10097"))
    }

    @Test
    fun httpsKeptAsIs() {
        assertEquals("https://nas.example.com:8451", normalizeServerUrl("https://nas.example.com:8451/"))
    }

    @Test
    fun zeroWidthCharsDropped() {
        assertEquals("http://nas:8451", normalizeServerUrl("http://nas\u200B:8451"))
    }

    @Test
    fun blankStaysBlank() {
        assertEquals("", normalizeServerUrl("   "))
    }
}
