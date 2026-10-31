package com.foldledger.util

/**
 * 分类图标的引用约定。
 *
 * 图标值就存在原有的 `emoji` 字段里（分类表与流水表都有该字段），**不新增数据库列**：
 *  - 预置 / 手选的 Emoji：字段值就是那 1~2 个字符，例如 `"🍜"`
 *  - 用户上传的图片：字段值是 `"img:<文件名>"`，文件落在 `filesDir/category_icons/` 下
 *
 * 这样做有三个好处：
 *  1. 老数据库（v1）不需要迁移，`ALTER TABLE` 一次都不用做
 *  2. 流水表里冗余存的 `categoryEmoji` 天然同步 —— 分类删掉后历史流水仍然显示得出来
 *  3. 备份格式完全不变，恢复回来的老数据照样可读
 */
object IconRef {

    /** 自定义图片的前缀 */
    const val CUSTOM_PREFIX = "img:"

    /** 应用私有目录下的子目录名 */
    const val DIR = "category_icons"

    /** 兜底图标（既不是 Emoji 也不是有效图片引用时用） */
    const val FALLBACK = "🏷️"

    fun isCustom(ref: String?): Boolean = ref?.startsWith(CUSTOM_PREFIX) == true

    fun fileNameOf(ref: String?): String = ref?.removePrefix(CUSTOM_PREFIX).orEmpty()

    fun of(fileName: String): String = CUSTOM_PREFIX + fileName

    /** 纯文本场景（导出、日志、无法渲染图片的地方）用的替代字符 */
    fun asText(ref: String?): String =
        when {
            ref.isNullOrBlank() -> FALLBACK
            isCustom(ref) -> "🖼️"
            else -> ref
        }
}
