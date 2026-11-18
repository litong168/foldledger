package com.foldledger.data

import com.foldledger.data.update.UpdateChecker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 自动更新纯逻辑单测（双端共用 UpdateChecker）。 */
class UpdateCheckerTest {

    private fun manifest(code: Int = 233, android: Boolean = true, windows: Boolean = true): String = """
        {
          "versionCode": $code,
          "versionName": "16.6.0",
          "minVersionCode": 230,
          "notes": "测试更新",
          "assets": {
            ${if (android) "\"android\": {\"name\":\"a.apk\",\"size\":100,\"sha256\":\"abc\"}," else ""}
            ${if (windows) "\"windows\": {\"name\":\"w.exe\",\"size\":200,\"sha256\":\"def\"}" else ""}
          }
        }
    """.trimIndent().let {
        // 去掉末尾可能残留的逗号
        it.replace(Regex(",\\s*}"), "\n}")
    }

    @Test
    fun parseManifest_readsAllFields() {
        val m = UpdateChecker.parseManifest(manifest())
        assertEquals(233, m.versionCode)
        assertEquals("16.6.0", m.versionName)
        assertEquals(230, m.minVersionCode)
        assertEquals("测试更新", m.notes)
        assertEquals(100L, m.assetFor("android")?.size)
        assertEquals("a.apk", m.assetFor("android")?.name)
        assertEquals("def", m.assetFor("windows")?.sha256)
    }

    /** Gitee manifest 会在 asset 里带 `url`（指向 Release 附件）；缺省时为空串，由源自己拼。 */
    @Test
    fun parseManifest_readsAssetUrl() {
        val json = """
            {"versionCode":234,"versionName":"16.7.0","minVersionCode":1,"notes":"",
             "assets":{"windows":{"name":"w.exe","size":9,"sha256":"x",
               "url":"https://gitee.com/litong168/foldledger/releases/download/v16.7.0/w.exe"}}}
        """.trimIndent()
        val m = UpdateChecker.parseManifest(json)
        assertEquals(
            "https://gitee.com/litong168/foldledger/releases/download/v16.7.0/w.exe",
            m.assetFor("windows")?.url
        )
        // 没有 url 字段的老格式（GitHub）应回落到空串
        assertEquals("", UpdateChecker.parseManifest(manifest()).assetFor("android")?.url)
    }

    @Test
    fun evaluate_newerVersion_hasUpdate() {
        val r = UpdateChecker.evaluate(UpdateChecker.parseManifest(manifest(233)), 232, "android")
        assertTrue(r.hasUpdate)
        assertFalse(r.force)
        assertNotNull(r.manifest)
    }

    @Test
    fun evaluate_sameOrOlderVersion_noUpdate() {
        assertFalse(UpdateChecker.evaluate(UpdateChecker.parseManifest(manifest(232)), 232, "android").hasUpdate)
        assertFalse(UpdateChecker.evaluate(UpdateChecker.parseManifest(manifest(231)), 232, "android").hasUpdate)
    }

    @Test
    fun evaluate_missingPlatformAsset_noUpdate() {
        val onlyWindows = UpdateChecker.parseManifest(manifest(233, android = false))
        assertFalse(UpdateChecker.evaluate(onlyWindows, 232, "android").hasUpdate)
        assertTrue(UpdateChecker.evaluate(onlyWindows, 232, "windows").hasUpdate)
    }

    @Test
    fun evaluate_skippedVersion_silent() {
        val r = UpdateChecker.evaluate(UpdateChecker.parseManifest(manifest(233)), 232, "android", skippedVersionCode = 233)
        assertFalse(r.hasUpdate)
    }

    @Test
    fun evaluate_minVersionHigher_force() {
        val forced = manifest(233).replace("\"minVersionCode\": 230", "\"minVersionCode\": 240")
        val r = UpdateChecker.evaluate(UpdateChecker.parseManifest(forced), 232, "android")
        assertTrue(r.hasUpdate)
        assertTrue(r.force)
    }

    @Test
    fun findAssetUrl_picksByName() {
        val release = """{"tag_name":"v16.6.0","assets":[
            {"name":"update-manifest.json","browser_download_url":"http://m"},
            {"name":"a.apk","browser_download_url":"http://apk"},
            {"name":"w.exe","browser_download_url":"http://exe"}
        ]}"""
        assertEquals("http://m", UpdateChecker.findAssetUrl(release, "update-manifest.json"))
        assertEquals("http://apk", UpdateChecker.findAssetUrl(release, "a.apk"))
        assertNull(UpdateChecker.findAssetUrl(release, "missing"))
    }

    @Test
    fun parseTagName_andVersionTriple() {
        val release = """{"tag_name":"v16.6.0","assets":[]}"""
        assertEquals("v16.6.0", UpdateChecker.parseTagName(release))
        assertEquals(16_006_000, UpdateChecker.versionTripleToCode("v16.6.0"))
        assertEquals(16_005_003, UpdateChecker.versionTripleToCode("16.5.3"))
    }

    @Test
    fun hex_lowercase() {
        assertEquals("ff0a", UpdateChecker.hex(byteArrayOf(0xff.toByte(), 0x0a)))
    }
}
