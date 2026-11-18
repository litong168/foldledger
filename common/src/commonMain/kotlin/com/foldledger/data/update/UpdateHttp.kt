package com.foldledger.data.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 更新用的传输层：GET GitHub Releases API + 流式下载安装包。
 *
 * 双端都是 JVM 目标，用 java.net.HttpURLConnection（零新增依赖，与 SyncHttp 一致）。
 * GitHub API 要求带 User-Agent，否则 403。
 *
 * ⚠️ 国内到 GitHub 下载域（release-assets.githubusercontent.com）**极不稳定**：
 * 实测约 23KB/s，且 SSL 握手/连接偶发失败（5 次里约 2~3 次成功）。所以所有请求都带
 * [RETRIES] 次重试 + 短退避；manifest 这种小文件尤其依赖重试才能稳定拿到。
 */
object UpdateHttp {

    const val LATEST_RELEASE_URL =
        "https://api.github.com/repos/litong168/foldledger/releases/latest"

    /**
     * 国内镜像（Gitee）的固定直链。
     *
     * 为什么不走 Gitee API：Gitee 的 Release 附件 API 需要鉴权且对小文件限流，直链更简单稳。
     * 约定 Gitee 仓库 `litong168/foldledger` 的 `release/` 目录里放三个**固定文件名**资产
     * （发版脚本负责覆盖）：`update-manifest.json` / `drip-ledger-latest.apk` / `drip-ledger-latest-setup.exe`。
     * raw 直链国内可直连，作为 GitHub 不可达时的兜底。
     */
    const val GITEE_MANIFEST_URL =
        "https://gitee.com/litong168/foldledger/raw/master/release/update-manifest.json"

    /** Gitee 资产直链（manifest 里的 name 若是固定名，可直接拼；否则用 manifest 的 url 字段） */
    fun giteeAssetUrl(name: String): String =
        "https://gitee.com/litong168/foldledger/raw/master/release/$name"

    private const val UA = "DripLedger-Updater/1.0"
    private const val RETRIES = 4

    /** GET 一段文本（JSON）。非 2xx 抛异常。带重试。 */
    suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        retry("GET") { getOnce(url) }
    }

    private fun getOnce(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 15_000
            conn.readTimeout = 45_000
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw UpdateHttpException(code, text)
            return text
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 下载文件到 [dest]（先写 .part，成功后 rename；失败留 .part 供重试）。
     *
     * 重试策略：每次从头重下并覆盖 .part（不做 Range 续传 —— 该下载域对 Range 支持不稳，
     * 且重定向后的签名 URL 每次都变，续传反而更容易拿到拼接坏包）。大文件下载慢，
     * 只有连接类瞬时错误才重试；"下载不完整"这类已传了一部分的失败也重试（从头）。
     */
    suspend fun download(
        url: String,
        dest: File,
        onProgress: ((Long, Long) -> Unit)? = null
    ): File = withContext(Dispatchers.IO) {
        retry("下载") { downloadOnce(url, dest, onProgress) }
    }

    private fun downloadOnce(
        url: String,
        dest: File,
        onProgress: ((Long, Long) -> Unit)?
    ): File {
        dest.parentFile?.mkdirs()
        val part = File(dest.absolutePath + ".part")
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 20_000
            // 下安装包很慢（国内约 23KB/s，94MB 可能 >1 小时），读超时要足够长
            conn.readTimeout = 300_000
            conn.setRequestProperty("User-Agent", UA)
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                throw UpdateHttpException(code, err)
            }
            val total = conn.contentLengthLong
            var downloaded = 0L
            BufferedInputStream(conn.inputStream, 64 * 1024).use { input ->
                FileOutputStream(part).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        downloaded += n
                        onProgress?.invoke(downloaded, total)
                    }
                }
            }
            if (total > 0 && downloaded != total) {
                throw UpdateHttpException(-1, "下载不完整：$downloaded/$total")
            }
            if (dest.exists()) dest.delete()
            if (!part.renameTo(dest)) {
                part.copyTo(dest, overwrite = true)
                part.delete()
            }
            return dest
        } finally {
            conn.disconnect()
        }
    }

    /** 瞬时网络错误重试（指数退避：0.4s / 0.8s / 1.6s）。4xx（除 429）不重试。 */
    private suspend fun <T> retry(label: String, block: () -> T): T {
        var last: Exception? = null
        for (attempt in 0 until RETRIES) {
            try {
                return block()
            } catch (e: Exception) {
                last = e
                val noRetry = e is UpdateHttpException && e.code in 400..499 && e.code != 429
                if (noRetry || attempt == RETRIES - 1) break
                delay(400L * (1 shl attempt))
            }
        }
        throw last ?: IllegalStateException("$label 失败")
    }

    /** 计算文件 SHA-256（小写十六进制） */
    suspend fun sha256(file: File): String = withContext(Dispatchers.IO) {
        val md = MessageDigest.getInstance("SHA-256")
        BufferedInputStream(FileInputStream(file), 64 * 1024).use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        UpdateChecker.hex(md.digest())
    }
}

class UpdateHttpException(val code: Int, body: String) :
    Exception("HTTP $code：${body.take(200)}")
