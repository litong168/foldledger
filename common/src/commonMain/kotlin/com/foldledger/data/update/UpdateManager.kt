package com.foldledger.data.update

import com.foldledger.APP_VERSION_CODE
import com.foldledger.data.prefs.AppPreferences
import kotlinx.coroutines.flow.first
import java.io.File

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
        val downloadUrl: String
    )

    @Volatile
    var available: Available? = null
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
                downloadUrl = url
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
     */
    suspend fun downloadAndVerify(
        dir: File,
        onProgress: ((Long, Long) -> Unit)? = null
    ): File {
        val info = available ?: throw IllegalStateException("没有可用的更新")
        dir.mkdirs()
        val dest = File(dir, info.asset.name)
        val file = UpdateHttp.download(info.downloadUrl, dest) { done, total ->
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

    companion object {
        const val MANIFEST_ASSET = "update-manifest.json"
    }
}
