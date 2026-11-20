package com.foldledger.data.update

/**
 * 下载速率闸（v16.8.1）：判断当前下载是否"慢到不值得等"，好让上层及时换源。
 *
 * 背景：国内连 GitHub 资产域实测 6~23KB/s，4.4MB 的 APK 要十几分钟、94MB 的 EXE 要一小时；
 * 而 Gitee 镜像实测 850KB/s。原来只有"manifest 拿不到"才回退，能拿到清单但下不动就一直卡在 GitHub。
 *
 * 判定规则：首块数据到达后先给 [warmupMs] 预热（避免握手/首包抖动误判），
 * 之后每满 [windowMs] 结算一次窗口速率，低于 [minBytesPerSec] 就判定过慢。
 *
 * 纯计算、无 IO、可注入时间戳 → 便于单测。
 */
class DownloadSpeedGate(
    private val minBytesPerSec: Long = DEFAULT_MIN_BYTES_PER_SEC,
    private val warmupMs: Long = DEFAULT_WARMUP_MS,
    private val windowMs: Long = DEFAULT_WINDOW_MS
) {
    private var started = false
    private var startedAt = 0L
    private var windowStart = 0L
    private var windowBytes = 0L

    /**
     * 喂入本次读到的字节数与当前时间（毫秒）。
     * @return 非 null 表示判定过慢，值为窗口内实测速率（B/s）；null 表示继续下载。
     */
    fun onBytes(bytes: Long, nowMs: Long): Long? {
        if (!started) {
            started = true
            startedAt = nowMs
            windowStart = nowMs
        }
        windowBytes += bytes
        if (nowMs - startedAt < warmupMs) return null
        val elapsed = nowMs - windowStart
        if (elapsed < windowMs) return null
        val bps = if (elapsed <= 0L) Long.MAX_VALUE else windowBytes * 1000L / elapsed
        // 无论是否过慢都重置窗口：过慢时上层会立刻中断，这里只为状态一致
        windowStart = nowMs
        windowBytes = 0L
        return if (bps < minBytesPerSec) bps else null
    }

    companion object {
        /** 低于这个速率就换源：Gitee 实测 850KB/s，GitHub 6~23KB/s，64KB/s 是安全分界 */
        const val DEFAULT_MIN_BYTES_PER_SEC = 64L * 1024
        const val DEFAULT_WARMUP_MS = 6_000L
        const val DEFAULT_WINDOW_MS = 6_000L
    }
}

/** 下载速率低于闸值（不是网络断开）——上层据此换源，同一个 URL 不必重试。 */
class UpdateTooSlowException(val bytesPerSec: Long) :
    Exception("下载过慢（约 ${bytesPerSec / 1024} KB/s）")
