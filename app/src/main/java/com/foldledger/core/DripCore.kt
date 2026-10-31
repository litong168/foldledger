package com.foldledger.core

import com.foldledger.util.Money

/**
 * 滴水记账 Rust 核心的 Kotlin 入口（v4.0）。
 *
 * ### 架构定位
 * 这里是「Rust 核心 + JNI」的桥：`drip-core`（见 `rust/drip-core/`）用 Rust 实现
 * 金额运算 / 统计聚合 / 搜索匹配 / 备份完整性，编译成 `libdrip_core.so` 后由本类
 * 经 JNI 调用。UI 仍是 Kotlin / Compose（保住折叠屏适配与澎湃动画），
 * 因此 App **照常安装在小米 18 FOLD**，包名 / 签名 / DB / 备份格式全部不变。
 *
 * ### 回退策略（关键）
 * `.so` 装载失败或未打包时，[nativeAvailable] 为 false，所有方法**无缝回退**到等价的
 * Kotlin 实现（金额部分直接复用 [Money]）。于是无论 Rust 是否就绪，App 都能正常工作，
 * 升级链绝不因 .so 缺失而中断。Rust 侧的正确性由 `cargo test` 保证与 Kotlin 逐字符等价。
 */
object DripCore {

    /** Rust .so 是否已装载；false = 全部走 Kotlin 回退。 */
    val nativeAvailable: Boolean = runCatching {
        System.loadLibrary("drip_core")
        true
    }.getOrDefault(false)

    // ---- 金额（回退 = Money，已逐字符对齐 Rust） ----
    fun formatCents(cents: Long, symbol: Boolean): String =
        if (nativeAvailable) runCatching { nativeFormatCents(cents, symbol) }.getOrDefault(Money.format(cents, symbol))
        else Money.format(cents, symbol)

    fun toPlain(cents: Long): String =
        if (nativeAvailable) runCatching { nativeToPlain(cents) }.getOrDefault(Money.centsToPlain(cents))
        else Money.centsToPlain(cents)

    /** 解析失败返回 null（Rust 用 [PARSE_INVALID] 哨兵传值）。 */
    fun parseToCents(input: String): Long? {
        if (nativeAvailable) {
            val v = runCatching { nativeParseToCents(input) }.getOrDefault(PARSE_INVALID)
            return if (v == PARSE_INVALID) null else v
        }
        return Money.parseToCents(input)
    }

    // ---- 搜索匹配（v4.5：多关键词 AND + 金额匹配） ----
    fun matchesQuery(name: String, note: String, amountCents: Long, query: String): Boolean =
        if (nativeAvailable) runCatching { nativeMatchesQuery(name, note, amountCents, query) }
            .getOrDefault(kotlinMatchesQuery(name, note, amountCents, query))
        else kotlinMatchesQuery(name, note, amountCents, query)

    // ---- 备份完整性 ----
    fun validateBackup(bytes: ByteArray): Boolean =
        if (nativeAvailable) runCatching { nativeValidateBackup(bytes) }
            .getOrDefault(kotlinValidateBackup(bytes))
        else kotlinValidateBackup(bytes)

    fun fnv1a(bytes: ByteArray): Int =
        if (nativeAvailable) runCatching { nativeFnv1a(bytes) }.getOrDefault(kotlinFnv1a(bytes))
        else kotlinFnv1a(bytes)

    // ===================== JNI 原生入口 =====================
    private external fun nativeFormatCents(cents: Long, symbol: Boolean): String
    private external fun nativeToPlain(cents: Long): String
    private external fun nativeParseToCents(input: String): Long
    private external fun nativeMatchesQuery(name: String, note: String, amountCents: Long, query: String): Boolean
    private external fun nativeValidateBackup(data: ByteArray): Boolean
    private external fun nativeFnv1a(data: ByteArray): Int

    private const val PARSE_INVALID = Long.MIN_VALUE

    // ===================== Kotlin 回退（与 Rust 逐等价） =====================
    /**
     * 与 Rust `stats::matches_query` 逐等价：空格分隔的多关键词 AND；
     * 关键词命中分类名/备注；**纯数字关键词**额外命中金额（带/不带千分位两种写法）。
     */
    private fun kotlinMatchesQuery(
        name: String,
        note: String,
        amountCents: Long,
        query: String
    ): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return true
        val nameL = name.lowercase()
        val noteL = note.lowercase()
        val abs = kotlin.math.abs(amountCents)
        val plain = Money.centsToPlain(abs)
        val grouped = Money.format(abs, symbol = false)
        return q.split(Regex("\\s+")).filter { it.isNotEmpty() }.all { token ->
            val t = token.lowercase()
            var hit = nameL.contains(t) || noteL.contains(t)
            if (!hit && isNumericToken(t)) {
                hit = plain.contains(t) || grouped.contains(t)
            }
            hit
        }
    }

    /** 纯数字（允许小数点与千分位逗号）才参与金额匹配 */
    private fun isNumericToken(t: String): Boolean =
        t.isNotEmpty() && t.all { it.isDigit() || it == '.' || it == ',' }

    private fun kotlinValidateBackup(bytes: ByteArray): Boolean {
        val s = String(bytes, Charsets.UTF_8)
        val isDrip = s.contains("\"format\":\"foldledger.backup\"")
        val version = jsonInt(s, "version") ?: 0
        return isDrip && version in 1..2 && s.contains("\"ledgers\":") && s.contains("\"transactions\":")
    }

    private fun kotlinFnv1a(bytes: ByteArray): Int {
        var h = -0x7ee3623b // 0x811c9dc5 as signed Int
        for (b in bytes) {
            h = h xor (b.toInt() and 0xFF)
            h = h * 0x01000193
        }
        return h
    }

    private fun jsonInt(text: String, key: String): Int? {
        val pat = "\"$key\":"
        val idx = text.indexOf(pat)
        if (idx < 0) return null
        val rest = text.substring(idx + pat.length)
        val num = rest.takeWhile { it.isDigit() || it == '-' }
        return num.toIntOrNull()
    }
}
