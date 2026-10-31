package com.foldledger.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 用户上传的分类图标的落盘与读取。
 *
 * 设计要点：
 *  - 统一压成 **256×256 的正方形 PNG**（先居中裁剪再缩放）。正方形是必须的 ——
 *    不同比例的原图直接塞进方形槽位会被拉伸变形，而图标槽位是固定尺寸的。
 *  - 解码分两趟：第一趟只读边界算 `inSampleSize`，避免把 4000×3000 的原图整张读进内存。
 *  - 内存里挂一个 LRU，列表滚动时不会反复解码。
 *  - 全部 IO 都在 `Dispatchers.IO`，不占主线程。
 */
object IconStore {

    private const val TARGET_PX = 256

    private val cache = LruCache<String, Bitmap>(32)

    fun iconDir(context: Context): File =
        File(context.filesDir, IconRef.DIR).apply { if (!exists()) mkdirs() }

    fun fileOf(context: Context, fileName: String): File = File(iconDir(context), fileName)

    /**
     * 把用户选中的图片导入应用私有目录。
     * @return 生成的文件名（用于拼成 `img:<fileName>`），失败返回 null
     */
    suspend fun import(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            val decoded = decodeBounded(context, uri) ?: return@runCatching null
            val square = cropToSquare(decoded, TARGET_PX)
            if (square !== decoded) decoded.recycle()

            val count = iconDir(context).listFiles()?.size ?: 0
            val name = "ic_${System.currentTimeMillis()}_$count.png"
            File(iconDir(context), name).outputStream().use { out ->
                square.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            cache.put(name, square)
            name
        }.getOrNull()
    }

    /** 同步读取一张已导入的图标；命中缓存则零解码 */
    fun load(context: Context, fileName: String): Bitmap? {
        cache.get(fileName)?.let { return it }
        val f = fileOf(context, fileName)
        if (!f.isFile) return null
        val bmp = runCatching { BitmapFactory.decodeFile(f.absolutePath) }.getOrNull() ?: return null
        cache.put(fileName, bmp)
        return bmp
    }

    /** 删除一张不再被引用的图标（用户换图标时调用，避免私有目录无限增长） */
    fun remove(context: Context, fileName: String) {
        cache.remove(fileName)
        runCatching { fileOf(context, fileName).delete() }
    }

    // ---------------- 内部 ----------------

    private fun decodeBounded(context: Context, uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= TARGET_PX &&
            bounds.outHeight / (sample * 2) >= TARGET_PX
        ) {
            sample *= 2
        }

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }
        }.getOrNull()
    }

    private fun cropToSquare(src: Bitmap, size: Int): Bitmap {
        val side = minOf(src.width, src.height)
        val x = (src.width - side) / 2
        val y = (src.height - side) / 2
        val cropped = Bitmap.createBitmap(src, x, y, side, side)
        if (cropped.width == size && cropped.height == size) return cropped
        val scaled = Bitmap.createScaledBitmap(cropped, size, size, true)
        if (scaled !== cropped) cropped.recycle()
        return scaled
    }
}
