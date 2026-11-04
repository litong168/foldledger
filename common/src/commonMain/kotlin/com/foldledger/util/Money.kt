package com.foldledger.util

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs

/**
 * 金额工具：内部一律以「分(Long)」存储，避免浮点误差。
 */
object Money {

    /** 1234 分 -> "12.34"（不带符号、不带千分位，用于输入框） */
    fun centsToPlain(cents: Long): String {
        val neg = cents < 0
        val v = abs(cents)
        val s = "${v / 100}.${(v % 100).toString().padStart(2, '0')}"
        return if (neg) "-$s" else s
    }

    /** 12345678 分 -> "¥123,456.78" */
    fun format(cents: Long, symbol: Boolean = true): String {
        val neg = cents < 0
        val v = abs(cents)
        val yuan = v / 100
        val fen = v % 100
        val grouped = yuan.toString().reversed().chunked(3).joinToString(",").reversed()
        val sb = StringBuilder()
        if (neg) sb.append('-')
        if (symbol) sb.append('¥')
        sb.append(grouped)
        if (fen != 0L) sb.append('.').append(fen.toString().padStart(2, '0'))
        return sb.toString()
    }

    /** 紧凑显示：2500000 分 -> "2.5万"、12345678 分 -> "12.35万" */
    fun formatCompact(cents: Long): String {
        val yuan = abs(cents) / 100.0
        val sign = if (cents < 0) "-" else ""
        return when {
            yuan >= 100_000_000 -> sign + trimZero(yuan / 100_000_000) + "亿"
            yuan >= 10_000 -> sign + trimZero(yuan / 10_000) + "万"
            else -> format(cents)
        }
    }

    private fun trimZero(v: Double): String {
        val s = String.format("%.2f", v)
        return s.trimEnd('0').trimEnd('.')
    }

    /** "12.34" -> 1234；非法输入返回 null */
    fun parseToCents(input: String): Long? {
        val t = input.trim().replace("¥", "").replace(",", "")
        if (t.isEmpty() || t == "." || t == "-") return null
        return try {
            BigDecimal(t)
                .setScale(2, RoundingMode.HALF_UP)
                .movePointRight(2)
                .longValueExact()
        } catch (_: Exception) {
            null
        }
    }

    /** 百分比：0.256 -> "25.6%" */
    fun percent(part: Long, total: Long): String {
        if (total <= 0) return "0%"
        val p = part.toDouble() / total.toDouble() * 100
        return String.format("%.1f%%", p)
    }
}
