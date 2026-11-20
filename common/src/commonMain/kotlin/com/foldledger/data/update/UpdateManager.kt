package com.foldledger.data.update

import com.foldledger.APP_VERSION_CODE
import com.foldledger.data.prefs.AppPreferences
import kotlinx.coroutines.flow.first
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/**
 * 双端共享的更新协调器：检查 → 下载 → 校验。安装由各平台自己完成（桌面起 setup / 手机拉安装界面）。
 *
 * 状态用普通对象 + mutableState 风格暴露（不用 Flow，UI 端用 LaunchedEffect 主动触发 + 读快照）。
 */
class UpdateManager(private val prefs: AppPreferences) {

    @Volatile
    var busy: Boolean = false
        private set

    @Volatile
    var progress: Pair<Long, Long> = 0L to -1L
        private set

    @Volatile
    var lastError: String? = null
        private set

    /** 更新弹窗要显示的信息 */
    data class Available(
        val versionName: String,
        val versionCode: Int,
        val notes: String,
        val force: Boolean,
        val asset: UpdateChecker.Asset,
        val downloadUrl: String,
        /** 本端平台标识（windows / android）：换源时用来在镜像清单里取同一个资产 */
        val platform: String = ""
    )

    @Volatile
    var available: Available? = null
        private set

    /** 本次下载是否已切到国内镜像（UI 用来提示，不参与逻辑） */
    @Volatile
    var usingMirror: Boolean = false
        private set

    /**
     * 检查更新。返回是否有可用更新。
     * 网络/解析失败一律返回 false 并写 [lastError]，绝不抛给调用方打扰用户。
     *
     * 源策略（v16.7）：**GitHub 优先，失败/缺资产回退 Gitee 镜像**。
     * 两条源拿到的是同构 manifest，仅资产下载 URL 来源不同。
     */
    suspend fun check(platform: String, force: Boolean = false): Boolean {
        if (busy) return false
        busy = true
        lastError = null
        try {
            if (!force) {
                val s = prefs.settings.first()
                if (!s.autoCheckUpdate) return false
                // 距上次检查 < 6 小时则跳过（手动触发 force=true 绕过）
                val now = System.currentTimeMillis()
                if (s.lastUpdateCheckAt > 0 && now - s.lastUpdateCheckAt < 6 * 3600_000L) {
                    return false
                }
            }
            val settings = prefs.settings.first()
            val source = fetchFromSources() ?: run {
                // 两源都拿不到：不算失败弹错，静默当"无更新"
                available = null
                return false
            }
            val manifest = source.manifest
            val result = UpdateChecker.evaluate(
                manifest = manifest,
                currentVersionCode = APP_VERSION_CODE,
                platform = platform,
                skippedVersionCode = settings.skippedVersionCode
            )
            prefs.markUpdateChecked(System.currentTimeMillis())
            if (!result.hasUpdate) {
                available = null
                return false
            }
            val asset = manifest.assetFor(platform)!!
            val url = source.resolveUrl(asset)
            available = Available(
                versionName = manifest.versionName.ifBlank { manifest.versionCode.toString() },
                versionCode = manifest.versionCode,
                notes = manifest.notes,
                force = result.force,
                asset = asset,
                downloadUrl = url,
                platform = platform
            )
            return true
        } catch (e: Exception) {
            lastError = e.message ?: e::class.simpleName
            available = null
            return false
        } finally {
            busy = false
        }
    }

    /** 一次源尝试的结果：manifest + 根据 asset 解析下载 URL 的能力 */
    private class SourceResult(
        val manifest: UpdateChecker.Manifest,
        /** 给定 asset 名，返回下载 URL（GitHub 用 API 列表解析） */
        val assetUrl: (String) -> String
    ) {
        /**
         * 优先用 asset 自带的 [UpdateChecker.Asset.url]（Gitee manifest 直接写 Release 附件地址），
         * 没有才回落到源自己的拼接规则。
         */
        fun resolveUrl(asset: UpdateChecker.Asset): String =
            asset.url.ifBlank { assetUrl(asset.name) }
    }

    /**
     * 依次尝试 GitHub、Gitee 两个源，返回第一个能解析出 manifest 的结果。
     * 任一源抛异常即跳到下一个；全失败返回 null。
     */
    private suspend fun fetchFromSources(): SourceResult? {
        // 1) GitHub Releases API（带完整 assets 列表）
        try {
            val releaseJson = UpdateHttp.get(UpdateHttp.LATEST_RELEASE_URL)
            val manifestUrl = UpdateChecker.findAssetUrl(releaseJson, MANIFEST_ASSET)
            if (manifestUrl != null) {
                val manifest = UpdateChecker.parseManifest(UpdateHttp.get(manifestUrl))
                return SourceResult(manifest) { name ->
                    UpdateChecker.findAssetUrl(releaseJson, name)
                        ?: throw IllegalStateException("找不到资产 $name")
                }
            }
        } catch (e: Exception) {
            lastError = "GitHub：${e.message ?: e::class.simpleName}"
        }
        // 2) Gitee 镜像（manifest 走 raw 直链；资产 URL 由 manifest 的 url 字段给出，指向 Release 附件）
        try {
            val manifest = UpdateChecker.parseManifest(UpdateHttp.get(UpdateHttp.GITEE_MANIFEST_URL))
            return SourceResult(manifest) { name -> UpdateHttp.giteeAssetUrl(name) }
        } catch (e: Exception) {
            lastError = "镜像：${e.message ?: e::class.simpleName}"
        }
        return null
    }

    /** 「跳过此版本」 */
    suspend fun skipThisVersion() {
        available?.let { prefs.setSkippedVersionCode(it.versionCode) }
        available = null
    }

    /**
     * 下载当前可用更新到 [dir]，校验 size + sha256，返回最终文件。
     * 校验失败删文件并抛异常。
     *
     * v16.8.1：**慢就换源**。原来的回退只看"manifest 拿不到"，于是出现"清单拿到了、资产却
     * 6KB/s 龟速下不完、还取消不掉"的死局。现在按 主源(带速率闸) → 镜像同版本资产(带闸)
     * → 主源无闸硬磨 三级走；每一级失败都会清掉半成品。取消（协程 cancel）会立刻中断。
     */
    suspend fun downloadAndVerify(
        dir: File,
        onProgress: ((Long, Long) -> Unit)? = null
    ): File {
        val info = available ?: throw IllegalStateException("没有可用的更新")
        dir.mkdirs()
        val dest = File(dir, info.asset.name)
        val primary = info.downloadUrl
        var last: Exception? = null
        var sawSlow = false

        usingMirror = false
        // 1) 主源：带速率闸，太慢就赶紧换源，别让用户干等十几分钟
        try {
            return downloadFrom(primary, dest, info, gate = true, onProgress = onProgress)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e is UpdateTooSlowException) sawSlow = true
            last = e
        }

        // 2) 国内镜像：只在版本号完全一致时才用（防止镜像没跟上导致降级覆盖）
        val mirror = runCatching { mirrorUrlFor(info) }.getOrNull()
        if (!mirror.isNullOrBlank() && mirror != primary) {
            usingMirror = true
            try {
                return downloadFrom(mirror, dest, info, gate = true, onProgress = onProgress)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is UpdateTooSlowException) sawSlow = true
                last = e
            } finally {
                usingMirror = false
            }
        }

        // 3) 两个源都慢：关掉闸门在主源上硬磨一次（慢也胜过失败，至少能下完）
        if (sawSlow) {
            try {
                return downloadFrom(primary, dest, info, gate = false, onProgress = onProgress)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IllegalStateException("下载失败")
    }

    /** 真正下一次并校验；失败抛异常（半成品已删），由调用方决定换哪个源 */
    private suspend fun downloadFrom(
        url: String,
        dest: File,
        info: Available,
        gate: Boolean,
        onProgress: ((Long, Long) -> Unit)?
    ): File {
        progress = 0L to -1L
        val file = UpdateHttp.download(url, dest, detectSlow = gate) { done, total ->
            progress = done to total
            onProgress?.invoke(done, total)
        }
        if (info.asset.size > 0 && file.length() != info.asset.size) {
            file.delete()
            throw IllegalStateException("安装包大小不符：${file.length()} / ${info.asset.size}")
        }
        if (info.asset.sha256.isNotBlank()) {
            val actual = UpdateHttp.sha256(file)
            if (!actual.equals(info.asset.sha256, ignoreCase = true)) {
                file.delete()
                throw IllegalStateException("安装包校验失败（SHA256 不符），已删除，请重试")
            }
        }
        return file
    }

    /**
     * 解析镜像里与本机同版本的资产直链；拿不到清单或版本不符返回 null。
     *
     * 优先用清单里的 `url` 字段（发版脚本写入的 Release 附件地址，已 URL 编码）；
     * 空则按约定拼 raw 直链（小文件可用，大文件会被 Gitee 拦 403，届时自然换回主源）。
     */
    private suspend fun mirrorUrlFor(info: Available): String? {
        val platform = info.platform.ifBlank {
            if (info.asset.name.endsWith(".apk", ignoreCase = true)) "android" else "windows"
        }
        val manifest = UpdateChecker.parseManifest(UpdateHttp.get(UpdateHttp.GITEE_MANIFEST_URL))
        val asset = UpdateChecker.mirrorAsset(manifest, platform, info.versionCode) ?: return null
        return asset.url.ifBlank { UpdateHttp.giteeAssetUrl(asset.name) }
    }

    companion object {
        const val MANIFEST_ASSET = "update-manifest.json"
    }
}
