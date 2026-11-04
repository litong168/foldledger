package com.foldledger.util

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * 桌面端的用户自定义分类图标（与 Android 的 `IconStore` 一一对应）。
 *
 * 设计刻意对齐 Android 版：统一压成 **256×256 正方形 PNG**（先居中裁剪再缩放），
 * 落在 App 数据目录 `~/.foldledger/category_icons/`。图标的**引用字符串**
 * `img:<fileName>` 存在既有 `emoji` 字段里 —— 不新增数据库列、不改备份格式、
 * 不动同步 payload，因此不需要任何 Room 迁移，`BackupFormat.VERSION` 恒 1。
 *
 * 注意：图片二进制本身不进备份 / 同步（双端只传 `img:` 字符串）。从 Android 同步过来的
 * 引用在桌面找不到文件时，共享的 `CategoryIcon` 会优雅降级成 🖼️，这是既有设计。
 */
object DesktopIconStore {

    private const val TARGET_PX = 256

    private val dir: File by lazy {
        File(System.getProperty("user.home"), ".foldledger/${IconRef.DIR}").apply { mkdirs() }
    }

    fun fileOf(fileName: String): File = File(dir, fileName)

    /** 导入一张本地图片，返回用于拼 `img:` 引用的文件名；失败返回 null。 */
    suspend fun import(source: File): String? = withContext(Dispatchers.IO) {
        runCatching {
            val src = ImageIO.read(source) ?: return@runCatching null
            val square = cropToSquare(src)
            val name = "ic_${System.currentTimeMillis()}_${dir.listFiles()?.size ?: 0}.png"
            if (!ImageIO.write(square, "png", fileOf(name))) return@runCatching null
            name
        }.getOrNull()
    }

    /** 同步读取（调用方已在 IO 线程）；文件不在就返回 null，交给共享 UI 降级。 */
    fun load(fileName: String): ImageBitmap? = runCatching {
        val f = fileOf(fileName)
        if (!f.isFile) return null
        ImageIO.read(f)?.toComposeImageBitmap()
    }.getOrNull()

    /** 换图标时清掉旧文件，避免私有目录无限增长。 */
    fun remove(fileName: String) {
        runCatching { fileOf(fileName).delete() }
    }

    private fun cropToSquare(src: BufferedImage): BufferedImage {
        val side = minOf(src.width, src.height)
        val x = (src.width - side) / 2
        val y = (src.height - side) / 2
        val cropped = src.getSubimage(x, y, side, side)
        val out = BufferedImage(TARGET_PX, TARGET_PX, BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        g.setRenderingHint(
            RenderingHints.KEY_INTERPOLATION,
            RenderingHints.VALUE_INTERPOLATION_BILINEAR
        )
        g.drawImage(cropped, 0, 0, TARGET_PX, TARGET_PX, null)
        g.dispose()
        return out
    }
}
