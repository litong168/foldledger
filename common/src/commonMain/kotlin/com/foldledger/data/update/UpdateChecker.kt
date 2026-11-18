package com.foldledger.data.update

import org.json.JSONObject

/**
 * 自动更新的纯逻辑层（双端共用、可单测、零平台依赖）。
 *
 * 数据源是 GitHub Releases API（`GET /repos/litong168/foldledger/releases/latest`）。
 * 每个 Release 带一个机器可读的 `update-manifest.json` asset，形如：
 * ```
 * {
 *   "versionCode": 233,
 *   "versionName": "16.6.0",
 *   "minVersionCode": 1,
 *   "notes": "本次更新说明",
 *   "assets": {
 *     "windows": {"name": "...setup.exe", "size": 123, "sha256": "..."},
 *     "android": {"name": "...release.apk", "size": 123, "sha256": "..."}
 *   }
 * }
 * ```
 *
 * 版本权威是 **versionCode 整数**（不是 versionName 三段比较），与项目铁律一致。
 * 客户端拿到清单后：比较版本 → 选本端 asset → 下载 → 校验 size + sha256 → 安装。
 */
object UpdateChecker {

    /** 本端资产条目。[url] 为空时由下载源按约定拼接（GitHub 走 API、Gitee 走约定直链）。 */
    data class Asset(
        val name: String,
        val size: Long,
        val sha256: String,
        /** 直链下载地址（可选）。Gitee 镜像用它指向 Release 附件——raw 对大文件强制登录，附件不用。 */
        val url: String = ""
    )

    /** 更新清单（从 update-manifest.json 解析） */
    data class Manifest(
        val versionCode: Int,
        val versionName: String,
        val minVersionCode: Int,
        val notes: String,
        val assets: Map<String, Asset>
    ) {
        /** 取本端资产；platform 取 "windows" / "android" */
        fun assetFor(platform: String): Asset? = assets[platform]
    }

    /** 解析 update-manifest.json。格式坏/缺字段时抛 IllegalArgumentException（由上层吞掉当"无更新"） */
    fun parseManifest(json: String): Manifest {
        val o = JSONObject(json)
        val rawAssets = o.optJSONObject("assets")
        return Manifest(
            versionCode = o.optInt("versionCode", 0),
            versionName = o.optString("versionName", ""),
            minVersionCode = o.optInt("minVersionCode", 0),
            notes = o.optString("notes", ""),
            assets = parseAssets(rawAssets)
        )
    }

    private fun parseAssets(raw: JSONObject?): Map<String, Asset> {
        if (raw == null) return emptyMap()
        val out = LinkedHashMap<String, Asset>()
        val it = raw.keys()
        while (it.hasNext()) {
            val k = it.next()
            val a = raw.getJSONObject(k)
            out[k] = Asset(
                name = a.optString("name", ""),
                size = a.optLong("size", 0L),
                sha256 = a.optString("sha256", ""),
                url = a.optString("url", "")
            )
        }
        return out
    }

    /**
     * 从 Releases API 的 latest 响应里找到一个 asset 的下载 url。
     * [fileName] 精确匹配 asset 名（如 update-manifest.json）。
     */
    fun findAssetUrl(releaseJson: String, fileName: String): String? {
        val arr = JSONObject(releaseJson).optJSONArray("assets") ?: return null
        for (i in 0 until arr.length()) {
            val a = arr.getJSONObject(i)
            if (a.optString("name", "") == fileName) {
                return a.optString("browser_download_url", "").takeIf { it.isNotBlank() }
            }
        }
        return null
    }

    /** 从 latest 响应里取 tag_name（如 v16.5.3），用于无 manifest 时回退展示 */
    fun parseTagName(releaseJson: String): String? =
        JSONObject(releaseJson).optString("tag_name", "").takeIf { it.isNotBlank() }

    /** 是否有新版本：远端 versionCode > 当前；且本端有资产 */
    data class CheckResult(
        val hasUpdate: Boolean,
        val force: Boolean,
        val manifest: Manifest?
    )

    /**
     * 判断更新是否要提示。
     * - 远端 versionCode <= 当前 → 无更新
     * - 本端没有对应资产 → 无更新（避免提示了却下不了）
     * - minVersionCode > 当前 → 强制更新
     * - skippedVersionCode == 远端 versionCode → 用户跳过过，静默
     */
    fun evaluate(
        manifest: Manifest,
        currentVersionCode: Int,
        platform: String,
        skippedVersionCode: Int = -1
    ): CheckResult {
        val hasAsset = manifest.assetFor(platform) != null
        val newer = manifest.versionCode > currentVersionCode
        if (!newer || !hasAsset || manifest.versionCode == skippedVersionCode) {
            return CheckResult(false, false, manifest)
        }
        val force = manifest.minVersionCode > currentVersionCode
        return CheckResult(true, force, manifest)
    }

    /** 三段版本号字符串 → 整数比较用（仅回退展示时用；权威永远是 versionCode） */
    fun versionTripleToCode(tag: String): Int {
        val nums = tag.trim().removePrefix("v").split('.').mapNotNull { it.toIntOrNull() }
        if (nums.isEmpty()) return 0
        // 兼容三段 (major.minor.patch) 与四段（末段当作 patch 的细分，乘 1000）
        return when (nums.size) {
            1 -> nums[0] * 1_000_000
            2 -> nums[0] * 1_000_000 + nums[1] * 1_000
            else -> nums[0] * 1_000_000 + nums[1] * 1_000 + nums[2]
        }
    }

    /** SHA-256 字节数组 → 十六进制小写（用于校验下载文件） */
    fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
