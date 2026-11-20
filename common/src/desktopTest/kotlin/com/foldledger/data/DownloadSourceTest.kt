package com.foldledger.data

import com.foldledger.data.update.DownloadSpeedGate
import com.foldledger.data.update.UpdateChecker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 下载换源（v16.8.1）的纯逻辑：
 * 1) 速率闸什么时候判"太慢值得换源"；
 * 2) 镜像清单里的资产必须与主源同版本才允许用。
 */
class DownloadSourceTest {

    /** 64KB/s 的闸值 → 6 秒窗口需 393216 字节；刚好等于不算慢，少 1 字节就算 */
    private val exactThresholdBytes = 393_216L

    /** 在 t=0 起算的空窗口（时间戳可控，便于逐窗口断言） */
    private fun newGate(): DownloadSpeedGate = DownloadSpeedGate().apply { onBytes(0L, 0L) }

    @Test
    fun warmupWindow_neverReportsSlow() {
        val gate = newGate()
        // 预热期内哪怕 1 字节都不给，也不判慢（避免握手/首包抖动误判）
        assertNull(gate.onBytes(0L, 2_000L))
        assertNull(gate.onBytes(0L, 5_999L))
    }

    @Test
    fun fastWindow_returnsNull() {
        // 6 秒下 400KB ≈ 66.7KB/s，高于闸值
        assertNull(newGate().onBytes(400_000L, 6_000L))
    }

    @Test
    fun slowWindow_returnsMeasuredRate() {
        // 6 秒只下 30KB = 5KB/s → 报慢，值为实测速率（上层据此换源）
        assertEquals(5_000L, newGate().onBytes(30_000L, 6_000L))
    }

    @Test
    fun zeroGrowthWindow_countsAsStall() {
        // 10 秒零增长 = 卡死，也必须判慢，而不是死等读超时
        assertEquals(0L, newGate().onBytes(0L, 10_000L))
    }

    @Test
    fun thresholdBoundary_isInclusive() {
        assertEquals(
            DownloadSpeedGate.DEFAULT_MIN_BYTES_PER_SEC,
            exactThresholdBytes * 1000L / 6_000L
        )
        assertNull("恰好等于闸值不算慢", newGate().onBytes(exactThresholdBytes, 6_000L))
        assertEquals(
            DownloadSpeedGate.DEFAULT_MIN_BYTES_PER_SEC - 1,
            newGate().onBytes(exactThresholdBytes - 6L, 6_000L)
        )
    }

    @Test
    fun windowResetsAfterReport() {
        val gate = newGate()
        assertNotNull("第一窗口慢", gate.onBytes(30_000L, 6_000L))
        // 第二窗口恢复高速：不能再把上一窗口的字节算进来
        assertNull(gate.onBytes(400_000L, 12_000L))
        // 第三窗口又慢：仍然能报出来
        assertNotNull(gate.onBytes(1_000L, 18_000L))
    }

    private fun mirrorManifest(code: Int, withWindows: Boolean = true): UpdateChecker.Manifest =
        UpdateChecker.parseManifest(
            """
            {
              "versionCode": $code,
              "versionName": "16.8.1",
              "assets": {
                ${if (withWindows) """"windows": {"name":"w.exe","size":200,"sha256":"def","url":"https://gitee/x/w.exe"},""" else ""}
                "android": {"name":"a.apk","size":100,"sha256":"abc","url":"https://gitee/x/a.apk"}
              }
            }
            """.trimIndent()
        )

    @Test
    fun mirrorAsset_sameVersionOnly() {
        val m = mirrorManifest(237)
        assertEquals("https://gitee/x/a.apk", UpdateChecker.mirrorAsset(m, "android", 237)!!.url)
        // 主源已是 238 而镜像还停在 237：不能用旧包降级覆盖
        assertNull(UpdateChecker.mirrorAsset(m, "android", 238))
        // 镜像清单里没有本端资产
        assertNull(UpdateChecker.mirrorAsset(mirrorManifest(237, withWindows = false), "windows", 237))
    }
}
