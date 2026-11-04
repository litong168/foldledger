package com.foldledger.util

import com.foldledger.data.db.Direction
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 账单字段映射（v9.1）——把账单表格的每一行变成一笔待导入的流水。
 *
 * 微信支付与支付宝的账单表头不一样（支付宝还在标题行下面压着几行说明），
 * 所以这里做两件事：
 *  1. **在前 12 行里找表头**（认准"收/支"或"金额"这种特征列）；
 *  2. **按别名认列**（"金额(元)" / "金额（元）" / "交易时间" / "付款时间" …）。
 *
 * 判定口径偏保守：拿不准的行**不导**（宁可少导，也别把"不计收支"的转账导成支出）。
 */
object BillMapper {

    /** 一条待导入的流水 */
    data class Mapped(
        val occurredAt: Long,
        val direction: Int,
        val amountCents: Long,
        /** 交易对方（商户） */
        val counterparty: String,
        /** 商品/备注 */
        val note: String,
        /** 交易单号（用于去重） */
        val orderId: String,
        /** 支付方式 */
        val payMethod: String
    )

    /** 列别名 → 逻辑字段 */
    enum class Col { TIME, COUNTERPARTY, GOODS, DIRECTION, AMOUNT, STATUS, ORDER_ID, REMARK, PAY_METHOD, NONE }

    private val ALIASES: Map<Col, List<String>> = mapOf(
        Col.TIME to listOf("交易时间", "付款时间", "交易创建时间", "时间", "交易日期"),
        Col.COUNTERPARTY to listOf("交易对方", "对方", "商户名称", "交易方"),
        Col.GOODS to listOf("商品", "商品名称", "交易内容", "商品说明"),
        Col.DIRECTION to listOf("收/支", "收支", "收入/支出", "方向"),
        Col.AMOUNT to listOf("金额(元)", "金额（元）", "金额", "交易金额", "金额(元)"),
        Col.STATUS to listOf("当前状态", "交易状态", "状态", "资金状态"),
        Col.ORDER_ID to listOf("交易单号", "交易号", "商家订单号", "订单号"),
        Col.REMARK to listOf("备注", "备注说明", "说明"),
        Col.PAY_METHOD to listOf("支付方式", "付款方式")
    )

    /** 表头行里必须出现的特征列（用于在文件前几行里定位表头） */
    private val HEADER_MARKERS = listOf("收/支", "收支", "金额", "交易时间", "付款时间", "交易对方")

    /**
     * 在前若干行里定位表头行。
     *
     * @return 表头行的下标；找不到返回 -1
     */
    fun findHeaderRow(rows: List<List<String>>, limit: Int = 12): Int {
        for (i in rows.take(limit).indices) {
            val cells = rows[i].map { it.trim() }
            val hit = cells.count { c -> HEADER_MARKERS.any { c.contains(it) } }
            if (hit >= 3) return i
        }
        // 兜底：只要有一行同时含"金额"和"时间"也算
        for (i in rows.take(limit).indices) {
            val cells = rows[i].map { it.trim() }
            if (cells.any { it.contains("金额") } && cells.any { it.contains("时间") }) return i
        }
        return -1
    }

    /** 是否像一份可导入的账单 */
    fun looksLikeBill(rows: List<List<String>>): Boolean = findHeaderRow(rows) >= 0

    /** 表头 → 各逻辑字段的列下标 */
    fun mapColumns(header: List<String>): Map<Col, Int> {
        val out = HashMap<Col, Int>()
        header.forEachIndexed { idx, raw ->
            val cell = raw.trim().removePrefix("﻿")
            if (cell.isEmpty()) return@forEachIndexed
            for ((col, names) in ALIASES) {
                if (out.containsKey(col)) continue
                if (names.any { cell == it || cell.startsWith(it) }) {
                    out[col] = idx
                    break
                }
            }
        }
        return out
    }

    /**
     * 把数据行映射成流水。
     *
     * @return 可导入的流水（已剔除"不计收支"、已退款/已关闭等行）
     */
    fun mapRows(header: List<String>, dataRows: List<List<String>>): List<Mapped> {
        val cols = mapColumns(header)
        val out = ArrayList<Mapped>()
        for (row in dataRows) {
            val m = mapRow(cols, row) ?: continue
            out += m
        }
        return out
    }

    private fun mapRow(cols: Map<Col, Int>, row: List<String>): Mapped? {
        fun cell(c: Col): String {
            val i = cols[c] ?: return ""
            return row.getOrNull(i)?.trim().orEmpty()
        }

        val amountCents = parseAmount(cell(Col.AMOUNT)) ?: return null
        if (amountCents <= 0L) return null

        // 方向：优先看"收/支"列
        val dirText = cell(Col.DIRECTION)
        val direction = when {
            dirText.contains("收入") -> Direction.INCOME
            dirText.contains("支出") -> Direction.EXPENSE
            dirText.contains("不计") || dirText.contains("其他") -> return null // 转账/退款，不计收支
            else -> {
                // 没有收/支列：金额带负号=支出，否则按"商户订单号/类型"猜不动 → 放弃
                return null
            }
        }

        // 状态：已退款/已关闭/已撤销的不导
        val status = cell(Col.STATUS)
        if (status.contains("退款") || status.contains("关闭") || status.contains("撤销") ||
            status.contains("失败") || status.contains("作废")
        ) return null

        val time = parseTime(cell(Col.TIME)) ?: return null

        val counterparty = cell(Col.COUNTERPARTY)
        val goods = cell(Col.GOODS)
        val remark = cell(Col.REMARK)
        val note = listOf(goods, remark)
            .filter { it.isNotEmpty() && it != "-" }
            .joinToString(" · ")
            .ifEmpty { counterparty }
            .take(60)

        return Mapped(
            occurredAt = time,
            direction = direction,
            amountCents = amountCents,
            counterparty = counterparty.take(40),
            note = note,
            orderId = cell(Col.ORDER_ID),
            payMethod = cell(Col.PAY_METHOD)
        )
    }

    /** "32.50" / "¥32.50" / "32.5元" → 3250 分；解析不出返回 null */
    fun parseAmount(text: String): Long? {
        if (text.isBlank()) return null
        val cleaned = text.replace(",", "").replace("¥", "").replace("￥", "").replace("元", "").trim()
        if (cleaned.isEmpty()) return null
        val v = cleaned.toDoubleOrNull() ?: return null
        if (v <= 0.0) return null
        return Math.round(v * 100)
    }

    /**
     * 时间解析。
     *
     * 账单里的时间有两种形态：文本（"2024-01-15 12:33:21"）和 Excel 日期序列号（45000.5）。
     */
    fun parseTime(text: String): Long? {
        if (text.isBlank()) return null
        val t = text.trim()
        // Excel 序列号（1900-01-00 起的天数）
        t.toDoubleOrNull()?.let { serial ->
            if (serial > 20000 && serial < 80000) {
                val ms = Math.round((serial - 25569.0) * 86400.0 * 1000.0)
                return if (ms > 0) ms else null
            }
        }
        val patterns = listOf(
            "yyyy-MM-dd HH:mm:ss", "yyyy/MM/dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy/MM/dd HH:mm",
            "yyyy年MM月dd日 HH:mm:ss", "yyyy-MM-dd", "yyyy/MM/dd"
        )
        for (p in patterns) {
            val sdf = SimpleDateFormat(p, Locale.CHINA)
            sdf.isLenient = true
            val d = runCatching { sdf.parse(t) }.getOrNull()
            if (d != null) return d.time
        }
        return null
    }
}
